package com.gridcharge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminLoginDTO {
    @NotBlank
    @Size(max = 128)
    private String password;
}
