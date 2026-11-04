package com.gridcharge.service;

import com.gridcharge.entity.AdminCancellationMail;
import com.gridcharge.mapper.AdminCancellationMailMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminCancellationMailSender {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final AdminCancellationMailMapper mapper;
    private final JavaMailSender mailSender;
    private final RedissonClient redisson;

    @Value("${spring.mail.username:}")
    private String from;

    /** 调度线程中执行；SMTP 调用不占用取消业务事务或业务锁。 */
    public void sendPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("邮件发送不能在数据库事务内执行");
        }
        for (Long id : mapper.dueIds()) {
            try {
                sendOne(id);
            } catch (RuntimeException e) {
                // 不记录邮件地址、正文或 SMTP 异常详情，避免泄露用户及认证信息。
                log.warn("取消通知处理失败，预约 ID={}，错误类型={}", id, e.getClass().getSimpleName());
            }
        }
    }

    private void sendOne(Long id) {
        RLock lock = redisson.getLock("gc:lock:admin:cancellation-mail:" + id);
        if (!lock.tryLock()) return;
        try {
            // 多实例可能读到同一任务，获得锁后再次检查状态和重试时间。
            AdminCancellationMail task = mapper.findDue(id);
            if (task == null) return;
            try {
                if (from == null || from.isBlank()) {
                    throw new IllegalStateException("未配置发件邮箱");
                }
                mailSender.send(message(task));
                if (mapper.markSent(id) != 1) {
                    throw new IllegalStateException("邮件发送状态保存失败");
                }
            } catch (RuntimeException e) {
                // 1、2、4……分钟退避，最长间隔一小时；任务持久化，重启后继续重试。
                long delaySeconds = Math.min(3600L, 60L * (1L << Math.min(task.getAttempts(), 6)));
                mapper.retryLater(id, LocalDateTime.now().plusSeconds(delaySeconds), e.getClass().getSimpleName());
                log.warn("取消通知待重试，预约 ID={}，错误类型={}", id, e.getClass().getSimpleName());
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private SimpleMailMessage message(AdminCancellationMail task) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(task.getRecipient());
        message.setSubject("充电预约取消通知");
        String opening = "SUCCESS".equals(task.getPreviousStatus())
                ? "您已成功预约的充电时段已被管理员取消。"
                : "您提交的充电预约申请已被管理员取消。";
        message.setText(opening + "\n\n"
                + "预约编号：" + task.getReservationId() + "\n"
                + "充电站：" + task.getStationName() + "\n"
                + "预约时段：" + TIME.format(task.getStartTime()) + " 至 " + TIME.format(task.getEndTime()) + "\n"
                + "原因：管理员取消该时段预约\n\n"
                + "请重新选择其他时段。给您带来不便，敬请谅解。");
        return message;
    }
}
