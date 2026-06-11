package com.hmdp.controller;

import com.hmdp.auth.UserContext;
import com.hmdp.dto.Result;
import com.hmdp.service.ITenantService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户信息。当前只暴露「查看本租户」,租户ID 一律取自登录态。
 */
@RestController
@RequestMapping("/tenant")
public class TenantController {

    @Resource
    private ITenantService tenantService;

    /** 查看当前登录账号所属租户 */
    @GetMapping("/current")
    public Result current() {
        return Result.ok(tenantService.getById(UserContext.getTenantId()));
    }
}
