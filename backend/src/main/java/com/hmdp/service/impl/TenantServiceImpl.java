package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.Tenant;
import com.hmdp.mapper.TenantMapper;
import com.hmdp.service.ITenantService;
import org.springframework.stereotype.Service;

@Service
public class TenantServiceImpl extends ServiceImpl<TenantMapper, Tenant> implements ITenantService {
}
