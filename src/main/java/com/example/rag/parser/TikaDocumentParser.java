package com.example.rag.parser;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

/**
 * 基于 Apache Tika 的文档解析器：自动识别 PDF/HTML 等格式并提取纯文本。
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private final Tika tika = new Tika();

    @Override
    public String parse(InputStream inputStream) {
        try {
            return tika.parseToString(inputStream);
        } catch (IOException | TikaException e) {
            throw new IllegalStateException("文档解析失败：" + e.getMessage(), e);
        }
    }
}
