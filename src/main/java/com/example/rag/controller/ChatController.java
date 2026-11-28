package com.example.rag.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.rag.model.ChatMessage;
import com.example.rag.model.ChatSession;
import com.example.rag.repository.ChatMessageRepository;
import com.example.rag.service.ChatService;
import com.example.rag.service.MemoryService;


@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final MemoryService memoryService;
    private final ChatMessageRepository messageRepository;

    public ChatController(ChatService chatService,
                          MemoryService memoryService,
                          ChatMessageRepository messageRepository) {
        this.chatService = chatService;
        this.memoryService = memoryService;
        this.messageRepository = messageRepository;
    }


    @PostMapping
    public SseEmitter chat(@RequestBody ChatRequest req) {
        // 参数校验放在建立 SSE 之前，错误直接以 4xx 返回，而不是在流里报错
        if (req.getQuestion() == null || req.getQuestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        ChatSession session = resolveSession(req);

        // traceId 取 UUID 前 8 位，贯穿整条链路方便 grep 单次问答的各阶段耗时
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);

            chatService.streamChat(req.getQuestion(), emitter, req.isSkipRetrieval(), session, traceId,
                    req.isEnableRecommend(), req.getKnowledgeBaseId());

            return emitter;
        } finally {
            // 同步段（会话解析、检索、消息组装）已在 streamChat 返回前完成，清理 MDC 不影响异步 SSE 回调
            MDC.clear();
        }
    }

    /** 按 id 升序分页取会话消息，beforeId 为 0 时从最早开始。 */
    @GetMapping("/{sessionId}/messages")
    public List<ChatMessage> messages(@PathVariable String sessionId,
                                      @RequestParam(defaultValue = "0") long beforeId,
                                      @RequestParam(defaultValue = "20") int limit) {
        ChatSession session = memoryService.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        return messageRepository.findPage(session.getId(), beforeId, Math.min(Math.max(limit, 1), 100));
    }

    private ChatSession resolveSession(ChatRequest req) {
        try {
            return memoryService.resolveSession(req.getSessionId(), req.getUserId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
