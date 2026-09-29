package com.example.rag.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.rag.config.RetrievalProperties;
import com.example.rag.embedding.EmbeddingClient;
import com.example.rag.model.Chunk;
import com.example.rag.rerank.RerankClient;
import com.example.rag.repository.ChunkRepository;

/**
 * 共享检索链路：向量化 → 按 candidate-limit 候选召回 → Rerank 取 top-k → 返回最终分块。
 * ChatService 和 EvalService 都调它，保证改一处两边生效。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingClient embeddingClient;

    private final ChunkRepository chunkRepository;

    private final RerankClient rerankClient;

    private final RetrievalProperties retrievalProperties;

    public RetrievalService(EmbeddingClient embeddingClient,
                            ChunkRepository chunkRepository,
                            RerankClient rerankClient,
                            RetrievalProperties retrievalProperties) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.rerankClient = rerankClient;
        this.retrievalProperties = retrievalProperties;
    }

    /**
     * 完整检索链路：向量化 → 候选召回 → Rerank → 截 top-k。
     * Rerank 异常时降级为向量原始顺序前 top-k。
     */
    public List<Chunk> retrieve(String query) {
        int candidateLimit = retrievalProperties.getCandidateLimit();
        int topK = retrievalProperties.getTopK();

        // 1. 向量化
        float[] queryVector = embeddingClient.embed(List.of(query)).get(0);

        // 2. 候选召回（取 candidateLimit 条）
        List<Chunk> candidates = chunkRepository.search(queryVector, candidateLimit);

        if (candidates.isEmpty()) {
            return candidates;
        }

        // 3. Rerank 精排取 top-k（异常降级）
        try {
            return rerankClient.rerank(query, candidates, topK);
        } catch (Exception e) {
            log.warn("Rerank 调用失败，降级为向量原始顺序前 {} 条：{}", topK, e.getMessage());
            return candidates.size() > topK
                    ? candidates.subList(0, topK)
                    : candidates;
        }
    }
}
