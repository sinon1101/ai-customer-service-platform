package com.hmdp.ws;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.TicketConstants;
import com.hmdp.dto.WsMessage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;

/**
 * 工单消息中继(M6):跨实例的实时消息扇出。
 * <p>
 * 发送方所在实例把消息 <b>publish</b> 到 Redis 频道 {@code ws:ticket:{ticketId}};
 * 所有实例都订阅 {@code ws:ticket:*},收到后用本机 {@link SessionRegistry} 把消息投递给
 * 持有该工单连接的访客 / 坐席。这样坐席与访客即便落在不同实例也能实时互通。
 * <p>
 * 选 Redis Pub/Sub 而非 RocketMQ:聊天消息短暂、低延迟,发布订阅做扇出最合适;
 * MQ 偏可靠投递 + 有延迟,它的舞台是 M2 文档异步管道。
 */
@Slf4j
@Component
public class TicketMessageRelay implements MessageListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SessionRegistry registry;

    /** 发布一条工单消息到 Redis 频道,由各实例的订阅回调投递到本地连接 */
    public void publish(Long ticketId, WsMessage msg) {
        stringRedisTemplate.convertAndSend(
                TicketConstants.WS_CHANNEL_PREFIX + ticketId, JSONUtil.toJsonStr(msg));
    }

    /** Redis 订阅回调:解析频道里的 ticketId,把消息投递给本机持有的该工单连接 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        Long ticketId = parseTicketId(channel);
        if (ticketId == null) {
            return;
        }
        // 会话结束信号:投递这条「已结束」提示后,主动断开本机持有的该工单所有连接
        boolean closing = TicketConstants.MSG_TYPE_CLOSED.equals(parseType(body));
        TextMessage frame = new TextMessage(body);
        for (WebSocketSession session : registry.sessions(ticketId)) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                // WebSocketSession 非线程安全,并发投递需对单连接串行化
                synchronized (session) {
                    session.sendMessage(frame);
                    if (closing) {
                        session.close(CloseStatus.NORMAL);
                    }
                }
            } catch (Exception e) {
                log.warn("WS 消息投递失败 ticketId={} sessionId={}", ticketId, session.getId(), e);
            }
        }
    }

    /** 从消息体解析 type(失败返回 null,不影响普通投递) */
    private String parseType(String body) {
        try {
            return JSONUtil.parseObj(body).getStr("type");
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseTicketId(String channel) {
        String idStr = StrUtil.removePrefix(channel, TicketConstants.WS_CHANNEL_PREFIX);
        try {
            return Long.valueOf(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
