package com.gridcharge.mapper;

import com.gridcharge.entity.*;
import com.gridcharge.vo.*;

public interface UserMapper {

    User findByEmail(String email);

    User findById(Long id);

    void insert(User user);
}
