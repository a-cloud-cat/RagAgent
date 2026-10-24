package com.example.rag.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.rag.model.Document;

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

    /** 列出所有未删除文档，按创建时间倒序；分块数用子查询带出，避免逐文档再查一次。 */
    public List<Document> listAll() {
        return jdbcTemplate.query(
                "SELECT d.id, d.name, d.status, d.create_time, "
                        + "(SELECT COUNT(*) FROM t_chunk c "
                        + "WHERE c.document_id = d.id AND c.deleted = 0) AS chunk_count "
                        + "FROM t_document d WHERE d.deleted = 0 ORDER BY d.create_time DESC",
                this::mapRow);
    }

    /** 软删除：将 deleted 置 1，列表与检索均已过滤该字段，不物理删除。 */
    public int markDeleted(Long id) {
        return jdbcTemplate.update(
                "UPDATE t_document SET deleted = 1, update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND deleted = 0", id);
    }

    /** 回收站：列出所有已删除文档，分块数统计已删除分块。 */
    public List<Document> listDeleted() {
        return jdbcTemplate.query(
                "SELECT d.id, d.name, d.status, d.create_time, "
                        + "(SELECT COUNT(*) FROM t_chunk c "
                        + "WHERE c.document_id = d.id AND c.deleted = 1) AS chunk_count "
                        + "FROM t_document d WHERE d.deleted = 1 ORDER BY d.update_time DESC",
                this::mapRow);
    }

    /** 恢复：将 deleted 置回 0，仅作用于已删除记录。 */
    public int restore(Long id) {
        return jdbcTemplate.update(
                "UPDATE t_document SET deleted = 0, update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND deleted = 1", id);
    }

    private Document mapRow(ResultSet rs, int rowNum) throws SQLException {
        Document doc = new Document();
        doc.setId(rs.getLong("id"));
        doc.setName(rs.getString("name"));
        doc.setStatus(rs.getString("status"));
        doc.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        doc.setChunkCount(rs.getInt("chunk_count"));
        return doc;
    }
}
