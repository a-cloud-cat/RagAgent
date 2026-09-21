package com.example.rag.embedding.dto;

import java.util.List;


/**
 * OpenAI 兼容 embedding 响应体（只映射我们用到的 data 字段，其余字段被 Jackson 自动忽略）。
 */
public record EmbeddingResponse(List<EmbeddingData> data) {
}
