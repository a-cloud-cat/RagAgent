package com.example.rag.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 规则式关键词提取：按空格/标点切分，去停用词，中文段补 2-gram 提升召回。
 * 不依赖分词器或大模型，保证可移植性（任意 PostgreSQL 均可运行）。
 */
public final class KeywordExtractor {

    /** 中英文停用词：疑问词、虚词、高频无意义词。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "自己", "这", "那", "这个", "那个", "什么", "怎么", "如何", "为什么", "哪", "哪些",
            "吗", "呢", "吧", "啊", "呀", "么", "把", "被", "让", "从", "向", "跟", "与",
            "及", "或", "并", "且", "而", "但", "可", "可以", "能", "能够", "应该", "应当",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "of", "to", "in", "on", "at", "by", "for", "with", "about", "as", "into",
            "how", "what", "why", "which", "who", "when", "where", "do", "does", "did",
            "and", "or", "but", "if", "then", "so", "it", "its", "this", "that", "these", "those"
    );

    /** 切分分隔符：空白 + 中英文标点。 */
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\s\\p{Punct}，。！？、；：\"\"''（）【】《》…—·]+");

    /** 判断是否含中文字符。 */
    private static final Pattern CHINESE_PATTERN = Pattern.compile("\\p{IsHan}");

    private KeywordExtractor() {
    }

    /**
     * 从查询中提取关键词列表。
     *
     * @param query 原始查询
     * @return 关键词列表（已去重、转小写、过滤停用词和单字）
     */
    public static List<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        String[] tokens = SPLIT_PATTERN.split(query.trim());

        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            String lower = token.toLowerCase();
            if (STOP_WORDS.contains(lower)) {
                continue;
            }
            // 单字噪声大，跳过（英文单字母、中文单字）
            if (lower.codePointCount(0, lower.length()) < 2) {
                continue;
            }
            keywords.add(lower);

            // 中文段补 2-gram，覆盖"自动配置"这类未被切分器拆出的词
            if (CHINESE_PATTERN.matcher(token).find()) {
                keywords.addAll(bigrams(token));
            }
        }

        return new ArrayList<>(keywords);
    }

    /** 生成字符串的 2-gram 列表（如"自动配置"→["自动","动配","配置"]）。 */
    private static List<String> bigrams(String text) {
        List<String> grams = new ArrayList<>();
        int[] codePoints = text.codePoints().toArray();
        for (int i = 0; i < codePoints.length - 1; i++) {
            String gram = new String(codePoints, i, 2);
            if (!STOP_WORDS.contains(gram)) {
                grams.add(gram);
            }
        }
        return grams;
    }
}
