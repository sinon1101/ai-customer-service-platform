package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.AuthLoginFormDTO;
import com.hmdp.dto.LoginUser;
import com.hmdp.dto.RegisterFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.SysUser;
import com.hmdp.entity.Tenant;
import com.hmdp.service.IAuthService;
import com.hmdp.service.ISysUserService;
import com.hmdp.service.ITenantService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 多租户鉴权:租户入驻 + 后台账号登录。
 * 登录成功后,token 的 Redis Hash 里带上 tenantId,后续每个请求据此完成租户隔离。
 */
@Service
@Slf4j
public class AuthServiceImpl implements IAuthService {

    @Resource
    private ITenantService tenantService;
    @Resource
    private ISysUserService sysUserService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public Result register(RegisterFormDTO form) {
        if (StrUtil.hasBlank(form.getTenantName(), form.getTenantCode(), form.getUsername(), form.getPassword())) {
            return Result.fail("租户名/编码/账号/密码不能为空");
        }
        // 租户编码全局唯一
        if (tenantService.lambdaQuery().eq(Tenant::getCode, form.getTenantCode()).count() > 0) {
            return Result.fail("租户编码已存在");
        }
        // 登录名全局唯一
        if (sysUserService.lambdaQuery().eq(SysUser::getUsername, form.getUsername()).count() > 0) {
            return Result.fail("登录名已被占用");
        }
        // 1. 建租户
        Tenant tenant = new Tenant()
                .setName(form.getTenantName())
                .setCode(form.getTenantCode())
                .setStatus(1)
                .setDailyQuota(10000);
        tenantService.save(tenant);
        // 2. 建首个管理员账号
        SysUser admin = new SysUser()
                .setTenantId(tenant.getId())
                .setUsername(form.getUsername())
                .setPassword(PasswordEncoder.encode(form.getPassword()))
                .setNickName(StrUtil.blankToDefault(form.getNickName(), form.getUsername()))
                .setRole("ADMIN")
                .setStatus(1);
        sysUserService.save(admin);
        log.info("租户入驻成功 tenantId={} code={} admin={}", tenant.getId(), tenant.getCode(), admin.getUsername());
        return Result.ok(tenant.getId());
    }

    @Override
    public Result login(AuthLoginFormDTO form) {
        if (StrUtil.hasBlank(form.getUsername(), form.getPassword())) {
            return Result.fail("账号或密码不能为空");
        }
        SysUser user = sysUserService.lambdaQuery()
                .eq(SysUser::getUsername, form.getUsername())
                .one();
        if (user == null || !PasswordEncoder.matches(user.getPassword(), form.getPassword())) {
            return Result.fail("账号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            return Result.fail("账号已被禁用");
        }
        // 租户状态校验
        Tenant tenant = tenantService.getById(user.getTenantId());
        if (tenant == null || tenant.getStatus() == null || tenant.getStatus() != 1) {
            return Result.fail("所属租户不可用");
        }
        // 生成 token,登录态(含 tenantId)存入 Redis Hash
        String token = UUID.randomUUID().toString(true);
        LoginUser loginUser = new LoginUser();
        loginUser.setId(user.getId());
        loginUser.setTenantId(user.getTenantId());
        loginUser.setUsername(user.getUsername());
        loginUser.setNickName(user.getNickName());
        loginUser.setRole(user.getRole());

        Map<String, Object> userMap = BeanUtil.beanToMap(loginUser, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((name, value) -> value == null ? null : value.toString()));
        String key = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key, userMap);
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.HOURS);
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }
}
