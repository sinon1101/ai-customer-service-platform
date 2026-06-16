package com.hmdp.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本实例(JVM)内的 WebSocket 会话注册表(M6):工单ID → 该工单在本机的所有连接(访客 + 坐席)。
 * <p>
 * 单机只看本表即可;多实例时,消息先经 {@link TicketMessageRelay} 通过 Redis Pub/Sub 扇出到各实例,
 * 各实例再用本表把消息投递给自己持有的连接。
 */
@Component
public class SessionRegistry {

    private final Map<Long, Set<WebSocketSession>> ticketSessions = new ConcurrentHashMap<>();

    public void add(Long ticketId, WebSocketSession session) {
        ticketSessions.computeIfAbsent(ticketId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void remove(Long ticketId, WebSocketSession session) {
        Set<WebSocketSession> sessions = ticketSessions.get(ticketId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                ticketSessions.remove(ticketId);
            }
        }
    }

    /** 取本机持有的某工单的连接集合(可能为空) */
    public Set<WebSocketSession> sessions(Long ticketId) {
        return ticketSessions.getOrDefault(ticketId, Set.of());
    }
}
