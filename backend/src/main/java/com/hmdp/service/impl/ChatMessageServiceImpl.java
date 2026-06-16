package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ChatMessage;
import com.hmdp.mapper.ChatMessageMapper;
import com.hmdp.service.IChatMessageService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 坐席会话消息持久化。所有读写都带 tenant_id,保持与平台一致的逻辑隔离。
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage>
        implements IChatMessageService {

    @Override
    public ChatMessage record(Long tenantId, Long ticketId, String senderRole,
                              Long senderId, String senderName, String content) {
        ChatMessage msg = new ChatMessage()
                .setTenantId(tenantId)
                .setTicketId(ticketId)
                .setSenderRole(senderRole)
                .setSenderId(senderId)
                .setSenderName(senderName)
                .setContent(content);
        save(msg);
        return msg;
    }

    @Override
    public List<ChatMessage> history(Long tenantId, Long ticketId) {
        return lambdaQuery()
                .eq(ChatMessage::getTenantId, tenantId)
                .eq(ChatMessage::getTicketId, ticketId)
                .orderByAsc(ChatMessage::getId)
                .list();
    }
}
