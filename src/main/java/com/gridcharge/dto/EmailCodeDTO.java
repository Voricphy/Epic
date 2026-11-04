package com.gridcharge.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class EmailCodeDTO {
    @NotBlank
    @Email
    @Size(max = 254)
    private String email;
}
