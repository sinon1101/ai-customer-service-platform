package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询店铺分类
     * @return
     */
    @Override
    public Result queryTypeList() {
        // 1.在redis中查询
        String shopTypeJson = stringRedisTemplate.opsForValue()
                .get("cache:shoptype");
        // 2.查询到直接返回
        if (StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 3.没查询到，在数据库中查询
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        // 4.数据库中未查询到，报错
        if (shopTypeList == null || shopTypeList.size() == 0) {
            return Result.fail("未查询到店铺类型");
        }
        // 5.数据库中查询到，先保存到redis中
        stringRedisTemplate.opsForValue().set("cache:shoptype", JSONUtil.toJsonStr(shopTypeList));
        // 6.返回
        return Result.ok(shopTypeList);
    }
}
