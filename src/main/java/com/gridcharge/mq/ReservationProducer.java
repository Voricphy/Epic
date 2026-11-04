package com.gridcharge.mq;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public void send(Long reservationId) {
        SendResult result = rocketMQTemplate.syncSend(
                "epic-reservation-topic",
                reservationId.toString()
        );

        if (result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException(
                    "预约消息发送未确认成功：" + result.getSendStatus()
            );
        }
    }
}