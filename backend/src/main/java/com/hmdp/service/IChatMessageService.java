package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.ChatMessage;

import java.util.List;

/**
 * 坐席会话消息(M6):落库 + 按工单回看历史。实时投递在 WebSocket 层,这里只管持久化。
 */
public interface IChatMessageService extends IService<ChatMessage> {

    /** 记一条消息并返回(含落库后的 id/createTime) */
    ChatMessage record(Long tenantId, Long ticketId, String senderRole, Long senderId, String senderName, String content);

    /** 按工单取全部历史消息(时序升序),用于坐席/访客进入会话时回看 */
    List<ChatMessage> history(Long tenantId, Long ticketId);
}
