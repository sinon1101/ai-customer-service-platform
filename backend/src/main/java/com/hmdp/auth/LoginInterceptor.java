package com.hmdp.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 二层拦截器(order 1,作用于受保护路径):
 * {@link UserContext} 里没有登录态 → 拦截并返回 401。
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserContext.get() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
