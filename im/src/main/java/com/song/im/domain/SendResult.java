package com.song.im.domain;

/**
 * 发消息结果：客户端靠 cid 认领，靠 seq 排序。
 */
public record SendResult(long msgId, long seq, String conversationId) {
}
