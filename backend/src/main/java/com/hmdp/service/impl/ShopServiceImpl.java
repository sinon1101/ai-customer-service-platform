package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.ReactiveKeyCommands;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,
        //        id, Shop.class, this::getById,
        //        RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    /**
     * 缓存穿透的查询店铺请求
     * @param id
     * @return
     */
    //public Shop queryWithPassThrough(Long id){
    //    // 1.从redis中查缓存
    //    String key = RedisConstants.CACHE_SHOP_KEY + id;
    //    String shopJson = stringRedisTemplate.opsForValue()
    //            .get(key);
    //    // 2.判断是否存在
    //    if (StrUtil.isNotBlank(shopJson)) {
    //        // 3.存在直接返回
    //        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
    //        return shop;
    //    }
    //    // 判断是否是空值
    //    if (shopJson != null) {
    //        // 返回错误信息
    //        return null;
    //    }
    //    // 4.不存在，查询数据库
    //    Shop shop = getById(id);
    //    if (shop == null) {
    //        // 将空值写入redis
    //        stringRedisTemplate.opsForValue()
    //                .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    //        // 5.数据库中不存在，返回错误
    //        return null;
    //    }
    //    // 6.写入redis
    //    stringRedisTemplate.opsForValue()
    //            .set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //    // 7.返回
    //    return shop;
    //}

    /**
     * 缓存击穿的查询店铺请求（通过互斥锁实现）
     * @param id
     * @return
     */
    //public Shop queryWithMutex(Long id){
    //    // 1.从redis中查缓存
    //    String key = RedisConstants.CACHE_SHOP_KEY + id;
    //    String shopJson = stringRedisTemplate.opsForValue()
    //            .get(key);
    //    // 2.判断是否存在
    //    if (StrUtil.isNotBlank(shopJson)) {
    //        // 3.存在直接返回
    //        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
    //        return shop;
    //    }
    //    // 判断是否是空值
    //    if (shopJson != null) {
    //        // 返回错误信息
    //        return null;
    //    }
    //
    //    // 4.实现缓存重建
    //    // 4.1获取互斥锁
    //    String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
    //    Shop shop = null;
    //    try {
    //        boolean isLock = tryLock(lockKey);
    //        // 4.2判断是否获取成功
    //        if (!isLock) {
    //            // 4.3失败则休眠并重试
    //            Thread.sleep(50);
    //            return queryWithMutex(id);
    //        }
    //        // 4.4成功则根据id查询数据库
    //        // todo 这里要做对redis中是否有数据做double check?
    //        shopJson = stringRedisTemplate.opsForValue().get(key);
    //        // 判断是否存在
    //        if (StrUtil.isNotBlank(shopJson)) {
    //            // 存在直接返回
    //            shop = JSONUtil.toBean(shopJson, Shop.class);
    //            return shop;
    //        }
    //        // 判断是否是空值
    //        if (shopJson == null) {
    //            // 返回错误信息
    //            return null;
    //        }
    //        // 根据店铺id查询数据库
    //        shop = getById(id);
    //        if (shop == null) {
    //            // 将空值写入redis
    //            stringRedisTemplate.opsForValue()
    //                    .set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    //            // 5.数据库中不存在，返回错误
    //            return null;
    //        }
    //        // 6.写入redis
    //        stringRedisTemplate.opsForValue()
    //                .set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    //    } catch (InterruptedException e) {
    //        throw new RuntimeException(e);
    //    } finally {
    //        // 7.释放互斥锁
    //        unlock(lockKey);
    //    }
    //
    //    // 8.返回
    //    return shop;
    //}

    // 创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 缓存击穿的查询店铺请求（通过逻辑过期实现）
     * @param id
     * @return
     */
    //public Shop queryWithLogicalExpire(Long id){
    //    // 1.从redis中查缓存
    //    String key = RedisConstants.CACHE_SHOP_KEY + id;
    //    String shopJson = stringRedisTemplate.opsForValue()
    //            .get(key);
    //    // 2.判断是否存在
    //    if (StrUtil.isBlank(shopJson)) {
    //        // 3.不存在直接返回
    //        return null;
    //    }
    //    // 4.存在，需要判断过期时间
    //    RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
    //    Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
    //    LocalDateTime expireTime = redisData.getExpireTime();
    //    // 5.判断是否过期
    //    if (expireTime.isAfter(LocalDateTime.now())){
    //        // 5.1 未过期，直接返回店铺信息
    //        return shop;
    //    }
    //    // 5.2 已过期，需要重建缓存
    //    // 6.缓存重建
    //    // 6.1 获取互斥锁
    //    String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
    //    // 6.2 判断是否获取互斥锁
    //    if (tryLock(lockKey)) {
    //        // todo 对缓存中是否有未过期的数据进行double check
    //
    //        shopJson = stringRedisTemplate.opsForValue()
    //                .get(key);
    //        // 2.判断是否存在
    //        if (StrUtil.isBlank(shopJson)) {
    //            // 3.不存在直接返回
    //            return null;
    //        }
    //        // 4.存在，需要判断过期时间
    //        redisData = JSONUtil.toBean(shopJson, RedisData.class);
    //        shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
    //        expireTime = redisData.getExpireTime();
    //        // 5.判断是否过期
    //        if (expireTime.isAfter(LocalDateTime.now())){
    //            // 先要释放锁，不然会造成死锁
    //            unlock(lockKey);
    //            // 5.1 未过期，直接返回店铺信息
    //            return shop;
    //        }
    //
    //        // 6.3 成功开启独立线程实现缓存重建
    //        CACHE_REBUILD_EXECUTOR.submit(() -> {
    //            try {
    //                // 重建缓存
    //                save2Redis(id, 20L);
    //            } catch (Exception e) {
    //                throw new RuntimeException(e);
    //            } finally {
    //                // 释放锁
    //                unlock(lockKey);
    //            }
    //        });
    //    }
    //    // 6.4 返回过期的店铺信息
    //    return shop;
    //}

    /**
     * 获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 将店铺数据保存到redis
     * @param id
     */
    //public void save2Redis(Long id, Long expireSeconds){
    //    // 1.查询店铺信息
    //    Shop shop = getById(id);
    //    // 2.封装逻辑过期时间
    //    RedisData redisData = new RedisData();
    //    redisData.setData(shop);
    //    redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
    //    // 3.写入redis
    //    stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    //}

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance);
        });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
