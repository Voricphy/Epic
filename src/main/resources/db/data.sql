USE epic_learning;
-- 仅演示数据；重复执行不会重置已存在记录。管理员通过 /admin/auth/login 使用配置中的密码登录。
INSERT IGNORE INTO tb_user(id,email,nickname,role) VALUES
(1,'admin@gridcharge.local','运营管理员','ADMIN'),(2,'driver@gridcharge.local','体验车主','USER'),(3,'friend@gridcharge.local','共同关注车主','USER');
INSERT IGNORE INTO tb_station(id,name,address,longitude,latitude,max_power_kw,pile_count,status) VALUES
(1,'福州大学城充电站','福州市闽侯县大学城',119.198000,26.058000,480,8,1),
(2,'福州软件园充电站','福州市鼓楼区软件大道',119.278000,26.119000,240,6,1),
(3,'福州南站充电站','福州市仓山区火车南站',119.385000,25.986000,600,12,1);
INSERT IGNORE INTO tb_station_slot(id,station_id,start_time,end_time,quota,power_per_car_kw,price,status) VALUES
(1,1,TIMESTAMP(DATE_ADD(CURDATE(),INTERVAL 1 DAY),'10:00:00'),TIMESTAMP(DATE_ADD(CURDATE(),INTERVAL 1 DAY),'11:00:00'),8,60,1.20,1),
(2,1,TIMESTAMP(DATE_ADD(CURDATE(),INTERVAL 1 DAY),'11:00:00'),TIMESTAMP(DATE_ADD(CURDATE(),INTERVAL 1 DAY),'12:00:00'),8,60,1.10,1),
(3,2,TIMESTAMP(DATE_ADD(CURDATE(),INTERVAL 1 DAY),'10:00:00'),TIMESTAMP(DATE_ADD(CURDATE(),INTERVAL 1 DAY),'11:00:00'),4,60,1.00,1);
INSERT IGNORE INTO tb_station_follow(user_id,station_id) VALUES(2,1),(3,1),(2,2);
