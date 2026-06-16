package com.hmdp.config;

import com.hmdp.constant.TicketConstants;
import com.hmdp.ws.ChatWebSocketHandler;
import com.hmdp.ws.TicketMessageRelay;
import com.hmdp.ws.WsHandshakeInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * M6 实时会话装配:
 * <ul>
 *   <li>注册 WebSocket 端点 {@code /ws/chat} + 握手鉴权拦截器;</li>
 *   <li>注册 Redis Pub/Sub 监听容器,订阅 {@code ws:ticket:*},把跨实例消息投递到本地连接。</li>
 * </ul>
 * 复用现有 Lettuce {@link RedisConnectionFactory}(指向 6381),与缓存/鉴权同一条 Redis。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private ChatWebSocketHandler chatWebSocketHandler;

    @Resource
    private WsHandshakeInterceptor wsHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, TicketConstants.WS_PATH)
                .addInterceptors(wsHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public RedisMessageListenerContainer ticketMessageListenerContainer(
            RedisConnectionFactory connectionFactory, TicketMessageRelay relay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(relay, new PatternTopic(TicketConstants.WS_CHANNEL_PATTERN));
        return container;
    }
}
