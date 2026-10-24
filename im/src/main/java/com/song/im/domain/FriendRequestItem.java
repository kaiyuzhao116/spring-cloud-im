package com.song.im.domain;

/**
 * 好友申请项（返回给"被申请方"）。
 */
public record FriendRequestItem(long id,
                                long fromUid,
                                String fromName,
                                String message,
                                int status,
                                long createdAt) {
}
