package com.hmdp.ws;

import cn.hutool.core.util.StrUtil;
import com.hmdp.constant.TicketConstants;
import com.hmdp.dto.WsMessage;
import com.hmdp.service.IChatMessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 坐席 ↔ 访客实时会话处理器(M6)。
 * <p>
 * 连接身份在 {@link WsHandshakeInterceptor} 握手时已校验并塞入会话属性。
 * 收到一条文本 → 落库({@link IChatMessageService})→ {@link TicketMessageRelay} 发布到 Redis 频道
 * → 各实例订阅回调把消息投递给该工单的所有连接(含发送者本人,作为回显确认)。
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private SessionRegistry registry;

    @Resource
    private TicketMessageRelay relay;

    @Resource
    private IChatMessageService chatMessageService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.add(ticketId(session), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String content = message.getPayload();
        if (StrUtil.isBlank(content)) {
            return;
        }
        Long ticketId = ticketId(session);
        Long tenantId = (Long) session.getAttributes().get(WsHandshakeInterceptor.ATTR_TENANT_ID);
        String role = (String) session.getAttributes().get(WsHandshakeInterceptor.ATTR_SENDER_ROLE);
        Long senderId = (Long) session.getAttributes().get(WsHandshakeInterceptor.ATTR_SENDER_ID);
        String senderName = (String) session.getAttributes().get(WsHandshakeInterceptor.ATTR_SENDER_NAME);

        // 落库(历史回看 / 重连补全)
        chatMessageService.record(tenantId, ticketId, role, senderId, senderName, content);
        // 跨实例扇出 → 投递给该工单的所有参与方
        relay.publish(ticketId, new WsMessage(
                TicketConstants.MSG_TYPE_CHAT, ticketId, role, senderId, senderName,
                content, System.currentTimeMillis()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.remove(ticketId(session), session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS 传输异常 sessionId={}", session.getId(), exception);
        registry.remove(ticketId(session), session);
    }

    private Long ticketId(WebSocketSession session) {
        return (Long) session.getAttributes().get(WsHandshakeInterceptor.ATTR_TICKET_ID);
    }
}
