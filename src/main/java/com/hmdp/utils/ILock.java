package com.hmdp.utils;

public interface ILock {
//    尝试获取锁；
//    true代表成功，false代表失败；
//    timeoutSec锁持有的时间超过后自动释放
    boolean tryLock(long timeoutSet);
    void unlock();
}
