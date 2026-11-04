package com.gridcharge.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ReservationDTO {
    @NotNull @Positive private Long slotId;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{8,64}")
    private String requestId;
}
