package com.song.im.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 查"某人在不在线"。
 *
 * <p>数据源是 ws-gateway 维护的在线表 {@code ws:online:{uid}}（HASH，有 TTL），
 * im 只读不写——在线状态的真源头在接入层，业务层不该自己维护一份。</p>
 *
 * <p>Redis 不可用时一律返回 false（宁可显示离线，也不要显示一个假的在线）。</p>
 */
@Component
public class OnlineQuery {

    private static final Logger log = LoggerFactory.getLogger(OnlineQuery.class);

    private static final String KEY_PREFIX = "ws:online:";

    private final StringRedisTemplate redis;

    public OnlineQuery(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isOnline(long uid) {
        try {
            Boolean exists = redis.hasKey(KEY_PREFIX + uid);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("查询在线状态失败 uid={} msg={}", uid, e.getMessage());
            return false;
        }
    }

    /** 批量查询：一次往返拿一批人的在线状态 */
    public Set<Long> onlineAmong(Collection<Long> uids) {
        if (uids == null || uids.isEmpty()) {
            return Set.of();
        }
        Set<Long> online = new HashSet<>();
        try {
            for (Long uid : uids) {
                if (Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + uid))) {
                    online.add(uid);
                }
            }
        } catch (Exception e) {
            log.warn("批量查询在线状态失败 size={} msg={}", uids.size(), e.getMessage());
        }
        return online;
    }
}
