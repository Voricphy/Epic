package com.gridcharge.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class User {
    private Long id;
    private String email;
    private String nickname;
    private String role;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
