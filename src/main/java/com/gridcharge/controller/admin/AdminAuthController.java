package com.gridcharge.controller.admin;

import com.gridcharge.dto.AdminLoginDTO;
import com.gridcharge.dto.Result;
import com.gridcharge.service.AdminAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
@Tag(name = "管理端：管理员登录")
public class AdminAuthController {
    private final AdminAuthService auth;

    @PostMapping("/login")
    @Operation(summary = "管理员密码登录")
    public Result<String> login(@Valid @RequestBody AdminLoginDTO dto) {
        return Result.success(auth.login(dto.getPassword()));
    }

    @PostMapping("/logout")
    @Operation(summary = "管理员退出当前登录会话")
    public Result<Void> logout(@RequestHeader("authorization") String token) {
        auth.logout(token);
        return Result.success();
    }
}
