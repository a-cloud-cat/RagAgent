package com.example.rag.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.rag.config.RetrievalProperties;
import com.example.rag.model.Chunk;

/**
 * BM25 打分器。对候选分块按查询词的词频、文档长度归一化和逆文档频率打分并排序。
 * 纯 Java 实现，不依赖数据库插件，保证可移植性。
 */
@Component
public class Bm25Scorer {

    private final double k1;
    private final double b;

    public Bm25Scorer(RetrievalProperties properties) {
        this.k1 = properties.getBm25K1();
        this.b = properties.getBm25B();
    }

    /**
     * 对候选分块列表计算 BM25 分数并按分数降序排序。
     *
     * @param candidates     候选分块（必须已填充 content）
     * @param terms          查询关键词
     * @param stats          语料统计（总分块数、平均分块长度）
     * @param termDocCounts  每个查询词在多少个分块中出现过（用于 IDF）
     * @return 已排序的候选列表（原列表被原地修改）
     */
    public List<Chunk> score(List<Chunk> candidates, List<String> terms,
                             CorpusStats stats, Map<String, Long> termDocCounts) {
        if (candidates == null || candidates.isEmpty() || terms == null || terms.isEmpty()) {
            return candidates;
        }

        for (Chunk chunk : candidates) {
            String content = chunk.getContent();
            if (content == null) {
                chunk.setScore(0.0);
                continue;
            }
            int docLen = content.length();
            double score = 0.0;

            for (String term : terms) {
                int tf = countOccurrences(content, term);
                if (tf == 0) {
                    continue;
                }
                long docFreq = termDocCounts.getOrDefault(term, 0L);
                double idf = Math.log((stats.totalDocs() - docFreq + 0.5) / (docFreq + 0.5) + 1.0);
                double denominator = tf + k1 * (1.0 - b + b * docLen / Math.max(stats.avgDocLength(), 1.0));
                double tfNorm = (tf * (k1 + 1.0)) / denominator;
                score += idf * tfNorm;
            }
            chunk.setScore(score);
        }

        candidates.sort((a, c) -> Double.compare(
                c.getScore() == null ? 0.0 : c.getScore(),
                a.getScore() == null ? 0.0 : a.getScore()));
        return candidates;
    }

    /** 统计 term 在 text 中出现的次数（区分大小写，调用方已统一小写）。 */
    private int countOccurrences(String text, String term) {
        if (term.isEmpty() || text.length() < term.length()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(term, idx)) != -1) {
            count++;
            idx += term.length();
        }
        return count;
    }
}
