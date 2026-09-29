package com.example.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * rerank 精排配置（rag.rerank.*）。
 */
@ConfigurationProperties(prefix = "rag.rerank")
public class RerankProperties {

    /** 是否启用 Rerank 精排 */
    private boolean enabled = false;

    /** 百炼 rerank 模型名 */
    private String model = "qwen3-rerank";

    /** 百炼 base-url（与 embedding 共用） */
    private String baseUrl = "https://dashscope.aliyuncs.com";

    /** 百炼 API-KEY（与 embedding 共用，从环境变量注入） */
    private String apiKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
