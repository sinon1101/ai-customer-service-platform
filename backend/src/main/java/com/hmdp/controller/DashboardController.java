package com.hmdp.controller;

import com.hmdp.auth.UserContext;
import com.hmdp.constant.GovernanceConstants;
import com.hmdp.constant.MetricsConstants;
import com.hmdp.constant.TicketConstants;
import com.hmdp.dto.LoginUser;
import com.hmdp.dto.Result;
import com.hmdp.governance.FaultInjector;
import com.hmdp.governance.LlmCircuitBreaker;
import com.hmdp.governance.RateLimiter;
import com.hmdp.governance.TenantBulkhead;
import com.hmdp.mapper.TicketMapper;
import com.hmdp.metrics.MetricsCollector;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M7 统计看板(ADMIN)。一站式汇总当前租户的运营/治理画像,供运营观测与压测演示:
 * <ul>
 *   <li><b>会话</b>:会话量、语义缓存命中率、降级率、限流拒绝数、LLM 调用次数;</li>
 *   <li><b>token</b>:真实 prompt/completion/total token 消耗(成本视角);</li>
 *   <li><b>坐席</b>:工单总量/各状态分布、平均等待时长、平均处理时长(从 ticket 表 SQL 聚合);</li>
 *   <li><b>治理实时态</b>:熔断状态、故障注入开关、信号量占用、令牌桶水位(复用 M5 gauge)。</li>
 * </ul>
 * 累计计数来自 Redis Hash {@code t:{tenantId}:metrics}(MetricsCollector 埋点),
 * 与 {@code GET /governance/status} 的实时 gauge 互补 —— 看板要的是<b>累计趋势</b>。
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @Resource
    private MetricsCollector metrics;

    @Resource
    private TicketMapper ticketMapper;

    @Resource
    private LlmCircuitBreaker breaker;

    @Resource
    private FaultInjector faultInjector;

    @Resource
    private TenantBulkhead bulkhead;

    @Resource
    private RateLimiter rateLimiter;

    @GetMapping("/overview")
    public Result overview() {
        Result denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        Long tenantId = UserContext.getTenantId();
        Long userId = UserContext.getUserId();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chat", chatBlock(tenantId));
        data.put("tokens", tokenBlock(tenantId));
        data.put("agent", agentBlock(tenantId));
        data.put("governance", governanceBlock(tenantId, userId));
        return Result.ok(data);
    }

    /** 会话维度:量 + 命中率/降级率(派生指标在服务端算好,前端直接展示) */
    private Map<String, Object> chatBlock(Long tenantId) {
        Map<String, Long> m = metrics.snapshot(tenantId);
        long requests = m.get(MetricsConstants.F_CHAT_REQUESTS);
        long eligible = m.get(MetricsConstants.F_CACHE_ELIGIBLE);
        long hit = m.get(MetricsConstants.F_CACHE_HIT);
        long degraded = m.get(MetricsConstants.F_DEGRADED);
        long llmCalls = m.get(MetricsConstants.F_LLM_CALLS);
        long rateLimited = m.get(MetricsConstants.F_RATE_LIMITED);

        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("requests", requests);
        chat.put("rateLimited", rateLimited);
        chat.put("llmCalls", llmCalls);
        chat.put("cacheEligible", eligible);
        chat.put("cacheHit", hit);
        chat.put("cacheHitRate", rate(hit, eligible));
        chat.put("degraded", degraded);
        chat.put("degradeRate", rate(degraded, requests));
        return chat;
    }

    /** token 维度:真实用量 + 每次 LLM 调用平均 token */
    private Map<String, Object> tokenBlock(Long tenantId) {
        Map<String, Long> m = metrics.snapshot(tenantId);
        long prompt = m.get(MetricsConstants.F_TOKENS_PROMPT);
        long completion = m.get(MetricsConstants.F_TOKENS_COMPLETION);
        long total = m.get(MetricsConstants.F_TOKENS_TOTAL);
        long llmCalls = m.get(MetricsConstants.F_LLM_CALLS);

        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("prompt", prompt);
        tokens.put("completion", completion);
        tokens.put("total", total);
        tokens.put("avgPerCall", llmCalls == 0 ? 0 : Math.round((double) total / llmCalls));
        return tokens;
    }

    /** 坐席维度:工单状态分布 + 平均等待/处理时长(ticket 表 SQL 聚合) */
    private Map<String, Object> agentBlock(Long tenantId) {
        long waiting = 0, assigned = 0, closed = 0, totalTickets = 0;
        List<Map<String, Object>> rows = ticketMapper.countByStatus(tenantId);
        for (Map<String, Object> row : rows) {
            String status = String.valueOf(row.get("status"));
            long cnt = ((Number) row.get("cnt")).longValue();
            totalTickets += cnt;
            if (TicketConstants.STATUS_WAITING.equals(status)) {
                waiting = cnt;
            } else if (TicketConstants.STATUS_ASSIGNED.equals(status)) {
                assigned = cnt;
            } else if (TicketConstants.STATUS_CLOSED.equals(status)) {
                closed = cnt;
            }
        }

        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("totalTickets", totalTickets);
        agent.put("waiting", waiting);
        agent.put("assigned", assigned);
        agent.put("closed", closed);
        agent.put("avgWaitSeconds", round1(ticketMapper.avgWaitSeconds(tenantId)));
        agent.put("avgHandleSeconds", round1(ticketMapper.avgHandleSeconds(tenantId)));
        return agent;
    }

    /** 治理实时态:复用 M5 的 gauge(熔断/故障/信号量/令牌桶),让看板一站式 */
    private Map<String, Object> governanceBlock(Long tenantId, Long userId) {
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

        Map<String, Object> gov = new LinkedHashMap<>();
        gov.put("circuitBreaker", circuit);
        gov.put("isolation", isolation);
        gov.put("rateLimit", rateLimit);
        return gov;
    }

    /** 比率 → 百分比保留 1 位小数;分母 0 返回 0 */
    private double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round((double) numerator / denominator * 1000) / 10.0;
    }

    private double round1(Double v) {
        if (v == null) {
            return 0.0;
        }
        return Math.round(v * 10) / 10.0;
    }

    /** 仅 ADMIN 可看看板;非 ADMIN 返回错误,放行返回 null */
    private Result requireAdmin() {
        LoginUser user = UserContext.get();
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return Result.fail("无权限");
        }
        return null;
    }
}
