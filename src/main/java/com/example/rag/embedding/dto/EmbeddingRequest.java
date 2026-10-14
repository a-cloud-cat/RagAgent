package com.example.rag.embedding.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;


/**
 * record：Java 17 的不可变数据载体，
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddingRequest(String model, List<String> input, Integer dimension) {
}
