package com.example.rag.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.example.rag.config.EmbeddingProperties;
import com.example.rag.config.RerankProperties;
import com.example.rag.config.RetrievalProperties;
import com.example.rag.eval.EvalReport.CaseResult;
import com.example.rag.eval.EvalReport.RetrievedChunk;
import com.example.rag.eval.EvalReport.TypeStats;
import com.example.rag.model.Chunk;
import com.example.rag.service.RetrievalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检索评测服务：加载评测用例，逐题复用主链路跑检索，判定命中并汇总指标（含 MRR）。
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

    private final RetrievalService retrievalService;

    private final RetrievalProperties retrievalProperties;

    private final EmbeddingProperties embeddingProperties;

    private final RerankProperties rerankProperties;

    public EvalService(ObjectMapper objectMapper,
                       RetrievalService retrievalService,
                       RetrievalProperties retrievalProperties,
                       EmbeddingProperties embeddingProperties,
                       RerankProperties rerankProperties) {
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.retrievalProperties = retrievalProperties;
        this.embeddingProperties = embeddingProperties;
        this.rerankProperties = rerankProperties;
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
        double mrrSum = 0;

        for (EvalCase evalCase : cases) {
            long start = System.nanoTime();
            List<Chunk> retrieved = retrievalService.retrieve(evalCase.getQuestion());
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;

            int hitRank = findHitRank(evalCase, retrieved);
            boolean negative = normalize(evalCase.getExpectedPhrase()).isEmpty();
            // 负例：未命中（hitRank==0）才算正确；正例：命中（hitRank>0）才算正确
            boolean hit = negative ? hitRank == 0 : hitRank > 0;
            // 负例正确时展示为名次 1（MRR 贡献 1.0），失败为 0
            int displayRank = negative ? (hit ? 1 : 0) : hitRank;

            if (hit) {
                hits++;
                mrrSum += displayRank > 0 ? 1.0 / displayRank : 0;
            }
            if (!retrieved.isEmpty()) {
                scoreSum += retrieved.get(0).getScore();
            }
            latencySum += latencyMs;

            results.add(buildCaseResult(evalCase, retrieved, hit, displayRank, latencyMs));
        }

        int total = cases.size();
        EvalReport report = new EvalReport();
        report.setTopK(topK);
        report.setTotal(total);
        report.setHits(hits);
        report.setHitRate(total == 0 ? 0 : round3((double) hits / total));
        report.setMrr(total == 0 ? 0 : round3(mrrSum / total));
        report.setAvgScore(total == 0 ? 0 : round3(scoreSum / total));
        report.setAvgLatencyMs(total == 0 ? 0 : round3(latencySum / total));
        report.setChunkSize(CHUNK_SIZE_DESC);
        report.setEmbeddingModel(embeddingProperties.getModel());
        report.setRerankEnabled(rerankProperties.isEnabled());
        report.setRerankModel(rerankProperties.isEnabled() ? rerankProperties.getModel() : null);
        report.setCases(results);
        report.setByType(aggregateByType(results));
        return report;
    }

    /** 按题型聚合：用 LinkedHashMap 保留题型在评测集中首次出现的顺序。 */
    private Map<String, TypeStats> aggregateByType(List<CaseResult> results) {
        Map<String, TypeStats> byType = new LinkedHashMap<>();
        for (CaseResult result : results) {
            byType.computeIfAbsent(result.getType(), k -> new TypeStats());
        }
        for (CaseResult result : results) {
            TypeStats stats = byType.get(result.getType());
            stats.setCount(stats.getCount() + 1);
            if (result.isHit()) {
                stats.setHits(stats.getHits() + 1);
                stats.setAvgRank(stats.getAvgRank() + result.getHitRank());
            }
            stats.setMrr(stats.getMrr() + (result.getHitRank() > 0 ? 1.0 / result.getHitRank() : 0));
        }
        for (TypeStats stats : byType.values()) {
            int count = stats.getCount();
            int hits = stats.getHits();
            stats.setHitRate(count == 0 ? 0 : round3((double) hits / count));
            stats.setMrr(count == 0 ? 0 : round3(stats.getMrr() / count));
            stats.setAvgRank(hits == 0 ? 0 : round3(stats.getAvgRank() / hits));
        }
        return byType;
    }

    /**
     * 按召回顺序返回第一个命中分块的名次（1 起算），未命中返回 0。
     * 正例命中条件：文档名精确相等，且分块内容包含期望短语；单块未命中时再尝试相邻分块拼接。
     * 负例（expectedPhrase 为空）：返回匹配到期望文档的分块名次，未匹配返回 0（=未命中=正确拒答）。
     */
    private int findHitRank(EvalCase evalCase, List<Chunk> retrieved) {
        String expectedDocument = evalCase.getDocument() == null ? "" : evalCase.getDocument().trim();
        String expectedPhrase = normalize(evalCase.getExpectedPhrase());
        boolean negative = expectedPhrase.isEmpty();

        if (!negative) {
            for (int i = 0; i < retrieved.size(); i++) {
                Chunk chunk = retrieved.get(i);
                if (expectedDocument.equals(chunk.getDocumentName())
                        && normalize(chunk.getContent()).contains(expectedPhrase)) {
                    return i + 1;
                }
            }
            // 单块未命中，尝试答案被分块边界切断的情形：拼接文档内相邻两块再匹配
            for (int i = 0; i < retrieved.size(); i++) {
                for (int j = 0; j < retrieved.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    Chunk a = retrieved.get(i);
                    Chunk b = retrieved.get(j);
                    if (b.getChunkIndex() != a.getChunkIndex() + 1) {
                        continue;
                    }
                    if (!expectedDocument.equals(a.getDocumentName())
                            || !expectedDocument.equals(b.getDocumentName())) {
                        continue;
                    }
                    String combined = normalize(combineAdjacent(a.getContent(), b.getContent()));
                    if (combined.contains(expectedPhrase)) {
                        return Math.min(i, j) + 1;
                    }
                }
            }
            return 0;
        }

        // 负例：期望文档为空时任何真实文档都不匹配 → 返回 0（未命中=正确）
        for (int i = 0; i < retrieved.size(); i++) {
            if (expectedDocument.equals(retrieved.get(i).getDocumentName())) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 拼接两个相邻分块，去掉重叠前缀，还原边界处的连续文本。 */
    private String combineAdjacent(String a, String b) {
        int overlap = overlapLength(a, b);
        return a + b.substring(overlap);
    }

    /** 返回 b 的前缀与 a 的后缀重合的最大字符数，用于去除分块重叠。 */
    private int overlapLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        for (int k = max; k > 0; k--) {
            if (a.endsWith(b.substring(0, k))) {
                return k;
            }
        }
        return 0;
    }

    /** 组装单题结果：名次、耗时与召回块精简视图。 */
    private CaseResult buildCaseResult(EvalCase evalCase, List<Chunk> retrieved,
                                       boolean hit, int hitRank, double latencyMs) {
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
        result.setType(evalCase.getType());
        result.setHit(hit);
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
            List<EvalCase> cases = objectMapper.readValue(in, new TypeReference<List<EvalCase>>() {
            });
            for (int i = 0; i < cases.size(); i++) {
                String type = cases.get(i).getType();
                if (type == null || type.isBlank()) {
                    throw new IllegalStateException(
                            "评测集第 " + (i + 1) + " 题缺少 type 字段：" + cases.get(i).getQuestion());
                }
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("评测集加载失败：" + CASES_RESOURCE, e);
        }
    }
}
