package com.song.wsgateway.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 判断一张"通行证"是否已被作废。
 *
 * <p>管理员踢人时，im 会写 {@code auth:kicked:{uid} = 踢出时刻}；
 * 这里比对 token 的签发时间：比它早的 token 一律拒绝。</p>
 *
 * <p>Redis 访问失败时<b>放行</b>：宁可短暂地放一个旧 token 进来，
 * 也不要因为 Redis 抖动让所有人连不上。</p>
 */
@Component
public class KickedChecker {

    private static final Logger log = LoggerFactory.getLogger(KickedChecker.class);

    private static final String KEY_PREFIX = "auth:kicked:";

    private final StringRedisTemplate redis;

    public KickedChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isRevoked(long uid, Date issuedAt) {
        if (issuedAt == null) {
            return false;
        }
        try {
            String kickedAt = redis.opsForValue().get(KEY_PREFIX + uid);
            if (kickedAt == null) {
                return false;
            }
            return issuedAt.getTime() < Long.parseLong(kickedAt);
        } catch (Exception e) {
            log.warn("校验 token 作废状态失败 uid={} msg={}", uid, e.getMessage());
            return false;
        }
    }
}
