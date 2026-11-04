package com.gridcharge.config;

import com.gridcharge.service.AdminCancellationMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 仅启用管理端取消邮件任务；用户端预约恢复等学习任务不在此注册。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "epic.admin.cancellation-mail", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AdminCancellationMailScheduling {
    private final AdminCancellationMailSender sender;

    @Scheduled(initialDelayString = "${epic.admin.cancellation-mail.poll-ms:10000}",
            fixedDelayString = "${epic.admin.cancellation-mail.poll-ms:10000}")
    public void sendPending() {
        try {
            sender.sendPending();
        } catch (RuntimeException e) {
            log.warn("取消邮件任务暂不可用，稍后重试，错误类型={}", e.getClass().getSimpleName());
        }
    }
}
