package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.auth.UserContext;
import com.hmdp.constant.TicketConstants;
import com.hmdp.dto.LoginUser;
import com.hmdp.dto.Result;
import com.hmdp.dto.TransferRequestDTO;
import com.hmdp.dto.WsMessage;
import com.hmdp.entity.ChatMessage;
import com.hmdp.entity.Ticket;
import com.hmdp.mapper.TicketMapper;
import com.hmdp.service.IChatMessageService;
import com.hmdp.service.ITicketService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.ws.TicketMessageRelay;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 人工坐席工单实现(M6)。
 * <p>
 * 抢单是本里程碑的高并发「写争抢」核心,与点评秒杀同构:
 * <b>Redisson 锁</b>(带)互斥同一工单的并发请求,锁内执行
 * <b>{@code UPDATE ... WHERE id=? AND status='WAITING'}</b>(裤腰带)—— 这条 WHERE 是 InnoDB
 * 行锁级的 CAS,影响行数=1 才算抢到;即便锁意外失效,DB 这层也绝不会双重派单。
 * 同步裁决(看影响行数即时返回成功/失败),不走 MQ —— MySQL 是工单的唯一事实源。
 */
@Slf4j
@Service
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket> implements ITicketService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IChatMessageService chatMessageService;

    @Resource
    private TicketMessageRelay relay;

    @Override
    public Result transfer(TransferRequestDTO form) {
        LoginUser visitor = UserContext.get();
        Long tenantId = visitor.getTenantId();
        String conversationId = form.getConversationId();

        // 幂等:同一访客只要还有未结束(WAITING/ASSIGNED)的工单就复用,不重复建单。
        // 注意:不能只按 conversationId 判,因为访客没和 AI 对话就直接转人工时 conversationId 为空,
        // 那样会跳过幂等、每点一次建一张新单。改按 visitorUserId,conversationId 有无都能正确去重。
        Ticket existing = lambdaQuery()
                .eq(Ticket::getTenantId, tenantId)
                .eq(Ticket::getVisitorUserId, visitor.getId())
                .in(Ticket::getStatus, TicketConstants.STATUS_WAITING, TicketConstants.STATUS_ASSIGNED)
                .orderByDesc(Ticket::getCreateTime)
                .last("LIMIT 1")
                .one();
        if (existing != null) {
            return Result.ok(existing);
        }

        String reason = StrUtil.isNotBlank(form.getReason()) ? form.getReason() : TicketConstants.REASON_USER_REQUEST;
        Ticket t = new Ticket()
                .setId(redisIdWorker.nextId(TicketConstants.TICKET_ID_PREFIX))
                .setTenantId(tenantId)
                .setConversationId(conversationId == null ? "" : conversationId)
                .setKbId(form.getKbId())
                .setVisitorUserId(visitor.getId())
                .setVisitorName(visitor.getNickName())
                .setReason(reason)
                .setLastQuestion(truncate(form.getLastQuestion(), 500))
                .setStatus(TicketConstants.STATUS_WAITING);
        save(t);
        log.info("转人工建单 ticketId={} tenantId={} visitor={} reason={}", t.getId(), tenantId, visitor.getId(), reason);
        return Result.ok(t);
    }

    @Override
    public Result listPending() {
        LoginUser agent = UserContext.get();
        if (!isAgent(agent)) {
            return Result.fail("无坐席权限");
        }
        List<Ticket> list = lambdaQuery()
                .eq(Ticket::getTenantId, agent.getTenantId())
                .eq(Ticket::getStatus, TicketConstants.STATUS_WAITING)
                .orderByAsc(Ticket::getCreateTime)   // 先到先得
                .list();
        return Result.ok(list);
    }

    @Override
    public Result claim(Long ticketId) {
        LoginUser agent = UserContext.get();
        if (!isAgent(agent)) {
            return Result.fail("无坐席权限");
        }
        Long tenantId = agent.getTenantId();

        RLock lock = redissonClient.getLock(TicketConstants.CLAIM_LOCK_PREFIX + ticketId);
        boolean locked;
        try {
            // 带:互斥同一工单的并发抢单,抢不到立即让步(等待 0s),不阻塞坐席
            locked = lock.tryLock(TicketConstants.CLAIM_LOCK_WAIT_SECONDS,
                    TicketConstants.CLAIM_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("抢单被中断,请重试");
        }
        if (!locked) {
            return Result.fail("工单正被其他坐席处理,请刷新");
        }
        try {
            // 裤腰带:DB 行锁级 CAS,只有当前仍是 WAITING 才能改成 ASSIGNED,影响行数=1 即抢到
            boolean won = lambdaUpdate()
                    .eq(Ticket::getId, ticketId)
                    .eq(Ticket::getTenantId, tenantId)
                    .eq(Ticket::getStatus, TicketConstants.STATUS_WAITING)
                    .set(Ticket::getStatus, TicketConstants.STATUS_ASSIGNED)
                    .set(Ticket::getAgentId, agent.getId())
                    .set(Ticket::getAgentName, agent.getNickName())
                    .set(Ticket::getAssignTime, LocalDateTime.now())
                    .update();
            if (!won) {
                // 没抢到:区分「不存在/跨租户」与「已被别人接走」,给坐席清晰反馈
                Ticket cur = lambdaQuery().eq(Ticket::getId, ticketId).eq(Ticket::getTenantId, tenantId)
                        .last("LIMIT 1").one();
                if (cur == null) {
                    return Result.fail("工单不存在");
                }
                return Result.fail("手慢了,该工单已被其他坐席接走");
            }
            Ticket t = getById(ticketId);
            log.info("坐席抢单成功 ticketId={} agentId={} tenantId={}", ticketId, agent.getId(), tenantId);
            // 系统提示:坐席已接入(落库 + 实时推给访客)
            publishSystem(t, TicketConstants.MSG_TYPE_SYSTEM, String.format(TicketConstants.SYS_AGENT_JOINED,
                    StrUtil.blankToDefault(agent.getNickName(), agent.getUsername())));
            return Result.ok(t);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Result close(Long ticketId) {
        LoginUser user = UserContext.get();
        Long tenantId = user.getTenantId();
        Ticket t = findOwned(ticketId, tenantId);
        if (t == null) {
            return Result.fail("工单不存在");
        }
        // 参与方校验:仅访客本人或接单坐席可关闭
        boolean isParticipant = user.getId().equals(t.getVisitorUserId())
                || user.getId().equals(t.getAgentId());
        if (!isParticipant) {
            return Result.fail("无权操作该工单");
        }
        if (TicketConstants.STATUS_CLOSED.equals(t.getStatus())) {
            return Result.ok();   // 幂等
        }
        lambdaUpdate()
                .eq(Ticket::getId, ticketId)
                .set(Ticket::getStatus, TicketConstants.STATUS_CLOSED)
                .set(Ticket::getCloseTime, LocalDateTime.now())
                .update();
        t.setStatus(TicketConstants.STATUS_CLOSED);
        // 结束信号:type=CLOSED —— 各实例收到后投递这条提示并主动断开该工单的所有 WS 连接
        publishSystem(t, TicketConstants.MSG_TYPE_CLOSED, TicketConstants.SYS_TICKET_CLOSED);
        return Result.ok();
    }

    @Override
    public Result mine() {
        LoginUser agent = UserContext.get();
        if (!isAgent(agent)) {
            return Result.fail("无坐席权限");
        }
        List<Ticket> list = lambdaQuery()
                .eq(Ticket::getTenantId, agent.getTenantId())
                .eq(Ticket::getAgentId, agent.getId())
                .eq(Ticket::getStatus, TicketConstants.STATUS_ASSIGNED)
                .orderByDesc(Ticket::getAssignTime)
                .list();
        return Result.ok(list);
    }

    @Override
    public Result detail(Long ticketId) {
        LoginUser user = UserContext.get();
        Long tenantId = user.getTenantId();
        Ticket t = findOwned(ticketId, tenantId);
        if (t == null) {
            return Result.fail("工单不存在");
        }
        // 参与方校验:访客本人、接单坐席,或尚在待接入池的坐席(便于接入前预览)
        boolean isParticipant = user.getId().equals(t.getVisitorUserId())
                || user.getId().equals(t.getAgentId())
                || (isAgent(user) && TicketConstants.STATUS_WAITING.equals(t.getStatus()));
        if (!isParticipant) {
            return Result.fail("无权查看该工单");
        }
        List<ChatMessage> messages = chatMessageService.history(tenantId, ticketId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticket", t);
        data.put("messages", messages);
        return Result.ok(data);
    }

    @Override
    public Ticket findOwned(Long ticketId, Long tenantId) {
        if (ticketId == null || tenantId == null) {
            return null;
        }
        return lambdaQuery()
                .eq(Ticket::getId, ticketId)
                .eq(Ticket::getTenantId, tenantId)
                .last("LIMIT 1")
                .one();
    }

    /** 记一条系统提示并实时推给该工单的所有参与方(type 区分:SYSTEM 普通提示 / CLOSED 结束信号) */
    private void publishSystem(Ticket t, String type, String content) {
        chatMessageService.record(t.getTenantId(), t.getId(),
                TicketConstants.SENDER_SYSTEM, null, "系统", content);
        relay.publish(t.getId(), new WsMessage(
                type, t.getId(),
                TicketConstants.SENDER_SYSTEM, null, "系统", content, System.currentTimeMillis()));
    }

    /** 坐席权限:AGENT 或 ADMIN(ADMIN 在 demo 里可兼任坐席) */
    private boolean isAgent(LoginUser user) {
        return user != null && ("AGENT".equals(user.getRole()) || "ADMIN".equals(user.getRole()));
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
