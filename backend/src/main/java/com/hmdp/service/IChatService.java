package com.hmdp.service;

import com.hmdp.dto.ChatRequestDTO;
import com.hmdp.dto.ChatResponseDTO;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * RAG 问答(M3):向量召回 → 拼 Prompt → LLM → (非流式整体 / SSE 流式) + 多轮上下文。
 * <p>
 * 注意:tenantId 由调用方(Controller 线程,UserContext 还在)显式传入 —— 流式回答是异步执行的,
 * 那时请求线程的 ThreadLocal 已被拦截器清空,不能在 service 里再读 UserContext。
 */
public interface IChatService {

    /** 非流式 RAG:一次性返回答案 + 引用溯源(便于联调/兜底) */
    ChatResponseDTO chat(ChatRequestDTO request, Long tenantId);

    /** 流式 RAG:SSE 先发一条 meta(conversationId+sources),再逐字推送 answer,最后 done */
    Flux<ServerSentEvent<String>> chatStream(ChatRequestDTO request, Long tenantId);
}
