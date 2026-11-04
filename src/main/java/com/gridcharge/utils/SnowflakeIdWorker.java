package com.gridcharge.utils;

import cn.hutool.core.lang.Snowflake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 应用内共用一个发号器；不同实例必须配置不同的数据中心/机器编号组合。 */
@Component
public class SnowflakeIdWorker {
    private final Snowflake snowflake;

    public SnowflakeIdWorker(
            @Value("${epic.id.worker-id:0}") long workerId,
            @Value("${epic.id.datacenter-id:0}") long datacenterId) {
        // 使用 Hutool 默认纪元；禁止容忍时钟回拨，异常时停止发号。
        this.snowflake = new Snowflake(null, workerId, datacenterId, false, 0L);
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
