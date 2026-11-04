-- 已有数据库升级：连接 epic_learning 后执行本文件。不改动原有业务表。
CREATE TABLE IF NOT EXISTS tb_admin_cancellation_mail (
 reservation_id BIGINT PRIMARY KEY,
 recipient VARCHAR(254) NOT NULL,
 station_name VARCHAR(100) NOT NULL,
 start_time DATETIME(3) NOT NULL,
 end_time DATETIME(3) NOT NULL,
 previous_status VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 last_error VARCHAR(100),
 sent_at DATETIME(3),
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_admin_mail_due(status,next_attempt_at,reservation_id)
) ENGINE=InnoDB;
