package com.song.auth.manager;

import com.song.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与解析。
 *
 * <p>精简版：payload 只有三个字段</p>
 * <pre>
 * sub  : 用户 ID       → 网关解出后注入 X-User-Id
 * iat  : 签发时间       → 排查问题时看"什么时候登的"
 * exp  : 过期时间       → 验签时自动校验，过期即 401
 * </pre>
 *
 * <p>以后要加字段就在 {@link #sign(Long)} 里加一行 {@code .claim("xxx", value)}，
 * 网关用 {@code claims.get("xxx")} 读取即可，不需要改结构。</p>
 */
@Component
public class JwtManager {

    private final JwtProperties properties;

    private final SecretKey key;

    public JwtManager(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 token：只放 sub / iat / exp。
     */
    public String sign(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
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

    /**
     * 解析并允许"刚刚过期"的 token（续签用）。
     *
     * <p>业务上需要一个宽限窗口：用户挂着没操作，票过期后才回来点页面，
     * 这时候应该能自动续签而不是被赶去重新登录。</p>
     *
     * @return claims；签名不对等硬错误仍会抛异常
     */
    public Claims parseAllowExpired(String token) {
        try {
            return parse(token);
        } catch (ExpiredJwtException e) {
            // 只有"过期"这一种情况放行，签名错/结构错照样抛
            return e.getClaims();
        }
    }

    public long getExpireSeconds() {
        return properties.getExpireSeconds();
    }

}
