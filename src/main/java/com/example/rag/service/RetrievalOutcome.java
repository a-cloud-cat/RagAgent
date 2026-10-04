package com.example.rag.service;

import java.util.List;

import com.example.rag.model.Chunk;

/**
 * 检索链路结果。evidenceReady 为证据闸门判定：
 * rerank 开启时按 top1 relevance_score 与阈值比较；关闭/降级时恒为放行。
 */
public class RetrievalOutcome {

    private final List<Chunk> chunks;

    private final double top1Score;

    private final boolean evidenceReady;

    public RetrievalOutcome(List<Chunk> chunks, double top1Score, boolean evidenceReady) {
        this.chunks = chunks;
        this.top1Score = top1Score;
        this.evidenceReady = evidenceReady;
    }

    public List<Chunk> getChunks() {
        return chunks;
    }

    public double getTop1Score() {
        return top1Score;
    }

    public boolean isEvidenceReady() {
        return evidenceReady;
    }
}
