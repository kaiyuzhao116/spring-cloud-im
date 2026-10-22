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

    private ChannelAttrs() {
    }
}
