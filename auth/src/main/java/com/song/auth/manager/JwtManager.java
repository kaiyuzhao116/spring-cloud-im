package com.song.auth.manager;

import com.song.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与解析。
 * <p>token 只带 uid / sid / dev / ver，不放权限列表。</p>
 */
@Component
public class JwtManager {

    public static final String CLAIM_SESSION_ID = "sid";

    public static final String CLAIM_DEVICE_TYPE = "dev";

    public static final String CLAIM_VERSION = "ver";

    private final JwtProperties properties;

    private final SecretKey key;

    public JwtManager(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String sign(Long userId, String sessionId, String deviceType, int version) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_SESSION_ID, sessionId)
                .claim(CLAIM_DEVICE_TYPE, deviceType)
                .claim(CLAIM_VERSION, version)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getExpireSeconds())))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpireSeconds() {
        return properties.getExpireSeconds();
    }

}
