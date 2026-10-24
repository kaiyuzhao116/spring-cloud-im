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

    /**
     * 判断某个单聊会话是否属于该用户（防止越权拉别人的聊天记录）。
     *
     * <p>会话 ID 里只有两个 uid，用户必须是其中之一。</p>
     */
    public static boolean contains(String conversationId, long uid) {
        if (conversationId == null || !conversationId.startsWith("s_")) {
            return false;
        }
        String[] parts = conversationId.substring(2).split("_");
        if (parts.length != 2) {
            return false;
        }
        try {
            return Long.parseLong(parts[0]) == uid || Long.parseLong(parts[1]) == uid;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
