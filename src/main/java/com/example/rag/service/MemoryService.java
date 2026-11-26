package com.example.rag.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.rag.config.MemoryProperties;
import com.example.rag.model.ChatMessage;
import com.example.rag.model.ChatSession;
import com.example.rag.repository.ChatMessageRepository;
import com.example.rag.repository.ChatSessionRepository;

/**
 * 记忆系统入口：会话解析、上下文装配、消息落库。
 * 上下文由服务端按字符预算装配，前端不再能决定喂给模型多少内容。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int TITLE_MAX_CHARS = 40;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final MemoryProperties properties;

    public MemoryService(ChatSessionRepository sessionRepository,
                         ChatMessageRepository messageRepository,
                         MemoryProperties properties) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.properties = properties;
    }

    /** 按 sessionKey 查会话，用于历史消息查询接口，不做归属校验（由调用方决定）。 */
    public Optional<ChatSession> findSession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findBySessionKey(sessionKey.trim());
    }

    /**
     * 解析会话：sessionKey 为空时新建；已存在时校验归属，防止读到他人会话。
     */    public ChatSession resolveSession(String sessionKey, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String uid = userId.trim();

        if (sessionKey == null || sessionKey.isBlank()) {
            ChatSession created = sessionRepository.create(UUID.randomUUID().toString(), uid);
            log.debug("新建会话 sessionKey={} userId={}", created.getSessionKey(), uid);
            return created;
        }

        Optional<ChatSession> existing = sessionRepository.findBySessionKey(sessionKey.trim());
        if (existing.isPresent()) {
            ChatSession session = existing.get();
            if (!session.getUserId().equals(uid)) {
                throw new IllegalArgumentException("会话不属于当前用户");
            }
            return session;
        }
        // 前端持有但库里没有（如数据库重置），按原 key 补建，保持前端状态连续
        return sessionRepository.create(sessionKey.trim(), uid);
    }

    /**
     * 装配上下文：取摘要 + 摘要之后的最近消息，按字符预算从最旧的开始丢弃。
     */
    public MemoryContext loadContext(ChatSession session) {
        int maxMessages = Math.max(properties.getRecentTurns() * 2, 2);
        List<ChatMessage> rows = messageRepository.findRecent(
                session.getId(), session.getSummaryLastId(), maxMessages);

        int budget = properties.getMaxContextChars();
        Deque<ChatMessage> kept = new ArrayDeque<>();
        int chars = 0;
        // 从最新一条往回累加，保证预算不足时优先保留刚刚发生的对话
        for (int i = rows.size() - 1; i >= 0; i--) {
            ChatMessage message = rows.get(i);
            int length = message.getContent() == null ? 0 : message.getContent().length();
            if (!kept.isEmpty() && chars + length > budget) {
                break;
            }
            kept.addFirst(message);
            chars += length;
        }
        // 保证上下文以 user 开头，避免 LLM 角色交替异常
        while (!kept.isEmpty() && ROLE_ASSISTANT.equals(kept.peekFirst().getRole())) {
            ChatMessage removed = kept.removeFirst();
            chars -= removed.getContent() == null ? 0 : removed.getContent().length();
        }

        int dropped = rows.size() - kept.size();
        if (dropped > 0) {
            log.debug("上下文裁剪 sessionId={} 丢弃 {} 条，保留 {} 条 / {} 字",
                    session.getId(), dropped, kept.size(), chars);
        }
        return new MemoryContext(session.getSummary(), List.copyOf(kept), dropped, chars);
    }

    /** 落库用户问题，首条问题同时作为会话标题。 */
    public ChatMessage appendUserMessage(ChatSession session, String question) {
        ChatMessage message = messageRepository.append(session.getId(), ROLE_USER, question, null);
        sessionRepository.initTitle(session.getId(), toTitle(question));
        sessionRepository.touch(session.getId());
        return message;
    }

    /** 落库助手回答，sources 为引用分块的 JSON，可为 null。 */
    public ChatMessage appendAssistantMessage(ChatSession session, String answer, String sourcesJson) {
        ChatMessage message = messageRepository.append(session.getId(), ROLE_ASSISTANT, answer, sourcesJson);
        sessionRepository.touch(session.getId());
        return message;
    }

    /** 未被摘要覆盖的消息条数，压缩触发条件。 */
    public long pendingMessageCount(ChatSession session) {
        return messageRepository.countAfter(session.getId(), session.getSummaryLastId());
    }

    /** 回写摘要与覆盖边界。 */
    public void saveSummary(ChatSession session, String summary, long summaryLastId) {
        sessionRepository.updateSummary(session.getId(), summary, summaryLastId);
        session.setSummary(summary);
        session.setSummaryLastId(summaryLastId);
    }

    /** 取会话中尚未被摘要覆盖、按时间正序的早期消息，供压缩使用。 */
    public List<ChatMessage> pendingMessages(ChatSession session, int limit) {
        return messageRepository.findRecent(session.getId(), session.getSummaryLastId(), limit);
    }

    private String toTitle(String question) {
        if (question == null) {
            return null;
        }
        String flat = question.replaceAll("\\s+", " ").trim();
        return flat.length() <= TITLE_MAX_CHARS ? flat : flat.substring(0, TITLE_MAX_CHARS);
    }
}
