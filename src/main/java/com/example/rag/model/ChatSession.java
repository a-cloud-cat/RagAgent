package com.example.rag.model;

import java.time.LocalDateTime;

/**
 * 会话实体，记忆系统的锚点。
 * sessionKey 由前端持有，userId 为匿名用户标识，summary 由服务端生成并落库。
 */
public class ChatSession {

    private Long id;
    /** 前端持有的会话标识（UUID），对外暴露的唯一入口 */
    private String sessionKey;
    /** 匿名用户标识（UUID），将来接登录可替换为真实用户 ID */
    private String userId;
    private String title;
    /** 服务端生成的早期对话摘要 */
    private String summary;
    /** 摘要已覆盖到的最后一条消息 ID，大于该值的消息仍以原文注入 */
    private long summaryLastId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime lastActiveTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public long getSummaryLastId() {
        return summaryLastId;
    }

    public void setSummaryLastId(long summaryLastId) {
        this.summaryLastId = summaryLastId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public LocalDateTime getLastActiveTime() {
        return lastActiveTime;
    }

    public void setLastActiveTime(LocalDateTime lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }
}
