package com.song.im.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 记录"某用户在某时刻之前签发的 token 全部作废"。
 *
 * <pre>
 * key   : auth:kicked:{uid}
 * value : 踢出时刻（毫秒时间戳）
 * TTL   : 30 分钟（= token 有效期，旧 token 本来就过期了，记录可以自动消失）
 * </pre>
 *
 * <p>为什么只记 uid 不记具体 token：我们的 token 没有 jti，无法定位单条凭证；
 * 按"签发时间早于踢出时刻"判断，效果等价且不需要改 JWT 结构。</p>
 */
@Component
public class KickRegistry {

    private static final Logger log = LoggerFactory.getLogger(KickRegistry.class);

    /** 与 token 有效期一致 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private static final String KEY_PREFIX = "auth:kicked:";

    private final StringRedisTemplate redis;

    public KickRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 踢人时调用：之后该用户所有"签发时间早于此刻"的 token 都会被拒绝 */
    public void markKicked(long uid) {
        try {
            redis.opsForValue().set(KEY_PREFIX + uid,
                    String.valueOf(System.currentTimeMillis()), TTL);
            log.info("已标记 token 作废 uid={}", uid);
        } catch (Exception e) {
            // 写失败只影响"是否需要重新登录"，不影响本次踢人（连接已经断了）
            log.warn("标记 token 作废失败 uid={} msg={}", uid, e.getMessage());
        }
    }
}
