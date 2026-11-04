package com.gridcharge.utils;

import com.gridcharge.dto.UserDTO;
import com.gridcharge.entity.User;
import com.gridcharge.exception.BusinessException;
import com.gridcharge.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {
    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserDTO current = UserHolder.getUser();
        if (current == null || current.getId() == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (!"ADMIN".equals(current.getRole())) {
            throw new BusinessException(403, "仅管理员可操作");
        }
        // 再查数据库，已撤销的管理员权限不等待 Redis 登录态过期。
        User user = userMapper.findById(current.getId());
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new BusinessException(403, "仅管理员可操作");
        }
        return true;
    }
}
