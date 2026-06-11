package com.hmdp.config;

import com.hmdp.auth.LoginInterceptor;
import com.hmdp.auth.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

/**
 * 多租户鉴权拦截器链:
 *   order 0  RefreshTokenInterceptor —— 全路径,有 token 就把登录态(含 tenantId)读进 UserContext 并续期
 *   order 1  LoginInterceptor        —— 受保护路径,UserContext 为空则 401
 * 公开路径:租户入驻 /auth/register、登录 /auth/login。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/register"
                )
                .order(1);
    }
}
