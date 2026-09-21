-- RagAgent 数据库结构
-- 执行：docker exec -i rag_pgvector-pgvector-1 psql -U postgres -d rag_db < schema_pg.sql

-- 1) 开启 pgvector 扩展（向量检索依赖，必须执行）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2) 文档表
CREATE TABLE IF NOT EXISTS t_document (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING',  -- PENDING / INGESTED / FAILED
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     DEFAULT 0
);
COMMENT ON TABLE t_document IS '文档表';
COMMENT ON COLUMN t_document.name IS '文档名称';
COMMENT ON COLUMN t_document.status IS '入库状态 PENDING/INGESTED/FAILED';

-- 3) 分块与向量表
CREATE TABLE IF NOT EXISTS t_chunk (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT  NOT NULL,
    chunk_index INT     NOT NULL,
    content     TEXT    NOT NULL,
    embedding   vector(1536),   -- 维度与 embedding 模型一致：text-embedding-v4 = 1536
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT  DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_chunk_document ON t_chunk (document_id);
-- 向量相似度检索索引（余弦距离，配合 <=> 操作符）；数据量较小时建索引很快
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw ON t_chunk USING hnsw (embedding vector_cosine_ops);
COMMENT ON TABLE t_chunk IS '文档分块与向量表';
COMMENT ON COLUMN t_chunk.embedding IS '文本向量（text-embedding-v4，1536 维）';
