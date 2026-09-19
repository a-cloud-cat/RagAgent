package com.example.rag.controller;

import com.example.rag.model.ChatRequest;
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

        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);

        chatService.streamChat(req.getQuestion(), emitter);

        return emitter;
    }
}
