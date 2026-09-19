package com.example.rag.service;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.rag.config.LlmProperties;

import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final WebClient webClient;

    private final LlmProperties llmProperties;

    public ChatService(WebClient webClient, LlmProperties llmProperties) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
    }

    public void streamChat(String question, SseEmitter emitter) {
        Map<String, Object> requestBody = Map.of(
                "model", llmProperties.getModelName(),
                "stream", true,
                "messages", new Object[]{
                        Map.of("role", "user", "content", question)
                }
        );

        Flux<String> flux = webClient.post()
                .uri(llmProperties.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + llmProperties.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class);

        // 订阅 Flux，三个回调分别处理：每个数据块、错误、完成。
        flux.subscribe(
                chunk -> {
                    try {
                        emitter.send(chunk);
                    } catch (IOException e) {
                        log.error("sse发送异常", e);
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );
    }
}
