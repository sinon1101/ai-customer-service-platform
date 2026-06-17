package com.hmdp.constant;

/**
 * M7 统计看板:业务指标采集相关常量。
 * <p>
 * 指标按租户存在一个 Redis Hash {@code t:{tenantId}:metrics} 里,各字段用 {@code HINCRBY}
 * 原子自增(跨实例共享、零新依赖,契合本项目「全 Redis 治理」主题)。看板读取时一次性
 * {@code HGETALL} 出来算命中率/降级率等派生指标。坐席效率不在这里,直接从 {@code ticket} 表 SQL 聚合。
 */
public class MetricsConstants {

    private MetricsConstants() {
    }

    /** 租户指标 Hash 的 key 后缀 → t:{tenantId}:metrics */
    public static final String METRICS_SUFFIX = "metrics";

    // ───────────────────── 会话/缓存/治理计数字段 ─────────────────────

    /** chat 会话量(通过限流、进入 service 的请求数) */
    public static final String F_CHAT_REQUESTS = "chat.requests";
    /** 可命中缓存的请求数(无历史单轮,语义缓存命中率的分母) */
    public static final String F_CACHE_ELIGIBLE = "cache.eligible";
    /** 语义缓存命中数 */
    public static final String F_CACHE_HIT = "cache.hit";
    /** 降级兜底次数(熔断/超时/失败/过载触发的静态话术返回) */
    public static final String F_DEGRADED = "chat.degraded";
    /** 实际成功的 LLM 调用次数(用于估真实 token 成本/QPS) */
    public static final String F_LLM_CALLS = "llm.calls";
    /** 限流拒绝次数(HTTP 429,未进入 service) */
    public static final String F_RATE_LIMITED = "chat.rateLimited";

    // ───────────────────── token 消耗(真实 Usage)─────────────────────

    /** 累计 prompt token */
    public static final String F_TOKENS_PROMPT = "tokens.prompt";
    /** 累计 completion token */
    public static final String F_TOKENS_COMPLETION = "tokens.completion";
    /** 累计 total token */
    public static final String F_TOKENS_TOTAL = "tokens.total";

    /** 看板返回时统一列出的全部计数字段(保证缺省值为 0、顺序稳定) */
    public static final String[] ALL_FIELDS = {
            F_CHAT_REQUESTS, F_CACHE_ELIGIBLE, F_CACHE_HIT, F_DEGRADED,
            F_LLM_CALLS, F_RATE_LIMITED,
            F_TOKENS_PROMPT, F_TOKENS_COMPLETION, F_TOKENS_TOTAL
    };
}
