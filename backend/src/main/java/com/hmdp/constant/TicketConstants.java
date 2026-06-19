package com.hmdp.constant;

/**
 * M6 人工坐席相关常量:工单状态/原因、消息角色/类型、WebSocket 路径与 Redis Pub/Sub 频道、抢单锁。
 */
public class TicketConstants {

    /** RedisIdWorker 生成工单ID用的业务前缀 */
    public static final String TICKET_ID_PREFIX = "ticket";

    // ───────────────── 工单状态 ─────────────────
    public static final String STATUS_WAITING = "WAITING";   // 待接入(在池子里等坐席抢)
    public static final String STATUS_ASSIGNED = "ASSIGNED"; // 已接入(被某坐席抢到)
    public static final String STATUS_CLOSED = "CLOSED";     // 已结束

    // ───────────────── 转人工原因 ─────────────────
    public static final String REASON_USER_REQUEST = "USER_REQUEST"; // 用户主动转人工
    public static final String REASON_BOT_FAILED = "BOT_FAILED";     // 机器人答不了 / 降级兜底
    public static final String REASON_NOT_FOUND = "NOT_FOUND";       // 知识库无果

    // ───────────────── 消息发送方角色 ─────────────────
    public static final String SENDER_VISITOR = "VISITOR";
    public static final String SENDER_AGENT = "AGENT";
    public static final String SENDER_SYSTEM = "SYSTEM";

    // ───────────────── 消息类型(WsMessage.type)─────────────────
    public static final String MSG_TYPE_CHAT = "CHAT";     // 普通聊天消息
    public static final String MSG_TYPE_SYSTEM = "SYSTEM"; // 系统提示(坐席接入)
    public static final String MSG_TYPE_CLOSED = "CLOSED"; // 会话结束信号(收到后各实例主动断开该工单连接)

    // ───────────────── WebSocket / Redis Pub/Sub ─────────────────
    /** WebSocket 端点路径(握手时带 ?ticketId=&token=) */
    public static final String WS_PATH = "/ws/chat";
    /** Redis Pub/Sub 频道前缀:ws:ticket:{ticketId},跨实例把消息扇出到持有该工单连接的实例 */
    public static final String WS_CHANNEL_PREFIX = "ws:ticket:";
    /** 订阅用的频道通配模式 */
    public static final String WS_CHANNEL_PATTERN = "ws:ticket:*";

    /** 抢单分布式锁(Redisson)key 前缀:lock:ticket:claim:{ticketId} */
    public static final String CLAIM_LOCK_PREFIX = "lock:ticket:claim:";
    /** 抢单锁最长等待(秒):抢不到立刻让步,不阻塞坐席 */
    public static final long CLAIM_LOCK_WAIT_SECONDS = 0L;
    /** 抢单锁租约(秒):锁内只做一次条件更新,极短即可(0 走 Redisson 看门狗自动续期) */
    public static final long CLAIM_LOCK_LEASE_SECONDS = 5L;

    // ───────────────── 系统提示话术 ─────────────────
    public static final String SYS_AGENT_JOINED = "坐席「%s」已接入,很高兴为您服务。";
    public static final String SYS_TICKET_CLOSED = "本次人工会话已结束,感谢您的咨询。";

    private TicketConstants() {
    }
}
