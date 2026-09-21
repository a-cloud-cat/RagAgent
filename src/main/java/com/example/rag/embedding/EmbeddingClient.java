package com.example.rag.embedding;

import java.util.List;

/**
 * 向量化客户端接口，定义文本转向量的契约。
 */
public interface EmbeddingClient {

    /**
     * 批量文本向量化
     * @param texts 待处理的一批文本
     * @return 和入参顺序一一对应的向量集合，每个元素是float[]向量数组
     * 保证实现接口接收一批文本，返回一批 float [] 向量
     */
    List<float[]> embed(List<String> texts);
}