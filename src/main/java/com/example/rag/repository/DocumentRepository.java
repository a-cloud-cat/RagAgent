package com.example.rag.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 文档表数据访问。
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新建文档并返回自增主键。
     */
    public Long insert(String name, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            // 显式只返回 id 列：PostgreSQL 驱动在 RETURN_GENERATED_KEYS 时会回传整行，
            // 导致 KeyHolder 含多个键，这里指定列名保证只取主键
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_document (name, status, create_time, update_time) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            ps.setString(1, name);
            ps.setString(2, status);
            ps.setTimestamp(3, now);
            ps.setTimestamp(4, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKeyAs(Long.class);
    }

    /**
     * 更新入库状态。
     */
    public void updateStatus(Long id, String status) {
        jdbcTemplate.update(
                "UPDATE t_document SET status = ?, update_time = ? WHERE id = ?",
                status, Timestamp.valueOf(LocalDateTime.now()), id);
    }
}
