package com.gridcharge.dto;

import lombok.Data;

/** 当前登录用户的信息，与 Redis Hash 中的同名字段对应。 */
@Data
public class UserDTO {
    private Long id;
    private String nickname;
    private String role;
}
