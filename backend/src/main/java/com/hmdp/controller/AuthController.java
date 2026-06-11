package com.hmdp.controller;

import com.hmdp.auth.UserContext;
import com.hmdp.dto.AuthLoginFormDTO;
import com.hmdp.dto.RegisterFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IAuthService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 多租户鉴权入口。/auth/register 与 /auth/login 公开,其余需登录。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private IAuthService authService;

    /** 租户入驻(创建租户 + 首个管理员) */
    @PostMapping("/register")
    public Result register(@RequestBody RegisterFormDTO form) {
        return authService.register(form);
    }

    /** 后台账号登录 */
    @PostMapping("/login")
    public Result login(@RequestBody AuthLoginFormDTO form) {
        return authService.login(form);
    }

    /** 当前登录态(含 tenantId) */
    @GetMapping("/me")
    public Result me() {
        return Result.ok(UserContext.get());
    }

    /** 退出登录 */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        return authService.logout(request.getHeader("authorization"));
    }
}
