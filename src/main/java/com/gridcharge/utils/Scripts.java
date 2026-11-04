package com.gridcharge.utils;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/** TODO 学习任务：Lua 脚本加载。当前仅保留扩展入口，不注册自动执行任务。 */
public class Scripts {
    public static DefaultRedisScript<Long> load(String name) {
        throw new UnsupportedOperationException("待实现：Lua 脚本加载");
    }
}
