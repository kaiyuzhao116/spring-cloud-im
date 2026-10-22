package com.song.wsgateway.session;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本机连接表：connId → Channel。
 *
 * <p>只保存本实例的连接。跨实例投递需要 P2 的 Redis 路由配合。</p>
 */
@Component
public class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public void register(String connId, Channel channel) {
        channels.put(connId, channel);
        log.info("连接注册 connId={}, userId={}, 本机连接数={}",
                connId, channel.attr(ChannelAttrs.USER_ID).get(), channels.size());
    }

    public void unregister(String connId) {
        Channel removed = channels.remove(connId);
        if (removed != null) {
            log.info("连接移除 connId={}, userId={}, 本机连接数={}",
                    connId, removed.attr(ChannelAttrs.USER_ID).get(), channels.size());
        }
    }

    public Channel get(String connId) {
        return channels.get(connId);
    }

    public int size() {
        return channels.size();
    }
}
