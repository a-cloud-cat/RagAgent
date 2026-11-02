package com.example.rag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 对话反馈表数据访问。
 */
@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public FeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一条反馈。
     */
    public void insert(String question, String answer, String feedback) {
        jdbcTemplate.update(
                "INSERT INTO t_feedback (question, answer, feedback) VALUES (?, ?, ?)",
                question, answer, feedback);
    }
}
