package com.gridcharge.mq;

import com.gridcharge.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "epic-reservation-topic",
        consumerGroup = "epic-reservation-consumer"
)
public class ReservationConsumer implements RocketMQListener<String> {

    private final ReservationService reservationService;

    @Override
    public void onMessage(String message) {
        Long reservationId = Long.valueOf(message);

        log.info("收到预约任务，订单 ID：{}", reservationId);

        reservationService.consume(reservationId);
    }
}