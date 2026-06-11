package com.hmdp.service.impl;

import com.hmdp.service.IChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 最小对话:用 Spring AI 的 ChatClient 直连百炼 DashScope。
 * Spring AI 把模型抽象成统一接口,后续换模型 / 加 RAG 都不动这层调用方式。
 */
@Service
public class ChatServiceImpl implements IChatService {

    private final ChatClient chatClient;

    public ChatServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String chat(String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
