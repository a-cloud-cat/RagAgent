package com.example.rag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * LLM 调用台账数据访问。仅供 LlmCallRecorder 使用。
 */
@Repository
public class LlmCallRepository {

    private final JdbcTemplate jdbcTemplate;

    public LlmCallRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(Long sessionId, String traceId, String purpose, String model,
                       Integer promptTokens, Integer completionTokens, Integer totalTokens,
                       long latencyMs, boolean success, String contextSnapshot) {
        jdbcTemplate.update(
                "INSERT INTO t_llm_call (session_id, trace_id, purpose, model, prompt_tokens, "
                        + "completion_tokens, total_tokens, latency_ms, success, context_snapshot) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                sessionId, traceId, purpose, model, promptTokens, completionTokens, totalTokens,
                (int) Math.min(latencyMs, Integer.MAX_VALUE), success, contextSnapshot);
    }
}
