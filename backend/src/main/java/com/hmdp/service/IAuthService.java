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

    /**
     * 匿名访客会话:终端用户从挂件进入时,按租户编码领取一个免登录的游客 token
     * (与登录 token 同构,role=VISITOR),后续问答/转人工/实时会话复用现有鉴权链。
     */
    Result visitorSession(String tenantCode);
}
