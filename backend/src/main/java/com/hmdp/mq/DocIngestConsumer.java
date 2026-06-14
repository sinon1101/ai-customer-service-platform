package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.constant.IngestConstants;
import com.hmdp.entity.KbDocument;
import com.hmdp.service.IKbDocumentService;
import com.hmdp.service.IKnowledgeBaseService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文档摄入消费者:KB_DOC_INGEST topic。
 * <p>
 * 流程:取文档 → PROCESSING → TokenTextSplitter 切片 → 组装带 metadata 的 Document
 * → vectorStore.add()(内部调百炼 embedding + 写 RediSearch)→ COMPLETED + 回写 chunk_count、doc_count++。
 * 任一步异常 → 置 FAILED 记 error_msg,并返回 RECONSUME_LATER 交给 RocketMQ 重试/死信兜底。
 */
@Slf4j
@Component
public class DocIngestConsumer {

    @Value("${rocketmq.name-server:127.0.0.1:9876}")
    private String nameServer;

    @Resource
    private IKbDocumentService kbDocumentService;

    @Resource
    private IKnowledgeBaseService knowledgeBaseService;

    @Resource
    private RedisVectorStore vectorStore;

    private final TokenTextSplitter splitter = new TokenTextSplitter();

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void start() throws Exception {
        consumer = new DefaultMQPushConsumer(IngestConstants.CONSUMER_GROUP);
        consumer.setNamesrvAddr(nameServer);
        consumer.subscribe(IngestConstants.TOPIC_DOC_INGEST, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                if (!handle(msg)) {
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("RocketMQ consumer 已启动,订阅 topic={}", IngestConstants.TOPIC_DOC_INGEST);
    }

    /** 处理单条消息,成功返回 true;失败置 FAILED 并返回 false(触发重试) */
    private boolean handle(MessageExt msg) {
        DocIngestMessage payload;
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            payload = JSONUtil.toBean(body, DocIngestMessage.class);
        } catch (Exception e) {
            // 消息体本身解析不了,重试也没用,直接吞掉避免无限重投
            log.error("摄入消息解析失败,丢弃。msgId={}", msg.getMsgId(), e);
            return true;
        }

        Long docId = payload.getDocId();
        KbDocument doc = kbDocumentService.getById(docId);
        if (doc == null) {
            log.warn("文档不存在,跳过 docId={}", docId);
            return true;
        }
        // 已完成的幂等跳过(重复投递)
        if ("COMPLETED".equals(doc.getStatus())) {
            return true;
        }

        try {
            kbDocumentService.lambdaUpdate()
                    .eq(KbDocument::getId, docId)
                    .set(KbDocument::getStatus, "PROCESSING")
                    .set(KbDocument::getErrorMsg, null)
                    .update();

            // 1. 整篇组成一个 Document,带租户/知识库/文档 metadata(M3 召回时按此过滤)
            Document source = new Document(doc.getContent(), java.util.Map.of(
                    "tenantId", String.valueOf(doc.getTenantId()),
                    "kbId", String.valueOf(doc.getKbId()),
                    "docId", String.valueOf(doc.getId()),
                    "docName", doc.getName()
            ));
            // 2. 切片
            List<Document> chunks = splitter.apply(List.of(source));
            if (chunks.isEmpty()) {
                throw new IllegalStateException("切片结果为空");
            }
            // 3. 向量化 + 写 RediSearch(内部调百炼 embedding)
            vectorStore.add(chunks);

            // 4. 回写完成状态 + chunk_count,知识库 doc_count++
            kbDocumentService.lambdaUpdate()
                    .eq(KbDocument::getId, docId)
                    .set(KbDocument::getStatus, "COMPLETED")
                    .set(KbDocument::getChunkCount, chunks.size())
                    .update();
            knowledgeBaseService.lambdaUpdate()
                    .eq(com.hmdp.entity.KnowledgeBase::getId, doc.getKbId())
                    .setSql("doc_count = doc_count + 1")
                    .update();

            log.info("文档摄入完成 docId={} chunks={}", docId, chunks.size());
            return true;
        } catch (Exception e) {
            log.error("文档摄入失败 docId={}", docId, e);
            String err = e.getMessage();
            kbDocumentService.lambdaUpdate()
                    .eq(KbDocument::getId, docId)
                    .set(KbDocument::getStatus, "FAILED")
                    .set(KbDocument::getErrorMsg, err == null ? e.getClass().getSimpleName()
                            : err.substring(0, Math.min(err.length(), 480)))
                    .update();
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("RocketMQ consumer 已关闭");
        }
    }
}
