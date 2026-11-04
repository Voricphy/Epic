package com.gridcharge.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class StationSlot {
    private Long id;
    private Long stationId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer quota;
    private Integer reserved;
    private BigDecimal powerPerCarKw;
    private BigDecimal price;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
