package com.gridcharge.service;

import com.gridcharge.dto.*;
import com.gridcharge.entity.User;

public interface UserService {
    void sendCode(EmailCodeDTO emailCodeDTO);

    String login(LoginDTO loginDTO);

    void logout(String authorization);

    User current();
}
