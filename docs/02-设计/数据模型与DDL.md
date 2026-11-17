---
title: 数据模型与 DDL
status: 草案
updated: 2026-11-17
---

# 数据模型与 DDL

> 上位文档：[§4.2 数据库表设计](../01-方案/04-RAG知识数据架构.md)、[§4.3 kb_chunk 字段](../01-方案/04-RAG知识数据架构.md)。
> 本文是"具体长什么样"，字段语义以上位文档为准；本文与上位文档冲突时，以上位文档为准并回来改本文。

## 1. 领域划分

| 域 | 表 | 说明 |
|-|-|-|
| 知识域 | kb_collection / kb_document / kb_document_version / kb_chunk / kb_evidence / kb_citation / kb_ingestion_job / kb_review_record | 入库、审核、检索的事实来源 |
| 会话域 | cbt_session / cbt_message / cbt_turn | 用户可见的对话与确认记录 |
| 运行时 | graph_checkpoint（框架表）、thread 映射 | 只保证流程能续跑，允许重建 |

**三条边界（不可混）：**

1. 知识域的表只能由入库流程写入，业务代码只读。
2. 会话域以用户可见事实为准；Checkpoint 与之冲突时以会话域为准。
3. `cbt_session` 与知识问答会话不是一张表（[ADR-0005](../03-adr/ADR-0005-双Graph独立State.md)）。

## 2. DDL 草案（PostgreSQL + pgvector）

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE kb_collection (
    id           BIGSERIAL PRIMARY KEY,
    name         TEXT NOT NULL UNIQUE,
    description  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kb_document (
    id           BIGSERIAL PRIMARY KEY,
    collection_id BIGINT NOT NULL REFERENCES kb_collection(id),
    title        TEXT NOT NULL,
    source       TEXT,                    -- 机构 / 出处
    source_url   TEXT,
    language     TEXT NOT NULL DEFAULT 'zh',
    status       TEXT NOT NULL DEFAULT 'draft',  -- draft|reviewing|published|archived
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kb_document_version (
    id           BIGSERIAL PRIMARY KEY,
    document_id  BIGINT NOT NULL REFERENCES kb_document(id),
    version      TEXT NOT NULL,
    published_at TIMESTAMPTZ,
    UNIQUE (document_id, version)
);

CREATE TABLE kb_chunk (
    id                  BIGSERIAL PRIMARY KEY,
    document_id         BIGINT NOT NULL REFERENCES kb_document(id),
    document_version_id BIGINT NOT NULL REFERENCES kb_document_version(id),
    parent_chunk_id     BIGINT REFERENCES kb_chunk(id),
    chunk_index         INT NOT NULL,
    title_path          TEXT,             -- 《指南》> 第3章 > 3.2
    section_path        TEXT,
    content             TEXT NOT NULL,
    embedding           vector(1024),     -- 维度随所选 Embedding 模型定，见第 9 章
    page_start          INT,
    page_end            INT,
    language            TEXT NOT NULL DEFAULT 'zh',
    tsv                 tsvector,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX kb_chunk_hnsw ON kb_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX kb_chunk_tsv  ON kb_chunk USING gin (tsv);
CREATE INDEX kb_chunk_doc  ON kb_chunk (document_version_id, chunk_index);

CREATE TABLE kb_evidence (
    id                      BIGSERIAL PRIMARY KEY,
    chunk_id                BIGINT NOT NULL REFERENCES kb_chunk(id),
    evidence_type           TEXT,          -- 枚举待定，见第 3 节
    condition               TEXT,          -- 适用条件原文
    population              TEXT,
    intervention            TEXT,
    recommendation_strength TEXT,          -- 原文记录，缺失为 NULL，禁止推断
    evidence_quality        TEXT,
    limitations             TEXT[],
    review_status           TEXT NOT NULL DEFAULT 'pending',
    reviewed_by             TEXT,
    reviewed_at             TIMESTAMPTZ
);

CREATE TABLE kb_citation (
    id         BIGSERIAL PRIMARY KEY,
    chunk_id   BIGINT NOT NULL REFERENCES kb_chunk(id),
    page       INT,
    section    TEXT,
    url        TEXT
);

CREATE TABLE kb_ingestion_job (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES kb_document(id),
    status      TEXT NOT NULL DEFAULT 'queued',
    error       TEXT,
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE TABLE kb_review_record (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES kb_document(id),
    reviewer    TEXT NOT NULL,
    decision    TEXT NOT NULL,             -- approve|reject|revise
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 3. 待定项（写 DDL 约束前必须定）

- [ ] `evidence_type` 枚举：先按 1–2 份真实指南试标 20 条/份，枚举稳定后再加 CHECK。
- [ ] `embedding` 维度：取决于所选 Embedding 模型，M1 锁定。
- [ ] HNSW 的 `m` / `ef_construction`：M2 用 Recall@K 与延迟权衡后定。
- [ ] `tsv` 的维护方式：触发器 or 应用侧写入。
- [ ] Checkpoint 表：以框架版本自带结构为准，**不自建**（[§6.3](../01-方案/06-交互与状态恢复.md)）。

## 4. 迁移

Flyway，命名 `V{序号}__{说明}.sql`。知识域与会话域分目录，便于单独回放。
