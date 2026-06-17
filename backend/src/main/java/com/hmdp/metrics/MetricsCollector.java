package com.hmdp.metrics;

import com.hmdp.constant.MetricsConstants;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M7 统计看板:业务指标采集器。
 * <p>
 * 每个租户的累计计数集中在一个 Redis Hash {@code t:{tenantId}:metrics},
 * 用 {@code HINCRBY} 原子自增 —— 跨实例共享、零新依赖、与 M5 治理(限流/熔断)同源在 Redis。
 * 全部方法对 {@code null tenantId} 容错(不计),不抛异常,绝不影响主链路(埋点失败不应拖累问答)。
 * <p>
 * 实时 gauge(熔断状态/信号量占用/令牌桶水位)不在这里 —— 那些读 {@code GET /governance/status}
 * 已有的实时态;这里只管<b>累计量</b>(会话量/命中率/降级/token)。坐席效率从 {@code ticket} 表 SQL 聚合。
 */
@Component
public class MetricsCollector {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private String key(Long tenantId) {
        return RedisConstants.tenantKey(tenantId, MetricsConstants.METRICS_SUFFIX);
    }

    /** 原子自增一个计数字段;埋点失败吞掉异常,不影响主链路 */
    private void inc(Long tenantId, String field, long delta) {
        if (tenantId == null || delta == 0) {
            return;
        }
        try {
            stringRedisTemplate.opsForHash().increment(key(tenantId), field, delta);
        } catch (Exception ignore) {
            // 指标采集是旁路,失败不应影响问答
        }
    }

    // ───────────────────── 埋点入口 ─────────────────────

    /** chat 会话量 +1(通过限流、进入 service) */
    public void recordChatRequest(Long tenantId) {
        inc(tenantId, MetricsConstants.F_CHAT_REQUESTS, 1);
    }

    /** 本次请求可命中缓存(无历史单轮)+1 —— 命中率的分母 */
    public void recordCacheEligible(Long tenantId) {
        inc(tenantId, MetricsConstants.F_CACHE_ELIGIBLE, 1);
    }

    /** 语义缓存命中 +1 */
    public void recordCacheHit(Long tenantId) {
        inc(tenantId, MetricsConstants.F_CACHE_HIT, 1);
    }

    /** 降级兜底 +1 */
    public void recordDegraded(Long tenantId) {
        inc(tenantId, MetricsConstants.F_DEGRADED, 1);
    }

    /** 限流拒绝(429)+1 */
    public void recordRateLimited(Long tenantId) {
        inc(tenantId, MetricsConstants.F_RATE_LIMITED, 1);
    }

    /** 一次成功的 LLM 调用 +1,并累加真实 token 用量(任一为负/空按 0) */
    public void recordLlmCall(Long tenantId, long promptTokens, long completionTokens, long totalTokens) {
        inc(tenantId, MetricsConstants.F_LLM_CALLS, 1);
        inc(tenantId, MetricsConstants.F_TOKENS_PROMPT, Math.max(0, promptTokens));
        inc(tenantId, MetricsConstants.F_TOKENS_COMPLETION, Math.max(0, completionTokens));
        inc(tenantId, MetricsConstants.F_TOKENS_TOTAL, Math.max(0, totalTokens));
    }

    // ───────────────────── 看板读取 ─────────────────────

    /** 读取某租户全部累计计数;缺省字段补 0,字段顺序稳定 */
    public Map<String, Long> snapshot(Long tenantId) {
        Map<String, Long> out = new LinkedHashMap<>();
        Map<Object, Object> raw;
        try {
            raw = stringRedisTemplate.opsForHash().entries(key(tenantId));
        } catch (Exception e) {
            raw = Map.of();
        }
        for (String field : MetricsConstants.ALL_FIELDS) {
            Object v = raw.get(field);
            out.put(field, v == null ? 0L : parseLong(v));
        }
        return out;
    }

    private long parseLong(Object v) {
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
