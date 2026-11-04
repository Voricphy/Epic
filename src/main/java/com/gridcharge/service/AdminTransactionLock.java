package com.gridcharge.service;

import com.gridcharge.exception.BusinessException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 管理端业务锁随数据库事务结束释放，不能在事务方法的 finally 中提前释放。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminTransactionLock {
    private final RedissonClient redisson;

    public void station(Long id) {
        acquire("gc:lock:admin:station:" + id);
    }

    public void slot(Long id) {
        acquire("gc:lock:admin:slot:" + id);
    }

    private void acquire(String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("管理端业务锁必须在数据库事务内使用");
        }
        RLock lock;
        try {
            lock = redisson.getLock(key);
            // 不传 leaseTime，让 Redisson watchdog 为长事务自动续期。
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                throw new BusinessException(503, "管理操作繁忙，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "管理操作等待被中断，请稍后重试");
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("管理端业务锁获取失败，key={}", key, e);
            throw new BusinessException(503, "管理操作锁服务暂不可用，请稍后重试");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!lock.isHeldByCurrentThread()) {
                    throw new BusinessException(503, "管理操作锁已失效，请稍后重试");
                }
            }

            @Override
            public void afterCompletion(int status) {
                try {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                } catch (RuntimeException e) {
                    // 事务结果已经确定，解锁失败不能误报为数据库提交失败。
                    log.error("管理端事务已结束，但业务锁释放失败，key={}", key, e);
                }
            }
        });
    }
}
