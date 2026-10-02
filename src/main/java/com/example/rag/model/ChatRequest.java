package com.example.rag.model;


public class ChatRequest {
    private String question;

    // 知识库健康检查失败、用户显式确认后由前端置 true，后端据此跳过检索
    private boolean skipRetrieval;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public boolean isSkipRetrieval() {
        return skipRetrieval;
    }

    public void setSkipRetrieval(boolean skipRetrieval) {
        this.skipRetrieval = skipRetrieval;
    }
}
