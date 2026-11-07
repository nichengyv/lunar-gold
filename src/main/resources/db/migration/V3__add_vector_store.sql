-- ============================================================
-- V3__add_vector_store.sql
-- 向量表：pgvector 扩展 + vector_record + HNSW 索引
-- 背景：此前该表由手工 SQL 创建，新环境用 Flyway 部署时缺失，
--       导致 RAG 检索报 "relation vector_record does not exist"。
-- 维度 1024：与 BAAI/bge-m3 的 embedding 输出维度一致，
--       换模型若维度不同，必须同步修改此处（vector 列维度是建表时固定的）。
-- ============================================================

-- 启用 pgvector 扩展（需超级用户；官方 pgvector 镜像默认即 postgres 用户）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_record (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    -- 对应 document_chunk.id；附件等非知识库来源可能为空
    chunk_id    BIGINT,
    content     TEXT NOT NULL,
    -- 来源类型：knowledge=知识库文档，attachment=聊天附件
    source_type VARCHAR(32) NOT NULL DEFAULT 'knowledge',
    embedding   vector(1024) NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- HNSW 索引：余弦相似度检索从全表扫描变为近似最近邻查找
CREATE INDEX IF NOT EXISTS idx_vector_record_embedding
    ON vector_record USING hnsw (embedding vector_cosine_ops);

-- 按用户过滤 + 按文档删除都走这两个索引
CREATE INDEX IF NOT EXISTS idx_vector_record_user ON vector_record (user_id);
CREATE INDEX IF NOT EXISTS idx_vector_record_user_doc
    ON vector_record (user_id, document_id, source_type);
