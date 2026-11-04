package com.gridcharge.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class StationFollow {
    private Long userId;
    private Long stationId;
    private LocalDateTime createTime;
}
