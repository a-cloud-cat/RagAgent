package com.example.rag.model;

/**
 * 文档分块（t_chunk）实体。embedding 标记 transient，仅入库写入用，不随检索结果回传。
 */
public class Chunk {

    private Long id;

    private Long documentId;

    /** 所属文档名（检索时 JOIN t_document 带出，入库时不使用） */
    private String documentName;

    private Integer chunkIndex;

    private String content;

    /** 仅入库时使用：与 content 一一对应的文本向量 */
    private transient float[] embedding;

    /** 检索结果使用：相似度得分（越大越相似） */
    private Double score;

    public Chunk() {
    }

    public Chunk(Long documentId, Integer chunkIndex, String content, float[] embedding) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embedding = embedding;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }
}
