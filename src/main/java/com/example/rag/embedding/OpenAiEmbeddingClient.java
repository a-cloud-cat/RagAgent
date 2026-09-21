package com.example.rag.embedding;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.rag.config.EmbeddingProperties;
import com.example.rag.embedding.dto.EmbeddingData;
import com.example.rag.embedding.dto.EmbeddingRequest;
import com.example.rag.embedding.dto.EmbeddingResponse;


/**
 * 百炼（OpenAI 兼容协议）的向量化实现。
 */
@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

    /** 百炼的 OpenAI 兼容 embedding 接口路径 */
    private static final String EMBEDDING_PATH = "/compatible-mode/v1/embeddings";

    private final WebClient webClient;

    private final EmbeddingProperties properties;

    public OpenAiEmbeddingClient(WebClient webClient, EmbeddingProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        // 配置防错：key 没填就立刻失败
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("百炼 API-KEY 未配置，请填写 application.yaml 中的 rag.embedding.api-key");
        }

        // 百炼 text-embedding-v4 默认返回 1024 维，需显式传 dimension 来返回配置的维度
        EmbeddingRequest request = new EmbeddingRequest(
                properties.getModel(), texts, properties.getDimension());

        // 非流式：一次性返回完整结果，所以用 bodyToMono（不是 bodyToFlux）。
        EmbeddingResponse response = webClient.post()
                .uri(properties.getBaseUrl() + EMBEDDING_PATH)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();

        if (response == null || response.data() == null) {
            throw new IllegalStateException("百炼 embedding 响应为空");
        }

        //一句话分为多个向量，一次性返回多个向量，每个向量的维度应是配置的维度
        // 条数校验：返回向量数与输入文本数一致，防止后续按顺序对齐分块会错位
        if (response.data().size() != texts.size()) {
            throw new IllegalStateException(
                    "embedding 返回条数不一致：输入 " + texts.size() + " 条，返回 " + response.data().size() + " 条");
        }

        List<float[]> vectors = new ArrayList<>(response.data().size());
        for (EmbeddingData item : response.data()) {
            int actual = item.embedding() == null ? 0 : item.embedding().length;
            // 维度校验：对不上就立刻失败，避免把错维度的向量写进库、影响后面检索
            if (actual != properties.getDimension()) {
                throw new IllegalStateException(
                        "向量维度不一致：期望 " + properties.getDimension() + "，实际 " + actual
                                + "，请检查 rag.embedding.model 与 dimension 是否匹配");
            }
            vectors.add(item.embedding());
        }

        log.debug("embedding 完成：输入 {} 条，返回 {} 条，维度 {}", texts.size(), vectors.size(), properties.getDimension());
        return vectors;
    }
}
