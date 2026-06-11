package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.ChatRequestDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IChatService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 最小对话入口(M1 冒烟)。需登录;真正的 RAG / SSE 流式 / 多轮放 M3。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private IChatService chatService;

    @PostMapping
    public Result chat(@RequestBody ChatRequestDTO request) {
        if (request == null || StrUtil.isBlank(request.getMessage())) {
            return Result.fail("message 不能为空");
        }
        return Result.ok(chatService.chat(request.getMessage()));
    }
}
