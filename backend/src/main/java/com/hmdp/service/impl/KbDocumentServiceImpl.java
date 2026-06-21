package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.auth.UserContext;
import com.hmdp.constant.IngestConstants;
import com.hmdp.dto.DocUploadDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.KbDocument;
import com.hmdp.entity.KnowledgeBase;
import com.hmdp.mapper.KbDocumentMapper;
import com.hmdp.mq.DocIngestMessage;
import com.hmdp.service.IKbDocumentService;
import com.hmdp.service.IKnowledgeBaseService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 知识库文档 —— 上传走「落库 PENDING + 发 MQ」异步管道,所有读写以
 * {@link UserContext#getTenantId()} 为边界,跨租户访问查不到。
 */
@Slf4j
@Service
public class KbDocumentServiceImpl extends ServiceImpl<KbDocumentMapper, KbDocument>
        implements IKbDocumentService {

    @Resource
    private IKnowledgeBaseService knowledgeBaseService;

    @Resource
    private DefaultMQProducer docIngestProducer;

    @Override
    public Result upload(Long kbId, DocUploadDTO form) {
        Long tenantId = UserContext.getTenantId();
        // 1. 校验知识库归属(不属于本租户 → 查不到 → 当作不存在)
        KnowledgeBase kb = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, kbId)
                .eq(KnowledgeBase::getTenantId, tenantId)
                .one();
        if (kb == null) {
            return Result.fail("知识库不存在");
        }
        if (StrUtil.isBlank(form.getContent())) {
            return Result.fail("文档内容不能为空");
        }

        // 2. 落库 PENDING
        String sourceType = StrUtil.isBlank(form.getSourceType()) ? "TEXT" : form.getSourceType();
        KbDocument doc = new KbDocument()
                .setTenantId(tenantId)
                .setKbId(kbId)
                .setName(StrUtil.isBlank(form.getName()) ? "未命名文档" : form.getName())
                .setSourceType(sourceType)
                .setContent(form.getContent())
                .setCharCount(form.getContent().length())
                .setChunkCount(0)
                .setStatus("PENDING");
        save(doc);

        // 3. 发 MQ(削峰:慢任务排队,消费端慢慢消化),失败则置 FAILED 并提示
        try {
            DocIngestMessage payload = new DocIngestMessage(tenantId, kbId, doc.getId());
            Message msg = new Message(
                    IngestConstants.TOPIC_DOC_INGEST,
                    JSONUtil.toJsonStr(payload).getBytes(StandardCharsets.UTF_8));
            docIngestProducer.send(msg);
        } catch (Exception e) {
            log.error("文档摄入消息发送失败 docId={}", doc.getId(), e);
            lambdaUpdate()
                    .eq(KbDocument::getId, doc.getId())
                    .set(KbDocument::getStatus, "FAILED")
                    .set(KbDocument::getErrorMsg, "消息投递失败:" + e.getMessage())
                    .update();
            return Result.fail("文档已保存,但摄入任务投递失败,请重试");
        }

        return Result.ok(doc.getId());
    }

    @Override
    public Result listByKb(Long kbId) {
        Long tenantId = UserContext.getTenantId();
        List<KbDocument> list = lambdaQuery()
                .eq(KbDocument::getTenantId, tenantId)
                .eq(KbDocument::getKbId, kbId)
                .orderByDesc(KbDocument::getCreateTime)
                // 列表不回传大字段 content,省带宽
                .select(KbDocument::getId, KbDocument::getKbId, KbDocument::getName,
                        KbDocument::getSourceType, KbDocument::getCharCount,
                        KbDocument::getChunkCount, KbDocument::getStatus,
                        KbDocument::getErrorMsg, KbDocument::getCreateTime,
                        KbDocument::getUpdateTime)
                .list();
        return Result.ok(list);
    }

    @Override
    public Result detail(Long docId) {
        KbDocument doc = getOwned(docId);
        if (doc == null) {
            return Result.fail("文档不存在");
        }
        return Result.ok(doc);
    }

    @Override
    public KbDocument getOwned(Long docId) {
        Long tenantId = UserContext.getTenantId();
        return lambdaQuery()
                .eq(KbDocument::getId, docId)
                .eq(KbDocument::getTenantId, tenantId)
                .one();
    }
}
