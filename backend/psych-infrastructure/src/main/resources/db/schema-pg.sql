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
