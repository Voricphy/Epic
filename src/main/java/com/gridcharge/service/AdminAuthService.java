package com.gridcharge.service;

import com.gridcharge.entity.User;
import com.gridcharge.exception.BusinessException;
import com.gridcharge.mapper.UserMapper;
import com.gridcharge.utils.RedisKeyConstant;
import com.gridcharge.utils.UserHolder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAuthService {
    private final UserMapper userMapper;
    private final StringRedisTemplate redis;

    @Value("${epic.admin.password:}")
    private String password;

    @Value("${epic.admin.email:admin@gridcharge.local}")
    private String email;

    public void logout(String token) {
        redis.delete(RedisKeyConstant.LOGIN_USER_KEY + token);
        UserHolder.removeUser();
    }

    public String login(String suppliedPassword) {
        if (password == null || password.isBlank() || suppliedPassword == null || !MessageDigest.isEqual(
                password.getBytes(StandardCharsets.UTF_8),
                suppliedPassword.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(401, "管理员密码错误");
        }
        User admin = userMapper.findByEmail(email);
        if (admin == null || !"ADMIN".equals(admin.getRole())) {
            throw new BusinessException(403, "管理员账号不存在或无管理员权限");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = RedisKeyConstant.LOGIN_USER_KEY + token;
        redis.opsForHash().putAll(key, Map.of(
                "id", admin.getId().toString(),
                "nickname", admin.getNickname(),
                "role", "ADMIN"));
        redis.expire(key, RedisKeyConstant.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return token;
    }
}
