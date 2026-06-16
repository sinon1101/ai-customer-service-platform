package com.hmdp.controller;

import com.hmdp.auth.UserContext;
import com.hmdp.constant.GovernanceConstants;
import com.hmdp.dto.LoginUser;
import com.hmdp.dto.Result;
import com.hmdp.governance.FaultInjector;
import com.hmdp.governance.LlmCircuitBreaker;
import com.hmdp.governance.RateLimiter;
import com.hmdp.governance.TenantBulkhead;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高并发治理管理面(M5,ADMIN)。用于观测与演示:
 * <ul>
 *   <li>{@code GET  /governance/status} —— 熔断器状态、故障注入开关、当前租户信号量占用、令牌桶水位(兼 M7 看板雏形);</li>
 *   <li>{@code POST /governance/fault?on=true|false} —— 切故障注入开关,故意触发熔断跳闸做演示(混沌工程)。</li>
 * </ul>
 */
@RestController
@RequestMapping("/governance")
public class GovernanceController {

    @Resource
    private LlmCircuitBreaker breaker;

    @Resource
    private FaultInjector faultInjector;

    @Resource
    private TenantBulkhead bulkhead;

    @Resource
    private RateLimiter rateLimiter;

    @GetMapping("/status")
    public Result status() {
        Result denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        Long tenantId = UserContext.getTenantId();
        Long userId = UserContext.getUserId();

        Map<String, Object> circuit = new LinkedHashMap<>();
        circuit.put("state", breaker.state());
        circuit.put("faultInjected", faultInjector.isEnabled());

        Map<String, Object> isolation = new LinkedHashMap<>();
        isolation.put("permits", GovernanceConstants.TENANT_BULKHEAD_PERMITS);
        isolation.put("used", bulkhead.usedPermits(tenantId));

        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("tenantCapacity", GovernanceConstants.RL_TENANT_CAPACITY);
        rateLimit.put("tenantTokens", rateLimiter.currentTokens(
                rateLimiter.tenantBucketKey(tenantId), GovernanceConstants.RL_TENANT_CAPACITY));
        rateLimit.put("userCapacity", GovernanceConstants.RL_USER_CAPACITY);
        rateLimit.put("userTokens", rateLimiter.currentTokens(
                rateLimiter.userBucketKey(tenantId, userId), GovernanceConstants.RL_USER_CAPACITY));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("circuitBreaker", circuit);
        data.put("isolation", isolation);
        data.put("rateLimit", rateLimit);
        return Result.ok(data);
    }

    @PostMapping("/fault")
    public Result fault(@RequestParam boolean on) {
        Result denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        faultInjector.set(on);
        return Result.ok("故障注入已" + (on ? "开启" : "关闭"));
    }

    /** 仅 ADMIN 可操作治理面;非 ADMIN 返回错误,放行返回 null */
    private Result requireAdmin() {
        LoginUser user = UserContext.get();
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return Result.fail("无权限");
        }
        return null;
    }
}
