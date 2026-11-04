package com.gridcharge.controller.user;

import com.gridcharge.dto.*;
import com.gridcharge.entity.*;
import com.gridcharge.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/auth")
@Validated
@Tag(name = "用户登录")
public class UserController {
    @Autowired
    private UserService userService;

    @PostMapping("/code")
    @Operation(summary = "发送邮箱验证码")
    public Result code(@Valid @RequestBody EmailCodeDTO emailCodeDTO) {
        userService.sendCode(emailCodeDTO);
        return Result.success();
    }

    @PostMapping("/login")
    @Operation(summary = "邮箱验证码登录")
    public Result<String> login(@Valid @RequestBody LoginDTO loginDTO) {
        String token = userService.login(loginDTO);
        return Result.success(token);
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public Result<Void> logout(@RequestHeader("Authorization") String token) {
        userService.logout(token);
        return Result.success();
    }

    @GetMapping("/me")
    @Operation(summary = "当前用户")
    public Result<User> me() {
        return null;
    }
}
