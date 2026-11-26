package com.example.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.rag.config.MemoryProperties;
import com.example.rag.model.ChatMessage;
import com.example.rag.model.ChatSession;
import com.example.rag.repository.ChatMessageRepository;
import com.example.rag.repository.ChatSessionRepository;

class MemoryServiceTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private MemoryProperties properties;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        properties = new MemoryProperties();
        properties.setRecentTurns(8);
        properties.setMaxContextChars(3000);
        service = new MemoryService(sessionRepository, messageRepository, properties);
    }

    @Test
    void 预算不足时优先保留最近的消息并丢弃开头assistant() {
        properties.setMaxContextChars(250);
        List<ChatMessage> rows = List.of(
                msg(1, "user", "一".repeat(100)),
                msg(2, "assistant", "二".repeat(100)),
                msg(3, "user", "三".repeat(100)),
                msg(4, "assistant", "四".repeat(100)),
                msg(5, "user", "五".repeat(100)),
                msg(6, "assistant", "六".repeat(100)));
        when(messageRepository.findRecent(eq(1L), eq(0L), anyInt())).thenReturn(rows);

        MemoryContext context = service.loadContext(session(1L, 0L));

        // 250 字预算只装得下最新两条：user(5) + assistant(6)
        assertEquals(2, context.recentMessages().size());
        assertEquals(List.of(5L, 6L),
                context.recentMessages().stream().map(ChatMessage::getId).toList());
        assertEquals(4, context.droppedMessages());
        assertEquals(200, context.contextChars());
    }

    @Test
    void 裁剪后以assistant开头时继续丢弃直到user开头() {
        properties.setMaxContextChars(250);
        List<ChatMessage> rows = List.of(
                msg(1, "user", "一".repeat(100)),
                msg(2, "assistant", "二".repeat(100)),
                msg(3, "assistant", "三".repeat(100)));
        when(messageRepository.findRecent(eq(1L), eq(0L), anyInt())).thenReturn(rows);

        MemoryContext context = service.loadContext(session(1L, 0L));

        // 窗口内只剩两条 assistant，逐条丢弃后为空，避免 LLM 报角色交替错误
        assertTrue(context.recentMessages().isEmpty());
        assertEquals(3, context.droppedMessages());
        assertEquals(0, context.contextChars());
    }

    @Test
    void 预算充足时保留全部消息且顺序不变() {
        List<ChatMessage> rows = List.of(
                msg(1, "user", "问题一"),
                msg(2, "assistant", "回答一"),
                msg(3, "user", "问题二"),
                msg(4, "assistant", "回答二"));
        when(messageRepository.findRecent(eq(1L), eq(0L), anyInt())).thenReturn(rows);

        MemoryContext context = service.loadContext(session(1L, 0L));

        assertEquals(List.of(1L, 2L, 3L, 4L),
                context.recentMessages().stream().map(ChatMessage::getId).toList());
        assertEquals(0, context.droppedMessages());
    }

    @Test
    void 摘要覆盖的早期消息不参与装配() {
        when(messageRepository.findRecent(eq(1L), eq(30L), anyInt())).thenReturn(List.of());
        MemoryContext context = service.loadContext(session(1L, 30L));
        assertEquals(0, context.recentMessages().size());
        verify(messageRepository).findRecent(1L, 30L, 16);
    }

    @Test
    void userId为空时拒绝解析会话() {
        assertThrows(IllegalArgumentException.class, () -> service.resolveSession("key-1", " "));
        verify(sessionRepository, never()).create(anyString(), anyString());
    }

    @Test
    void 会话归属不匹配时拒绝访问() {
        ChatSession other = session(1L, 0L);
        other.setSessionKey("key-1");
        other.setUserId("user-A");
        when(sessionRepository.findBySessionKey("key-1")).thenReturn(Optional.of(other));

        assertThrows(IllegalArgumentException.class, () -> service.resolveSession("key-1", "user-B"));
    }

    @Test
    void 会话存在且归属匹配时直接复用() {
        ChatSession existing = session(9L, 0L);
        existing.setSessionKey("key-9");
        existing.setUserId("user-A");
        when(sessionRepository.findBySessionKey("key-9")).thenReturn(Optional.of(existing));

        ChatSession resolved = service.resolveSession("key-9", "user-A");

        assertEquals(9L, resolved.getId());
        verify(sessionRepository, never()).create(anyString(), anyString());
    }

    @Test
    void 会话不存在时按前端key补建() {
        when(sessionRepository.findBySessionKey("key-new")).thenReturn(Optional.empty());
        ChatSession created = session(11L, 0L);
        created.setSessionKey("key-new");
        created.setUserId("user-A");
        when(sessionRepository.create("key-new", "user-A")).thenReturn(created);

        ChatSession resolved = service.resolveSession("key-new", "user-A");

        assertEquals(11L, resolved.getId());
        assertTrue("key-new".equals(resolved.getSessionKey()));
    }

    @Test
    void 保存摘要后会话对象同步更新() {
        ChatSession target = session(1L, 0L);
        service.saveSummary(target, "摘要内容", 20L);
        verify(sessionRepository).updateSummary(1L, "摘要内容", 20L);
        assertEquals("摘要内容", target.getSummary());
        assertEquals(20L, target.getSummaryLastId());
    }

    @Test
    void 待压缩条数来自未被摘要覆盖的消息() {
        when(messageRepository.countAfter(anyLong(), anyLong())).thenReturn(7L);
        assertEquals(7L, service.pendingMessageCount(session(1L, 5L)));
        verify(messageRepository).countAfter(1L, 5L);
    }

    private ChatSession session(long id, long summaryLastId) {
        ChatSession session = new ChatSession();
        session.setId(id);
        session.setSummaryLastId(summaryLastId);
        return session;
    }

    private ChatMessage msg(long id, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
