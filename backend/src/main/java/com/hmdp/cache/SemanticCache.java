package com.hmdp.cache;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.CacheConstants;
import com.hmdp.dto.ChatResponseDTO;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 语义缓存(M4)。把「问题 → 答案」按问题向量存进独立缓存索引,新问题先在这里按高阈值找相似历史问答,
 * 命中直接返回(省一次 LLM 调用、抗高并发读)。覆盖缓存三件套:
 * <ul>
 *   <li><b>穿透</b>:知识库找不到答案的「未命中型」回答也入缓存,但只给很短 TTL(空值缓存语义版);</li>
 *   <li><b>击穿</b>:未命中时对「同一问题」加互斥锁,只放一个线程打 LLM,其余等它回填(见 {@link #tryLock});</li>
 *   <li><b>雪崩</b>:缓存 TTL 叠加随机抖动,避免大量缓存同时过期。</li>
 * </ul>
 * 缓存项 TTL 通过对底层 Redis key {@code aics:cache:{id}} 单独 {@code EXPIRE} 实现,
 * 过期后 RediSearch 会惰性把它移出索引。
 */
@Slf4j
@Component
public class SemanticCache {

    @Resource
    private RedisVectorStore semanticCacheStore;

    @Resource
    private JedisPooled vectorStoreJedis;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 命中的缓存答案 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CachedAnswer {
        private String answer;
        private List<ChatResponseDTO.Source> sources;
        /** 是否为「知识库找到了答案」的有效命中(false = 未命中型空值缓存) */
        private boolean answered;
    }

    /**
     * 语义查找:按租户(+知识库)过滤,在缓存索引里找相似度超阈值的历史问答。
     * @return 命中则返回缓存答案,否则 empty
     */
    public Optional<CachedAnswer> lookup(Long tenantId, Long kbId, String question) {
        try {
            FilterExpressionBuilder fb = new FilterExpressionBuilder();
            var filter = fb.and(
                    fb.eq("tenantId", String.valueOf(tenantId)),
                    fb.eq("kbId", kbTag(kbId)));
            SearchRequest req = SearchRequest.builder()
                    .query(question)
                    .topK(1)
                    .similarityThreshold(CacheConstants.HIT_SIMILARITY_THRESHOLD)
                    .filterExpression(filter.build())
                    .build();
            List<Document> hits = semanticCacheStore.similaritySearch(req);
            if (hits == null || hits.isEmpty()) {
                return Optional.empty();
            }
            Document d = hits.get(0);
            Map<String, Object> md = d.getMetadata();
            String answer = String.valueOf(md.getOrDefault("answer", ""));
            boolean answered = Boolean.parseBoolean(String.valueOf(md.getOrDefault("answered", "false")));
            List<ChatResponseDTO.Source> sources = parseSources(String.valueOf(md.getOrDefault("sources", "[]")));
            log.info("语义缓存命中 tenantId={} kbId={} score={} answered={}", tenantId, kbId, d.getScore(), answered);
            return Optional.of(new CachedAnswer(answer, sources, answered));
        } catch (Exception e) {
            // 缓存只是优化,任何异常都退化为「未命中」,不影响主链路
            log.warn("语义缓存查找异常,降级为未命中 tenantId={} kbId={}", tenantId, kbId, e);
            return Optional.empty();
        }
    }

    /**
     * 写入缓存:问题作为被向量化的文本,answer/sources/answered 作为 metadata 随存。
     * TTL = 有效答案基础 TTL + 随机抖动(雪崩);未命中型答案用很短 TTL(穿透)。
     */
    public void save(Long tenantId, Long kbId, String question, String answer,
                     List<ChatResponseDTO.Source> sources, boolean answered) {
        try {
            Document doc = new Document(question, Map.of(
                    "tenantId", String.valueOf(tenantId),
                    "kbId", kbTag(kbId),
                    "question", question,
                    "answer", answer == null ? "" : answer,
                    "answered", String.valueOf(answered),
                    "sources", JSONUtil.toJsonStr(sources == null ? List.of() : sources)
            ));
            semanticCacheStore.add(List.of(doc));
            // 给底层 key 单独设 TTL:有效答案 base + 抖动;未命中型短 TTL
            long ttl = answered
                    ? CacheConstants.ANSWER_TTL_SECONDS
                      + ThreadLocalRandom.current().nextLong(CacheConstants.ANSWER_TTL_JITTER_SECONDS)
                    : CacheConstants.NULL_ANSWER_TTL_SECONDS;
            vectorStoreJedis.expire(CacheConstants.CACHE_PREFIX + doc.getId(), ttl);
            log.info("语义缓存写入 tenantId={} kbId={} answered={} ttl={}s", tenantId, kbId, answered, ttl);
        } catch (Exception e) {
            log.warn("语义缓存写入异常,忽略 tenantId={} kbId={}", tenantId, kbId, e);
        }
    }

    // ───────────────────── 击穿:互斥锁 ─────────────────────

    /** 抢重建锁(同一租户+知识库+问题维度);拿到的线程负责打 LLM 并回填缓存 */
    public boolean tryLock(Long tenantId, Long kbId, String question) {
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey(tenantId, kbId, question), "1",
                        CacheConstants.LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(ok);
    }

    public void unlock(Long tenantId, Long kbId, String question) {
        stringRedisTemplate.delete(lockKey(tenantId, kbId, question));
    }

    /**
     * 廉价等待持锁者重建完成:只轮询锁键是否还在(GET,不触发 embedding),
     * 直到锁释放或超过最长等待时间。返回后调用方再做一次语义查找读其回填结果。
     */
    public void awaitUnlock(Long tenantId, Long kbId, String question) {
        String key = lockKey(tenantId, kbId, question);
        long deadline = System.currentTimeMillis() + CacheConstants.LOCK_WAIT_MAX_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                return; // 锁已释放,持锁者已重建完成(或异常退出)
            }
            try {
                Thread.sleep(CacheConstants.LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String lockKey(Long tenantId, Long kbId, String question) {
        // 对 (kbId + 问题) 做 hash 作为锁维度,避免超长 key
        String hash = SecureUtil.md5(kbTag(kbId) + "|" + question);
        return RedisConstants.tenantKey(tenantId, CacheConstants.LOCK_KEY_SUFFIX + hash);
    }

    // ───────────────────── helpers ─────────────────────

    private String kbTag(Long kbId) {
        return kbId == null ? CacheConstants.KB_ALL_SENTINEL : String.valueOf(kbId);
    }

    private List<ChatResponseDTO.Source> parseSources(String json) {
        try {
            return JSONUtil.toList(json, ChatResponseDTO.Source.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
