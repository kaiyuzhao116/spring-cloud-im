package com.song.wsgateway.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 全局在线表：{@code ws:online:{uid}} 是一个 HASH。
 *
 * <pre>
 * key   : ws:online:1
 * field : connId
 * value : 实例地址（127.0.0.1:8089）
 * TTL   : 120 秒
 * </pre>
 *
 * <p>为什么用 HASH 而不是 String：一个用户可能有多条连接（多端），
 * 用 String 会互相覆盖，而且踢人时无法定位到"哪一条连接"。</p>
 *
 * <p>为什么要有 TTL：实例崩溃时没人来删除，靠过期自动清理。
 * 因此活着的连接必须定期续期（我们借客户端 PING 这个天然节拍器）。</p>
 *
 * <p>所有异常一律吞掉：在线表是辅助结构，不能因为它不可用而影响收发消息。</p>
 */
@Component
public class OnlineRegistry {

    private static final Logger log = LoggerFactory.getLogger(OnlineRegistry.class);

    /** 与续期周期（PING 25 秒）配合：120 秒允许连续丢 4 次续期 */
    private static final Duration TTL = Duration.ofSeconds(120);

    private static final String KEY_PREFIX = "ws:online:";

    private final StringRedisTemplate redis;

    /** 本实例标识，写进 value，便于排查"这个连接在哪台机器上" */
    private final String instanceId;

    public OnlineRegistry(StringRedisTemplate redis, @Value("${netty.port:8089}") int port) {
        this.redis = redis;
        this.instanceId = "127.0.0.1:" + port;
    }

    /** 连接建立：登记 + 设过期时间 */
    public void register(long uid, String connId) {
        try {
            String key = key(uid);
            redis.opsForHash().put(key, connId, instanceId);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("在线表登记失败 uid={} connId={} msg={}", uid, connId, e.getMessage());
        }
    }

    /** 续期：活着的连接必须定期刷新，否则 TTL 到期会被误判离线 */
    public void touch(long uid) {
        try {
            redis.expire(key(uid), TTL);
        } catch (Exception e) {
            log.warn("在线表续期失败 uid={} msg={}", uid, e.getMessage());
        }
    }

    /** 断开：只删自己那一个 field，绝不能 DEL 整个 key（会误删同用户的其它连接） */
    public void remove(long uid, String connId) {
        try {
            redis.opsForHash().delete(key(uid), connId);
        } catch (Exception e) {
            log.warn("在线表移除失败 uid={} connId={} msg={}", uid, connId, e.getMessage());
        }
    }

    /** 查询某用户的所有在线连接（connId → 实例地址） */
    public Map<Object, Object> list(long uid) {
        try {
            return redis.opsForHash().entries(key(uid));
        } catch (Exception e) {
            log.warn("在线表查询失败 uid={} msg={}", uid, e.getMessage());
            return Map.of();
        }
    }

    private String key(long uid) {
        return KEY_PREFIX + uid;
    }
}
