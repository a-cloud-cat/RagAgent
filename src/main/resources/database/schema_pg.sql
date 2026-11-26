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

-- 4) 知识库表（文档分类归属，检索时可选过滤）
CREATE TABLE IF NOT EXISTS t_knowledge_base (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL UNIQUE,
    description VARCHAR(255),
    create_time TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE t_knowledge_base IS '知识库分类表';

-- 文档归属到知识库（可空，null 表示全库可见）
ALTER TABLE t_document ADD COLUMN IF NOT EXISTS knowledge_base_id BIGINT REFERENCES t_knowledge_base(id);

-- 5) 对话反馈表
CREATE TABLE IF NOT EXISTS t_feedback (
    id          BIGSERIAL PRIMARY KEY,
    question    TEXT        NOT NULL,
    answer      TEXT        NOT NULL,
    feedback    VARCHAR(8)  NOT NULL,  -- UP / DOWN
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE t_feedback IS '对话反馈表';
COMMENT ON COLUMN t_feedback.feedback IS '反馈类型 UP(赞)/DOWN(踩)';

-- 6) 会话表（记忆系统的锚点：一切工作记忆按会话隔离）
CREATE TABLE IF NOT EXISTS t_chat_session (
    id                BIGSERIAL PRIMARY KEY,
    session_key       VARCHAR(64)  NOT NULL UNIQUE,  -- 前端持有的会话标识（UUID）
    user_id           VARCHAR(64)  NOT NULL,         -- 匿名用户标识（UUID），将来接登录可替换为真实用户 ID
    title             VARCHAR(255),                  -- 会话标题（取首条问题，便于会话列表展示）
    summary           TEXT,                          -- 服务端生成的早期对话摘要
    summary_last_id   BIGINT       NOT NULL DEFAULT 0, -- 摘要已覆盖到的最后一条消息 ID，之后的消息仍以原文注入
    create_time       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    last_active_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_session_user ON t_chat_session (user_id, last_active_time DESC);
COMMENT ON TABLE t_chat_session IS '会话表';
COMMENT ON COLUMN t_chat_session.session_key IS '前端持有的会话标识 UUID';
COMMENT ON COLUMN t_chat_session.summary_last_id IS '摘要覆盖到的最后一条消息 ID，大于该 ID 的消息仍以原文注入';

-- 7) 会话消息表（工作记忆的持久层，也是将来微调语料的原始来源）
CREATE TABLE IF NOT EXISTS t_chat_message (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT      NOT NULL REFERENCES t_chat_session(id),
    role        VARCHAR(16) NOT NULL,  -- 仅 user / assistant，服务端写入前做白名单校验
    content     TEXT        NOT NULL,
    sources     JSONB,                 -- assistant 消息引用的分块列表，用于前端还原引用标注
    create_time TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_message_session ON t_chat_message (session_id, id);
COMMENT ON TABLE t_chat_message IS '会话消息表';
COMMENT ON COLUMN t_chat_message.role IS '消息角色，仅允许 user/assistant';
COMMENT ON COLUMN t_chat_message.sources IS '回答引用的分块 JSON，user 消息为空';
