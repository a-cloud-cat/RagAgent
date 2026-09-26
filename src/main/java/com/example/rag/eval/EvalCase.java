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
}
