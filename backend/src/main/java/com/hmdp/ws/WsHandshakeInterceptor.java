package com.hmdp.ws;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.constant.TicketConstants;
import com.hmdp.dto.LoginUser;
import com.hmdp.entity.Ticket;
import com.hmdp.service.ITicketService;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手鉴权(M6)。浏览器对 WS 不便带 header,故 token 走 query 参数:
 * {@code /ws/chat?ticketId={id}&token={token}}。
 * <p>
 * 校验链:① token → Redis 登录态(含 tenantId);② 工单属本租户;③ 连接者是该工单的<b>参与方</b>
 * (访客本人 或 接单坐席);④ 工单未结束。任一不过则拒绝握手(401)。
 * 通过后把身份(ticketId / tenantId / 角色 / 账号)塞进会话属性,供 {@link ChatWebSocketHandler} 使用。
 * <p>
 * 注:{@code /ws/**} 已在 {@code MvcConfig} 放行 LoginInterceptor —— 鉴权完全在这里做。
 */
@Slf4j
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_TICKET_ID = "ticketId";
    public static final String ATTR_TENANT_ID = "tenantId";
    public static final String ATTR_SENDER_ROLE = "senderRole";
    public static final String ATTR_SENDER_ID = "senderId";
    public static final String ATTR_SENDER_NAME = "senderName";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ITicketService ticketService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return reject(response, "不支持的握手请求");
        }
        HttpServletRequest req = servletRequest.getServletRequest();
        String token = req.getParameter("token");
        String ticketIdStr = req.getParameter("ticketId");
        if (StrUtil.isBlank(token) || StrUtil.isBlank(ticketIdStr)) {
            return reject(response, "缺少 token 或 ticketId");
        }

        // ① token → 登录态
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(RedisConstants.LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            return reject(response, "登录态无效");
        }
        LoginUser user = BeanUtil.fillBeanWithMap(userMap, new LoginUser(), false);

        Long ticketId;
        try {
            ticketId = Long.valueOf(ticketIdStr);
        } catch (NumberFormatException e) {
            return reject(response, "ticketId 非法");
        }

        // ② 工单属本租户
        Ticket ticket = ticketService.findOwned(ticketId, user.getTenantId());
        if (ticket == null) {
            return reject(response, "工单不存在");
        }
        // ④ 已结束不再开聊
        if (TicketConstants.STATUS_CLOSED.equals(ticket.getStatus())) {
            return reject(response, "会话已结束");
        }
        // ③ 参与方校验:访客本人 / 接单坐席
        String role;
        if (user.getId().equals(ticket.getVisitorUserId())) {
            role = TicketConstants.SENDER_VISITOR;
        } else if (user.getId().equals(ticket.getAgentId())) {
            role = TicketConstants.SENDER_AGENT;
        } else {
            return reject(response, "无权进入该会话");
        }

        attributes.put(ATTR_TICKET_ID, ticketId);
        attributes.put(ATTR_TENANT_ID, user.getTenantId());
        attributes.put(ATTR_SENDER_ROLE, role);
        attributes.put(ATTR_SENDER_ID, user.getId());
        attributes.put(ATTR_SENDER_NAME, StrUtil.blankToDefault(user.getNickName(), user.getUsername()));
        log.info("WS 握手通过 ticketId={} role={} userId={}", ticketId, role, user.getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private boolean reject(ServerHttpResponse response, String reason) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        log.warn("WS 握手拒绝:{}", reason);
        return false;
    }
}
