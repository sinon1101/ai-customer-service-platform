package com.hmdp.dto;

import lombok.Data;

/**
 * 登录态载体:存入 Redis token Hash,并由拦截器读出放进 {@link com.hmdp.auth.UserContext}。
 * 多租户鉴权的核心 —— 每个请求线程都能拿到 tenantId。
 */
@Data
public class LoginUser {
    private Long id;
    private Long tenantId;
    private String username;
    private String nickName;
    private String role;
}
