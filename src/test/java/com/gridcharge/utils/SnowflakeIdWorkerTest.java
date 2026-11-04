package com.gridcharge.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class SnowflakeIdWorkerTest {
    @Test
    void concurrentGenerationIsUniqueAndIncreasingWithinEachThread() throws Exception {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(3, 4);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        var tasks = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 8; i++) {
                tasks.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    long previous = 0;
                    for (int j = 0; j < 5000; j++) {
                        long id = worker.nextId();
                        assertTrue(id > previous);
                        assertTrue(ids.add(id), "重复 ID：" + id);
                        previous = id;
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(40000, ids.size());
    }

    @Test
    void configuredNodesHaveDistinctIdsAndCorrectNodeBits() {
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        for (long[] node : new long[][] {{0, 0}, {1, 0}, {0, 1}, {31, 31}}) {
            SnowflakeIdWorker worker = new SnowflakeIdWorker(node[0], node[1]);
            for (int i = 0; i < 5000; i++) {
                long id = worker.nextId();
                assertEquals(node[0], (id >>> 12) & 31);
                assertEquals(node[1], (id >>> 17) & 31);
                assertTrue(ids.add(id));
            }
        }
        assertEquals(20000, ids.size());
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "32,0", "0,-1", "0,32"})
    void invalidNodeConfigurationPreventsStartup(long workerId, long datacenterId) {
        new ApplicationContextRunner().withUserConfiguration(SnowflakeIdWorker.class)
                .withPropertyValues("epic.id.worker-id=" + workerId,
                        "epic.id.datacenter-id=" + datacenterId)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void springCreatesOneGeneratorWithConfiguredNodeWithoutRedis() {
        new ApplicationContextRunner().withUserConfiguration(SnowflakeIdWorker.class)
                .withPropertyValues("epic.id.worker-id=7", "epic.id.datacenter-id=12")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    SnowflakeIdWorker worker = context.getBean(SnowflakeIdWorker.class);
                    assertSame(worker, context.getBean(SnowflakeIdWorker.class));
                    long id = worker.nextId();
                    assertEquals(7, (id >>> 12) & 31);
                    assertEquals(12, (id >>> 17) & 31);
                });
    }

    @Test
    void clockRollbackStopsGenerationUntilClockRecovers() {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(0, 0);
        long first = worker.nextId();
        Object snowflake = ReflectionTestUtils.getField(worker, "snowflake");
        Object lastTimestamp = ReflectionTestUtils.getField(snowflake, "lastTimestamp");
        // 模拟上次发号时间领先当前系统时间，不修改机器时钟。
        ReflectionTestUtils.setField(snowflake, "lastTimestamp", System.currentTimeMillis() + 60000L);
        assertThrows(IllegalStateException.class, worker::nextId);
        ReflectionTestUtils.setField(snowflake, "lastTimestamp", lastTimestamp);
        assertTrue(worker.nextId() > first);
    }
}
