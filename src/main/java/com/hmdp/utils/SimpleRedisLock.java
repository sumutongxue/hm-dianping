package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.TimeoutUtils;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }
    //不同业务有不同的锁，名字需要和业务有关,前面的需要用户传递给我们，所以需要构造函数赋值
    private static final String KEY_PREFIX="lock:";

    @Override
    public boolean tryLock(long timeoutSet) {
        //value代表线程,获取线程
        long threadId = Thread.currentThread().getId();
        Boolean  success=stringRedisTemplate.opsForValue().
                setIfAbsent(  KEY_PREFIX+name, threadId+"",
                        timeoutSet, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //调用lua脚本

    }
    //    @Override
//    public void unlock() {
//        //释放
//        stringRedisTemplate.delete(KEY_PREFIX+name);
//
//    }
}
