package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IAuthService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名访客入口:面向终端用户的聊天挂件用。公开端点(不需登录)。
 * <p>
 * 终端用户进入挂件 → {@code POST /visitor/session?tenant={code}} 领取一个免登录游客 token,
 * 之后的问答 {@code /chat(/stream)}、转人工 {@code /ticket/transfer}、实时会话 {@code /ws/chat}
 * 全部复用现有多租户鉴权链(游客 token 与登录 token 同构,role=VISITOR)。
 */
@RestController
@RequestMapping("/visitor")
public class VisitorController {

    @Resource
    private IAuthService authService;

    /** 领取匿名游客会话(按租户编码) */
    @PostMapping("/session")
    public Result session(@RequestParam("tenant") String tenantCode) {
        return authService.visitorSession(tenantCode);
    }
}
