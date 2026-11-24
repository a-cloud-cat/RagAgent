package com.example.rag.service.intent;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.rag.config.LlmProperties;

/**
 * 用 LLM 做意图分类的兜底路径。Pattern 和 Embedding 都拿不准时才调用，避免浪费 token。
 * 直接发 chat/completions，prompt 要求输出严格 JSON，不引入 Jackson 等额外依赖。
 */
@Component
public class LlmIntentClient {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClient.class);

    private static final String SYSTEM_PROMPT = """
            你是意图分类器，从用户问题中判断意图并提取结构化实体。
            可选意图（只能输出以下值之一）：
            - ORDER_QUERY：订单状态、物流、退货退款、售后
            - STOCK_QUERY：商品价格、库存、有无现货、补货
            - CATEGORY_QUERY：商品分类、品类、有什么商品
            - CHITCHAT：闲聊、打招呼、问时间/天气/自己是谁等非业务问题
            - REFUSE：涉及脏话、违法、敏感内容
            - RAG_QUERY：技术问题、系统使用、知识库相关（默认兜底）
            
            实体提取（能提就提，提不到就省略）：
            - orderNo：订单编号（纯数字串）
            - productId：商品 ID（数字）
            - productName：商品名（如"小米10"）
            - amount：金额（带单位或纯数字）
            - errorCode：错误码
            
            只输出 JSON，不要任何解释。格式：
            {"intent":"ORDER_QUERY","confidence":0.95,"entities":{"orderNo":"20241027001"}}
            """;

    private final WebClient webClient;
    private final LlmProperties llmProperties;

    public LlmIntentClient(WebClient webClient, LlmProperties llmProperties) {
        this.webClient = webClient;
        this.llmProperties = llmProperties;
    }

    /**
     * 调 LLM 分类，返回 IntentResult。任何异常返回 null 让调用方走兜底。
     */
    public IntentResult classify(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", llmProperties.getModelName(),
                    "stream", false,
                    "temperature", 0.1,
                    "messages", java.util.List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", question)
                    )
            );

            long t0 = System.currentTimeMillis();
            String response = webClient.post()
                    .uri(llmProperties.getBaseUrl() + "/chat/completions")
                    .header("Authorization", "Bearer " + llmProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            resp -> resp.bodyToMono(String.class).flatMap(body -> {
                                log.warn("LlmIntentClient HTTP {}: {}", resp.statusCode(), body);
                                return reactor.core.publisher.Mono.error(new RuntimeException("HTTP " + resp.statusCode()));
                            }))
                    .bodyToMono(String.class)
                    .block();

            if (response == null) {
                log.warn("LlmIntentClient 响应为空");
                return null;
            }

            String json = extractContent(response);
            if (json == null || json.isBlank()) {
                log.warn("LlmIntentClient content 为空: {}", response);
                return null;
            }

            IntentResult result = parseIntentJson(json);
            log.info("LLM 意图分类 耗时={}ms question=\"{}\" → intent={} confidence={}",
                    System.currentTimeMillis() - t0, truncate(question, 30),
                    result.intent(), String.format("%.2f", result.confidence()));
            return result;

        } catch (Exception e) {
            log.warn("LlmIntentClient 调用失败，走兜底: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 LLM 返回的 JSON。手动解析，不依赖 Jackson。 */
    private IntentResult parseIntentJson(String json) {
        // 先做 intent 字段
        Intent intent = Intent.RAG_QUERY;
        double confidence = 0.5;
        Map<String, String> entities = new LinkedHashMap<>();

        // 找 "intent":"XXX"
        String intentStr = extractJsonField(json, "intent");
        if (intentStr != null) {
            try {
                intent = Intent.valueOf(intentStr.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                log.warn("LLM 返回未知意图: {}", intentStr);
            }
        }

        // 找 "confidence":0.95
        String confStr = extractJsonNumber(json, "confidence");
        if (confStr != null) {
            try {
                confidence = Double.parseDouble(confStr);
                if (confidence < 0.0) confidence = 0.0;
                if (confidence > 1.0) confidence = 1.0;
            } catch (NumberFormatException ignored) {}
        }

        // 找 "entities":{...}
        int entitiesIdx = json.indexOf("\"entities\"");
        if (entitiesIdx >= 0) {
            int braceStart = json.indexOf('{', entitiesIdx);
            int braceEnd = findMatchingBrace(json, braceStart);
            if (braceStart >= 0 && braceEnd > braceStart) {
                String entitiesJson = json.substring(braceStart, braceEnd + 1);
                // 简单提取每个 key-value
                int i = 1; // 跳过开头 {
                while (i < entitiesJson.length() - 1) {
                    int keyStart = entitiesJson.indexOf("\"", i);
                    if (keyStart < 0) break;
                    int keyEnd = entitiesJson.indexOf("\"", keyStart + 1);
                    if (keyEnd < 0) break;
                    String key = entitiesJson.substring(keyStart + 1, keyEnd);
                    int colon = entitiesJson.indexOf(":", keyEnd);
                    if (colon < 0) break;
                    int valStart = entitiesJson.indexOf("\"", colon);
                    if (valStart < 0) break;
                    int valEnd = entitiesJson.indexOf("\"", valStart + 1);
                    if (valEnd < 0) break;
                    String value = entitiesJson.substring(valStart + 1, valEnd);
                    entities.put(key, value);
                    i = valEnd + 1;
                }
            }
        }

        return new IntentResult(intent, confidence, entities, "llm");
    }

    /** 从 JSON 字符串中提取指定 key 的字符串值。 */
    private String extractJsonField(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return null;
        int colon = json.indexOf(":", keyIdx);
        if (colon < 0) return null;
        int valStart = json.indexOf("\"", colon);
        if (valStart < 0 || valStart - colon > 10) return null; // 中间不能太长
        int valEnd = json.indexOf("\"", valStart + 1);
        if (valEnd < 0) return null;
        return json.substring(valStart + 1, valEnd);
    }

    /** 从 JSON 字符串中提取指定 key 的数字值（字符串形式返回）。 */
    private String extractJsonNumber(String json, String key) {
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return null;
        int colon = json.indexOf(":", keyIdx);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.' || json.charAt(i) == '-')) {
            i++;
        }
        return i > start ? json.substring(start, i) : null;
    }

    /** 找与 start 位置 { 匹配的 } 的位置。 */
    private int findMatchingBrace(String s, int start) {
        if (start < 0 || s.charAt(start) != '{') return -1;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** 从非流式 LLM 响应里提取 message.content，复用 ChatService 同风格逻辑。 */
    private String extractContent(String response) {
        int contentIdx = response.indexOf("\"content\":\"");
        if (contentIdx < 0) return null;
        int start = contentIdx + "\"content\":\"".length();
        // 找结尾——注意 content 可能跨 chunk，这里是一次性非流式，完整 JSON
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < response.length()) {
            char c = response.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < response.length()) {
                char next = response.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(next);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
