package com.example.rag.rerank.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 百炼 rerank 原生端点请求体（嵌套结构）。
 */
public class RerankRequest {

    private String model;

    private Input input;

    private Parameters parameters;

    public RerankRequest() {
    }

    public RerankRequest(String model, String query, List<String> documents, int topN) {
        this.model = model;
        this.input = new Input(query, documents);
        this.parameters = new Parameters(topN, true);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Input getInput() {
        return input;
    }

    public void setInput(Input input) {
        this.input = input;
    }

    public Parameters getParameters() {
        return parameters;
    }

    public void setParameters(Parameters parameters) {
        this.parameters = parameters;
    }

    public static class Input {

        private String query;

        private List<String> documents;

        public Input() {
        }

        public Input(String query, List<String> documents) {
            this.query = query;
            this.documents = documents;
        }

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public List<String> getDocuments() {
            return documents;
        }

        public void setDocuments(List<String> documents) {
            this.documents = documents;
        }
    }

    public static class Parameters {

        @JsonProperty("top_n")
        private int topN;

        @JsonProperty("return_documents")
        private boolean returnDocuments;

        public Parameters() {
        }

        public Parameters(int topN, boolean returnDocuments) {
            this.topN = topN;
            this.returnDocuments = returnDocuments;
        }

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }

        public boolean isReturnDocuments() {
            return returnDocuments;
        }

        public void setReturnDocuments(boolean returnDocuments) {
            this.returnDocuments = returnDocuments;
        }
    }
}
