package com.example.rag.service;

import java.util.List;

import com.example.rag.model.ChatMessage;

/**
 * 一轮问答装配出的记忆上下文。
 *
 * @param summary        服务端生成的早期对话摘要，可为 null
 * @param recentMessages 摘要之后仍需以原文注入的最近消息，按时间正序
 * @param droppedMessages 因字符预算或角色对齐被丢弃的消息条数，用于观测
 * @param contextChars   实际注入的字符数
 */
public record MemoryContext(
        String summary,
        List<ChatMessage> recentMessages,
        int droppedMessages,
        int contextChars) {
}
