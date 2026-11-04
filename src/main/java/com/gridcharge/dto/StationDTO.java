package com.gridcharge.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class StationDTO {
    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 255)
    private String address;

    @NotNull
    @DecimalMin("-180")
    @DecimalMax("180")
    @Digits(integer = 3, fraction = 6)
    private BigDecimal longitude;

    @NotNull
    @DecimalMin("-85")
    @DecimalMax("85")
    @Digits(integer = 2, fraction = 6)
    private BigDecimal latitude;

    @NotNull
    @DecimalMin("0.01")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal maxPowerKw;

    @NotNull
    @Min(1)
    @Max(10000)
    private Integer pileCount;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
