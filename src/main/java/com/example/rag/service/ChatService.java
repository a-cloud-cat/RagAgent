package com.example.rag.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.rag.config.LlmProperties;
import com.example.rag.model.Chunk;

import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一个严谨的问答助手。请严格根据下面「参考资料」回答用户问题：
            1. 资料里有依据的，直接回答；
            2. 资料里没有的内容，明确说「根据现有资料无法回答」，不要编造；
            3. 回答使用与问题相同的语言。

            参考资料：
            %s""";

    private final WebClient webClient;

    private final LlmProperties llmProperties;

    private final RetrievalService retrievalService;

    public ChatService(WebClient webClient,
                       LlmProperties llmProperties,
                       RetrievalService retrievalService) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
        this.retrievalService = retrievalService;
    }

    public void streamChat(String question, SseEmitter emitter) {
        // 检索链路：向量化 → 候选召回 → Rerank → 截 topK
        List<Chunk> retrieved = retrievalService.retrieve(question);

        if (retrieved.isEmpty()) {
            log.info("未召回任何分块，按普通对话处理");
        } else {
            log.info("召回 {} 个分块，相似度：{}",
                    retrieved.size(),
                    retrieved.stream()
                            .map(c -> String.format("%.3f", c.getScore()))
                            .collect(Collectors.joining(", ")));
        }

        List<Map<String, String>> messages = new ArrayList<>();
        if (!retrieved.isEmpty()) {
            String context = buildContext(retrieved);
            messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT_TEMPLATE.formatted(context)));
        }
        messages.add(Map.of("role", "user", "content", question));

        Map<String, Object> requestBody = Map.of(
                "model", llmProperties.getModelName(),
                "stream", true,
                "messages", messages
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

    /** 把召回分块编号拼接成参考资料文本。 */
    private String buildContext(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append('[').append(i + 1).append("] ").append(chunks.get(i).getContent()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
