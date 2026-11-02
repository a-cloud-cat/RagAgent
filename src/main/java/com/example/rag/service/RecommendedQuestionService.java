package com.example.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.rag.config.LlmProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于问题与回答生成可追问的推荐问题，失败返回空列表不影响主流程。
 */
@Service
public class RecommendedQuestionService {

    private static final Logger log = LoggerFactory.getLogger(RecommendedQuestionService.class);

    private static final String PROMPT = """
            你是一个追问推荐助手。根据用户的问题和助手的回答，生成 2-3 个用户可能继续追问的相关问题。
            要求：
            1. 问题要与当前回答紧密相关，是用户可能自然想继续了解的方向
            2. 每个问题独立完整，不使用「它」「这个」等指代
            3. 只输出 JSON 数组，不要解释，不要 Markdown，格式：["问题1", "问题2", "问题3"]

            用户问题：%s
            助手回答：%s
            """;

    private final WebClient webClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public RecommendedQuestionService(WebClient webClient, LlmProperties llmProperties) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
        this.objectMapper = new ObjectMapper();
    }

    public List<String> recommend(String question, String answer) {
        if (question == null || answer == null || answer.isBlank()) {
            return List.of();
        }

        Map<String, Object> requestBody = Map.of(
                "model", llmProperties.getModelName(),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "user", "content", PROMPT.formatted(question, answer))));

        try {
            String response = webClient.post()
                    .uri(llmProperties.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + llmProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (response == null) {
                return List.of();
            }
            String content = extractContent(response);
            if (content == null || content.isBlank()) {
                return List.of();
            }
            return parseQuestions(content);
        } catch (Exception e) {
            log.warn("推荐问题生成失败，跳过", e);
            return List.of();
        }
    }

    private String extractContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseQuestions(String content) {
        // 兼容 LLM 输出包了 ```json 代码块或前后多余文本
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            return List.of();
        }
        try {
            List<String> questions = objectMapper.readValue(
                    content.substring(start, end + 1), new TypeReference<List<String>>() {});
            List<String> result = new ArrayList<>();
            for (String q : questions) {
                if (q != null && !q.isBlank() && result.size() < 3) {
                    result.add(q.trim());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("推荐问题 JSON 解析失败，原始内容：{}", content);
            return List.of();
        }
    }
}
