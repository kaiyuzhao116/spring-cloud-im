package com.song.im.domain;

/**
 * 会话列表项（某人视角下的一个会话）。
 *
 * <p>未读数不存库，用 {@code lastSeq - lastReadSeq} 算出来，避免计数错位。</p>
 *
 * <p>注意：{@code peerName} 需要调 user 服务才有，当前先返回 null，
 * 前端暂用 uid 映射；后续接入后即可填充。</p>
 */
public record ConversationItem(String conversationId,
                               long peerUid,
                               String peerName,
                               long lastSeq,
                               long lastReadSeq,
                               long unread,
                               String preview,
                               Long lastMsgAt) {
}
