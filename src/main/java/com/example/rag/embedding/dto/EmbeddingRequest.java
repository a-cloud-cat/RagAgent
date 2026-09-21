package com.example.rag.embedding.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;


/**
 * OpenAI 兼容 embedding 请求体。
 * record：Java 17 的不可变数据载体，自动生成构造器与 getter（model() / input() / dimension()）。
 * dimension 为 null 时不序列化（部分兼容服务不支持该参数）。
 * JsonInclude (JsonInclude.Include.NON_NULL)：对象里字段值为 null 时，序列化输出 JSON 就不要这个字段，不要输出 "xxx":null。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingRequest(String model, List<String> input, Integer dimension) {

    public EmbeddingRequest(String model, List<String> input) {
        this(model, input, null);
    }
}
