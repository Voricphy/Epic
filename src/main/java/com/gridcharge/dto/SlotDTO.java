package com.gridcharge.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class SlotDTO {
    @NotNull @Future private LocalDateTime startTime;
    @NotNull @Future private LocalDateTime endTime;

    @NotNull
    @Min(1)
    @Max(10000)
    private Integer quota;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal powerPerCarKw;

    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 6, fraction = 2)
    private BigDecimal price;
}
