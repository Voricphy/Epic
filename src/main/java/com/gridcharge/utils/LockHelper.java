package com.gridcharge.utils;

import java.util.function.Supplier;

/** TODO 学习任务：Redisson 分布式锁。当前仅保留扩展入口，不注册自动执行任务。 */
public class LockHelper {
    public <T> T withLock(String key, Supplier<T> action) {
        throw new UnsupportedOperationException("待实现：Redisson 分布式锁");
    }
}
