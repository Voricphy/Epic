package com.gridcharge.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Reservation {
    // 雪花 ID 超过 JavaScript 安全整数范围，响应以字符串保留完整精度。
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private Long userId;
    private Long slotId;
    private String requestId;
    private String status;
    private String failureReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
