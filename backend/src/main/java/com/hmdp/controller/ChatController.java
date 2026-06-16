package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.auth.UserContext;
import com.hmdp.dto.ChatRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.exception.RateLimitException;
import com.hmdp.governance.RateLimiter;
import com.hmdp.service.IChatService;
import com.hmdp.service.IKnowledgeBaseService;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * RAG 问答入口(M3)。需登录。
 * <ul>
 *   <li>{@code POST /chat}        —— 非流式,一次性返回答案 + 引用溯源(便于联调)。</li>
 *   <li>{@code POST /chat/stream} —— SSE 流式,先发 meta(conversationId+sources)再逐字推 answer。</li>
 * </ul>
 * 关键:租户上下文(tenantId)在 Controller 线程从 {@link UserContext} 取出后显式下传 ——
 * 流式回答异步执行,届时拦截器已清空 ThreadLocal,不能在 service 内再读。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private IChatService chatService;

    @Resource
    private IKnowledgeBaseService knowledgeBaseService;

    @Resource
    private RateLimiter rateLimiter;

    @PostMapping
    public Result chat(@RequestBody ChatRequestDTO request) {
        String error = validate(request);
        if (error != null) {
            return Result.fail(error);
        }
        // 限流(M5):租户+用户双层令牌桶,超限抛 RateLimitException → 由 WebExceptionAdvice 映射 429
        rateLimiter.checkChatRateLimit(UserContext.getTenantId(), UserContext.getUserId());
        return Result.ok(chatService.chat(request, UserContext.getTenantId()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequestDTO request) {
        String error = validate(request);
        if (error != null) {
            return Flux.just(ServerSentEvent.<String>builder(error).event("error").build());
        }
        // 限流(M5):SSE 路径把拒绝转成 error 事件(沿用校验失败的返回风格),不抛到 advice
        try {
            rateLimiter.checkChatRateLimit(UserContext.getTenantId(), UserContext.getUserId());
        } catch (RateLimitException e) {
            return Flux.just(ServerSentEvent.<String>builder(e.getMessage()).event("error").build());
        }
        // UserContext 还在请求线程上,先取出 tenantId 再进入异步流
        return chatService.chatStream(request, UserContext.getTenantId());
    }

    /** 公共校验:非空 + kbId 归属当前租户;返回错误文案,null 表示通过 */
    private String validate(ChatRequestDTO request) {
        if (request == null || StrUtil.isBlank(request.getMessage())) {
            return "message 不能为空";
        }
        if (request.getKbId() != null && !knowledgeBaseService.isOwned(request.getKbId())) {
            return "知识库不存在";
        }
        return null;
    }
}
