package com.song.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 与会话相关配置。
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * HS256 密钥，长度必须 >= 32 字符。生产环境应改为 RS256 并把密钥放配置中心/KMS。
     */
    private String secret;

    /**
     * accessToken 有效期（秒）。
     */
    private long expireSeconds = 1800L;

    /**
     * 会话（Redis auth:sess:*）有效期（秒），一般与 refreshToken 同寿。
     */
    private long sessionTtlSeconds = 604800L;

    /**
     * 登录失败锁定次数。
     */
    private int maxFailCount = 5;

    /**
     * 失败计数窗口（秒）。
     */
    private long failWindowSeconds = 900L;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }

    public void setExpireSeconds(long expireSeconds) {
        this.expireSeconds = expireSeconds;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public int getMaxFailCount() {
        return maxFailCount;
    }

    public void setMaxFailCount(int maxFailCount) {
        this.maxFailCount = maxFailCount;
    }

    public long getFailWindowSeconds() {
        return failWindowSeconds;
    }

    public void setFailWindowSeconds(long failWindowSeconds) {
        this.failWindowSeconds = failWindowSeconds;
    }
}
