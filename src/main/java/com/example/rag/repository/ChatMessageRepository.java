package com.example.rag.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.rag.model.ChatMessage;

/**
 * 会话消息数据访问。消息按自增主键排序，天然按写入顺序。
 */
@Repository
public class ChatMessageRepository {

    private static final String COLUMNS = "id, session_id, role, content, sources, create_time";

    private final JdbcTemplate jdbcTemplate;

    public ChatMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加一条消息，返回落库后的实体（含主键与创建时间）。 */
    public ChatMessage append(long sessionId, String role, String content, String sourcesJson) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_chat_message (session_id, role, content, sources) "
                        + "VALUES (?, ?, ?, ?::jsonb) RETURNING " + COLUMNS,
                this::mapRow, sessionId, role, content, sourcesJson);
    }

    /**
     * 取 id 大于 afterId 的最近 limit 条消息，按时间正序返回。
     * afterId 传会话的 summary_last_id，即可跳过已被摘要覆盖的早期消息。
     */
    public List<ChatMessage> findRecent(long sessionId, long afterId, int limit) {
        List<ChatMessage> rows = new ArrayList<>(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM t_chat_message "
                        + "WHERE session_id = ? AND id > ? ORDER BY id DESC LIMIT ?",
                this::mapRow, sessionId, afterId, limit));
        // 查询按倒序取最近 N 条，反转后恢复对话顺序
        Collections.reverse(rows);
        return rows;
    }

    /** 统计未被摘要覆盖的消息条数，用于判断是否触发压缩。 */
    public long countAfter(long sessionId, long afterId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_chat_message WHERE session_id = ? AND id > ?",
                Long.class, sessionId, afterId);
        return count == null ? 0 : count;
    }

    /** 按 id 升序分页取消息，beforeId 为 0 表示从最早开始，用于前端加载历史。 */
    public List<ChatMessage> findPage(long sessionId, long beforeId, int limit) {
        if (beforeId <= 0) {
            return jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM t_chat_message "
                            + "WHERE session_id = ? ORDER BY id ASC LIMIT ?",
                    this::mapRow, sessionId, limit);
        }
        List<ChatMessage> rows = new ArrayList<>(jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM t_chat_message "
                        + "WHERE session_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                this::mapRow, sessionId, beforeId, limit));
        Collections.reverse(rows);
        return rows;
    }

    private ChatMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
        ChatMessage message = new ChatMessage();
        message.setId(rs.getLong("id"));
        message.setSessionId(rs.getLong("session_id"));
        message.setRole(rs.getString("role"));
        message.setContent(rs.getString("content"));
        message.setSources(rs.getString("sources"));
        Timestamp ts = rs.getTimestamp("create_time");
        message.setCreateTime(ts == null ? null : ts.toLocalDateTime());
        return message;
    }
}
