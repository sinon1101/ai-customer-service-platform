package com.hmdp.governance;

import com.hmdp.constant.GovernanceConstants;
import com.hmdp.constant.ChatConstants;
import com.hmdp.exception.RateLimitException;
import com.hmdp.metrics.MetricsCollector;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;

/**
 * 令牌桶限流(M5)。Redis+Lua 原子「补令牌 + 扣令牌」,护住又慢又贵的 LLM 配额/账单。
 * <p>
 * chat 入口按<b>租户 + 用户双层</b>限流:租户桶护整体配额(防某租户打爆账单),
 * 用户桶防单个用户刷接口。任一桶无令牌即拒绝(HTTP 429)。
 * 复用点评 seckill/unlock 的 Redis+Lua 范式({@code DefaultRedisScript} + {@code ClassPathResource})。
 */
@Slf4j
@Component
public class RateLimiter {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MetricsCollector metrics;

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;
    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setLocation(new ClassPathResource("/LuaScripts/token_bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试从指定桶取 1 个令牌。
     * @return true=放行(已扣),false=令牌不足(应拒绝)
     */
    public boolean tryAcquire(String bucketKey, int capacity, double refillPerSec) {
        Long allowed = stringRedisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                Collections.singletonList(bucketKey),
                String.valueOf(capacity),
                String.valueOf(refillPerSec),
                String.valueOf(System.currentTimeMillis()),
                "1");
        return allowed != null && allowed == 1L;
    }

    /**
     * chat 入口双层限流:先租户桶,再用户桶;任一拒绝即抛 {@link RateLimitException}。
     * 在请求线程上调用(此时 UserContext 仍在)。
     */
    public void checkChatRateLimit(Long tenantId, Long userId) {
        if (!tryAcquire(tenantBucketKey(tenantId), GovernanceConstants.RL_TENANT_CAPACITY,
                GovernanceConstants.RL_TENANT_REFILL_PER_SEC)) {
            log.warn("租户级限流触发 tenantId={}", tenantId);
            metrics.recordRateLimited(tenantId);
            throw new RateLimitException(ChatConstants.RATE_LIMITED_MESSAGE);
        }
        if (userId != null) {
            if (!tryAcquire(userBucketKey(tenantId, userId), GovernanceConstants.RL_USER_CAPACITY,
                    GovernanceConstants.RL_USER_REFILL_PER_SEC)) {
                log.warn("用户级限流触发 tenantId={} userId={}", tenantId, userId);
                metrics.recordRateLimited(tenantId);
                throw new RateLimitException(ChatConstants.RATE_LIMITED_MESSAGE);
            }
        }
    }

    public String tenantBucketKey(Long tenantId) {
        return RedisConstants.tenantKey(tenantId, GovernanceConstants.RL_TENANT_SUFFIX);
    }

    public String userBucketKey(Long tenantId, Long userId) {
        return RedisConstants.tenantKey(tenantId, GovernanceConstants.RL_USER_SUFFIX_PREFIX + userId);
    }

    /** 读桶内当前剩余令牌(仅展示用,不补充不消费);桶不存在视为满 */
    public double currentTokens(String bucketKey, int capacity) {
        Object v = stringRedisTemplate.opsForHash().get(bucketKey, "tokens");
        return v == null ? capacity : Double.parseDouble(v.toString());
    }
}
