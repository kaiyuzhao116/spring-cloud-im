package com.song.wsgateway.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.wsgateway.protocol.Frame;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本机连接表：connId → Channel，以及 userId → Channel 集合（用于推送）。
 *
 * <p>只保存本实例的连接。跨实例推送由 MQ 广播解决：每个实例都收到事件，
 * 各自查本机有没有目标用户。</p>
 */
@Component
public class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    private final Map<Long, Set<Channel>> userChannels = new ConcurrentHashMap<>();

    public void register(String connId, Channel channel) {
        channels.put(connId, channel);
        Long userId = channel.attr(ChannelAttrs.USER_ID).get();
        if (userId != null) {
            userChannels.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(channel);
        }
        log.info("连接注册 connId={}, userId={}, 本机连接数={}",
                connId, userId, channels.size());
    }

    public void unregister(String connId) {
        Channel removed = channels.remove(connId);
        if (removed == null) {
            return;
        }
        Long userId = removed.attr(ChannelAttrs.USER_ID).get();
        if (userId != null) {
            Set<Channel> set = userChannels.get(userId);
            if (set != null) {
                set.remove(removed);
                if (set.isEmpty()) {
                    userChannels.remove(userId);
                }
            }
        }
        log.info("连接移除 connId={}, userId={}, 本机连接数={}",
                connId, userId, channels.size());
    }

    public Channel get(String connId) {
        return channels.get(connId);
    }

    public int size() {
        return channels.size();
    }

    /**
     * 推给某个用户的全部在线连接（多端）。
     *
     * @return 实际推送的连接数，0 表示用户不在线
     */
    public int pushToUser(long userId, Frame frame) {
        Set<Channel> targets = userChannels.get(userId);
        if (targets == null || targets.isEmpty()) {
            return 0;
        }

        String json;
        try {
            json = MAPPER.writeValueAsString(frame);
        } catch (Exception e) {
            log.warn("推送帧序列化失败: {}", e.getMessage());
            return 0;
        }

        int pushed = 0;
        for (Channel channel : targets) {
            // isWritable：对端消费不过来时丢弃，避免写缓冲无限膨胀
            if (channel.isActive() && channel.isWritable()) {
                channel.writeAndFlush(new TextWebSocketFrame(json));
                pushed++;
            }
        }
        return pushed;
    }
}
