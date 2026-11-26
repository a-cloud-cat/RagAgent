package com.example.rag.model;

import java.time.LocalDateTime;

/**
 * 会话消息实体。role 仅允许 user / assistant，由服务端写入前校验。
 */
public class ChatMessage {

    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    /** assistant 消息引用的分块 JSON；user 消息为空 */
    private String sources;
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSources() {
        return sources;
    }

    public void setSources(String sources) {
        this.sources = sources;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
