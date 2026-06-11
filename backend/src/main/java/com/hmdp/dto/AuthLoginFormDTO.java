package com.hmdp.dto;

import lombok.Data;

/**
 * 后台账号登录表单(username + password)。
 */
@Data
public class AuthLoginFormDTO {
    private String username;
    private String password;
}
