package com.song.im.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 本地消息表（Outbox）访问。
 *
 * <p>核心表结构：</p>
 * <pre>
 * status: 0 待发送 / 1 发送中 / 2 已发送 / 3 死信
 * </pre>
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入一条待发事件。
     *
     * <p><b>必须和业务数据在同一个事务里</b>——这正是 Outbox 的全部意义：
     * "消息落库"和"要发通知"要么都成功，要么都失败。</p>
     */
    public void insert(String eventType, String payload) {
        jdbcTemplate.update("""
                        INSERT INTO im_outbox (event_type, payload, status, next_retry_at)
                        VALUES (?, ?, 0, CURRENT_TIMESTAMP(3))
                        """,
                eventType, payload);
    }

    /**
     * 抢占一批待发事件：改成"发送中"并返回。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 让多实例并行扫描时互不阻塞、
     * 也不会重复取到同一行（MySQL 8 支持）。</p>
     */
    public List<OutboxRow> claimBatch(int limit) {
        List<OutboxRow> rows = jdbcTemplate.query("""
                        SELECT id, event_type, payload, retry
                        FROM im_outbox
                        WHERE status = 0 AND next_retry_at <= CURRENT_TIMESTAMP(3)
                        ORDER BY id ASC
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, i) -> new OutboxRow(rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("retry")),
                limit);

        rows.forEach(row -> jdbcTemplate.update(
                "UPDATE im_outbox SET status = 1 WHERE id = ?", row.id()));
        return rows;
    }

    public void markSent(long id) {
        jdbcTemplate.update("""
                        UPDATE im_outbox
                        SET status = 2, sent_at = CURRENT_TIMESTAMP(3)
                        WHERE id = ?
                        """, id);
    }

    /**
     * 发送失败：回到待发状态并推迟重试。
     *
     * @param retry   已重试次数（含本次）
     * @param nextAt  下次可重试时间
     * @param dead    是否转入死信
     */
    public void markFailed(long id, int retry, Timestamp nextAt, boolean dead) {
        jdbcTemplate.update("""
                        UPDATE im_outbox
                        SET status = ?, retry = ?, next_retry_at = ?
                        WHERE id = ?
                        """,
                dead ? 3 : 0, retry, nextAt, id);
    }

    /**
     * 回收"卡住"的事件。
     *
     * <p>进程在"发送中"状态崩溃时，这些行不会有人再处理；
     * 让它们超时回到待发状态，最多造成一次重复发送（消费端按 seq 去重即可）。</p>
     */
    public int resetStuck(long olderThanSeconds) {
        return jdbcTemplate.update("""
                        UPDATE im_outbox
                        SET status = 0
                        WHERE status = 1
                          AND created_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND)
                        """, olderThanSeconds);
    }

    /** 清理已发送的历史记录，避免表无限增长 */
    public int deleteSentBefore(long olderThanHours) {
        return jdbcTemplate.update("""
                        DELETE FROM im_outbox
                        WHERE status = 2
                          AND sent_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL ? HOUR)
                        """, olderThanHours);
    }

    public record OutboxRow(long id, String eventType, String payload, int retry) {
    }
}
