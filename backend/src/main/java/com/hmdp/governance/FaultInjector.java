package com.hmdp.governance;

import com.hmdp.constant.GovernanceConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 故障注入开关(M5,演示用)。打开后,被守护的 LLM 调用会直接抛错 ——
 * 用来<b>故意触发</b>熔断跳闸做演示(混沌工程,业界标准做法),无需改动真实 API-KEY。
 * 开关存 Redis key {@link GovernanceConstants#FAULT_INJECT_KEY},通过 /governance/fault 切换。
 */
@Slf4j
@Component
public class FaultInjector {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 是否处于故障注入开启状态 */
    public boolean isEnabled() {
        return "on".equals(stringRedisTemplate.opsForValue().get(GovernanceConstants.FAULT_INJECT_KEY));
    }

    /** 切换故障注入开关 */
    public void set(boolean on) {
        if (on) {
            stringRedisTemplate.opsForValue().set(GovernanceConstants.FAULT_INJECT_KEY, "on");
        } else {
            stringRedisTemplate.delete(GovernanceConstants.FAULT_INJECT_KEY);
        }
        log.warn("故障注入开关 → {}", on ? "ON(LLM 调用将被强制失败)" : "OFF");
    }

    /** 若开启注入,抛错模拟 LLM 故障(在被守护的调用入口处调用) */
    public void failIfEnabled() {
        if (isEnabled()) {
            throw new IllegalStateException("注入故障:模拟 LLM 不可用");
        }
    }
}
