package com.bite.aiwear.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
/**
 * 发送验证码响应体：脱敏后的收件地址与验证码有效期（秒）。
 */
@Builder
public class SendVerificationCodeResponse {

    private String sendTo;

    private Integer expireTime;
}
