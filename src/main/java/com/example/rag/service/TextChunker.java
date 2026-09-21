package com.example.rag.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 纯文本切块：按字符数做「滑动窗口」切块，相邻块保留重叠，避免句子被切断后丢失上下文。
 * 中英文混合都适用（按字符而非字节计数）。
 */
@Component
public class TextChunker {

    /** 每块最大字符数 */
    private static final int CHUNK_SIZE = 300;

    /** 相邻块重叠字符数 */
    private static final int OVERLAP_SIZE = 50;

    /**
     * @param text 原始文本
     * @return 去空白后的分块列表（空文本返回空列表）
     */
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        // 连续空白（含换行/制表符）压成一个空格
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return chunks;
        }

        int step = CHUNK_SIZE - OVERLAP_SIZE;
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end == normalized.length()) {
                break;
            }
        }
        return chunks;
    }
}
