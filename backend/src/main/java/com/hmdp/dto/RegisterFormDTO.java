package com.hmdp.dto;

import lombok.Data;

/**
 * 租户入驻:一次性创建租户 + 首个管理员账号。
 */
@Data
public class RegisterFormDTO {
    /** 企业名称 */
    private String tenantName;
    /** 租户编码(全局唯一) */
    private String tenantCode;
    /** 管理员登录名 */
    private String username;
    /** 管理员密码 */
    private String password;
    /** 管理员昵称 */
    private String nickName;
}
