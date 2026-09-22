package com.example.rag.parser;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TikaDocumentParser 单元测试：用 test classpath 下的小 HTML 夹具，
 * 验证正文被提取、标签与 script/style 内容被剥离。
 */
class TikaDocumentParserTest {

    private final TikaDocumentParser parser = new TikaDocumentParser();

    @Test
    void html夹具_提取正文并剥离标签与脚本样式() {
        String text;
        try (InputStream in = getClass().getResourceAsStream("/parser/sample.html")) {
            assertThat(in).as("测试夹具 /parser/sample.html 必须存在").isNotNull();
            text = parser.parse(in);
        } catch (Exception e) {
            throw new AssertionError("读取或解析夹具失败", e);
        }

        assertThat(text)
                .contains("解析器单测夹具")
                .contains("蓝莓二五八一");
        assertThat(text)
                .doesNotContain("<p>")
                .doesNotContain("埋点脚本")
                .doesNotContain("color: red");
    }
}
