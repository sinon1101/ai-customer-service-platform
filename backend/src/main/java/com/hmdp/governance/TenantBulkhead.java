package com.hmdp.governance;

import com.hmdp.constant.GovernanceConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 租户隔离(Bulkhead,M5)。给每个租户一个独立、有上限的信号量名额池:
 * 某租户流量暴涨,最多占满<b>它自己</b>那 N 个名额(占满则其新请求被拒/降级),
 * 但<b>别的租户的名额毫发无损</b> —— 故障不扩散,像轮船的隔舱。
 * <p>
 * 单机内存态(Bulkhead 教科书实现,单节点 demo 足够;熔断器走 Redis 跨实例,两者定位不同)。
 * 仅在<b>真正要调 LLM</b> 时抢名额:语义缓存命中路径不碰 LLM,不占名额。
 */
@Slf4j
@Component
public class TenantBulkhead {

    /** 按租户惰性创建的信号量;permits 来自 {@link GovernanceConstants#TENANT_BULKHEAD_PERMITS} */
    private final Map<Long, Semaphore> semaphores = new ConcurrentHashMap<>();

    private Semaphore semaphoreOf(Long tenantId) {
        return semaphores.computeIfAbsent(tenantId,
                t -> new Semaphore(GovernanceConstants.TENANT_BULKHEAD_PERMITS));
    }

    /**
     * 尝试抢该租户一个并发名额(不阻塞)。
     * @return true=抢到(调用方负责 {@link #release} 释放),false=名额已满(过载)
     */
    public boolean tryAcquire(Long tenantId) {
        boolean ok = semaphoreOf(tenantId).tryAcquire();
        if (!ok) {
            log.warn("租户信号量已满,触发隔离过载 tenantId={} permits={}",
                    tenantId, GovernanceConstants.TENANT_BULKHEAD_PERMITS);
        }
        return ok;
    }

    /** 释放该租户一个并发名额(必须与成功的 tryAcquire 配对,放在 finally / doFinally) */
    public void release(Long tenantId) {
        Semaphore s = semaphores.get(tenantId);
        if (s != null) {
            s.release();
        }
    }

    /** 当前该租户已占用的名额数(供 /governance/status 看板展示) */
    public int usedPermits(Long tenantId) {
        Semaphore s = semaphores.get(tenantId);
        return s == null ? 0 : GovernanceConstants.TENANT_BULKHEAD_PERMITS - s.availablePermits();
    }
}
