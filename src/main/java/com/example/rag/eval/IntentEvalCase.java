package com.example.rag.eval;

/**
 * 意图识别评测用例：对应 intent-cases.json 中的一条「问题 → 期望意图」。
 */
public class IntentEvalCase {

    private String question;

    /** 期望意图，取值与 Intent 枚举名一致 */
    private String expectedIntent;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getExpectedIntent() {
        return expectedIntent;
    }

    public void setExpectedIntent(String expectedIntent) {
        this.expectedIntent = expectedIntent;
    }
}
