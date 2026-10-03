package com.example.rag.eval;

/**
 * 检索评测用例：对应 retrieval-cases.json 中的一条「问题 → 期望命中分块」。
 */
public class EvalCase {

    /** 评测问题 */
    private String question;

    /** 期望命中的文档名 */
    private String document;

    /** 期望分块必须包含的短语 */
    private String expectedPhrase;

    /** 题型：精确术语 / 语义改写 / 跨块 / 知识库外，用于分题型统计 */
    private String type;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getDocument() {
        return document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public String getExpectedPhrase() {
        return expectedPhrase;
    }

    public void setExpectedPhrase(String expectedPhrase) {
        this.expectedPhrase = expectedPhrase;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
