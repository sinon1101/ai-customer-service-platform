package com.hmdp.constant;

/**
 * M5 高并发治理相关常量(限流 / 隔离 / 熔断 / 降级)。
 * <p>
 * 项目灵魂是「用一套高并发治理体系驯服又慢又贵的 LLM 后端」:这里集中限流令牌桶、
 * 租户信号量隔离、LLM 熔断器、降级兜底的阈值与 Redis key 规范。
 * 保护的不是百炼,而是<b>我们自己的配额/账单、自己服务器的线程、租户间的公平</b>。
 */
public class GovernanceConstants {

    private GovernanceConstants() {
    }

    // ───────────────────── 限流:Redis+Lua 令牌桶(租户 + 用户双层)─────────────────────

    /** 租户级令牌桶 key 后缀 → t:{tenantId}:rl:chat(护住整体 LLM 配额) */
    public static final String RL_TENANT_SUFFIX = "rl:chat";

    /** 用户级令牌桶 key 后缀前段 → t:{tenantId}:rl:chat:u:{userId}(防单用户刷接口) */
    public static final String RL_USER_SUFFIX_PREFIX = "rl:chat:u:";

    /** 租户桶容量(允许的突发量) */
    public static final int RL_TENANT_CAPACITY = 20;
    /** 租户桶补充速率(令牌/秒):稳态出口速率,卡在 LLM 受得了的水平 */
    public static final double RL_TENANT_REFILL_PER_SEC = 5.0;

    /** 用户桶容量 */
    public static final int RL_USER_CAPACITY = 5;
    /** 用户桶补充速率(令牌/秒) */
    public static final double RL_USER_REFILL_PER_SEC = 1.0;

    // ───────────────────── 隔离:每租户信号量 Bulkhead ─────────────────────

    /** 每租户允许的 LLM 并发调用数(信号量名额);某租户暴涨最多占满自己这些,不拖垮别人 */
    public static final int TENANT_BULKHEAD_PERMITS = 3;

    // ───────────────────── 熔断:手搓 Redis 熔断器(全局护 LLM)─────────────────────

    /** 熔断器状态 key(全局,非租户维度;LLM 是所有租户共享的下游) */
    public static final String CB_KEY = "aics:cb:llm";

    /** 统计滚动窗口(秒):窗口内统计 LLM 调用成败 */
    public static final int CB_WINDOW_SECONDS = 30;
    /** 触发熔断的最小样本数:样本太少不熔断,避免偶发抖动误判 */
    public static final int CB_MIN_CALLS = 5;
    /** 触发熔断的失败率阈值(0~1):窗口内失败率 ≥ 此值且样本足够 → OPEN */
    public static final double CB_FAILURE_RATE_THRESHOLD = 0.5;
    /** OPEN 冷却时间(毫秒):冷却到点后放一个 HALF_OPEN 探测 */
    public static final long CB_OPEN_COOLDOWN_MILLIS = 15_000;

    // ───────────────────── LLM 超时(配合熔断:超时计为失败)─────────────────────

    /** 单次 LLM 调用超时(毫秒):超时即视为失败、计入熔断窗口并降级兜底 */
    public static final long LLM_TIMEOUT_MILLIS = 20_000;

    // ───────────────────── 故障注入(演示用,混沌工程)─────────────────────

    /** 故障注入开关 key:存在且为 "on" 时,被守护的 LLM 调用直接抛错,用于演示熔断跳闸 */
    public static final String FAULT_INJECT_KEY = "aics:fault:llm";
}
