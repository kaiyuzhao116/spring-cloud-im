package com.song.im.domain;

/**
 * 一条历史消息（返回给客户端的字段，尽量精简）。
 *
 * <p>字段名 {@code from} 与 WS 下行帧保持一致，前端可直接复用渲染逻辑。</p>
 */
public record MessageItem(long msgId, long seq, long from, String content, long ts) {
}
