package com.gridcharge.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Station {
    private Long id;
    private String name;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private BigDecimal maxPowerKw;
    private Integer pileCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
