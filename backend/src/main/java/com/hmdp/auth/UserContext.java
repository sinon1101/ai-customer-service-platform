package com.hmdp.auth;

import com.hmdp.dto.LoginUser;

/**
 * 当前登录态的 ThreadLocal 持有者(多租户版)。
 * 由 {@link RefreshTokenInterceptor} 在请求进入时写入,在请求结束时清除。
 * 业务层通过 {@link #getTenantId()} 拿到租户上下文,实现逻辑隔离。
 */
public class UserContext {

    private static final ThreadLocal<LoginUser> TL = new ThreadLocal<>();

    public static void save(LoginUser user) {
        TL.set(user);
    }

    public static LoginUser get() {
        return TL.get();
    }

    public static void remove() {
        TL.remove();
    }

    /** 当前登录账号ID */
    public static Long getUserId() {
        LoginUser user = TL.get();
        return user == null ? null : user.getId();
    }

    /** 当前租户ID —— 多租户隔离的关键 */
    public static Long getTenantId() {
        LoginUser user = TL.get();
        return user == null ? null : user.getTenantId();
    }
}
