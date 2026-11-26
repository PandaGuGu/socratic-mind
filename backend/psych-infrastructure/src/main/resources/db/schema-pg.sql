-- PostgreSQL + pgvector 表结构（psych.store=postgres 时由 SchemaInitializer 执行）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kb_document (
    id           TEXT PRIMARY KEY,
    collection   TEXT,
    title        TEXT NOT NULL,
    source       TEXT,
    source_url   TEXT,
    version      TEXT,
    language     TEXT DEFAULT 'zh',
    status       TEXT NOT NULL DEFAULT 'draft',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS kb_chunk (
    id                  TEXT PRIMARY KEY,
    document_id         TEXT NOT NULL,
    document_version_id TEXT NOT NULL,
    parent_id           TEXT,
    chunk_index         INT NOT NULL,
    title_path          TEXT,
    section_path        TEXT,
    content             TEXT NOT NULL,
    -- 维度必须与 embedding 模型一致：哈希桩 256，BGE-M3 1024。
    -- 切换模型时不能直接复用旧列：清空 kb_chunk 后重建该列与 HNSW 索引。
    embedding           vector(256),
    page_start          INT,
    page_end            INT,
    language            TEXT DEFAULT 'zh',
    is_parent           BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS kb_chunk_doc_idx ON kb_chunk (document_id);
CREATE INDEX IF NOT EXISTS kb_chunk_hnsw ON kb_chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS kb_evidence (
    id                      TEXT PRIMARY KEY,
    chunk_id                TEXT NOT NULL,
    evidence_type           TEXT,
    condition_text          TEXT,
    population              TEXT,
    intervention            TEXT,
    recommendation_strength TEXT,
    evidence_quality        TEXT,
    limitations             TEXT[],
    review_status           TEXT NOT NULL DEFAULT 'pending'
);

CREATE INDEX IF NOT EXISTS kb_evidence_chunk_idx ON kb_evidence (chunk_id);

-- 文档生命周期与语料权限（v0.6.0）
--
-- 五个字段，各自对应一类真实事故：
-- - content_hash：内容没变就不重新向量化。没有它，每晚全量同步会把 CPU 烧光；
-- - external_key：外部系统的主键。没有它，改标题就会变成一篇新文档，旧块永远留在索引里；
-- - acl_scope：逗号分隔的可见范围。空 = 公开。权限必须落在检索之前，而不是生成之后；
-- - deleted_at：软删除。检索先看它，物理删另给一条命令；
-- - updated_at：最后一次同步时间，用来发现"很久没同步过"的文档。
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS external_key TEXT;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS acl_scope    TEXT;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS deleted_at   TIMESTAMPTZ;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS updated_at   TIMESTAMPTZ;

-- 同一外部键只能对应一篇文档。部分索引：手工上传的资料没有外部键，不能互相冲突。
CREATE UNIQUE INDEX IF NOT EXISTS ux_kb_document_external_key
    ON kb_document (external_key) WHERE external_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS kb_document_live_idx ON kb_document (status, deleted_at);
