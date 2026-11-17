package com.example.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

/**
 * 检索配置（rag.retrieval.*）。
 */
@ConfigurationProperties(prefix = "rag.retrieval")
public class RetrievalProperties {

    /** 最终送入 LLM 的分块条数 */
    private int topK = 3;

    /** 送 Rerank 精排的候选池大小（必须 ≥ topK） */
    private int candidateLimit = 20;

    /** 证据闸门阈值：rerank top1 相关性低于此值时按无证据拒答（仅 rerank 开启时生效） */
    private double evidenceThreshold = 0.48;

    /** 是否开启 BM25 关键词召回（与向量召回合并后送 Rerank） */
    private boolean bm25Enabled = true;

    /** BM25 词频饱和参数 k1，典型值 1.2 */
    private double bm25K1 = 1.2;

    /** BM25 文档长度归一化参数 b，典型值 0.75 */
    private double bm25B = 0.75;

    /** BM25 关键词召回的候选条数 */
    private int bm25CandidateLimit = 20;

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public double getEvidenceThreshold() {
        return evidenceThreshold;
    }

    public void setEvidenceThreshold(double evidenceThreshold) {
        this.evidenceThreshold = evidenceThreshold;
    }

    public boolean isBm25Enabled() {
        return bm25Enabled;
    }

    public void setBm25Enabled(boolean bm25Enabled) {
        this.bm25Enabled = bm25Enabled;
    }

    public double getBm25K1() {
        return bm25K1;
    }

    public void setBm25K1(double bm25K1) {
        this.bm25K1 = bm25K1;
    }

    public double getBm25B() {
        return bm25B;
    }

    public void setBm25B(double bm25B) {
        this.bm25B = bm25B;
    }

    public int getBm25CandidateLimit() {
        return bm25CandidateLimit;
    }

    public void setBm25CandidateLimit(int bm25CandidateLimit) {
        this.bm25CandidateLimit = bm25CandidateLimit;
    }

    @PostConstruct
    void validate() {
        if (candidateLimit < topK) {
            throw new IllegalStateException(
                    "rag.retrieval.candidate-limit (" + candidateLimit
                            + ") 不能小于 rag.retrieval.top-k (" + topK + ")");
        }
        if (evidenceThreshold < 0 || evidenceThreshold > 1) {
            throw new IllegalStateException(
                    "rag.retrieval.evidence-threshold (" + evidenceThreshold + ") 必须在 0~1 之间");
        }
        if (bm25K1 <= 0) {
            throw new IllegalStateException(
                    "rag.retrieval.bm25-k1 (" + bm25K1 + ") 必须大于 0");
        }
        if (bm25B < 0 || bm25B > 1) {
            throw new IllegalStateException(
                    "rag.retrieval.bm25-b (" + bm25B + ") 必须在 0~1 之间");
        }
    }
}
