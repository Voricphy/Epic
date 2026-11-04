package com.gridcharge.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class LoginDTO {
    @NotBlank
    @Email
    @Size(max = 254)
    private String email;

    @NotBlank
    @Pattern(regexp = "[0-9]{6}")
    private String code;
}
