package com.example.rag.controller;

public class ChatRequest {

    private String question;

    // 会话标识：首次请求留空，服务端新建会话并通过 event:session 下发；后续请求原样带回
    private String sessionId;

    // 匿名用户标识（前端 localStorage 生成），用于会话归属校验
    private String userId;

    // 知识库健康检查失败、用户显式确认后由前端置 true，后端据此跳过检索
    private boolean skipRetrieval;

    // 是否开启回答后的追问推荐，前端设置面板控制，默认开启
    private boolean enableRecommend = true;

    // 可空，指定检索哪个知识库，null 表示全库
    private Long knowledgeBaseId;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isSkipRetrieval() {
        return skipRetrieval;
    }

    public void setSkipRetrieval(boolean skipRetrieval) {
        this.skipRetrieval = skipRetrieval;
    }

    public boolean isEnableRecommend() {
        return enableRecommend;
    }

    public void setEnableRecommend(boolean enableRecommend) {
        this.enableRecommend = enableRecommend;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }
}
