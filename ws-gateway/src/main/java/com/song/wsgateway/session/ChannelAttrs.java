package com.song.wsgateway.session;

import io.netty.util.AttributeKey;

/**
 * Channel 上挂载的身份属性。
 *
 * <p>握手验签通过后写入，之后每条消息直接读取，避免重复解析 token。</p>
 */
public final class ChannelAttrs {

    /** 用户 ID（来自 token 的 sub） */
    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("userId");

    /** 连接 ID（本机唯一，P1 起会写进 Redis 路由） */
    public static final AttributeKey<String> CONN_ID = AttributeKey.valueOf("connId");

    /** token 过期时间（毫秒），用于主动断开已过期的连接 */
    public static final AttributeKey<Long> TOKEN_EXP = AttributeKey.valueOf("tokenExp");

    /** 关闭原因；不设置时视为对端主动关闭 */
    public static final AttributeKey<CloseReason> CLOSE_REASON = AttributeKey.valueOf("closeReason");

    /**
     * 握手完成时间（毫秒），用来计算连接存活时长。
     *
     * <p>用包装类 {@code Long} 而非 {@code long}：握手失败的连接没有这个值，
     * 读取时需要能判断 null。</p>
     */
    public static final AttributeKey<Long> CONNECT_TIME = AttributeKey.valueOf("connectTime");

    /**
     * 每条连接最近推送过的 seq：{@code fromUid -> 最近 N 个 seq 的窗口}。
     *
     * <p>刻意<b>只做"完全相等"判重</b>，不做大小比较：Outbox 是至少一次投递，
     * 并发抢占与失败重试都会让消息乱序到达，用"最大 seq"当水位线会把
     * 晚到的小 seq 误判成重复而丢弃。</p>
     *
     * <p>单聊里 {@code fromUid} 唯一确定一个会话，所以不需要再做会话 ID 拼接。</p>
     */
    public static final AttributeKey<java.util.Map<Long, java.util.Deque<Long>>> RECENT_SEQ =
            AttributeKey.valueOf("recentSeq");

    private ChannelAttrs() {
    }
}
