package com.audiobook.server.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    /** 密钥最短长度（字符），低于该长度的密钥熵值不足以支撑 HS512 签名 */
    private static final int MIN_SECRET_LENGTH = 32;

    // 不再提供任何硬编码兜底密钥：属性缺失时由下方 @PostConstruct 校验直接启动失败。
    // 旧兜底默认值已提交进 git 历史，等同于公开密钥，绝不能再作为运行时回退。
    @Value("${jwt.secret:}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expiration;

    private static final String CLAIM_IS_ADMIN = "isAdmin";
    private static final String CLAIM_TOKEN_VERSION = "tokenVersion";

    /**
     * 启动即校验：jwt.secret 为空或过弱时直接阻断启动。
     *
     * 不校验时，application.yml 中 jwt.secret 默认值 ${JWT_SECRET:} 会让生产忘设环境变量时
     * secret 为空字符串，签名密钥退化为 SHA-512("") ——
     * 一个公开常量，任何人可伪造任意用户（含管理员）的合法 token。
     * dev profile 自带仅限本地开发的密钥（>32 字符），不受影响。
     */
    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 未配置：请通过环境变量 JWT_SECRET 注入（生产必需）。"
                            + "禁止使用空密钥——空密钥派生的签名密钥是公开常量，可伪造任意用户 token");
        }
        if (secret.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足（至少 " + MIN_SECRET_LENGTH + " 字符），密钥熵值过低，拒绝启动");
        }
        log.info("JWT 密钥校验通过（长度 {}）", secret.length());
    }

    private SecretKey getSigningKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] keyBytes = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception e) {
            throw new RuntimeException("Error creating signing key", e);
        }
    }

    public String generateToken(String username, boolean isAdmin, Integer tokenVersion) {
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_IS_ADMIN, isAdmin)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion != null ? tokenVersion : 0)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey(), Jwts.SIG.HS512)
                .compact();
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String getUsernameFromToken(String token) {
        return extractUsername(token);
    }

    public int getTokenVersionFromToken(String token) {
        Object version = extractAllClaims(token).get(CLAIM_TOKEN_VERSION);
        return version instanceof Number ? ((Number) version).intValue() : 0;
    }

    public boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }

    public boolean validateToken(String token) {
        return !isTokenExpired(token);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
