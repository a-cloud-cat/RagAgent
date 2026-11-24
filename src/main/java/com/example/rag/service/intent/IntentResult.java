package com.example.rag.service.intent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 意图识别结果：意图类型 + 置信度 + 从问题中抽取的结构化实体。
 * confidence 0.0-1.0，1.0 表示 Pattern 直接命中（零歧义），越低表示越不确定。
 * entities 里可能的 key：orderNo, productId, amount, errorCode, productName
 */
public record IntentResult(
        Intent intent,
        double confidence,
        Map<String, String> entities,
        String matchedBy  // "pattern" / "embedding" / "llm" / "default"
) {
    /** Pattern 直接命中的快捷构造（置信度最高，实体从 Pattern 提取或留空）。 */
    public static IntentResult pattern(Intent intent, Map<String, String> entities) {
        return new IntentResult(intent, 1.0, entities != null ? entities : new LinkedHashMap<>(), "pattern");
    }

    /** 兜底走 RAG_QUERY。 */
    public static IntentResult fallback() {
        return new IntentResult(Intent.RAG_QUERY, 0.0, new LinkedHashMap<>(), "default");
    }

    /** 便捷取实体，key 不存在返回 null。 */
    public String entity(String key) {
        return entities == null ? null : entities.get(key);
    }
}
