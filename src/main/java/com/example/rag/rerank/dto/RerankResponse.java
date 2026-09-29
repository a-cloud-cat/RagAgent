package com.example.rag.rerank.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 百炼 rerank 原生端点响应体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RerankResponse {

    private Output output;

    private Usage usage;

    @JsonProperty("request_id")
    private String requestId;

    public Output getOutput() {
        return output;
    }

    public void setOutput(Output output) {
        this.output = output;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Output {

        private List<Result> results;

        public List<Result> getResults() {
            return results;
        }

        public void setResults(List<Result> results) {
            this.results = results;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {

        /** 指向入参 documents 数组的下标，用来映射回原 Chunk */
        private int index;

        @JsonProperty("relevance_score")
        private double relevanceScore;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public double getRelevanceScore() {
            return relevanceScore;
        }

        public void setRelevanceScore(double relevanceScore) {
            this.relevanceScore = relevanceScore;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        @JsonProperty("total_tokens")
        private int totalTokens;

        public int getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
