package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时会话消息载体(M6):
 * <ul>
 *   <li>客户端 → 服务端:只需带 {@code content}(其余由服务端按连接身份补全);</li>
 *   <li>服务端 → Redis Pub/Sub → 各实例 → WebSocket:完整字段下发给该工单的所有参与方。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsMessage {

    /** 消息类型:CHAT 普通聊天 / SYSTEM 系统提示 */
    private String type;

    /** 所属工单 */
    private Long ticketId;

    /** 发送方角色:VISITOR / AGENT / SYSTEM */
    private String senderRole;

    /** 发送方账号ID(SYSTEM 为空) */
    private Long senderId;

    /** 发送方昵称 */
    private String senderName;

    /** 消息内容 */
    private String content;

    /** 发送时间戳(毫秒) */
    private Long timestamp;
}
