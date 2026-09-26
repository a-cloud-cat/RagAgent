package com.example.rag.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.example.rag.config.EmbeddingProperties;
import com.example.rag.config.RetrievalProperties;
import com.example.rag.embedding.EmbeddingClient;
import com.example.rag.eval.EvalReport.CaseResult;
import com.example.rag.eval.EvalReport.RetrievedChunk;
import com.example.rag.model.Chunk;
import com.example.rag.repository.ChunkRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检索评测服务：加载评测用例，逐题复用主链路跑向量化与 topK 检索，判定命中并汇总指标。
 */
@Service
public class EvalService {

    /** 评测集在 classpath 下的位置 */
    private static final String CASES_RESOURCE = "eval/retrieval-cases.json";

    /** 报告中分块摘要截取的字符数 */
    private static final int SNIPPET_LENGTH = 80;

    /** 与 TextChunker 常量保持一致，仅用于在报告中记录评测时的切块参数 */
    private static final String CHUNK_SIZE_DESC = "300/50";

    private final ObjectMapper objectMapper;

    private final EmbeddingClient embeddingClient;

    private final ChunkRepository chunkRepository;

    private final RetrievalProperties retrievalProperties;

    private final EmbeddingProperties embeddingProperties;

    public EvalService(ObjectMapper objectMapper,
                       EmbeddingClient embeddingClient,
                       ChunkRepository chunkRepository,
                       RetrievalProperties retrievalProperties,
                       EmbeddingProperties embeddingProperties) {
        this.objectMapper = objectMapper;
        this.embeddingClient = embeddingClient;
        this.chunkRepository = chunkRepository;
        this.retrievalProperties = retrievalProperties;
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * 跑一遍完整评测。
     *
     * @return 汇总报告
     */
    public EvalReport run() {
        List<EvalCase> cases = loadCases();
        int topK = retrievalProperties.getTopK();

        List<CaseResult> results = new ArrayList<>(cases.size());
        int hits = 0;
        double scoreSum = 0;
        double latencySum = 0;

        for (EvalCase evalCase : cases) {
            long start = System.nanoTime();
            float[] queryVector = embeddingClient.embed(List.of(evalCase.getQuestion())).get(0);
            List<Chunk> retrieved = chunkRepository.search(queryVector, topK);
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;

            int hitRank = findHitRank(evalCase, retrieved);
            if (hitRank > 0) {
                hits++;
            }
            if (!retrieved.isEmpty()) {
                scoreSum += retrieved.get(0).getScore();
            }
            latencySum += latencyMs;

            results.add(buildCaseResult(evalCase, retrieved, hitRank, latencyMs));
        }

        int total = cases.size();
        EvalReport report = new EvalReport();
        report.setTopK(topK);
        report.setTotal(total);
        report.setHits(hits);
        report.setHitRate(total == 0 ? 0 : round3((double) hits / total));
        report.setAvgScore(total == 0 ? 0 : round3(scoreSum / total));
        report.setAvgLatencyMs(total == 0 ? 0 : round3(latencySum / total));
        report.setChunkSize(CHUNK_SIZE_DESC);
        report.setEmbeddingModel(embeddingProperties.getModel());
        report.setCases(results);
        return report;
    }

    /**
     * 按召回顺序返回第一个命中分块的名次（1 起算），未命中返回 0。
     * 命中条件：文档名精确相等，且分块内容包含期望短语（均做去空白、忽略大小写归一化）。
     */
    private int findHitRank(EvalCase evalCase, List<Chunk> retrieved) {
        String expectedDocument = evalCase.getDocument().trim();
        String expectedPhrase = normalize(evalCase.getExpectedPhrase());

        for (int i = 0; i < retrieved.size(); i++) {
            Chunk chunk = retrieved.get(i);
            if (expectedDocument.equals(chunk.getDocumentName())
                    && normalize(chunk.getContent()).contains(expectedPhrase)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 组装单题结果：名次、耗时与召回块精简视图。 */
    private CaseResult buildCaseResult(EvalCase evalCase, List<Chunk> retrieved,
                                       int hitRank, double latencyMs) {
        List<RetrievedChunk> chunkViews = new ArrayList<>(retrieved.size());
        for (Chunk chunk : retrieved) {
            RetrievedChunk view = new RetrievedChunk();
            view.setDocument(chunk.getDocumentName());
            view.setChunkIndex(chunk.getChunkIndex());
            view.setScore(chunk.getScore());
            view.setSnippet(snippet(chunk.getContent()));
            chunkViews.add(view);
        }

        CaseResult result = new CaseResult();
        result.setQuestion(evalCase.getQuestion());
        result.setHit(hitRank > 0);
        result.setHitRank(hitRank);
        result.setLatencyMs(round3(latencyMs));
        result.setRetrieved(chunkViews);
        return result;
    }

    /** 截取分块内容前 {@link #SNIPPET_LENGTH} 个字符作为摘要。 */
    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_LENGTH
                ? content
                : content.substring(0, SNIPPET_LENGTH);
    }

    /** 去掉全部空白并转小写：兼容短语被分块边界/换行切断的情形。 */
    private String normalize(String text) {
        return text.replaceAll("\\s+", "").toLowerCase();
    }

    /** 保留三位小数，让报告 JSON 简洁可读。 */
    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 从 classpath 读取并解析评测集。 */
    private List<EvalCase> loadCases() {
        try (InputStream in = new ClassPathResource(CASES_RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<EvalCase>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("评测集加载失败：" + CASES_RESOURCE, e);
        }
    }
}
