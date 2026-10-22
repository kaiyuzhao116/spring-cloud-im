package com.song.im.domain;

/**
 * 会话 ID 规则：
 * <pre>
 * 单聊：s_{小uid}_{大uid}   例：s_1_2（双方算出来一致）
 * 群聊（后续）：g_{groupId}
 * </pre>
 */
public final class ConversationId {

    private ConversationId() {
    }

    public static String single(long uidA, long uidB) {
        long min = Math.min(uidA, uidB);
        long max = Math.max(uidA, uidB);
        return "s_" + min + "_" + max;
    }
}
