package com.song.wsgateway.protocol;

/**
 * 帧类型（最小集：只有收发消息与心跳）。
 *
 * <p>约定：类型只增不改。旧客户端遇到不认识的 type 直接忽略即可。</p>
 */
public enum FrameType {

    /** 上行：客户端发消息；下行：服务端推消息（同名字段不同，靠方向区分） */
    MSG,

    /** 下行：发送成功确认，回传 cid 并带上 seq */
    ACK,

    /** 上行：客户端心跳 */
    PING,

    /** 下行：服务端心跳响应 */
    PONG;

    /**
     * 按名称解析，未知类型返回 {@code null}（调用方应忽略该帧，不报错）。
     */
    public static FrameType of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (FrameType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
