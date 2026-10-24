package com.song.im.friend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 好友关系表访问。
 *
 * <p>关系是双向存两行（A→B、B→A），好处是"查我的好友"只需
 * {@code WHERE uid = ?}，能直接走 {@code uk_uid_friend}，而不用 OR 两个字段。</p>
 */
@Repository
public class FriendRepository {

    /** 幂等写入：已存在就把 status 拉回 1（重新加好友） */
    private static final String UPSERT = """
            INSERT INTO im_friend (uid, friend_uid, status) VALUES (?, ?, 1)
            ON DUPLICATE KEY UPDATE status = 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public FriendRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 双向建立关系（两条 SQL，调用方负责事务） */
    public void upsertBoth(long uid, long friendUid) {
        jdbcTemplate.update(UPSERT, uid, friendUid);
        jdbcTemplate.update(UPSERT, friendUid, uid);
    }

    public boolean exists(long uid, long friendUid) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM im_friend
                        WHERE uid = ? AND friend_uid = ? AND status = 1
                        """,
                Integer.class, uid, friendUid);
        return count != null && count > 0;
    }

    public List<Long> friendIds(long uid) {
        return jdbcTemplate.queryForList("""
                        SELECT friend_uid FROM im_friend
                        WHERE uid = ? AND status = 1 ORDER BY id
                        """,
                Long.class, uid);
    }
}
