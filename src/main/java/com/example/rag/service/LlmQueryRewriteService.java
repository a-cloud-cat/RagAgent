package com.example.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.rag.config.LlmProperties;

@Service
public class LlmQueryRewriteService implements QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriteService.class);

    private static final String REWRITE_PROMPT = """
            你是查询改写助手。基于多轮对话历史，把用户当前的追问改写成一个独立、完整、适合知识库检索的问题。
            要求：
            1. 补全省略的主语和指代（如"它""这个""上面提到的"），使其脱离上下文也能理解；
            2. 保留原问题的核心意图和关键词，不添加历史中没有的信息；
            3. 只输出改写后的问题，不要解释、不要加引号。""";

    private final WebClient webClient;

    private final LlmProperties llmProperties;

    public LlmQueryRewriteService(WebClient webClient, LlmProperties llmProperties) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
    }

    @Override
    public String rewrite(String question, List<Map<String, String>> history) {
        // 无历史说明是首轮问题，本身就是完整问题，无需改写也省一次 LLM 调用
        if (history == null || history.isEmpty()) {
            return question;
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", REWRITE_PROMPT));
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content",
                "当前问题：" + question + "\n请输出改写后的检索问题。"));

        Map<String, Object> requestBody = Map.of(
                "model", llmProperties.getModelName(),
                "stream", false,
                "messages", messages
        );

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
                return question;
            }
            String rewritten = extractContent(response);
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            log.info("问题改写：「{}」→「{}」", question, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("问题改写失败，回退原问题：{}", e.getMessage());
            return question;
        }
    }

    private String extractContent(String response) {
        try {
            int contentIdx = response.indexOf("\"content\":\"");
            if (contentIdx < 0) {
                return null;
            }
            int start = contentIdx + "\"content\":\"".length();
            int end = response.indexOf("\"", start);
            if (end < 0) {
                return null;
            }
            return response.substring(start, end)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"");
        } catch (Exception e) {
            return null;
        }
    }
}
