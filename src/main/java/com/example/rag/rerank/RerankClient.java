package com.example.rag.rerank;

import java.util.List;

import com.example.rag.model.Chunk;

/**
 * Rerank 精排客户端接口：对候选分块按问题相关性重排并截取 topN。
 */
public interface RerankClient {

    /**
     * @param query      用户问题文本
     * @param candidates 候选分块（向量召回结果）
     * @param topN       最终保留的条数
     * @return 按 rerank 相关性降序的 topN 个分块
     */
    List<Chunk> rerank(String query, List<Chunk> candidates, int topN);
}
