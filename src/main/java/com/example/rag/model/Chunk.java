package com.example.rag.model;

/**
 * 文档分块 t_chunk 对应实体；embedding 不参与检索结果回传，故此处只保留文本字段。
 */
public class Chunk {

    private Long id;

    private Long documentId;

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
