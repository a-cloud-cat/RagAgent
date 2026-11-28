package com.example.rag.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.rag.model.ChatSession;
import com.example.rag.repository.LlmCallRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * LLM 调用台账记录器：成本度量与微调语料还原的数据来源。
 * 台账写入属于旁路，任何异常都必须吞掉，不能影响主链路。
 */
@Service
public class LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmCallRecorder.class);

    private final LlmCallRepository repository;

    private final ObjectMapper objectMapper;

    public LlmCallRecorder(LlmCallRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一次 LLM 调用。
     *
     * @param session         可空，评测等无会话场景传 null
     * @param usage           流式响应解析出的 usage，可为 null
     * @param messages        实际发给模型的 messages，用于还原微调样本
     */
    public void record(ChatSession session, String traceId, String purpose, String model,
                       Map<String, Object> usage, long latencyMs, boolean success,
                       List<Map<String, String>> messages) {
        try {
            repository.append(
                    session == null ? null : session.getId(),
                    traceId, purpose, model,
                    intOf(usage, "prompt_tokens"),
                    intOf(usage, "completion_tokens"),
                    intOf(usage, "total_tokens"),
                    latencyMs, success,
                    messages == null ? null : objectMapper.writeValueAsString(messages));
        } catch (Exception e) {
            log.warn("[traceId={}] LLM 调用台账写入失败 purpose={}", traceId, purpose, e);
        }
    }

    private Integer intOf(Map<String, Object> usage, String key) {
        if (usage == null) {
            return null;
        }
        Object value = usage.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }
}
