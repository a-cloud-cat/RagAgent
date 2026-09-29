package com.example.rag.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.rag.config.RerankProperties;
import com.example.rag.model.Chunk;
import com.example.rag.rerank.dto.RerankRequest;
import com.example.rag.rerank.dto.RerankResponse;

/**
 * 百炼 qwen3-rerank 实现（原生端点）。
 */
public class BaiLianRerankClient implements RerankClient {

    private static final Logger log = LoggerFactory.getLogger(BaiLianRerankClient.class);

    private static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    private final WebClient webClient;

    private final RerankProperties properties;

    public BaiLianRerankClient(WebClient webClient, RerankProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public List<Chunk> rerank(String query, List<Chunk> candidates, int topN) {
        if (candidates.size() <= topN) {
            return new ArrayList<>(candidates);
        }

        List<String> documents = candidates.stream()
                .map(Chunk::getContent)
                .collect(Collectors.toList());

        RerankRequest request = new RerankRequest(
                properties.getModel(), query, documents, topN);

        RerankResponse response = webClient.post()
                .uri(properties.getBaseUrl() + RERANK_PATH)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RerankResponse.class)
                .block();

        if (response == null || response.getOutput() == null
                || response.getOutput().getResults() == null) {
            throw new IllegalStateException("百炼 rerank 响应为空");
        }

        List<RerankResponse.Result> results = response.getOutput().getResults();
        if (results.size() > topN) {
            results = results.subList(0, topN);
        }

        List<Chunk> reranked = new ArrayList<>(results.size());
        for (RerankResponse.Result result : results) {
            int idx = result.getIndex();
            Chunk chunk = candidates.get(idx);
            chunk.setScore(result.getRelevanceScore());
            reranked.add(chunk);
        }

        log.debug("rerank 完成：候选 {} 条，取 {} 条", candidates.size(), reranked.size());
        return reranked;
    }
}
