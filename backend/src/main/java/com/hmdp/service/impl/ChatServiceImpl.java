package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.ChatConstants;
import com.hmdp.dto.ChatRequestDTO;
import com.hmdp.dto.ChatResponseDTO;
import com.hmdp.service.IChatService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * RAG 问答引擎(M3)。
 * <p>
 * 提问 → embedding + 向量召回 Top-K(按 tenantId/kbId 过滤,逻辑隔离)→ 拼 Prompt(带引用溯源)
 * → LLM(非流式整体 / SSE 逐字流式)→ 把本轮对话写回 Redis 多轮上下文(限定窗口,控 token)。
 */
@Slf4j
@Service
public class ChatServiceImpl implements IChatService {

    private final ChatClient chatClient;

    @Resource
    private RedisVectorStore vectorStore;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    public ChatServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public ChatResponseDTO chat(ChatRequestDTO request, Long tenantId) {
        String question = request.getMessage();
        String conversationId = resolveConversationId(request.getConversationId());

        List<Document> docs = retrieve(question, tenantId, request.getKbId());
        List<Message> history = loadHistory(tenantId, conversationId);

        String answer = chatClient.prompt()
                .system(buildSystemPrompt(docs))
                .messages(history)
                .user(question)
                .call()
                .content();

        saveTurn(tenantId, conversationId, question, answer);
        return new ChatResponseDTO(conversationId, answer, toSources(docs));
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(ChatRequestDTO request, Long tenantId) {
        String question = request.getMessage();
        String conversationId = resolveConversationId(request.getConversationId());

        List<Document> docs = retrieve(question, tenantId, request.getKbId());
        List<Message> history = loadHistory(tenantId, conversationId);

        // 先把 conversationId + 引用溯源作为一条 meta 事件下发,便于前端立刻拿到会话号/来源
        JSONObject meta = new JSONObject()
                .set("conversationId", conversationId)
                .set("sources", toSources(docs));
        Flux<ServerSentEvent<String>> metaEvent =
                Flux.just(ServerSentEvent.<String>builder(meta.toString()).event("meta").build());

        // 逐字流式;一边推送一边累积完整答案,结束时落库多轮上下文
        StringBuilder full = new StringBuilder();
        Flux<ServerSentEvent<String>> tokenEvents = chatClient.prompt()
                .system(buildSystemPrompt(docs))
                .messages(history)
                .user(question)
                .stream()
                .content()
                .doOnNext(full::append)
                .map(token -> ServerSentEvent.<String>builder(token).event("message").build());

        Flux<ServerSentEvent<String>> doneEvent = Flux.defer(() -> {
            saveTurn(tenantId, conversationId, question, full.toString());
            return Flux.just(ServerSentEvent.<String>builder("[DONE]").event("done").build());
        });

        return Flux.concat(metaEvent, tokenEvents, doneEvent)
                .doOnError(e -> log.error("RAG 流式回答异常 convId={}", conversationId, e));
    }

    // ───────────────────── 召回 / Prompt / 溯源 ─────────────────────

    /** 向量召回:按租户(及可选知识库)过滤的 Top-K 相似片段 */
    private List<Document> retrieve(String question, Long tenantId, Long kbId) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = fb.eq("tenantId", String.valueOf(tenantId));
        if (kbId != null) {
            filter = fb.and(filter, fb.eq("kbId", String.valueOf(kbId)));
        }
        SearchRequest req = SearchRequest.builder()
                .query(question)
                .topK(ChatConstants.TOP_K)
                .similarityThreshold(ChatConstants.SIMILARITY_THRESHOLD)
                .filterExpression(filter.build())
                .build();
        List<Document> docs = vectorStore.similaritySearch(req);
        return docs == null ? List.of() : docs;
    }

    /** 拼系统提示:把召回片段作为唯一事实来源,并约束模型不得编造 */
    private String buildSystemPrompt(List<Document> docs) {
        StringBuilder ctx = new StringBuilder();
        if (docs.isEmpty()) {
            ctx.append("(无)");
        } else {
            int i = 1;
            for (Document d : docs) {
                String name = String.valueOf(d.getMetadata().getOrDefault("docName", "未知文档"));
                ctx.append("[").append(i++).append("] 来源:").append(name).append("\n")
                        .append(d.getText()).append("\n\n");
            }
        }
        return """
                你是企业知识库智能客服助手。请严格依据下方【知识库资料】回答用户问题:
                - 只根据资料作答,不得编造资料之外的信息;
                - 若资料中找不到答案,请如实告知「抱歉,知识库中暂未找到相关信息,建议您转接人工客服」,不要杜撰;
                - 使用简体中文,回答简洁、专业、友好。

                【知识库资料】
                %s""".formatted(ctx);
    }

    /** 把召回片段转为引用溯源(文档名 + 得分 + 截断摘要) */
    private List<ChatResponseDTO.Source> toSources(List<Document> docs) {
        return docs.stream().map(d -> {
            String name = String.valueOf(d.getMetadata().getOrDefault("docName", "未知文档"));
            String text = d.getText() == null ? "" : d.getText();
            String snippet = text.length() > 80 ? text.substring(0, 80) + "…" : text;
            return new ChatResponseDTO.Source(name, d.getScore(), snippet);
        }).collect(Collectors.toList());
    }

    // ───────────────────── 多轮上下文(Redis,限定窗口)─────────────────────

    private String resolveConversationId(String fromClient) {
        return StrUtil.isNotBlank(fromClient)
                ? fromClient
                : String.valueOf(redisIdWorker.nextId(ChatConstants.CONVERSATION_ID_PREFIX));
    }

    private String historyKey(Long tenantId, String conversationId) {
        return RedisConstants.tenantKey(tenantId, ChatConstants.HISTORY_KEY_SUFFIX + conversationId);
    }

    /** 读取多轮上下文:Redis List 里按时序存的 {role,content},还原成 Spring AI Message 列表 */
    private List<Message> loadHistory(Long tenantId, String conversationId) {
        List<String> raw = stringRedisTemplate.opsForList().range(historyKey(tenantId, conversationId), 0, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(raw.size());
        for (String item : raw) {
            JSONObject obj = JSONUtil.parseObj(item);
            String content = obj.getStr("content");
            messages.add("assistant".equals(obj.getStr("role"))
                    ? new AssistantMessage(content)
                    : new UserMessage(content));
        }
        return messages;
    }

    /** 落库本轮对话:追加 user+assistant 两条,裁剪到窗口大小并续期(空答案不落库) */
    private void saveTurn(Long tenantId, String conversationId, String question, String answer) {
        if (StrUtil.isBlank(answer)) {
            return;
        }
        String key = historyKey(tenantId, conversationId);
        stringRedisTemplate.opsForList().rightPush(key,
                new JSONObject().set("role", "user").set("content", question).toString());
        stringRedisTemplate.opsForList().rightPush(key,
                new JSONObject().set("role", "assistant").set("content", answer).toString());
        // 只保留最近 HISTORY_WINDOW 条,控制后续 prompt 的 token 体量
        stringRedisTemplate.opsForList().trim(key, -ChatConstants.HISTORY_WINDOW, -1);
        stringRedisTemplate.expire(key, ChatConstants.HISTORY_TTL_MINUTES, TimeUnit.MINUTES);
    }
}
