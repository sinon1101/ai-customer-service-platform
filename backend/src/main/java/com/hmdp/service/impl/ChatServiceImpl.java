package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.cache.SemanticCache;
import com.hmdp.cache.SemanticCache.CachedAnswer;
import com.hmdp.constant.CacheConstants;
import com.hmdp.constant.ChatConstants;
import com.hmdp.constant.GovernanceConstants;
import com.hmdp.dto.ChatRequestDTO;
import com.hmdp.dto.ChatResponseDTO;
import com.hmdp.governance.FaultInjector;
import com.hmdp.governance.LlmCircuitBreaker;
import com.hmdp.governance.TenantBulkhead;
import com.hmdp.metrics.MetricsCollector;
import com.hmdp.service.IChatService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * RAG 问答引擎(M3)+ 语义缓存(M4)。
 * <p>
 * 提问 → <b>语义缓存短路</b>(无历史的单轮提问先在缓存向量集找相似历史问答,命中直接返回、不打 LLM)
 * → 未命中:embedding + 向量召回 Top-K(按 tenantId/kbId 过滤)→ 拼 Prompt(带引用溯源)
 * → LLM(非流式整体 / SSE 逐字流式)→ 把答案写回语义缓存 + 多轮上下文。
 * <p>
 * 语义缓存仅对<b>无历史的单轮提问</b>启用:有上下文时答案依赖历史,按问题做键会答错。
 * 缓存三件套(穿透空值缓存 / 击穿互斥锁 / 雪崩随机 TTL)封装在 {@link SemanticCache}。
 */
@Slf4j
@Service
public class ChatServiceImpl implements IChatService {

    private final ChatClient chatClient;

    @Resource
    private RedisVectorStore vectorStore;

    @Resource
    private SemanticCache semanticCache;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    // ───── M5 高并发治理:隔离(信号量)/ 熔断 / 故障注入 ─────
    @Resource
    private TenantBulkhead bulkhead;

    @Resource
    private LlmCircuitBreaker breaker;

    @Resource
    private FaultInjector faultInjector;

    // ───── M7 统计看板:业务指标采集(会话量/命中率/降级/token)─────
    @Resource
    private MetricsCollector metrics;

    /** 同步 LLM 调用的有界执行器(配合超时;daemon 线程不阻塞退出)。并发上限由租户信号量约束。 */
    private final ExecutorService llmExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-call");
        t.setDaemon(true);
        return t;
    });

    public ChatServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public ChatResponseDTO chat(ChatRequestDTO request, Long tenantId) {
        String question = request.getMessage();
        Long kbId = request.getKbId();
        String conversationId = resolveConversationId(request.getConversationId());

        metrics.recordChatRequest(tenantId);
        List<Message> history = loadHistory(tenantId, conversationId);
        boolean cacheEligible = history.isEmpty();

        // 1. 语义缓存短路(仅无历史单轮)
        if (cacheEligible) {
            metrics.recordCacheEligible(tenantId);
            Optional<CachedAnswer> hit = semanticCache.lookup(tenantId, kbId, question);
            if (hit.isPresent()) {
                return cachedResponse(tenantId, conversationId, question, hit.get());
            }
            // 2. 未命中:互斥锁防击穿
            return generateWithLock(tenantId, kbId, conversationId, question);
        }

        // 多轮(有历史):答案依赖上下文,不走缓存,直接生成
        Generated g = generate(question, tenantId, kbId, history);
        if (!g.degraded) {
            saveTurn(tenantId, conversationId, question, g.answer);
        }
        return new ChatResponseDTO(conversationId, g.answer, g.sources, false, g.degraded);
    }

    /** 击穿防护:未命中时只放一个线程打 LLM 并回填缓存,其余线程等它重建完再读结果 */
    private ChatResponseDTO generateWithLock(Long tenantId, Long kbId, String conversationId, String question) {
        if (semanticCache.tryLock(tenantId, kbId, question)) {
            // 抢到锁:我负责重建
            try {
                // double check:可能在「初次未命中」与「抢到锁」之间已被别人回填
                Optional<CachedAnswer> dbl = semanticCache.lookup(tenantId, kbId, question);
                if (dbl.isPresent()) {
                    return cachedResponse(tenantId, conversationId, question, dbl.get());
                }
                Generated g = generate(question, tenantId, kbId, List.of());
                // 降级兜底不回填缓存、不落历史(只是临时话术,非有效答案)
                if (!g.degraded) {
                    semanticCache.save(tenantId, kbId, question, g.answer, g.sources, g.answered);
                    saveTurn(tenantId, conversationId, question, g.answer);
                }
                return new ChatResponseDTO(conversationId, g.answer, g.sources, false, g.degraded);
            } finally {
                semanticCache.unlock(tenantId, kbId, question);
            }
        }
        // 没抢到锁:廉价轮询等持锁者重建完成,再读它回填的结果(避免重复打 LLM)
        semanticCache.awaitUnlock(tenantId, kbId, question);
        Optional<CachedAnswer> filled = semanticCache.lookup(tenantId, kbId, question);
        if (filled.isPresent()) {
            return cachedResponse(tenantId, conversationId, question, filled.get());
        }
        // 持锁者异常未回填:自己兜底算一次(不回填,交给下一个抢到锁的)
        Generated g = generate(question, tenantId, kbId, List.of());
        if (!g.degraded) {
            saveTurn(tenantId, conversationId, question, g.answer);
        }
        return new ChatResponseDTO(conversationId, g.answer, g.sources, false, g.degraded);
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(ChatRequestDTO request, Long tenantId) {
        String question = request.getMessage();
        Long kbId = request.getKbId();
        String conversationId = resolveConversationId(request.getConversationId());

        metrics.recordChatRequest(tenantId);
        List<Message> history = loadHistory(tenantId, conversationId);
        boolean cacheEligible = history.isEmpty();

        if (cacheEligible) {
            metrics.recordCacheEligible(tenantId);
            Optional<CachedAnswer> hit = semanticCache.lookup(tenantId, kbId, question);
            if (hit.isPresent()) {
                return streamCached(tenantId, conversationId, question, hit.get());
            }
            // 未命中:抢重建锁,抢到的负责流式生成 + 回填缓存 + 释放锁;没抢到的等一下再看缓存
            if (semanticCache.tryLock(tenantId, kbId, question)) {
                Optional<CachedAnswer> dbl = semanticCache.lookup(tenantId, kbId, question);
                if (dbl.isPresent()) {
                    semanticCache.unlock(tenantId, kbId, question);
                    return streamCached(tenantId, conversationId, question, dbl.get());
                }
                return streamFromLlm(tenantId, kbId, conversationId, question, List.of(), true);
            }
            // 没抢到锁:廉价轮询等持锁者重建完成,再读结果;命中就回放,否则不回填地流式生成
            semanticCache.awaitUnlock(tenantId, kbId, question);
            Optional<CachedAnswer> filled = semanticCache.lookup(tenantId, kbId, question);
            if (filled.isPresent()) {
                return streamCached(tenantId, conversationId, question, filled.get());
            }
            return streamFromLlm(tenantId, kbId, conversationId, question, List.of(), false);
        }

        // 多轮:不走缓存
        return streamFromLlm(tenantId, kbId, conversationId, question, history, false);
    }

    /**
     * 流式:从 LLM 逐字生成;ownLock=true 时在结束回填缓存,并在终止(完成/取消/异常)时释放锁。
     * <p>
     * M5 治理:进入前依次过「故障注入 / 熔断放行 / 租户信号量隔离」三关,任一不过 → 直接降级流(不打 LLM);
     * 流式过程中 LLM 出错/超时 → 记熔断失败并切降级流;无论如何在 doFinally 释放信号量(与锁同位置)。
     */
    private Flux<ServerSentEvent<String>> streamFromLlm(Long tenantId, Long kbId, String conversationId,
                                                        String question, List<Message> history, boolean ownLock) {
        // ① 故障注入:演示用,强制失败 → 记一次熔断失败,走降级流
        if (faultInjector.isEnabled()) {
            breaker.recordFailure();
            if (ownLock) semanticCache.unlock(tenantId, kbId, question);
            return streamDegraded(tenantId, conversationId);
        }
        // ② 熔断:跳闸中 → 直接降级流(快速失败,不打 LLM)
        if (!breaker.allow()) {
            log.warn("LLM 熔断跳闸中,流式直接降级兜底 tenantId={}", tenantId);
            if (ownLock) semanticCache.unlock(tenantId, kbId, question);
            return streamDegraded(tenantId, conversationId);
        }
        // ③ 隔离:抢该租户信号量名额,满了 → 过载降级流
        if (!bulkhead.tryAcquire(tenantId)) {
            if (ownLock) semanticCache.unlock(tenantId, kbId, question);
            return streamDegraded(tenantId, conversationId);
        }

        // 召回(embedding)也可能失败:失败即记熔断失败 + 释放名额/锁 + 降级流
        List<Document> docs;
        try {
            docs = retrieve(question, tenantId, kbId);
        } catch (Exception e) {
            log.warn("流式召回失败,降级兜底 tenantId={}", tenantId, e);
            breaker.recordFailure();
            bulkhead.release(tenantId);
            if (ownLock) semanticCache.unlock(tenantId, kbId, question);
            return streamDegraded(tenantId, conversationId);
        }
        List<ChatResponseDTO.Source> sources = toSources(docs);

        JSONObject meta = new JSONObject()
                .set("conversationId", conversationId)
                .set("sources", sources)
                .set("cached", false)
                .set("degraded", false);
        Flux<ServerSentEvent<String>> metaEvent =
                Flux.just(ServerSentEvent.<String>builder(meta.toString()).event("meta").build());

        StringBuilder full = new StringBuilder();
        // 流式同样改用 chatResponse():逐块取文本下发,并在结束时读取真实 token 用量(通常随最后一块下发)
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        Flux<ServerSentEvent<String>> tokenEvents = chatClient.prompt()
                .system(buildSystemPrompt(docs))
                .messages(history)
                .user(question)
                .stream()
                .chatResponse()
                .timeout(Duration.ofMillis(GovernanceConstants.LLM_TIMEOUT_MILLIS))
                .doOnNext(cr -> {
                    if (cr.getMetadata() != null && cr.getMetadata().getUsage() != null) {
                        usageRef.set(cr.getMetadata().getUsage());
                    }
                })
                .map(this::tokenText)
                .filter(StrUtil::isNotEmpty)
                .doOnNext(full::append)
                .map(token -> ServerSentEvent.<String>builder(token).event("message").build());

        Flux<ServerSentEvent<String>> doneEvent = Flux.defer(() -> {
            String answer = full.toString();
            saveTurn(tenantId, conversationId, question, answer);
            breaker.recordSuccess();
            recordLlmUsage(tenantId, usageRef.get());
            if (ownLock) {
                semanticCache.save(tenantId, kbId, question, answer, sources, isAnswered(answer));
            }
            return Flux.just(ServerSentEvent.<String>builder("[DONE]").event("done").build());
        });

        return Flux.concat(metaEvent, tokenEvents, doneEvent)
                .onErrorResume(e -> {
                    // LLM 流式出错/超时 → 记熔断失败,切降级流
                    log.error("RAG 流式回答异常,降级兜底 convId={}", conversationId, e);
                    breaker.recordFailure();
                    return streamDegraded(tenantId, conversationId);
                })
                // 不管完成/取消/异常都释放信号量名额与锁,避免泄漏/死锁
                .doFinally(sig -> {
                    bulkhead.release(tenantId);
                    if (ownLock) semanticCache.unlock(tenantId, kbId, question);
                });
    }

    /** 流式降级兜底:按 code point 逐字回放静态 FAQ 话术(保持前端流式 UX),不调用 LLM、不落历史/缓存 */
    private Flux<ServerSentEvent<String>> streamDegraded(Long tenantId, String conversationId) {
        metrics.recordDegraded(tenantId);
        JSONObject meta = new JSONObject()
                .set("conversationId", conversationId)
                .set("sources", List.of())
                .set("cached", false)
                .set("degraded", true);
        Flux<ServerSentEvent<String>> metaEvent =
                Flux.just(ServerSentEvent.<String>builder(meta.toString()).event("meta").build());
        Flux<ServerSentEvent<String>> tokenEvents =
                Flux.fromIterable(toCodePoints(ChatConstants.DEGRADE_FALLBACK_ANSWER))
                        .map(token -> ServerSentEvent.<String>builder(token).event("message").build());
        Flux<ServerSentEvent<String>> doneEvent =
                Flux.just(ServerSentEvent.<String>builder("[DONE]").event("done").build());
        return Flux.concat(metaEvent, tokenEvents, doneEvent);
    }

    /** 流式:命中语义缓存,按缓存答案逐字下发(保持前端流式 UX 一致),不调用 LLM */
    private Flux<ServerSentEvent<String>> streamCached(Long tenantId, String conversationId,
                                                       String question, CachedAnswer cached) {
        metrics.recordCacheHit(tenantId);
        saveTurn(tenantId, conversationId, question, cached.getAnswer());

        JSONObject meta = new JSONObject()
                .set("conversationId", conversationId)
                .set("sources", cached.getSources())
                .set("cached", true)
                .set("degraded", false);
        Flux<ServerSentEvent<String>> metaEvent =
                Flux.just(ServerSentEvent.<String>builder(meta.toString()).event("meta").build());

        Flux<ServerSentEvent<String>> tokenEvents = Flux.fromIterable(toCodePoints(cached.getAnswer()))
                .map(token -> ServerSentEvent.<String>builder(token).event("message").build());

        Flux<ServerSentEvent<String>> doneEvent =
                Flux.just(ServerSentEvent.<String>builder("[DONE]").event("done").build());

        return Flux.concat(metaEvent, tokenEvents, doneEvent);
    }

    // ───────────────────── 生成(召回 + LLM)─────────────────────

    /** 单次生成结果:答案 + 引用溯源 + 是否在知识库中找到了答案(决定缓存 TTL)+ 是否为降级兜底 */
    private record Generated(String answer, List<ChatResponseDTO.Source> sources, boolean answered, boolean degraded) {
    }

    /**
     * 带治理的同步生成(M5):熔断放行判断 → 租户信号量隔离 → 超时调 LLM → 失败/超时/过载/跳闸 一律降级兜底。
     * 注:语义缓存命中路径不经此方法,故不占治理资源。
     */
    private Generated generate(String question, Long tenantId, Long kbId, List<Message> history) {
        // 熔断:跳闸中直接降级(快速失败,毫秒级返回,不计失败 —— 这本就是保护)
        if (!breaker.allow()) {
            log.warn("LLM 熔断跳闸中,直接降级兜底 tenantId={}", tenantId);
            return degraded(tenantId);
        }
        // 隔离:抢该租户信号量名额,满了 → 过载降级(chat 以兜底消化过载,不抛 503)
        if (!bulkhead.tryAcquire(tenantId)) {
            return degraded(tenantId);
        }
        try {
            Generated g = callLlmWithTimeout(question, tenantId, kbId, history);
            breaker.recordSuccess();
            return g;
        } catch (Exception e) {
            log.warn("LLM 生成失败/超时,降级兜底 tenantId={}", tenantId, e);
            breaker.recordFailure();
            return degraded(tenantId);
        } finally {
            bulkhead.release(tenantId);
        }
    }

    /** 在有界线程池上调用 LLM 并施加超时;超时即取消并抛出(由调用方计为熔断失败) */
    private Generated callLlmWithTimeout(String question, Long tenantId, Long kbId, List<Message> history)
            throws Exception {
        Future<Generated> future = llmExecutor.submit(() -> rawGenerate(question, tenantId, kbId, history));
        try {
            return future.get(GovernanceConstants.LLM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            throw (cause instanceof Exception) ? (Exception) cause : ee;
        } catch (TimeoutException te) {
            future.cancel(true);
            throw te;
        }
    }

    /** 原始生成:故障注入点 → 召回 → 拼 Prompt → LLM(无治理,被 {@link #generate} 守护) */
    private Generated rawGenerate(String question, Long tenantId, Long kbId, List<Message> history) {
        faultInjector.failIfEnabled();   // 演示:注入故障 → 抛错 → 计熔断失败,驱动跳闸
        List<Document> docs = retrieve(question, tenantId, kbId);
        // 用 chatResponse() 而非 content(),以便读取真实 token 用量(M7 看板的 token 消耗指标)
        ChatResponse resp = chatClient.prompt()
                .system(buildSystemPrompt(docs))
                .messages(history)
                .user(question)
                .call()
                .chatResponse();
        String answer = resp != null && resp.getResult() != null && resp.getResult().getOutput() != null
                ? resp.getResult().getOutput().getText() : "";
        recordLlmUsage(tenantId, resp != null && resp.getMetadata() != null ? resp.getMetadata().getUsage() : null);
        return new Generated(answer, toSources(docs), isAnswered(answer), false);
    }

    /** 记一次成功 LLM 调用 + 累加真实 token 用量到看板(total 缺失时回退为 prompt+completion) */
    private void recordLlmUsage(Long tenantId, Usage usage) {
        long prompt = usage != null ? toLong(usage.getPromptTokens()) : 0;
        long completion = usage != null ? toLong(usage.getCompletionTokens()) : 0;
        long total = usage != null ? toLong(usage.getTotalTokens()) : 0;
        if (total == 0) {
            total = prompt + completion;
        }
        metrics.recordLlmCall(tenantId, prompt, completion, total);
    }

    private long toLong(Integer v) {
        return v == null ? 0L : v.longValue();
    }

    /** 从流式 ChatResponse 取本块文本(空块返回 "") */
    private String tokenText(ChatResponse cr) {
        if (cr == null || cr.getResult() == null || cr.getResult().getOutput() == null) {
            return "";
        }
        String t = cr.getResult().getOutput().getText();
        return t == null ? "" : t;
    }

    /** 降级兜底:静态 FAQ 话术(不再调 LLM/embedding,避免级联故障);degraded=true */
    private Generated degraded(Long tenantId) {
        metrics.recordDegraded(tenantId);
        return new Generated(ChatConstants.DEGRADE_FALLBACK_ANSWER, List.of(), false, true);
    }

    /** 是否真的答出来了:非空且不含「未找到」兜底语。决定缓存按有效答案还是空值存(穿透防护)。 */
    private boolean isAnswered(String answer) {
        return StrUtil.isNotBlank(answer) && !answer.contains(ChatConstants.NOT_FOUND_MARKER);
    }

    /** 命中缓存的统一返回:落多轮上下文 + 标记 cached=true */
    private ChatResponseDTO cachedResponse(Long tenantId, String conversationId, String question, CachedAnswer cached) {
        metrics.recordCacheHit(tenantId);
        saveTurn(tenantId, conversationId, question, cached.getAnswer());
        return new ChatResponseDTO(conversationId, cached.getAnswer(), cached.getSources(), true, false);
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

    /** 把字符串按 Unicode code point 切成单字列表(正确处理中文/surrogate),用于流式逐字下发缓存答案 */
    private List<String> toCodePoints(String s) {
        if (StrUtil.isEmpty(s)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(s.length());
        s.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
        return out;
    }
}
