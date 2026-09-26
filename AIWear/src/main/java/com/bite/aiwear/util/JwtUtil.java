package com.bite.aiwear.util;

import com.bite.aiwear.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JWT 工具类。
 * 负责生成/解析 HS256 签名的令牌，并把令牌缓存到 Redis 实现同一用户复用令牌与登出失效。
 */
@Component
@Slf4j
public class JwtUtil {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value(("${jwt.secret}"))
    private String secret;

    @Value(("${jwt.expiration}"))
    private Long expiration;

    // 缓存前缀key
    private static final String USER_TOKEN_KEY_PREFIX = "jwt:user:";

    /**
     * 为用户生成 JWT：若 Redis 中已存在未过期令牌则直接复用，避免重复签发。
     * 注意：过期时间由配置 jwt.expiration（小时）再放大，见下文计算。
     */
    public String generateToken(User user) {

        String userKey = USER_TOKEN_KEY_PREFIX + user.getId();
        if (redisTemplate.hasKey(userKey)) {
            return redisTemplate.opsForValue().get(userKey);
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());

        Date now = new Date();
        // expiration 单位为小时，此处 *60*60*1000 转毫秒后再 *2 为令牌有效期（小时数翻倍）
        Date expireDate = new Date(now.getTime() + expiration * 1000 * 60 * 60 * 2);

        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        // 生成的令牌
        String token = Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(now)
                .setExpiration(expireDate)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        redisTemplate.opsForValue()
                .set(
                        userKey,
                        token,
                        expiration,
                        TimeUnit.HOURS
                );

        return token;
    }

    // 先来获取token
    /**
     * 从 "Bearer xxx" 形式的请求头中截取纯令牌部分。
     */
    public String parseToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new RuntimeException("缺少请求头令牌！");
        }
        return authorization.substring(7);
    }

    // 从token中解析userId
    /**
     * 用配置密钥验签并解析令牌，返回其中的 Claims。
     */
    private Claims getClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // 根据token获取userId
    /**
     * 从令牌中解析出 userId。
     */
    public Long getUserId(String token) {
        Claims claims = getClaims(token);
        Long userId = Long.valueOf(claims.get("userId").toString());
        return userId;
    }

    // 删除令牌
    /**
     * 登出：解析令牌拿到 userId，删除 Redis 中对应的登录态缓存。
     */
    public boolean removeToken(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        Claims claims = getClaims(token);
        Long userId = Long.valueOf(claims.get("userId").toString());
        String tokenKey = USER_TOKEN_KEY_PREFIX + userId;
        if (redisTemplate.hasKey(tokenKey)) {
            return redisTemplate.delete(tokenKey);
        }
        return false;
    }

}
