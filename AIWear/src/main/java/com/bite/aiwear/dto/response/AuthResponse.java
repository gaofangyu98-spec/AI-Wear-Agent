package com.bite.aiwear.dto.response;

import lombok.Data;

import java.io.Serializable;

// 统一认证的请求实体类
@Data
public class AuthResponse implements Serializable {
    private Long userId;

    private String username;

    private String email;

    private String token;

}
