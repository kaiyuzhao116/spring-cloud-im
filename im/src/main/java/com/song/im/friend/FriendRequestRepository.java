package com.song.im.friend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 好友申请表访问。
 *
 * <p>唯一键 {@code uk_from_to(from_uid, to_uid)} 保证"同一对用户只有一条申请记录"，
 * 重复申请走 UPDATE 覆盖（重置为待处理），不会越积越多。</p>
 */
@Repository
public class FriendRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    public FriendRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 重复申请：覆盖验证消息并重置为待处理 */
    public void upsertPending(long fromUid, long toUid, String message) {
        jdbcTemplate.update("""
                        INSERT INTO im_friend_request (from_uid, to_uid, message, status)
                        VALUES (?, ?, ?, 0)
                        ON DUPLICATE KEY UPDATE message = VALUES(message), status = 0,
                                                created_at = CURRENT_TIMESTAMP(3), handled_at = NULL
                        """,
                fromUid, toUid, message);
    }

    /** 我收到的待处理申请 */
    public List<Row> listIncoming(long toUid, int limit) {
        return jdbcTemplate.query("""
                        SELECT id, from_uid, message, status, created_at
                        FROM im_friend_request
                        WHERE to_uid = ? AND status = 0
                        ORDER BY created_at DESC
                        LIMIT ?
                        """,
                (rs, i) -> {
                    Timestamp at = rs.getTimestamp("created_at");
                    return new Row(rs.getLong("id"), rs.getLong("from_uid"), rs.getString("message"),
                            rs.getInt("status"), at == null ? 0 : at.getTime());
                },
                toUid, limit);
    }

    public Row findById(long id) {
        List<Row> list = jdbcTemplate.query("""
                        SELECT id, from_uid, message, status, created_at
                        FROM im_friend_request WHERE id = ?
                        """,
                (rs, i) -> {
                    Timestamp at = rs.getTimestamp("created_at");
                    return new Row(rs.getLong("id"), rs.getLong("from_uid"), rs.getString("message"),
                            rs.getInt("status"), at == null ? 0 : at.getTime());
                },
                id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 处理申请。
     *
     * @return 影响行数；0 表示这条申请不属于该用户或已被处理（防越权与重复处理）
     */
    public int markHandled(long id, long toUid, int status) {
        return jdbcTemplate.update("""
                        UPDATE im_friend_request
                        SET status = ?, handled_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ? AND to_uid = ? AND status = 0
                        """,
                status, id, toUid);
    }

    public record Row(long id, long fromUid, String message, int status, long createdAt) {
    }
}
