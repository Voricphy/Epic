package com.gridcharge.utils;

import com.gridcharge.dto.UserDTO;

/** 保存当前线程的登录用户，请求结束后需要调用 removeUser。 */
public final class UserHolder {
    private static final ThreadLocal<UserDTO> USER = new ThreadLocal<>();

    private UserHolder() {}

    public static void saveUser(UserDTO user) {
        USER.set(user);
    }

    public static UserDTO getUser() {
        return USER.get();
    }

    public static void removeUser() {
        USER.remove();
    }
}
