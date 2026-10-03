package com.example.rag.eval;

import java.util.List;
import java.util.Map;

/**
 * 检索评测报告：POST /eval/retrieval 的返回体。
 */
public class EvalReport {

    /** 本次检索使用的 topK */
    private int topK;

    /** 总题数 */
    private int total;

    /** 命中题数 */
    private int hits;

    /** 命中率 hits / total（0~1） */
    private double hitRate;

    /** MRR：所有题 1/hitRank 的均值（未命中按 0 计） */
    private double mrr;

    /** 每题第 1 名召回分块相似度的均值 */
    private double avgScore;

    /** 每题「向量化 + 检索」平均耗时（毫秒） */
    private double avgLatencyMs;

    /** 评测时的切块参数，如 "300/50" */
    private String chunkSize;

    /** 评测时的向量模型名 */
    private String embeddingModel;

    /** 评测时是否启用 Rerank */
    private boolean rerankEnabled;

    /** 评测时的 rerank 模型名（未启用时为 null） */
    private String rerankModel;

    /** 每题一条明细 */
    private List<CaseResult> cases;

    /** 按题型聚合的统计（key 为题型名） */
    private Map<String, TypeStats> byType;

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getHits() {
        return hits;
    }

    public void setHits(int hits) {
        this.hits = hits;
    }

    public double getHitRate() {
        return hitRate;
    }

    public void setHitRate(double hitRate) {
        this.hitRate = hitRate;
    }

    public double getMrr() {
        return mrr;
    }

    public void setMrr(double mrr) {
        this.mrr = mrr;
    }

    public double getAvgScore() {
        return avgScore;
    }

    public void setAvgScore(double avgScore) {
        this.avgScore = avgScore;
    }

    public double getAvgLatencyMs() {
        return avgLatencyMs;
    }

    public void setAvgLatencyMs(double avgLatencyMs) {
        this.avgLatencyMs = avgLatencyMs;
    }

    public String getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(String chunkSize) {
        this.chunkSize = chunkSize;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public List<CaseResult> getCases() {
        return cases;
    }

    public void setCases(List<CaseResult> cases) {
        this.cases = cases;
    }

    public Map<String, TypeStats> getByType() {
        return byType;
    }

    public void setByType(Map<String, TypeStats> byType) {
        this.byType = byType;
    }

    /**
     * 单题评测结果。
     */
    public static class CaseResult {

        /** 原题 */
        private String question;

        /** 题型 */
        private String type;

        /** topK 内是否命中期望分块 */
        private boolean hit;

        /** 命中位置（1 起算；未命中为 0） */
        private int hitRank;

        /** 本题「向量化 + 检索」耗时（毫秒） */
        private double latencyMs;

        /** 实际召回的分块（按相似度降序） */
        private List<RetrievedChunk> retrieved;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isHit() {
            return hit;
        }

        public void setHit(boolean hit) {
            this.hit = hit;
        }

        public int getHitRank() {
            return hitRank;
        }

        public void setHitRank(int hitRank) {
            this.hitRank = hitRank;
        }

        public double getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(double latencyMs) {
            this.latencyMs = latencyMs;
        }

        public List<RetrievedChunk> getRetrieved() {
            return retrieved;
        }

        public void setRetrieved(List<RetrievedChunk> retrieved) {
            this.retrieved = retrieved;
        }
    }

    /**
     * 召回分块的精简视图：只保留判定与人工排查所需字段。
     */
    public static class RetrievedChunk {

        /** 所属文档名 */
        private String document;

        /** 文档内分块序号 */
        private int chunkIndex;

        /** 相似度得分（越大越相似） */
        private double score;

        /** 分块内容前若干字，供人工核对 */
        private String snippet;

        public String getDocument() {
            return document;
        }

        public void setDocument(String document) {
            this.document = document;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public void setChunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getSnippet() {
            return snippet;
        }

        public void setSnippet(String snippet) {
            this.snippet = snippet;
        }
    }

    /**
     * 单个题型的聚合指标。
     */
    public static class TypeStats {

        /** 该题型题数 */
        private int count;

        /** 命中题数 */
        private int hits;

        /** 命中率 hits / count（0~1） */
        private double hitRate;

        /** MRR：该题型所有题 1/hitRank 的均值（未命中按 0 计） */
        private double mrr;

        /** 平均命中名次（仅统计命中题；无命中时为 0） */
        private double avgRank;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public int getHits() {
            return hits;
        }

        public void setHits(int hits) {
            this.hits = hits;
        }

        public double getHitRate() {
            return hitRate;
        }

        public void setHitRate(double hitRate) {
            this.hitRate = hitRate;
        }

        public double getMrr() {
            return mrr;
        }

        public void setMrr(double mrr) {
            this.mrr = mrr;
        }

        public double getAvgRank() {
            return avgRank;
        }

        public void setAvgRank(double avgRank) {
            this.avgRank = avgRank;
        }
    }
}
