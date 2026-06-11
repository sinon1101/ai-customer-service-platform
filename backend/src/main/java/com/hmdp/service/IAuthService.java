package com.hmdp.service;

import com.hmdp.dto.AuthLoginFormDTO;
import com.hmdp.dto.RegisterFormDTO;
import com.hmdp.dto.Result;

public interface IAuthService {

    /** 租户入驻:创建租户 + 首个管理员账号 */
    Result register(RegisterFormDTO form);

    /** 后台账号登录,成功返回 token */
    Result login(AuthLoginFormDTO form);

    /** 退出登录:删除 token */
    Result logout(String token);
}
