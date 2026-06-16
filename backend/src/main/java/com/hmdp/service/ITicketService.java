package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.TransferRequestDTO;
import com.hmdp.entity.Ticket;

/**
 * 人工坐席工单(M6):转人工建单 → 待接入池 → 多坐席抢单 → 会话结束。
 * 所有操作以 {@link com.hmdp.auth.UserContext#getTenantId()} 为边界,保持逻辑隔离。
 */
public interface ITicketService extends IService<Ticket> {

    /** 访客转人工:生成待接入工单(同对话已有未结束工单则幂等返回) */
    Result transfer(TransferRequestDTO form);

    /** 坐席侧:列出当前租户的待接入工单(先到先得) */
    Result listPending();

    /**
     * 坐席抢单:Redisson 锁互斥 + DB 条件更新(WHERE status='WAITING')双保险,
     * 影响行数=1 才算抢到;只有一个坐席能成功,绝不双重派单。
     */
    Result claim(Long ticketId);

    /** 结束会话(访客或接单坐席均可) */
    Result close(Long ticketId);

    /** 坐席侧:我当前已接入(未结束)的工单 */
    Result mine();

    /** 工单详情 + 历史消息(供进入会话时回看);越权返回失败 */
    Result detail(Long ticketId);

    /** 取当前租户下的工单(不存在或跨租户返回 null),供 WebSocket 握手做参与方校验 */
    Ticket findOwned(Long ticketId, Long tenantId);
}
