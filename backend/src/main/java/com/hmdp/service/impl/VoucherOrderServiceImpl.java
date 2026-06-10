package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;


    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("/LuaScripts/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //private class VoucherOrderHandler implements Runnable {
    //
    //    String queueName = "stream.orders";
    //
    //    @Override
    //    public void run() {
    //        // 初始化消费者组
    //        try {
    //            // 创建消费者组，如果组已存在则忽略错误
    //            stringRedisTemplate.opsForStream().createGroup(queueName, "g1");
    //        } catch (Exception e) {
    //            log.warn("消费者组g1已存在，跳过创建");
    //        }
    //        while (true) {
    //            try {
    //                // 1.获取消息队列中的订单信息
    //                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
    //                        Consumer.from("g1", "c1"),
    //                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
    //                        StreamOffset.create(queueName, ReadOffset.lastConsumed())
    //                );
    //                // 2.判断消息是否获取成功
    //                if (list == null || list.isEmpty()) {
    //                    // 2.1 如果获取失败，继续下一次循环
    //                    continue;
    //                }
    //                // 解析消息中的订单消息
    //                MapRecord<String, Object, Object> record = list.get(0);
    //                Map<Object, Object> value = record.getValue();
    //                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
    //                // 3.如果获取成功，可以下单
    //                handleVoucherOrder(voucherOrder);
    //                // 4.ACK确认
    //                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
    //            } catch (Exception e) {
    //                log.error("订单处理异常", e);
    //                handlePendingList();
    //            }
    //        }
    //    }
    //
    //    private void handlePendingList() {
    //        while (true) {
    //            try {
    //                // 1.获取pending-list中的订单信息
    //                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
    //                        Consumer.from("g1", "c1"),
    //                        StreamReadOptions.empty().count(1),
    //                        StreamOffset.create(queueName, ReadOffset.from("0"))
    //                );
    //                // 2.判断消息是否获取成功
    //                if (list == null || list.isEmpty()) {
    //                    // 2.1 如果获取失败，则跳出
    //                    break;
    //                }
    //                // 解析消息中的订单消息
    //                MapRecord<String, Object, Object> record = list.get(0);
    //                Map<Object, Object> value = record.getValue();
    //                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
    //                // 3.如果获取成功，可以下单
    //                handleVoucherOrder(voucherOrder);
    //                // 4.ACK确认
    //                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
    //            } catch (Exception e) {
    //                log.error("订单pending-list处理异常", e);
    //                try {
    //                    Thread.sleep(20);
    //                } catch (InterruptedException ex) {
    //                    throw new RuntimeException(ex);
    //                }
    //            }
    //        }
    //    }
    //}

    // 阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                // 1.获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("订单处理异常", e);
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        //SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁 失败不等待
        boolean isLock = redisLock.tryLock();
        // 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 抢购秒杀券
     *
     * @param voucherId
     * @return
     */
    //@Override
    //public Result seckillVoucher(Long voucherId) {
    //    // 1.查询优惠券
    //    SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
    //    // 2.判断秒杀是否开始
    //    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
    //        return Result.fail("秒杀尚未开始！");
    //    }
    //    // 3.判断秒杀是否结束
    //    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
    //        return Result.fail("秒杀已经结束！");
    //    }
    //    // 4.判断库存是否充足
    //    if (voucher.getStock() < 1) {
    //        return Result.fail("库存不足！");
    //    }
    //    Long userId = UserHolder.getUser().getId();
    //    // 创建锁对象
    //    //SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
    //    RLock redisLock = redissonClient.getLock("lock:order:" + userId);
    //    // 获取锁 失败不等待
    //    boolean isLock = redisLock.tryLock();
    //    // 判断是否获取锁成功
    //    if (!isLock) {
    //        // 获取锁失败
    //        return Result.fail("不允许重复下单！");
    //    }
    //    try {
    //        // 获取代理对象
    //        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //        return proxy.createVoucherOrder(voucherId);
    //    } catch (IllegalStateException e) {
    //        throw new RuntimeException(e);
    //    } finally {
    //        // 释放锁
    //        redisLock.unlock();
    //    }
    //}
    //@Override
    //public Result seckillVoucher(Long voucherId) {
    //    // 获取用户
    //    Long userId = UserHolder.getUser().getId();
    //
    //    // 1.执行lua脚本
    //    Long result = stringRedisTemplate.execute(
    //            SECKILL_SCRIPT,
    //            Collections.emptyList(),
    //            voucherId.toString(), userId.toString()
    //    );
    //    // 2.判断结果是为0
    //    int r = result.intValue();
    //    if (r != 0) {
    //        // 2.1。不为0，代表没有购买资格
    //        return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
    //    }
    //    // 2.2.为0，有购买资格，把下单信息保存到阻塞队列
    //    long orderId = redisIdWorker.nextId("order");
    //    VoucherOrder voucherOrder = new VoucherOrder();
    //    voucherOrder.setVoucherId(voucherId);
    //    voucherOrder.setUserId(userId);
    //    voucherOrder.setId(orderId);
    //    // 放入阻塞队列
    //    orderTasks.add(voucherOrder);
    //    // 获取代理对象
    //    proxy = (IVoucherOrderService) AopContext.currentProxy();
    //    // 3.返回订单id
    //    return Result.ok(orderId);
    //}
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2.判断结果是为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1。不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.为0，有购买资格，把下单信息保存到阻塞队列

        //VoucherOrder voucherOrder = new VoucherOrder();
        //voucherOrder.setVoucherId(voucherId);
        //voucherOrder.setUserId(userId);
        //voucherOrder.setId(orderId);
        // 放入阻塞队列
        //orderTasks.add(voucherOrder);
        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3.返回订单id
        return Result.ok(orderId);
    }

    //@Transactional
    //public Result createVoucherOrder(Long voucherId) {
    //    // 一人一单
    //    Long userId = UserHolder.getUser().getId();
    //    int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    //    if (count > 0) {
    //        return Result.fail("用户已经购买过一次了！");
    //    }
    //    // 5.扣减库存
    //    boolean success = iSeckillVoucherService.update()
    //            .setSql("stock = stock - 1")
    //            .eq("voucher_id", voucherId).gt("stock", 0)
    //            .update();
    //    if (!success) {
    //        return Result.fail("库存不足！");
    //    }
    //    // 6.创建订单
    //    VoucherOrder voucherOrder = new VoucherOrder();
    //    voucherOrder.setVoucherId(voucherId);
    //    voucherOrder.setUserId(userId);
    //    long orderId = redisIdWorker.nextId("order");
    //    voucherOrder.setId(orderId);
    //    save(voucherOrder);
    //    // 7.返回订单id
    //    return Result.ok(orderId);
    //}

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次了！");
            return;
        }
        // 5.扣减库存
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
    }
}
