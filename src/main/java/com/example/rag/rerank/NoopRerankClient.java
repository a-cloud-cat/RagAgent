package com.example.rag.rerank;

import java.util.ArrayList;
import java.util.List;

import com.example.rag.model.Chunk;

/**
 * 空实现：开关关闭时原样透传候选前 topN 条，不调 API。
 */
public class NoopRerankClient implements RerankClient {

    @Override
    public List<Chunk> rerank(String query, List<Chunk> candidates, int topN) {
        int size = Math.min(topN, candidates.size());
        return new ArrayList<>(candidates.subList(0, size));
    }
}
