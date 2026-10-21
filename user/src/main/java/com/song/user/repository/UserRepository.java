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

    private static final String COLUMNS = "id, account, password_hash, nickname, avatar, status, created_at";

    private static final RowMapper<UserDTO> ROW_MAPPER = (rs, rowNum) -> {
        UserDTO dto = new UserDTO();
        dto.setId(rs.getLong("id"));
        dto.setAccount(rs.getString("account"));
        dto.setPasswordHash(rs.getString("password_hash"));
        dto.setNickname(rs.getString("nickname"));
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

}
