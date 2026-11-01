package com.example.rag.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.rag.config.RerankProperties;
import com.example.rag.config.RetrievalProperties;
import com.example.rag.embedding.EmbeddingClient;
import com.example.rag.model.Chunk;
import com.example.rag.repository.ChunkRepository;
import com.example.rag.rerank.RerankClient;

/**
 * 共享检索链路：向量化 → 按 candidate-limit 候选召回 → Rerank 取 top-k → 证据闸门。
 * ChatService 和 EvalService 都调它，保证改一处两边生效。
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingClient embeddingClient;

    private final ChunkRepository chunkRepository;

    private final RerankClient rerankClient;

    private final RetrievalProperties retrievalProperties;

    private final RerankProperties rerankProperties;

    public RetrievalService(EmbeddingClient embeddingClient,
                            ChunkRepository chunkRepository,
                            RerankClient rerankClient,
                            RetrievalProperties retrievalProperties,
                            RerankProperties rerankProperties) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.rerankClient = rerankClient;
        this.retrievalProperties = retrievalProperties;
        this.rerankProperties = rerankProperties;
    }

    /**
     * 完整检索链路：向量化 → 候选召回 → Rerank → 截 top-k → 证据闸门。
     * Rerank 异常时降级为向量原始顺序前 top-k，并放行（故障期沿用带资料回答）。
     * traceId 仅用于把各阶段耗时日志串到同一次问答。
     */
    public RetrievalOutcome retrieve(String query, String traceId) {
        long start = System.currentTimeMillis();
        int candidateLimit = retrievalProperties.getCandidateLimit();
        int topK = retrievalProperties.getTopK();

        // 1. 向量化
        long t0 = System.currentTimeMillis();
        float[] queryVector = embeddingClient.embed(List.of(query)).get(0);
        log.info("[traceId={}] 阶段=向量化 耗时={}ms", traceId, System.currentTimeMillis() - t0);

        // 2. 候选召回（取 candidateLimit 条）
        long t1 = System.currentTimeMillis();
        List<Chunk> candidates = chunkRepository.search(queryVector, candidateLimit);
        log.info("[traceId={}] 阶段=召回 耗时={}ms 候选数={}", traceId, System.currentTimeMillis() - t1, candidates.size());

        if (candidates.isEmpty()) {
            log.info("[traceId={}] 阶段=检索 耗时={}ms 结果=无候选", traceId, System.currentTimeMillis() - start);
            return new RetrievalOutcome(candidates, 0, false);
        }

        // 3. Rerank 精排取 top-k（异常降级为向量原始顺序，降级时不卡闸门）
        List<Chunk> retrieved;
        try {
            long t2 = System.currentTimeMillis();
            retrieved = rerankClient.rerank(query, candidates, topK);
            log.info("[traceId={}] 阶段=rerank 耗时={}ms", traceId, System.currentTimeMillis() - t2);
        } catch (Exception e) {
            log.warn("[traceId={}] Rerank 调用失败，降级为向量原始顺序前 {} 条：{}", traceId, topK, e.getMessage());
            List<Chunk> fallback = candidates.size() > topK
                    ? candidates.subList(0, topK)
                    : candidates;
            log.info("[traceId={}] 阶段=检索 耗时={}ms 结果=降级", traceId, System.currentTimeMillis() - start);
            return new RetrievalOutcome(fallback, scoreOf(fallback), true);
        }

        if (retrieved.isEmpty()) {
            log.info("[traceId={}] 阶段=检索 耗时={}ms 结果=无结果", traceId, System.currentTimeMillis() - start);
            return new RetrievalOutcome(retrieved, 0, false);
        }

        double top1Score = scoreOf(retrieved);
        if (!rerankProperties.isEnabled()) {
            // 分数是向量余弦，跨 query 不可比，闸门只在 rerank 开启时生效；
            // 例外：无候选时更早返回按无证据处理，即使 rerank 关闭也会拦截
            log.info("[traceId={}] 阶段=检索 耗时={}ms 结果=放行(rerank关闭) top1={}",
                    traceId, System.currentTimeMillis() - start, String.format("%.3f", top1Score));
            return new RetrievalOutcome(retrieved, top1Score, true);
        }

        double threshold = retrievalProperties.getEvidenceThreshold();
        boolean ready = top1Score >= threshold;
        if (ready) {
            log.info("[traceId={}] 阶段=闸门 结果=放行 top1={} ≥ {}", traceId,
                    String.format("%.3f", top1Score), threshold);
        } else {
            log.info("[traceId={}] 阶段=闸门 结果=拦截 top1={} < {}", traceId,
                    String.format("%.3f", top1Score), threshold);
        }
        log.info("[traceId={}] 阶段=检索 耗时={}ms", traceId, System.currentTimeMillis() - start);
        return new RetrievalOutcome(retrieved, top1Score, ready);
    }

    private double scoreOf(List<Chunk> chunks) {
        Double score = chunks.get(0).getScore();
        return score == null ? 0 : score;
    }
}
