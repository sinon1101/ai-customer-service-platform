package com.hmdp.governance;

import com.hmdp.constant.GovernanceConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;

/**
 * LLM 熔断器(M5)。手搓 Redis 状态机(CLOSED / OPEN / HALF_OPEN),给「调 LLM」装智能保险丝:
 * <ul>
 *   <li>{@link #allow()} —— 调 LLM 前问一句:能放行吗?OPEN 跳闸中直接拒绝(走兜底),冷却到点放一个探测;</li>
 *   <li>{@link #recordSuccess()} / {@link #recordFailure()} —— 调用结束后回报成败,驱动状态转移;</li>
 *   <li>{@link #state()} —— 当前状态,供 /governance/status 看板展示。</li>
 * </ul>
 * 状态存 Redis(跨实例共享),阈值见 {@link GovernanceConstants}。复用点评 Redis+Lua 原子计数范式。
 */
@Slf4j
@Component
public class LlmCircuitBreaker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> CB_SCRIPT;
    static {
        CB_SCRIPT = new DefaultRedisScript<>();
        CB_SCRIPT.setLocation(new ClassPathResource("/LuaScripts/circuit_breaker.lua"));
        CB_SCRIPT.setResultType(Long.class);
    }

    private Long run(String op) {
        return stringRedisTemplate.execute(
                CB_SCRIPT,
                Collections.singletonList(GovernanceConstants.CB_KEY),
                op,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(GovernanceConstants.CB_WINDOW_SECONDS * 1000L),
                String.valueOf(GovernanceConstants.CB_MIN_CALLS),
                String.valueOf(GovernanceConstants.CB_FAILURE_RATE_THRESHOLD),
                String.valueOf(GovernanceConstants.CB_OPEN_COOLDOWN_MILLIS));
    }

    /** 调 LLM 前的放行判断:true=可调用,false=熔断跳闸中(应直接走降级兜底) */
    public boolean allow() {
        Long r = run("allow");
        return r != null && r == 1L;
    }

    /** LLM 调用成功后回报:HALF_OPEN 下成功 → 合闸恢复 CLOSED */
    public void recordSuccess() {
        run("success");
    }

    /** LLM 调用失败/超时后回报:失败率超阈值 → 跳闸 OPEN;HALF_OPEN 下失败 → 立即回 OPEN */
    public void recordFailure() {
        run("failure");
    }

    /** 当前熔断状态(CLOSED/OPEN/HALF_OPEN),仅展示用的非原子读 */
    public String state() {
        Object s = stringRedisTemplate.opsForHash().get(GovernanceConstants.CB_KEY, "state");
        return s == null ? "CLOSED" : s.toString();
    }
}
