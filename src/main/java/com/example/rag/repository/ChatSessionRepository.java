package com.example.rag.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.rag.model.ChatSession;

/**
 * 会话数据访问。session_key 为对外标识，id 为内部主键。
 */
@Repository
public class ChatSessionRepository {

    private static final String COLUMNS =
            "id, session_key, user_id, title, summary, summary_last_id, "
                    + "create_time, update_time, last_active_time";

    private final JdbcTemplate jdbcTemplate;

    public ChatSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新建会话。sessionKey 由调用方生成（UUID），冲突时抛 DuplicateKeyException。 */
    public ChatSession create(String sessionKey, String userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO t_chat_session (session_key, user_id) VALUES (?, ?) RETURNING " + COLUMNS,
                this::mapRow, sessionKey, userId);
    }

    public Optional<ChatSession> findBySessionKey(String sessionKey) {
        List<ChatSession> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM t_chat_session WHERE session_key = ?",
                this::mapRow, sessionKey);
        return rows.stream().findFirst();
    }

    /** 更新活跃时间，每次问答结束时调用。 */
    public void touch(long sessionId) {
        jdbcTemplate.update(
                "UPDATE t_chat_session SET last_active_time = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                sessionId);
    }

    /** 首条用户问题写入时设置会话标题，已有标题则不覆盖。 */
    public void initTitle(long sessionId, String title) {
        jdbcTemplate.update(
                "UPDATE t_chat_session SET title = ?, update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND title IS NULL",
                title, sessionId);
    }

    /** 摘要压缩完成后回写摘要与覆盖边界。 */
    public void updateSummary(long sessionId, String summary, long summaryLastId) {
        jdbcTemplate.update(
                "UPDATE t_chat_session SET summary = ?, summary_last_id = ?, update_time = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                summary, summaryLastId, sessionId);
    }

    /** 按用户列出最近会话，用于会话列表。 */
    public List<ChatSession> listByUser(String userId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM t_chat_session WHERE user_id = ? "
                        + "ORDER BY last_active_time DESC LIMIT ?",
                this::mapRow, userId, limit);
    }

    private ChatSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        ChatSession session = new ChatSession();
        session.setId(rs.getLong("id"));
        session.setSessionKey(rs.getString("session_key"));
        session.setUserId(rs.getString("user_id"));
        session.setTitle(rs.getString("title"));
        session.setSummary(rs.getString("summary"));
        session.setSummaryLastId(rs.getLong("summary_last_id"));
        session.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        session.setUpdateTime(toLocalDateTime(rs.getTimestamp("update_time")));
        session.setLastActiveTime(toLocalDateTime(rs.getTimestamp("last_active_time")));
        return session;
    }

    private java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
