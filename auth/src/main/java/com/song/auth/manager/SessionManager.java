package com.song.auth.manager;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 会话存储（方案 A：两个 key）。
 * <pre>
 * auth:sess:{sid}       HASH    一次登录的明细，TTL = sessionTtl
 * auth:user:{uid}:ver   STRING  全端失效版本号，永不TTL，只增不减
 * </pre>
 */
@Component
public class SessionManager {

    private static final String SESSION_KEY_PREFIX = "auth:sess:";

    private static final String VER_KEY_FORMAT = "auth:user:%d:ver";

    private final StringRedisTemplate redis;

    public SessionManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    public static String versionKey(Long userId) {
        return String.format(VER_KEY_FORMAT, userId);
    }

    /**
     * 写入会话。必须配合 {@link #initVersion(Long)} 使用。
     */
    public void create(String sessionId, Long userId, String deviceType, String ip, String userAgent, long ttlSeconds) {
        Map<String, String> session = new HashMap<>(8);
        session.put("uid", String.valueOf(userId));
        session.put("deviceType", deviceType);
        session.put("ip", ip == null ? "" : ip);
        session.put("ua", userAgent == null ? "" : userAgent);
        session.put("loginAt", String.valueOf(System.currentTimeMillis()));
        session.put("lastSeen", String.valueOf(System.currentTimeMillis()));
        String key = sessionKey(sessionId);
        redis.opsForHash().putAll(key, session);
        redis.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 初始化版本号：只在第一次写入 0，之后绝不覆盖（否则旧 token 会复活）。
     */
    public void initVersion(Long userId) {
        redis.opsForValue().setIfAbsent(versionKey(userId), "0");
    }

    public int currentVersion(Long userId) {
        String value = redis.opsForValue().get(versionKey(userId));
        return value == null ? 0 : Integer.parseInt(value);
    }

    public void delete(String sessionId) {
        redis.delete(sessionKey(sessionId));
    }

    public boolean exists(String sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(sessionKey(sessionId)));
    }

    /**
     * 登录失败计数：返回本次失败后的次数。
     */
    public long increaseFailCount(String account, long windowSeconds) {
        String key = "auth:fail:" + account;
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, windowSeconds, TimeUnit.SECONDS);
        return count == null ? 1 : count;
    }

    public long getFailCount(String account) {
        String value = redis.opsForValue().get("auth:fail:" + account);
        return value == null ? 0 : Long.parseLong(value);
    }

    public void clearFailCount(String account) {
        redis.delete("auth:fail:" + account);
    }

}
