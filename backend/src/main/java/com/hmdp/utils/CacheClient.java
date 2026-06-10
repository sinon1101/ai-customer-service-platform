package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dpFallback, Long time, TimeUnit unit){
        // 1.从redis中查缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue()
                .get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断是否是空值
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4.不存在，查询数据库
        R r = dpFallback.apply(id);
        if (r == null) {
            // 将空值写入redis
            set(key, "", time, unit);
            // 5.数据库中不存在，返回错误
            return null;
        }
        // 6.写入redis
        set(key, r, time, unit);
        // 7.返回
        return r;
    }

    // 创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dpFallback, Long time, TimeUnit unit){
        // 1.从redis中查缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue()
                .get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在直接返回
            return null;
        }
        // 4.存在，需要判断过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 5.1 未过期，直接返回店铺信息
            return r;
        }
        // 5.2 已过期，需要重建缓存
        // 6.缓存重建
        // 6.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        // 6.2 判断是否获取互斥锁
        if (tryLock(lockKey)) {
            // todo 对缓存中是否有未过期的数据进行double check

            json = stringRedisTemplate.opsForValue()
                    .get(key);
            // 2.判断是否存在
            if (StrUtil.isBlank(json)) {
                // 3.不存在直接返回
                return null;
            }
            // 4.存在，需要判断过期时间
            redisData = JSONUtil.toBean(json, RedisData.class);
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();
            // 5.判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())){
                // 先要释放锁，不然会造成死锁
                unlock(lockKey);
                // 5.1 未过期，直接返回店铺信息
                return r;
            }

            // 6.3 成功开启独立线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    R r1 = dpFallback.apply(id);
                    setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4 返回过期的店铺信息
        return r;
    }

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

}
