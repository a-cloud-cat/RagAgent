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
    }
}
