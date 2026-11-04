package com.gridcharge.service;

import com.gridcharge.exception.BusinessException;
import com.gridcharge.mapper.StationMapper;
import com.gridcharge.utils.RedisKeyConstant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只有全量加载完成的过滤器才允许判定“不存在”；删除站点不清除布隆位。 */
@Component
@Slf4j
public class StationBloomFilter implements ApplicationRunner {
    private final RedissonClient redisson;
    private final StringRedisTemplate redis;
    private final StationMapper mapper;
    private final TransactionTemplate scanTransaction;
    private final long expectedInsertions;
    private final double falseProbability;

    public StationBloomFilter(RedissonClient redisson, StringRedisTemplate redis,
            StationMapper mapper, PlatformTransactionManager transactionManager,
            @Value("${epic.station-bloom.expected-insertions:100000}") long expectedInsertions,
            @Value("${epic.station-bloom.false-probability:0.01}") double falseProbability) {
        if (expectedInsertions <= 0 || !(falseProbability > 0 && falseProbability < 1))
            throw new IllegalArgumentException("站点布隆容量必须为正数，误判率必须在 0 和 1 之间");
        this.redisson = redisson;
        this.redis = redis;
        this.mapper = mapper;
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;
        this.scanTransaction = new TransactionTemplate(transactionManager);
        // 新增事务内触发初始化时，也只扫描已提交数据，避免沿用旧快照。
        scanTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        scanTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        scanTransaction.setReadOnly(true);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            initialize(true);
        } catch (RuntimeException e) {
            log.warn("站点布隆初始化失败，查询暂时回源数据库", e);
        }
    }

    public boolean mightContain(Long id) {
        try {
            if (!ready()) initialize(false);
            if (!ready()) return true;
            // 再查就绪状态，避免检查期间 Redis 数据丢失或正在重建时误判不存在。
            return filter().contains(id.toString()) || !ready();
        } catch (RuntimeException e) {
            log.warn("站点布隆不可用，查询回源数据库，站点 ID：{}", id, e);
            return true;
        }
    }

    /** 新增先取得构建锁，持有到提交/回滚，避免全量扫描漏掉正在创建的站点。 */
    public void beforeCreate() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("新增站点必须在事务内同步布隆过滤器");
        RLock lock = acquireLock();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!lock.isHeldByCurrentThread())
                    throw new BusinessException(503, "站点布隆同步锁已失效，请稍后重试");
            }

            @Override
            public void afterCompletion(int status) {
                try {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                } catch (RuntimeException e) {
                    log.error("站点事务结束后布隆同步锁释放失败", e);
                }
            }
        });
        if (!ready()) populate();
    }

    public void add(Long id) {
        // 在数据库提交前写入；写失败让事务回滚，回滚留下的布隆位只会造成误判。
        filter().add(id.toString());
    }

    private boolean ready() {
        return "1".equals(redis.opsForValue().get(RedisKeyConstant.STATION_BLOOM_READY))
                && filter().isExists();
    }

    private RBloomFilter<String> filter() {
        return redisson.getBloomFilter(RedisKeyConstant.STATION_BLOOM, StringCodec.INSTANCE);
    }

    private void initialize(boolean force) {
        RLock lock = acquireLock();
        try {
            if (force || !ready()) populate();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private RLock acquireLock() {
        RLock lock = redisson.getLock(RedisKeyConstant.STATION_BLOOM_LOCK);
        try {
            if (!lock.tryLock(2, TimeUnit.SECONDS))
                throw new BusinessException(503, "站点索引正在同步，请稍后重试");
            return lock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "站点索引同步等待被中断");
        }
    }

    private void populate() {
        redis.delete(RedisKeyConstant.STATION_BLOOM_READY);
        RBloomFilter<String> filter = filter();
        filter.tryInit(expectedInsertions, falseProbability);
        // 空数据库也创建位图；实际站点 ID 为正数。
        filter.add("-1");
        scanTransaction.executeWithoutResult(status -> {
            long after = 0;
            while (true) {
                List<Long> ids = mapper.idsAfter(after);
                if (ids.isEmpty()) break;
                filter.add(ids.stream().map(String::valueOf).toList());
                after = ids.get(ids.size() - 1);
            }
        });
        if (!redisson.getLock(RedisKeyConstant.STATION_BLOOM_LOCK).isHeldByCurrentThread())
            throw new BusinessException(503, "站点布隆构建锁已失效");
        redis.opsForValue().set(RedisKeyConstant.STATION_BLOOM_READY, "1");
    }
}
