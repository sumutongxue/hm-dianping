package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;//工具
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override

    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()){
            //创锁对象,调刚刚创建的
            SimpleRedisLock lock = new SimpleRedisLock
                    (stringRedisTemplate, "order:" + userId);
            //获取锁
            boolean isLock = lock.tryLock(1500);
            //是否成功
            if(!isLock){
                //重试或者返回错误，我们是高并发的下单的业务，所以返回错误
                return Result.fail("不允许多次下单");
            }try{//我们希望的是值一样，每次来都是一个全新的对象，搜调用intern
                //获取和事务相关的代理对象
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                return proxy.createVoucherOrder(voucherId);

            }finally {lock.unlock();

            }



        }


    }

    @Override
    public Result createVoucherOrder(Long voucherId) {
        return null;
    }

    @Transactional
    public Result creatVoucherOrder(Long voucherId) {

        //加入判断用户一人一单
        //查询
        Long userId = UserHolder.getUser().getId();


        int count = query().eq("use_id", userId).eq("voucher_id", voucherId).count();
        if(count>0){
            return  Result.fail("no");
        }
        //5，扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")//Set条件
                .eq("voucher_id", voucherId)
                .gt("stock",0)//where条件,修改为大于0
                .update();
        if (!success) {
            //扣减库存
            return Result.fail("库存不足！");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id，使用生成器获取全局唯一id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2.用户id
        voucherOrder.setUserId(userId);
        // 6.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
