package com.song.im.repository;

import com.song.im.domain.ConversationItem;
import com.song.im.domain.MessageItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 消息与会话卡片的数据库访问。
 *
 * <p>发一条消息 = 1 行 im_message + 2 行 im_conversation_member（双方各一行）。</p>
 */
@Repository
public class MessageRepository {

    private static final String INSERT_MESSAGE = """
            INSERT INTO im_message (conversation_id, seq, from_uid, to_uid, msg_type, content, client_msg_id)
            VALUES (?, ?, ?, ?, 1, ?, ?)
            """;

    /** 发送方那行：自己发的消息当然已读，所以 last_read_seq 跟着推进 */
    private static final String UPSERT_SENDER = """
            INSERT INTO im_conversation_member
                (conversation_id, uid, peer_uid, last_seq, last_read_seq, last_msg_preview, last_msg_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                last_seq = VALUES(last_seq),
                last_read_seq = VALUES(last_read_seq),
                last_msg_preview = VALUES(last_msg_preview),
                last_msg_at = VALUES(last_msg_at)
            """;

    /** 接收方那行：只推进 last_seq（未读 +1），绝不动 last_read_seq */
    private static final String UPSERT_RECEIVER = """
            INSERT INTO im_conversation_member
                (conversation_id, uid, peer_uid, last_seq, last_msg_preview, last_msg_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                last_seq = VALUES(last_seq),
                last_msg_preview = VALUES(last_msg_preview),
                last_msg_at = VALUES(last_msg_at)
            """;

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 该会话当前最大的 seq（用于 Redis 计数器丢失时恢复） */
    public long maxSeq(String conversationId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM im_message WHERE conversation_id = ?",
                Long.class, conversationId);
        return value == null ? 0L : value;
    }

    /** 按幂等键查已存在的消息 */
    public MessageRow findByClientMsgId(long fromUid, String clientMsgId) {
        List<MessageRow> rows = jdbcTemplate.query(
                "SELECT id, seq FROM im_message WHERE from_uid = ? AND client_msg_id = ?",
                (rs, rowNum) -> new MessageRow(rs.getLong("id"), rs.getLong("seq")),
                fromUid, clientMsgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入消息，返回自增主键 */
    public long insert(String conversationId, long seq, long fromUid, long toUid,
                       String content, String clientMsgId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_MESSAGE, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, conversationId);
            ps.setLong(2, seq);
            ps.setLong(3, fromUid);
            ps.setLong(4, toUid);
            ps.setString(5, content);
            ps.setString(6, clientMsgId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    /**
     * 更新双方会话卡片。
     *
     * <p>发送方：last_seq 与 last_read_seq 都推到 seq（自己发的消息不算未读）；
     * 接收方：只推 last_seq，未读 = last_seq - last_read_seq 自然增加。</p>
     */
    public void upsertMembers(String conversationId, long fromUid, long toUid,
                              long seq, String preview, Timestamp messageTime) {
        jdbcTemplate.update(UPSERT_SENDER,
                conversationId, fromUid, toUid, seq, seq, preview, messageTime);
        jdbcTemplate.update(UPSERT_RECEIVER,
                conversationId, toUid, fromUid, seq, preview, messageTime);
    }

    /**
     * 拉取某会话在 {@code beforeSeq} 之前的消息（倒序取，由调用方决定是否反转）。
     *
     * <p>走唯一索引 {@code uk_conv_seq(conversation_id, seq)}，是范围扫描，不会全表扫。</p>
     *
     * @param beforeSeq 上界（不含）；传 {@link Long#MAX_VALUE} 表示取最新
     * @param limit     最多取几条（调用方通常多取 1 条用于判断 hasMore）
     */
    public List<MessageItem> findBefore(String conversationId, long beforeSeq, int limit) {
        return jdbcTemplate.query("""
                        SELECT id, seq, from_uid, content, recalled, created_at
                        FROM im_message
                        WHERE conversation_id = ? AND seq < ?
                        ORDER BY seq DESC
                        LIMIT ?
                        """,
                MESSAGE_ITEM_MAPPER,
                conversationId, beforeSeq, limit);
    }

    /**
     * 拉取某会话在 {@code afterSeq} 之后的消息（升序，用于重连补缺口）。
     *
     * @param afterSeq 下界（不含），通常是客户端本地已知的最大 seq
     */
    public List<MessageItem> findAfter(String conversationId, long afterSeq, int limit) {
        return jdbcTemplate.query("""
                        SELECT id, seq, from_uid, content, recalled, created_at
                        FROM im_message
                        WHERE conversation_id = ? AND seq > ?
                        ORDER BY seq ASC
                        LIMIT ?
                        """,
                MESSAGE_ITEM_MAPPER,
                conversationId, afterSeq, limit);
    }

    /**
     * 找一条消息（撤回前校验用）。
     *
     * @return 不存在返回 null
     */
    public RecallRow findForRecall(String conversationId, long seq) {
        List<RecallRow> list = jdbcTemplate.query("""
                        SELECT seq, from_uid, recalled, created_at
                        FROM im_message
                        WHERE conversation_id = ? AND seq = ?
                        """,
                (rs, rowNum) -> new RecallRow(
                        rs.getLong("seq"),
                        rs.getLong("from_uid"),
                        rs.getInt("recalled") == 1,
                        rs.getTimestamp("created_at").getTime()),
                conversationId, seq);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 撤回：内容清空 + 打标记。
     *
     * <p>{@code from_uid = ?} 放在 SQL 里，从数据库层面杜绝"撤别人的消息"。</p>
     *
     * @return 影响行数；0 表示不是本人的消息或不存在
     */
    public int markRecalled(String conversationId, long seq, long fromUid) {
        return jdbcTemplate.update("""
                        UPDATE im_message
                        SET recalled = 1, content = ''
                        WHERE conversation_id = ? AND seq = ? AND from_uid = ? AND recalled = 0
                        """,
                conversationId, seq, fromUid);
    }

    /** 撤回后同步会话卡片的预览（只改"最后一条"是该消息的卡片） */
    public void updatePreview(String conversationId, long seq, String preview) {
        jdbcTemplate.update("""
                        UPDATE im_conversation_member
                        SET last_msg_preview = ?
                        WHERE conversation_id = ? AND last_seq = ?
                        """,
                preview, conversationId, seq);
    }

    private static final RowMapper<MessageItem> MESSAGE_ITEM_MAPPER = (rs, rowNum) -> new MessageItem(
            rs.getLong("id"),
            rs.getLong("seq"),
            rs.getLong("from_uid"),
            rs.getString("content"),
            rs.getTimestamp("created_at").getTime(),
            rs.getInt("recalled") == 1);

    public record RecallRow(long seq, long fromUid, boolean recalled, long createdAt) {
    }

    /**
     * 某用户的会话列表，按最近消息时间倒序。
     *
     * <p>走 {@code idx_uid_time(uid, last_msg_at DESC)}，不需要回消息表。</p>
     */
    public List<ConversationItem> findConversations(long uid, int limit) {
        return jdbcTemplate.query("""
                        SELECT conversation_id, peer_uid, last_seq, last_read_seq,
                               last_msg_preview, last_msg_at
                        FROM im_conversation_member
                        WHERE uid = ?
                        ORDER BY last_msg_at DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> {
                    long lastSeq = rs.getLong("last_seq");
                    long lastReadSeq = rs.getLong("last_read_seq");
                    Timestamp at = rs.getTimestamp("last_msg_at");
                    return new ConversationItem(
                            rs.getString("conversation_id"),
                            rs.getLong("peer_uid"),
                            null,
                            lastSeq,
                            lastReadSeq,
                            Math.max(0, lastSeq - lastReadSeq),
                            rs.getString("last_msg_preview"),
                            at == null ? null : at.getTime());
                },
                uid, limit);
    }

    /**
     * 推进已读位置（只增不减）。
     *
     * <p>用 {@code GREATEST} 而不是直接赋值：客户端重复上报、乱序上报都不会把已读位置退回。</p>
     *
     * @return 影响行数；0 表示该用户不在这个会话里（或会话不存在）
     */
    public int markRead(String conversationId, long uid, long seq) {
        return jdbcTemplate.update("""
                        UPDATE im_conversation_member
                        SET last_read_seq = GREATEST(last_read_seq, ?)
                        WHERE conversation_id = ? AND uid = ?
                        """,
                seq, conversationId, uid);
    }

    public record MessageRow(long id, long seq) {
    }
}
