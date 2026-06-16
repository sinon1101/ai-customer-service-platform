package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 坐席会话消息(M6):坐席 ↔ 访客实时聊天的落库记录。
 * <p>
 * 实时投递走 WebSocket + Redis Pub/Sub;本表持久化用于历史回看 / 重连补全。
 */
@Data
@Accessors(chain = true)
@TableName("chat_message")
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属租户 */
    private Long tenantId;

    /** 所属工单 */
    private Long ticketId;

    /** 发送方角色:VISITOR / AGENT / SYSTEM */
    private String senderRole;

    /** 发送方账号ID(SYSTEM 为空) */
    private Long senderId;

    /** 发送方昵称(冗余) */
    private String senderName;

    /** 消息内容 */
    private String content;

    private LocalDateTime createTime;
}
