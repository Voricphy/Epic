-- MySQL 5.7.19+，不包含 DROP，避免误删已有业务。MySQL 5.7 接受 CHECK 语法但不执行约束。
CREATE DATABASE IF NOT EXISTS epic_learning CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE epic_learning;
CREATE TABLE IF NOT EXISTS tb_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, email VARCHAR(254) NOT NULL,
 nickname VARCHAR(64) NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'USER',
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_user_email(email), CHECK(role IN ('USER','ADMIN'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tb_station (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, address VARCHAR(255) NOT NULL,
 longitude DECIMAL(9,6) NOT NULL, latitude DECIMAL(8,6) NOT NULL,
 max_power_kw DECIMAL(10,2) NOT NULL, pile_count INT NOT NULL, status TINYINT NOT NULL DEFAULT 1,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_station_status(status,id), CHECK(max_power_kw>0), CHECK(pile_count>0), CHECK(status IN (0,1)),
 CHECK(longitude BETWEEN -180 AND 180), CHECK(latitude BETWEEN -85 AND 85)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tb_station_slot (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, station_id BIGINT NOT NULL,
 start_time DATETIME(3) NOT NULL, end_time DATETIME(3) NOT NULL,
 quota INT NOT NULL, reserved INT NOT NULL DEFAULT 0,
 power_per_car_kw DECIMAL(10,2) NOT NULL, price DECIMAL(8,2) NOT NULL, status TINYINT NOT NULL DEFAULT 1,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_slot_station FOREIGN KEY(station_id) REFERENCES tb_station(id),
 UNIQUE KEY uk_slot_period(station_id,start_time,end_time), KEY idx_slot_time(station_id,status,start_time),
 CHECK(end_time>start_time), CHECK(quota>0), CHECK(reserved>=0 AND reserved<=quota),
 CHECK(power_per_car_kw>0), CHECK(price>=0), CHECK(status IN (0,1))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tb_reservation (
 id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, slot_id BIGINT NOT NULL,
 request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) NOT NULL, failure_reason VARCHAR(255),
 active_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PROCESSING','SUCCESS') THEN user_id ELSE NULL END) STORED,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_reservation_user FOREIGN KEY(user_id) REFERENCES tb_user(id),
 CONSTRAINT fk_reservation_slot FOREIGN KEY(slot_id) REFERENCES tb_station_slot(id),
 UNIQUE KEY uk_request(user_id,request_id), UNIQUE KEY uk_active_slot(slot_id,active_user_id),
 KEY idx_reservation_user(user_id,id), KEY idx_reservation_recovery(status,update_time),
 CHECK(status IN ('PROCESSING','SUCCESS','CANCELLED','FAILED'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS tb_station_follow (
 user_id BIGINT NOT NULL, station_id BIGINT NOT NULL,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(user_id,station_id), KEY idx_follow_station(station_id,user_id),
 CONSTRAINT fk_follow_user FOREIGN KEY(user_id) REFERENCES tb_user(id),
 CONSTRAINT fk_follow_station FOREIGN KEY(station_id) REFERENCES tb_station(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 管理端取消通知的持久化发送任务，与取消订单在同一事务内写入。
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
