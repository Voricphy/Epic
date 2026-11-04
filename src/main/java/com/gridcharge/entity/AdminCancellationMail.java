package com.gridcharge.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 管理端取消时保存通知快照，发送时不依赖用户端流程。 */
@Data
public class AdminCancellationMail {
    private Long reservationId;
    private String recipient;
    private String stationName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String previousStatus;
    private String status;
    private int attempts;
}
