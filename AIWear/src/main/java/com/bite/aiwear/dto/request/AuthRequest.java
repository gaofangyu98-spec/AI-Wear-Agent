package com.bite.aiwear.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// 统一认证的请求实体类
@Data
public class AuthRequest {

    @NotBlank(message = "账号不能为空")
    private String account;

    private String password;

    private String verificationCode;
}
