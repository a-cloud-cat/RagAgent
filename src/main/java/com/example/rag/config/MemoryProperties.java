package com.example.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

/**
 * 记忆系统配置（rag.memory.*）。
 * 上下文预算以字符数近似 token 数控制，避免引入分词器依赖。
 */
@ConfigurationProperties(prefix = "rag.memory")
public class MemoryProperties {

    /** 上下文最多注入的对话轮数（一轮 = 一问一答） */
    private int recentTurns = 8;

    /** 上下文注入的字符预算，超出后丢弃更早的消息（中文约 1 字 ≈ 1 token） */
    private int maxContextChars = 3000;

    /** 未被摘要覆盖的消息条数超过该值时触发压缩 */
    private int summaryTriggerMessages = 16;

    /** 压缩时保留最近多少轮原文，其余归档进摘要 */
    private int summaryKeepTurns = 4;

    public int getRecentTurns() {
        return recentTurns;
    }

    public void setRecentTurns(int recentTurns) {
        this.recentTurns = recentTurns;
    }

    public int getMaxContextChars() {
        return maxContextChars;
    }

    public void setMaxContextChars(int maxContextChars) {
        this.maxContextChars = maxContextChars;
    }

    public int getSummaryTriggerMessages() {
        return summaryTriggerMessages;
    }

    public void setSummaryTriggerMessages(int summaryTriggerMessages) {
        this.summaryTriggerMessages = summaryTriggerMessages;
    }

    public int getSummaryKeepTurns() {
        return summaryKeepTurns;
    }

    public void setSummaryKeepTurns(int summaryKeepTurns) {
        this.summaryKeepTurns = summaryKeepTurns;
    }

    @PostConstruct
    void validate() {
        if (recentTurns < 1) {
            throw new IllegalStateException("rag.memory.recent-turns 必须大于 0");
        }
        if (maxContextChars < 200) {
            throw new IllegalStateException("rag.memory.max-context-chars 不能小于 200");
        }
        if (summaryTriggerMessages < 2) {
            throw new IllegalStateException("rag.memory.summary-trigger-messages 不能小于 2");
        }
        if (summaryKeepTurns < 0) {
            throw new IllegalStateException("rag.memory.summary-keep-turns 不能为负");
        }
    }
}
