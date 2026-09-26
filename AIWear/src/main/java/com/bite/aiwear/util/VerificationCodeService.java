package com.bite.aiwear.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱验证码服务类。
 * 基于 Redis 存储 6 位数字验证码，5 分钟过期；校验成功后立即删除，防止重复使用。
 */
@Slf4j
@Service
public class VerificationCodeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    // redis缓存前缀
    private static final String CODE_KEY_PREFIX = "verification:code:";

    // 过期时间
    private static final int TIMEOUT_MINUTES = 5;

    // 生成6位随机验证码
    public String generateCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(999999);
        return String.valueOf(code);
    }

    // 保存验证码
    public void saveCode(String email, String code) {
        String key = CODE_KEY_PREFIX + email;
        redisTemplate.opsForValue().set(key, code, TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    // 验证码是否存在
    public boolean hasCode(String email) {
        String key = CODE_KEY_PREFIX + email;
        return redisTemplate.hasKey(key);
    }

    // 校验验证码
    public boolean verifyCode(String email, String code) {
        String key = CODE_KEY_PREFIX + email;
        if (redisTemplate.hasKey(key)) {
            String oldCode = redisTemplate.opsForValue().get(key);
            redisTemplate.delete(key);
            return  code.equals(oldCode);
        } else {
            log.warn("验证码不存在或已过期， 邮箱{}", email);
            return false;
        }
    }

}
