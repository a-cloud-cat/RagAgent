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
import com.example.rag.config.RetrievalProperties;
import com.example.rag.embedding.EmbeddingClient;
import com.example.rag.model.Chunk;
import com.example.rag.repository.ChunkRepository;

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

    private final EmbeddingClient embeddingClient;

    private final ChunkRepository chunkRepository;

    private final RetrievalProperties retrievalProperties;

    public ChatService(WebClient webClient,
                       LlmProperties llmProperties,
                       EmbeddingClient embeddingClient,
                       ChunkRepository chunkRepository,
                       RetrievalProperties retrievalProperties) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.retrievalProperties = retrievalProperties;
    }

    public void streamChat(String question, SseEmitter emitter) {
        // 1) 问题向量化 → 2) 检索最相似的 topK 分块 → 3) 拼成上下文
        float[] questionVector = embeddingClient.embed(List.of(question)).get(0);
        List<Chunk> retrieved = chunkRepository.search(questionVector, retrievalProperties.getTopK());

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
