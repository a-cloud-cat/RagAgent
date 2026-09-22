package com.example.rag.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextChunker 单元测试：覆盖空输入、短文本、恰好临界、超长切块与重叠拼接。
 * 切块参数：块长 300，重叠 50，步长 250。
 */
class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    void null输入_返回空列表() {
        assertThat(chunker.split(null)).isEmpty();
    }

    @Test
    void 纯空白输入_返回空列表() {
        assertThat(chunker.split("   \n\t  ")).isEmpty();
    }

    @Test
    void 短于300字_只切一块且内容为去空白后的原文() {
        List<String> chunks = chunker.split("  hello   world  ");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("hello world");
    }

    @Test
    void 恰好300字_只切一块() {
        String text = "字".repeat(300);

        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(300);
    }

    @Test
    void 超长文本_每块不超过300且相邻块重叠50字() {
        // 600 字 → [0,300) [250,550) [500,600) 共 3 块
        List<String> chunks = chunker.split("字".repeat(600));

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).hasSizeLessThanOrEqualTo(300));

        String first = chunks.get(0);
        String second = chunks.get(1);
        String third = chunks.get(2);
        // 重叠：后一块的前 50 字必须等于前一块的后 50 字，保证上下文不丢
        assertThat(second.substring(0, 50)).isEqualTo(first.substring(first.length() - 50));
        assertThat(third.substring(0, 50)).isEqualTo(second.substring(second.length() - 50));
    }

    @Test
    void 连续空白与换行_被压缩成单个空格() {
        List<String> chunks = chunker.split("第一段\n\n\n第二段\t  第三段");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("第一段 第二段 第三段");
    }
}
