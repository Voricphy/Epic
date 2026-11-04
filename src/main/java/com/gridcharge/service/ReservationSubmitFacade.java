package com.gridcharge.service;

import com.gridcharge.dto.ReservationDTO;
import com.gridcharge.entity.Reservation;
import com.gridcharge.exception.BusinessException;
import com.gridcharge.mq.ReservationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReservationSubmitFacade {

    @Autowired
    private  RedissonClient redissonClient;
    @Autowired
    private  ReservationService reservationService;
    @Autowired
    private ReservationProducer reservationProducer;

    // 这里不要加 @Transactional
    public Reservation submit(
            Long userId,
            ReservationDTO reservationDTO,
            boolean synchronous) {

        // 同一个用户竞争同一把锁，不同用户使用不同的锁
        RLock lock = redissonClient.getLock(
                "lock:reservation:user:" + userId);

        boolean acquired = false;
        try {
            // 最多等待 5 秒获取锁
            acquired = lock.tryLock(5, TimeUnit.SECONDS);

            if (!acquired) {
                throw new BusinessException(
                        "预约处理中，请稍后使用原 requestId 重试");
            }

            // 调用另一个 Spring Bean 的事务方法：
            // 正常返回到这里时，事务已提交完成
            Reservation reservation =
                    reservationService.submit(userId, reservationDTO, synchronous);
            if (!synchronous && "PROCESSING".equals(reservation.getStatus())) {
                try {
                    // 同一 requestId 重试时，仍处于 PROCESSING 的订单也会补发消息。
                    reservationProducer.send(reservation.getId());
                } catch (RuntimeException e) {
                    // 订单已经提交，发送超时也可能已送达，不能改为 FAILED 或删除订单。
                    log.warn("预约消息发送未确认，订单 ID：{}", reservation.getId(), e);
                    throw new BusinessException(503, "预约已受理，订单 ID：" + reservation.getId()
                            + "，消息发送结果未确认，请使用原 requestId 重试或查询预约状态");
                }
            }
            return reservation;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                    "预约等待被中断，请使用原 requestId 重试");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
