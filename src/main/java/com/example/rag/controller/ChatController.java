package com.example.rag.controller;

import java.util.UUID;

import org.slf4j.MDC;

import com.example.rag.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }


    @PostMapping
    public SseEmitter chat(@RequestBody ChatRequest req) {
        // traceId 取 UUID 前 8 位，贯穿整条链路方便 grep 单次问答的各阶段耗时
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        try {
            SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);

            chatService.streamChat(req.getQuestion(), emitter, req.isSkipRetrieval(),
                    req.getHistory(), req.getSummary(), traceId, req.isEnableRecommend());

            return emitter;
        } finally {
            // 同步段（检索、消息组装）已在 streamChat 返回前完成，清理 MDC 不影响异步 SSE 回调
            MDC.clear();
        }
    }
}
