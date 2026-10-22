package com.song.wsgateway.support;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * WS 握手阶段的 JWT 验签器。
 *
 * <p>与 auth 的签发密钥必须一致（HS384）。只做两件事：验签 + 校验 exp，
 * 然后取 {@code sub} 作为 userId。不访问 Redis、不查库，纯本地计算。</p>
 */
@Component
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(@Value("${ws.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析并验签。
     *
     * @param token 不含 "Bearer " 前缀的原始 token
     * @return claims（含 sub / iat / exp）
     * @throws io.jsonwebtoken.JwtException 签名不对、过期、结构非法
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 验签并取出 userId。
     *
     * @throws io.jsonwebtoken.JwtException token 非法或已过期
     * @throws IllegalArgumentException     sub 不是合法数字
     */
    public Long verifyAndGetUserId(String token) {
        Claims claims = parse(token);
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("token 缺少 sub");
        }
        return Long.valueOf(sub);
    }

}
