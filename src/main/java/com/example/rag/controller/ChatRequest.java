package com.example.rag.controller;

import java.util.List;
import java.util.Map;

public class ChatRequest {
    private String question;

    // 知识库健康检查失败、用户显式确认后由前端置 true，后端据此跳过检索
    private boolean skipRetrieval;

    // 当前问题之前的对话历史，[{role, content}]，不含当前 question
    private List<Map<String, String>> history;

    // 早期对话的摘要，由前端从 localStorage 传入；无则为 null
    private String summary;

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

    public List<Map<String, String>> getHistory() {
        return history;
    }

    public void setHistory(List<Map<String, String>> history) {
        this.history = history;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
