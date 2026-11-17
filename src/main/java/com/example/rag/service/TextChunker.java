package com.example.rag.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 结构感知切块：优先在语义边界（问答分隔、Markdown 标题、分隔线）下刀，
 * 超长段落再退回到字符滑动窗口切块，相邻块保留重叠。
 * 无结构标记的纯文本退化为按段落合并 + 字符数补切，与旧行为兼容。
 */
@Component
public class TextChunker {

    private static final int CHUNK_SIZE = 300;

    private static final int OVERLAP_SIZE = 50;

    /**
     * @param text 原始文本
     * @return 分块列表（空文本返回空列表）
     */
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        // 统一换行符为 \n，多空格压成一个，3 个以上换行压成双换行（段落边界）
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n");
        if (normalized.trim().isEmpty()) {
            return chunks;
        }

        String[] paragraphs = normalized.split("\\n\\n");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) {
                continue;
            }
            // 遇到结构标记：当前块先落盘，再开新块
            if (isStructuralStart(para) && current.length() > 0) {
                flush(current, chunks);
            }
            // 拼上本段会超长：当前块先落盘
            if (current.length() > 0 && current.length() + para.length() + 1 > CHUNK_SIZE) {
                flush(current, chunks);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(para);
        }
        flush(current, chunks);
        return chunks;
    }

    /** 段落是否是一个新语义单元的起点（问答 / 标题 / 分隔线） */
    private boolean isStructuralStart(String para) {
        return para.startsWith("问题：") || para.startsWith("问题:")
                || para.startsWith("Q（") || para.startsWith("Q(") || para.startsWith("Q:")
                || para.startsWith("#") || para.startsWith("=") || para.startsWith("-".repeat(3));
    }

    /** 把当前累积文本落盘：不超长直接存，超长走滑动窗口补切 */
    private void flush(StringBuilder current, List<String> chunks) {
        if (current.length() == 0) {
            return;
        }
        String text = current.toString().trim();
        current.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        if (text.length() <= CHUNK_SIZE) {
            chunks.add(text);
            return;
        }
        int step = CHUNK_SIZE - OVERLAP_SIZE;
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end == text.length()) {
                break;
            }
        }
    }
}
