package com.gridcharge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.gridcharge.utils.RedisKeyConstant;
import com.gridcharge.utils.UserHolder;
import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.exception.BusinessException;
import com.gridcharge.mapper.*;
import com.gridcharge.service.*;
import cn.hutool.core.util.RandomUtil;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** 学习骨架：保留 MySQL 基础操作，TODO 方法由学习者补充。 */
@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private JavaMailSender javaMailSender;
    // 读取 application.yml 中的发件邮箱地址
    @Value("${spring.mail.username}")
    private String fromEmail;
    // QQ 邮箱 SMTP 授权码
    @Value("${spring.mail.password}")
    private String smtpPassword;

    /**
     * 通过QQ邮箱发送验证码
     * @param emailCodeDTO
     */
    @Override
    public void sendCode(EmailCodeDTO emailCodeDTO) {
        if(fromEmail == null || fromEmail.isBlank() || smtpPassword == null || smtpPassword.isBlank()){
            throw new BusinessException(503, "请先配置发件邮箱和SMTP授权码！");
        }
        String email = emailCodeDTO.getEmail();
        String codeKey = "gc:auth:code:" + email;
        String cooldownKey = "gc:auth:cooldown:" + email;
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", 60, TimeUnit.SECONDS);
        if(flag == null){
            throw new BusinessException(503, "验证码服务暂不可用！");
        }
        if(!flag){
            throw new BusinessException(429, "发送过于频繁，请稍后再试！");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(codeKey, code, 3, TimeUnit.MINUTES);
//        新建一个空的邮件对象
        SimpleMailMessage simpleMailMessage = new SimpleMailMessage();
//        发件人地址
        simpleMailMessage.setFrom(fromEmail);
//        收件人地址
        simpleMailMessage.setTo(email);
//        邮件标题
        simpleMailMessage.setSubject("登录验证码");
//        邮件正文
        simpleMailMessage.setText("您的验证码是："+code+"，3分钟内有效，请勿泄露！");
        javaMailSender.send(simpleMailMessage);
    }

    /**
     * 登录邮箱验证码
     * @param loginDTO
     * @return
     */
    @Override
    public String login(LoginDTO loginDTO) {
//      校验验证码
        String email = loginDTO.getEmail();
        String codeKey = "gc:auth:code:" + email;
        String cacheCode = stringRedisTemplate.opsForValue().get(codeKey);
        if(!loginDTO.getCode().equals(cacheCode)){
            throw new BusinessException(401, "验证码错误！");
        }
//      根据邮箱查询用户
        User user = userMapper.findByEmail(email);
        if(user == null){
            user = new User();
            user.setEmail(email);
            String nickname = email.substring(0, email.indexOf('@'));
            user.setNickname(nickname);
            userMapper.insert(user);
            user = userMapper.findById(user.getId());
        }
        if ("ADMIN".equals(user.getRole())) {
            throw new BusinessException(403, "管理员请使用管理端密码登录");
        }
//      生成token
        String token = RandomUtil.randomString(32);
        String tokenKey = RedisKeyConstant.LOGIN_USER_KEY + token;
//      将User对象转换为HashMap存储
        Map<String, Object> userMap = BeanUtil.beanToMap(user);
        userMap.values().removeIf(value -> value == null);
        userMap.replaceAll((key, value) -> value.toString());
//      存入Redis
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
//      设置token有效期
        stringRedisTemplate.expire(tokenKey, RedisKeyConstant.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return token;
    }

    @Override
    public void logout(String authorization) {
        stringRedisTemplate.delete(RedisKeyConstant.LOGIN_USER_KEY + authorization);
        UserHolder.removeUser();
    }

    @Override
    public User current() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "请先实现登录与用户上下文");
        }
        return userMapper.findById(user.getId());
    }
}
