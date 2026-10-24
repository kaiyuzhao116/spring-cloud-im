package com.song.apigateway.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 判断 token 是否已被管理员作废。
 *
 * <pre>
 * key   : auth:kicked:{uid}
 * value : 踢出时刻（毫秒）
 * 规则  : token 的签发时间 &lt; 该值 → 已作废
 * </pre>
 *
 * <p>Redis 异常时返回 false（放行）：可用性优先，避免 Redis 抖动导致全员被登出。</p>
 */
@Component
public class KickedChecker {

    private static final Logger log = LoggerFactory.getLogger(KickedChecker.class);

    private static final String KEY_PREFIX = "auth:kicked:";

    private final ReactiveStringRedisTemplate redis;

    public KickedChecker(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    public Mono<Boolean> isRevoked(String userId, long issuedAtMillis) {
        return redis.opsForValue().get(KEY_PREFIX + userId)
                .map(kickedAt -> issuedAtMillis < Long.parseLong(kickedAt))
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.warn("校验 token 作废状态失败 uid={} msg={}", userId, e.getMessage());
                    return Mono.just(false);
                });
    }
}
