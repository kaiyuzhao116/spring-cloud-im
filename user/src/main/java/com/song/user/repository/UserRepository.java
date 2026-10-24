package com.song.user.repository;

import com.song.api.user.dto.UserDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 用户表访问（最小实现：JdbcTemplate，暂不引入 ORM）。
 */
@Repository
public class UserRepository {

    private static final String COLUMNS = "id, account, password_hash, nickname, role, avatar, status, created_at";

    private static final RowMapper<UserDTO> ROW_MAPPER = (rs, rowNum) -> {
        UserDTO dto = new UserDTO();
        dto.setId(rs.getLong("id"));
        dto.setAccount(rs.getString("account"));
        dto.setPasswordHash(rs.getString("password_hash"));
        dto.setNickname(rs.getString("nickname"));
        dto.setRole(rs.getString("role"));
        dto.setAvatar(rs.getString("avatar"));
        dto.setStatus(rs.getInt("status"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        dto.setCreatedAt(createdAt == null ? null : createdAt.getTime());
        return dto;
    };

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserDTO> findByAccount(String account) {
        List<UserDTO> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sys_user WHERE account = ?", ROW_MAPPER, account);
        return list.stream().findFirst();
    }

    public Optional<UserDTO> findById(Long userId) {
        List<UserDTO> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sys_user WHERE id = ?", ROW_MAPPER, userId);
        return list.stream().findFirst();
    }

    /**
     * 创建用户。
     *
     * <p>不做"先查后插"：账号唯一性交给 {@code uk_account} 兜底，
     * 否则并发注册时两个请求都查不到、都通过，最后才在插入时炸。</p>
     *
     * @throws org.springframework.dao.DuplicateKeyException 账号已存在
     */
    public UserDTO insert(String account, String passwordHash, String nickname) {
        jdbcTemplate.update("""
                        INSERT INTO sys_user (account, password_hash, nickname, status)
                        VALUES (?, ?, ?, 1)
                        """,
                account, passwordHash, nickname);

        return findByAccount(account)
                .orElseThrow(() -> new IllegalStateException("创建用户后查询失败: " + account));
    }

    /**
     * 按账号精确查找一个人（加好友用）。
     *
     * <p>只做精确匹配：账号唯一索引直接命中，且天然"一对一"——
     * 前缀模糊会让一个输入框列出几十个人，既慢又容易被用来批量抓用户。</p>
     */
    public Optional<UserDTO> findByAccountExact(String account, Long excludeUid) {
        List<UserDTO> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sys_user WHERE account = ? AND status = 1 AND id <> ? LIMIT 1",
                ROW_MAPPER, account, excludeUid == null ? -1L : excludeUid);
        return list.stream().findFirst();
    }

    /**
     * 后台用户列表（支持关键词模糊匹配账号/昵称）。
     *
     * <p>后台才用，数据量可控，这里用 {@code %kw%} 方便管理员找人；
     * 对外搜索接口仍是精确账号匹配。</p>
     */
    public List<UserDTO> listForAdmin(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM sys_user ORDER BY id ASC LIMIT ?",
                    ROW_MAPPER, limit);
        }
        String like = "%" + keyword.trim() + "%";
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sys_user WHERE account LIKE ? OR nickname LIKE ? ORDER BY id ASC LIMIT ?",
                ROW_MAPPER, like, like, limit);
    }

    /** 启用/禁用（"删除用户"用禁用代替，避免消息表出现孤儿 uid） */
    public int updateStatus(Long userId, int status) {
        return jdbcTemplate.update("UPDATE sys_user SET status = ? WHERE id = ?", status, userId);
    }

    public int updatePasswordHash(Long userId, String passwordHash) {
        return jdbcTemplate.update("UPDATE sys_user SET password_hash = ? WHERE id = ?", passwordHash, userId);
    }

    /** 批量查询（好友/会话列表填昵称，避免 N+1 次 RPC 与查询） */
    public List<UserDTO> findByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM sys_user WHERE id IN (" + placeholders + ")",
                ROW_MAPPER, ids.toArray());
    }

}
