package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.auth.UserContext;
import com.hmdp.dto.KnowledgeBaseFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.KnowledgeBase;
import com.hmdp.mapper.KnowledgeBaseMapper;
import com.hmdp.service.IKnowledgeBaseService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 知识库 CRUD —— 所有操作都以 {@link UserContext#getTenantId()} 为边界,
 * 读写都强制带 tenant_id,任何跨租户访问都查不到 / 改不动,实现逻辑隔离。
 */
@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements IKnowledgeBaseService {

    @Override
    public Result create(KnowledgeBaseFormDTO form) {
        if (StrUtil.isBlank(form.getName())) {
            return Result.fail("知识库名称不能为空");
        }
        Long tenantId = UserContext.getTenantId();
        KnowledgeBase kb = new KnowledgeBase()
                .setTenantId(tenantId)
                .setName(form.getName())
                .setDescription(form.getDescription())
                .setStatus(1)
                .setDocCount(0);
        save(kb);
        return Result.ok(kb.getId());
    }

    @Override
    public Result listCurrentTenant() {
        Long tenantId = UserContext.getTenantId();
        List<KnowledgeBase> list = lambdaQuery()
                .eq(KnowledgeBase::getTenantId, tenantId)
                .orderByDesc(KnowledgeBase::getCreateTime)
                .list();
        return Result.ok(list);
    }

    @Override
    public Result getOne(Long id) {
        KnowledgeBase kb = getOwned(id);
        if (kb == null) {
            return Result.fail("知识库不存在");
        }
        return Result.ok(kb);
    }

    @Override
    public Result update(Long id, KnowledgeBaseFormDTO form) {
        KnowledgeBase kb = getOwned(id);
        if (kb == null) {
            return Result.fail("知识库不存在");
        }
        if (StrUtil.isNotBlank(form.getName())) {
            kb.setName(form.getName());
        }
        kb.setDescription(form.getDescription());
        updateById(kb);
        return Result.ok();
    }

    @Override
    public Result setStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            return Result.fail("状态非法");
        }
        KnowledgeBase kb = getOwned(id);
        if (kb == null) {
            return Result.fail("知识库不存在");
        }
        kb.setStatus(status);
        updateById(kb);
        return Result.ok();
    }

    @Override
    public Result remove(Long id) {
        KnowledgeBase kb = getOwned(id);
        if (kb == null) {
            return Result.fail("知识库不存在");
        }
        removeById(id);
        return Result.ok();
    }

    @Override
    public boolean isOwned(Long id) {
        return id != null && getOwned(id) != null;
    }

    @Override
    public boolean isEnabled(Long id, Long tenantId) {
        if (id == null || tenantId == null) {
            return false;
        }
        Long cnt = lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getTenantId, tenantId)
                .eq(KnowledgeBase::getStatus, 1)
                .count();
        return cnt != null && cnt > 0;
    }

    @Override
    public List<Long> listEnabledIds(Long tenantId) {
        if (tenantId == null) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .select(KnowledgeBase::getId)
                .eq(KnowledgeBase::getTenantId, tenantId)
                .eq(KnowledgeBase::getStatus, 1)
                .list()
                .stream()
                .map(KnowledgeBase::getId)
                .toList();
    }

    /** 取当前租户名下的知识库,拿不到(不存在或不属于本租户)返回 null,杜绝越权 */
    private KnowledgeBase getOwned(Long id) {
        Long tenantId = UserContext.getTenantId();
        return lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getTenantId, tenantId)
                .one();
    }
}
