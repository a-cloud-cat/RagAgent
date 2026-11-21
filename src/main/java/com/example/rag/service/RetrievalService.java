package com.example.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final Bm25Scorer bm25Scorer;

    private final RetrievalProperties retrievalProperties;

    private final RerankProperties rerankProperties;

    public RetrievalService(EmbeddingClient embeddingClient,
                            ChunkRepository chunkRepository,
                            RerankClient rerankClient,
                            Bm25Scorer bm25Scorer,
                            RetrievalProperties retrievalProperties,
                            RerankProperties rerankProperties) {
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.rerankClient = rerankClient;
        this.bm25Scorer = bm25Scorer;
        this.retrievalProperties = retrievalProperties;
        this.rerankProperties = rerankProperties;
    }

    /**
     * 完整检索链路：向量化 → 向量召回 + BM25 关键词召回 → 合并去重 → Rerank → 截 top-k → 证据闸门。
     * Rerank 异常时降级为合并后原始顺序前 top-k，并放行（故障期沿用带资料回答）。
     * traceId 仅用于把各阶段耗时日志串到同一次问答。
     */
    public RetrievalOutcome retrieve(String query, String traceId, Long knowledgeBaseId) {
        long start = System.currentTimeMillis();
        int candidateLimit = retrievalProperties.getCandidateLimit();
        int topK = retrievalProperties.getTopK();

        // 1. 向量化
        long t0 = System.currentTimeMillis();
        float[] queryVector = embeddingClient.embed(List.of(query)).get(0);
        log.info("[traceId={}] 阶段=向量化 耗时={}ms", traceId, System.currentTimeMillis() - t0);

        // 2. 向量召回（取 candidateLimit 条，可选知识库过滤）
        long t1 = System.currentTimeMillis();
        List<Chunk> vectorCandidates = chunkRepository.search(queryVector, candidateLimit, knowledgeBaseId);
        log.info("[traceId={}] 阶段=向量召回 耗时={}ms 候选数={} knowledgeBaseId={}", traceId, System.currentTimeMillis() - t1, vectorCandidates.size(), knowledgeBaseId);

        // 3. BM25 关键词召回（可选，与向量召回互补）
        List<Chunk> bm25Candidates = List.of();
        if (retrievalProperties.isBm25Enabled()) {
            long t1b = System.currentTimeMillis();
            bm25Candidates = bm25Retrieve(query, knowledgeBaseId);
            log.info("[traceId={}] 阶段=BM25召回 耗时={}ms 候选数={}", traceId, System.currentTimeMillis() - t1b, bm25Candidates.size());
        }

        // 4. RRF 排名融合两路召回（按 1/(k+rank) 累计，不碰绝对分数）
        List<Chunk> candidates = rrfFusion(vectorCandidates, bm25Candidates);

        if (candidates.isEmpty()) {
            log.info("[traceId={}] 阶段=检索 耗时={}ms 结果=无候选", traceId, System.currentTimeMillis() - start);
            return new RetrievalOutcome(candidates, 0, false);
        }

        // 5. Rerank 精排取 top-k（异常降级为合并后原始顺序，降级时不卡闸门）
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

    /**
     * BM25 关键词召回：提取关键词 → ILIKE 召回候选 → 拉语料统计与词频 → BM25 打分排序。
     * 任一步骤异常时返回空列表，不影响向量召回主链路。
     */
    private List<Chunk> bm25Retrieve(String query, Long knowledgeBaseId) {
        try {
            List<String> keywords = KeywordExtractor.extract(query);
            if (keywords.isEmpty()) {
                return List.of();
            }

            int limit = retrievalProperties.getBm25CandidateLimit();
            List<Chunk> candidates = chunkRepository.searchByKeywords(keywords, limit, knowledgeBaseId);
            if (candidates.isEmpty()) {
                return List.of();
            }

            CorpusStats stats = chunkRepository.getCorpusStats(knowledgeBaseId);
            if (stats.totalDocs() == 0) {
                return List.of();
            }

            Map<String, Long> termDocCounts = chunkRepository.countTermDocFreq(keywords, knowledgeBaseId);
            return bm25Scorer.score(candidates, keywords, stats, termDocCounts);
        } catch (Exception e) {
            // BM25 是补充链路，失败不影响向量召回主流程
            log.error("[BM25] 关键词召回失败，跳过", e);
            return List.of();
        }
    }

    /**
     * RRF 排名融合：对向量召回和 BM25 召回各自的排名位置，
     * 按 1/(k + rank) 累计分数，按融合分降序返回。
     * 不使用绝对分数，只看排名位置——天然适配不同分数体系融合。
     */
    private List<Chunk> rrfFusion(List<Chunk> vectorCandidates, List<Chunk> bm25Candidates) {
        int k = retrievalProperties.getRrfK();
        Map<Long, Double> rrfScores = new HashMap<>();
        Map<Long, Chunk> chunkMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorCandidates.size(); i++) {
            Chunk c = vectorCandidates.get(i);
            chunkMap.put(c.getId(), c);
            rrfScores.merge(c.getId(), 1.0 / (k + i), Double::sum);
        }

        for (int i = 0; i < bm25Candidates.size(); i++) {
            Chunk c = bm25Candidates.get(i);
            chunkMap.putIfAbsent(c.getId(), c);
            rrfScores.merge(c.getId(), 1.0 / (k + i), Double::sum);
        }

        List<Chunk> all = new ArrayList<>(chunkMap.values());
        all.sort((a, b) -> {
            double diff = rrfScores.get(b.getId()) - rrfScores.get(a.getId());
            if (diff != 0) {
                return diff > 0 ? 1 : -1;
            }
            return 0;
        });

        for (Chunk c : all) {
            c.setScore(rrfScores.get(c.getId()));
        }
        return all;
    }

    private double scoreOf(List<Chunk> chunks) {
        Double score = chunks.get(0).getScore();
        return score == null ? 0 : score;
    }
}
