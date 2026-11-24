package com.example.rag.service.intent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.example.rag.embedding.EmbeddingClient;

import jakarta.annotation.PostConstruct;

/**
 * 三路门控意图识别：Pattern → Embedding 相似度 → LLM 兜底。
 * 顺序短路：Pattern 直接命中 → 0ms 返回；Pattern 不确定才往下走 Embedding；Embedding 也拿不准才调 LLM。
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    /** Embedding 相似度阈值，低于此值认为语义也不确定，继续走 LLM。 */
    private static final double EMBEDDING_SIM_THRESHOLD = 0.55;

    private final EmbeddingClient embeddingClient;
    private final LlmIntentClient llmIntentClient;

    /** Pattern 规则表（初始化时构造）。 */
    private final List<IntentRule> rules = new ArrayList<>();

    /** 每个意图的模板向量库（启动时从 intent-samples.json 加载并预计算 embedding）。 */
    private final Map<Intent, List<float[]>> intentEmbeddings = new LinkedHashMap<>();

    public IntentRouter(EmbeddingClient embeddingClient, LlmIntentClient llmIntentClient) {
        this.embeddingClient = embeddingClient;
        this.llmIntentClient = llmIntentClient;
        initPatternRules();
    }

    /** 启动时加载模板问法并预计算向量（失败不阻塞启动，Embedding 路径自动跳过）。 */
    @PostConstruct
    void initEmbeddingTemplates() {
        try {
            String json = loadResource("intent-samples.json");
            Map<Intent, List<String>> samples = parseSamplesJson(json);
            if (samples.isEmpty()) {
                log.warn("intent-samples.json 为空，Embedding 路径不可用");
                return;
            }
            // 批量 embedding：分批发，百炼限制每批 ≤10 条
            List<String> all = new ArrayList<>();
            List<Intent> templateIntentOrder = new ArrayList<>();
            for (Map.Entry<Intent, List<String>> e : samples.entrySet()) {
                for (String text : e.getValue()) {
                    all.add(text);
                    templateIntentOrder.add(e.getKey());
                }
            }
            List<float[]> vectors = new ArrayList<>();
            int BATCH_SIZE = 10;
            for (int i = 0; i < all.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, all.size());
                vectors.addAll(embeddingClient.embed(all.subList(i, end)));
            }
            // 按意图分组
            Map<Intent, List<float[]>> grouped = new LinkedHashMap<>();
            for (int i = 0; i < templateIntentOrder.size(); i++) {
                Intent intent = templateIntentOrder.get(i);
                grouped.computeIfAbsent(intent, k -> new ArrayList<>()).add(vectors.get(i));
            }
            intentEmbeddings.putAll(grouped);
            log.info("Embedding 模板加载完成: {} 个意图, 共 {} 条模板", grouped.size(), all.size());
        } catch (Exception e) {
            log.warn("Embedding 模板加载失败，Embedding 路径不可用: {}", e.getMessage());
        }
    }

    /** 三路门控分类入口。 */
    public IntentResult classify(String question) {
        if (question == null || question.isBlank()) {
            return IntentResult.fallback();
        }
        String q = question.trim();

        // —— ① Pattern 正则（0ms，零成本）——
        IntentResult patternResult = tryPattern(q);
        if (patternResult != null) {
            return patternResult;
        }

        // —— ② Embedding 相似度（百炼，~150ms）——
        IntentResult embResult = tryEmbedding(q);
        if (embResult != null) {
            return embResult;
        }

        // —— ③ LLM 兜底（DeepSeek，~500ms）——
        IntentResult llmResult = llmIntentClient.classify(q);
        if (llmResult != null) {
            return llmResult;
        }

        // —— 最终兜底 ——
        return IntentResult.fallback();
    }

    // ──── ① Pattern 路径 ────

    private IntentResult tryPattern(String question) {
        for (IntentRule rule : rules) {
            for (Pattern pattern : rule.patterns()) {
                Matcher m = pattern.matcher(question);
                if (m.find()) {
                    Map<String, String> entities = extractEntities(question);
                    log.info("[pattern] question=\"{}\" → intent={} (matched={})",
                            truncate(question, 40), rule.intent(), pattern.pattern());
                    return IntentResult.pattern(rule.intent(), entities);
                }
            }
        }
        return null;
    }

    /** 从问题里提取结构化实体（订单号、商品ID 等）。Pattern 路径也顺便做。 */
    private Map<String, String> extractEntities(String question) {
        Map<String, String> entities = new LinkedHashMap<>();
        // 订单号：10-20 位连续数字
        Matcher orderMatcher = Pattern.compile("\\d{10,20}").matcher(question);
        if (orderMatcher.find()) {
            entities.put("orderNo", orderMatcher.group());
        }
        // 商品 ID：先找"商品"后的数字，再兜底任意小数字
        Matcher productMatcher = Pattern.compile("商品[^0-9]*(\\d+)").matcher(question);
        if (productMatcher.find()) {
            entities.put("productId", productMatcher.group(1));
        } else {
            // 兜底：问题里出现的第一个 ≤6 位正整数
            Matcher fallback = Pattern.compile("\\b(\\d{1,6})\\b").matcher(question);
            if (fallback.find()) {
                long id = Long.parseLong(fallback.group(1));
                if (id > 0 && id < 100000) {
                    entities.put("productId", fallback.group(1));
                }
            }
        }
        return entities;
    }

    // ──── ② Embedding 路径 ────

    private IntentResult tryEmbedding(String question) {
        if (intentEmbeddings.isEmpty()) {
            return null;
        }
        try {
            List<float[]> queryVecs = embeddingClient.embed(List.of(question));
            if (queryVecs == null || queryVecs.isEmpty()) {
                return null;
            }
            float[] queryVec = queryVecs.get(0);

            // 对每个意图取最相似模板的相似度
            Intent bestIntent = null;
            double bestScore = -1;
            for (Map.Entry<Intent, List<float[]>> entry : intentEmbeddings.entrySet()) {
                for (float[] templateVec : entry.getValue()) {
                    double sim = cosineSim(queryVec, templateVec);
                    if (sim > bestScore) {
                        bestScore = sim;
                        bestIntent = entry.getKey();
                    }
                }
            }

            if (bestIntent != null && bestScore >= EMBEDDING_SIM_THRESHOLD) {
                log.info("[embedding] question=\"{}\" → intent={} score={}",
                        truncate(question, 40), bestIntent, String.format("%.3f", bestScore));
                return new IntentResult(bestIntent, bestScore, extractEntities(question), "embedding");
            }
            log.info("[embedding] 相似度不足: bestScore={}, 继续 LLM", String.format("%.3f", bestScore));
            return null;

        } catch (Exception e) {
            log.warn("Embedding 路径失败，继续 LLM: {}", e.getMessage());
            return null;
        }
    }

    /** 余弦相似度。两个向量必须同维度。 */
    private double cosineSim(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ──── Pattern 规则表（原 IntentRouter 逻辑，保持不变） ────

    private void initPatternRules() {
        // 订单查询
        rules.add(new IntentRule(Intent.ORDER_QUERY, List.of(
                Pattern.compile("\\d{10,20}"),
                Pattern.compile("订单.?号?"),
                Pattern.compile("物流|快递|配送|发货"),
                Pattern.compile("退货|退款|售后")
        )));
        // 库存/价格
        rules.add(new IntentRule(Intent.STOCK_QUERY, List.of(
                Pattern.compile("库存|缺货|有货|补货"),
                Pattern.compile("多少钱|价格|报价|售价|现价"),
                Pattern.compile("几台|几个|几件")
        )));
        // 分类
        rules.add(new IntentRule(Intent.CATEGORY_QUERY, List.of(
                Pattern.compile("分类|品类|类目"),
                Pattern.compile("有什么商品|哪些商品|商品列表")
        )));
        // 闲聊（必须在业务查询之后）
        rules.add(new IntentRule(Intent.CHITCHAT, List.of(
                Pattern.compile("^(你好|您好|hi|hello|在吗|在不)[!！。. ]*$", Pattern.CASE_INSENSITIVE),
                Pattern.compile("^你是谁|你叫什么|你能做什么|你会什么"),
                Pattern.compile("今天.+时间|现在.+时间|几点了|日期"),
                Pattern.compile("天气怎么样|天气预报"),
                Pattern.compile("^谢谢|多谢|好的|嗯嗯|好哒")
        )));
        // 拒答
        rules.add(new IntentRule(Intent.REFUSE, List.of(
                Pattern.compile("傻逼|草泥马|fuck", Pattern.CASE_INSENSITIVE)
        )));
    }

    // ──── intent-samples.json 加载 & 解析 ────

    private String loadResource(String name) throws Exception {
        ClassPathResource resource = new ClassPathResource(name);
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 手动解析 intent-samples.json。结构固定：{"INTENT":["问法1","问法2",...],...} */
    private Map<Intent, List<String>> parseSamplesJson(String json) {
        Map<Intent, List<String>> result = new LinkedHashMap<>();
        // 去掉外层 {}
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return result;
        String inner = json.substring(start + 1, end);

        int i = 0;
        while (i < inner.length()) {
            // 找 key："INTENT_NAME"
            int keyStart = inner.indexOf("\"", i);
            if (keyStart < 0) break;
            int keyEnd = inner.indexOf("\"", keyStart + 1);
            if (keyEnd < 0) break;
            String key = inner.substring(keyStart + 1, keyEnd).trim();
            Intent intent;
            try {
                intent = Intent.valueOf(key.toUpperCase());
            } catch (IllegalArgumentException e) {
                i = keyEnd + 1;
                continue;
            }

            // 找 [ ... ] 数组
            int bracketStart = inner.indexOf("[", keyEnd);
            int bracketEnd = inner.indexOf("]", bracketStart);
            if (bracketStart < 0 || bracketEnd < 0) {
                i = keyEnd + 1;
                continue;
            }
            String arr = inner.substring(bracketStart + 1, bracketEnd);
            List<String> texts = new ArrayList<>();
            // 简单按 "..." 提取每个字符串
            int j = 0;
            while (j < arr.length()) {
                int qs = arr.indexOf("\"", j);
                if (qs < 0) break;
                int qe = arr.indexOf("\"", qs + 1);
                if (qe < 0) break;
                texts.add(arr.substring(qs + 1, qe));
                j = qe + 1;
            }
            result.put(intent, texts);
            i = bracketEnd + 1;
        }
        return result;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record IntentRule(Intent intent, List<Pattern> patterns) {}
}
