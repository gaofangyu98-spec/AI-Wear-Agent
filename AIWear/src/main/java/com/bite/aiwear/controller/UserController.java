package com.bite.aiwear.controller;

import com.bite.aiwear.commom.Result;
import com.bite.aiwear.dto.request.AuthRequest;
import com.bite.aiwear.dto.request.SendVerificationCodeRequest;
import com.bite.aiwear.dto.response.AuthResponse;
import com.bite.aiwear.dto.response.SendVerificationCodeResponse;
import com.bite.aiwear.log.ApiLog;
import com.bite.aiwear.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/api/user")
/**
 * 用户模块控制器。
 * 提供发送邮箱验证码与统一登录/注册认证接口，挂在 /api/user 下。
 */
public class UserController {

    @Autowired
    private UserService userService;

    // 发送邮箱验证码
    @ApiLog
    @PostMapping("/send-code")
    public Result<SendVerificationCodeResponse> sendVerificationCode(@RequestBody @Valid SendVerificationCodeRequest request) {
        boolean success = userService.sendVerificationCode(request);
        if (success) {
            return Result.success("验证码发送成功", SendVerificationCodeResponse.builder().sendTo("***").expireTime(300).build());
        } else {
            return Result.serverError("验证码发送失败，请稍后重试");
        }
    }

    // 统一认证接口
    @ApiLog
    @PostMapping("/auth")
    public Result<AuthResponse> auth(@RequestBody @Valid AuthRequest request) {
        return Result.success("操作成功", userService.auth(request));
    }
}
