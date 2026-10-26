package com.song.wsgateway.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.song.wsgateway.protocol.Frame;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** WebSocket 关闭码：同账号在别处登录（客户端据此判断"不要重连"） */
    private static final int CLOSE_KICKED = 4001;

    /** 每条连接最多记录多少个会话（LRU 淘汰） */
    private static final int MAX_TRACKED_CONVS = 500;

    /**
     * 每个会话保留最近多少个已推 seq 用于判重。
     *
     * <p>MQ 重投的窗口是秒级，同一会话几秒内不会有 32 条重复；
     * 窗口外的极小概率放行由前端按 seq 精确匹配兜住。</p>
     */
    private static final int RECENT_SEQ_WINDOW = 32;

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
     * 某用户在本机上的全部连接（返回副本，遍历时被 unregister 修改也安全）。
     */
    public List<Channel> channelsOf(long userId) {
        Set<Channel> set = userChannels.get(userId);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    /**
     * 踢掉某用户在本机的连接（可排除指定 connId）。
     *
     * <p>用于单端登录：新连接广播 kick 事件后，所有实例（含本机）都调用本方法，
     * 把除自己之外的旧连接以 4001 关掉。</p>
     *
     * <p>必须先发 Close 帧再关（addListener(CLOSE)）：客户端靠 4001 判断"被踢"，
     * 如果直接 close，客户端只会看到 1006，从而触发自动重连，形成互踢循环。</p>
     *
     * @return 实际处理掉的连接数
     */
    public int kickOthers(long userId, String excludeConnId, long newerThan) {
        int kicked = 0;
        for (Channel channel : channelsOf(userId)) {
            String connId = channel.attr(ChannelAttrs.CONN_ID).get();
            if (excludeConnId != null && excludeConnId.equals(connId)) {
                continue;   // 不踢新连接自己
            }

            Long connectTime = channel.attr(ChannelAttrs.CONNECT_TIME).get();
            if (connectTime != null && connectTime > newerThan) {
                // 这条比事件里的新连接还新 → 说明是更晚的登录，乱序事件不该动它
                continue;
            }

            channel.attr(ChannelAttrs.CLOSE_REASON).set(CloseReason.KICKED);
            if (channel.isActive()) {
                channel.writeAndFlush(new CloseWebSocketFrame(CLOSE_KICKED, "kicked by new login"))
                        .addListener(ChannelFutureListener.CLOSE);
            } else {
                channel.close();   // 已经半死，直接关，触发 channelInactive 清理
            }
            kicked++;
            log.info("踢掉旧连接 userId={} connId={}", userId, connId);
        }
        return kicked;
    }

    /**
     * 推一条聊天消息，<b>带幂等</b>：同一会话里 seq 不大于已推值的直接跳过。
     *
     * <p>为什么需要：Outbox 是"至少一次"投递，MQ 重投或"发成功但标记失败"
     * 都会让同一条事件被处理两次。没有这一层，客户端会收到重复帧，
     * 未读数被多 +1（气泡有前端 seq 去重挡着，未读没有）。</p>
     *
     * <p>只有真正写成功才更新已推 seq：写失败（连接不可写）当作没推过，
     * 后续补推不会被自己的记录挡住。</p>
     *
     * @return 实际推送的连接数
     */
    public int pushMessageOnce(long toUid, long fromUid, long seq, Frame frame) {
        String json = toJson(frame);
        if (json == null) {
            return 0;
        }

        int pushed = 0;
        for (Channel channel : channelsOf(toUid)) {
            if (channel.isActive() && channel.isWritable()
                    && writeIfNew(channel, fromUid, seq, json)) {
                pushed++;
            }
        }
        return pushed;
    }

    /**
     * 推给某用户的所有连接，<b>但跳过指定的那一条</b>（多端同步用）。
     *
     * <p>场景：admin 在手机上发消息，他电脑上也该看到这条；
     * 但手机那条连接已经本地渲染过了，不能再收一遍。</p>
     *
     * <p>同样要判重：同一条事件被 Outbox 重投时，
     * 你自己的其它端也不该收到两条。</p>
     *
     * @param excludeConnId 要跳过的连接（发消息的那条），null 表示不跳过
     * @return 实际推送的连接数
     */
    public int pushToUserExcept(long userId, String excludeConnId, long fromUid, long seq, Frame frame) {
        String json = toJson(frame);
        if (json == null) {
            return 0;
        }

        int pushed = 0;
        for (Channel channel : channelsOf(userId)) {
            String connId = channel.attr(ChannelAttrs.CONN_ID).get();
            if (excludeConnId != null && excludeConnId.equals(connId)) {
                continue;                       // 这条就是发消息的连接
            }
            if (channel.isActive() && channel.isWritable()
                    && writeIfNew(channel, fromUid, seq, json)) {
                pushed++;
            }
        }
        return pushed;
    }

    /**
     * 幂等写入：同一条消息（同 fromUid + seq）对同一条连接只写一次。
     *
     * <p>Outbox 是"至少一次"投递，重复事件靠这里吸收；
     * 只挡"完全相同"，不比较大小——乱序到达的小 seq 必须照推。</p>
     *
     * @return true 表示真的写出去了
     */
    private boolean writeIfNew(Channel channel, long fromUid, long seq, String json) {
        Map<Long, Deque<Long>> recentSeq = recentSeqOf(channel);
        synchronized (recentSeq) {
            Deque<Long> window = recentSeq.computeIfAbsent(fromUid,
                    k -> new ArrayDeque<>(RECENT_SEQ_WINDOW));
            if (window.contains(seq)) {
                return false;
            }
            channel.writeAndFlush(new TextWebSocketFrame(json));
            window.addLast(seq);
            if (window.size() > RECENT_SEQ_WINDOW) {
                window.removeFirst();
            }
            return true;
        }
    }

    private String toJson(Frame frame) {
        try {
            return MAPPER.writeValueAsString(frame);
        } catch (Exception e) {
            log.warn("推送帧序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 每条连接一份"最近推过的 seq"，会话维度用 LRU 限制，防止内存无界 */
    private Map<Long, Deque<Long>> recentSeqOf(Channel channel) {
        Map<Long, Deque<Long>> map = channel.attr(ChannelAttrs.RECENT_SEQ).get();
        if (map != null) {
            return map;
        }

        Map<Long, Deque<Long>> created = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, Deque<Long>> eldest) {
                        return size() > MAX_TRACKED_CONVS;
                    }
                });
        Map<Long, Deque<Long>> existing = channel.attr(ChannelAttrs.RECENT_SEQ).setIfAbsent(created);
        return existing != null ? existing : created;
    }

    /**
     * 推给某个用户的全部在线连接（多端），不做幂等判断。
     *
     * <p>用于 NOTIFY 这类不携带 seq 的帧。</p>
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
