package com.example.rag.parser;

import java.io.InputStream;

/**
 * 文档解析器：把二进制文档流转成纯文本，供后续切块入库。
 */
public interface DocumentParser {

    /**
     * 解析文档内容为纯文本。
     *
     * @param inputStream 文档二进制流，由调用方负责关闭
     * @return 提取出的纯文本
     */
    String parse(InputStream inputStream);
}
