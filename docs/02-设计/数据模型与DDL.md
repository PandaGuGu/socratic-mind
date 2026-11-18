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

## 2.1 会话域 DDL（已实现）

会话域此前只有领域划分、没有一条建表语句。现在落在 `db/schema-cbt.sql`，**同一份文件**在 PostgreSQL 与 H2（PostgreSQL 兼容模式）上都能执行——生产是前者，验证可以是后者。

| 表 | 一行是什么 | 谁读它 |
|-|-|-|
| `cbt_session` | 一次会话的当前状态：停在哪一步、确认过什么、安全状态 | 流程续跑、会话列表、字段快照 |
| `cbt_message` | 一句对话（role / stage / content / at） | 记录页、窗外回看、咨询师导出 |
| `cbt_turn` | 一轮的决策依据（动作、阶段迁移、安全规则、模型来源、拦截规则、耗时） | 内部控制台、审计 |

三张表分开的理由与 cbt-agent 的 checkpoint / 业务表分离同源：只存 checkpoint 会出现"能续跑但查不到当时说了什么"。

```sql
CREATE TABLE IF NOT EXISTS cbt_session (
  id VARCHAR(64) PRIMARY KEY, thread_id VARCHAR(96) NOT NULL, user_id VARCHAR(64) NOT NULL,
  stage VARCHAR(48) NOT NULL, awaiting_user BOOLEAN NOT NULL DEFAULT FALSE,
  safety_status VARCHAR(24), safety_reason VARCHAR(400),
  target_problem TEXT, situation TEXT, automatic_thought TEXT,
  emotions TEXT, evidence_for TEXT, evidence_against TEXT,
  candidate_thought TEXT, user_confirmed_thought TEXT,
  intervention_type VARCHAR(48), action_plan TEXT, action_result TEXT,
  summary TEXT, pending_question TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS cbt_message (
  session_id VARCHAR(64) NOT NULL, seq INT NOT NULL,
  role VARCHAR(16) NOT NULL, stage VARCHAR(48) NOT NULL, content TEXT,
  at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (session_id, seq)
);

CREATE TABLE IF NOT EXISTS cbt_turn (
  session_id VARCHAR(64) NOT NULL, turn_no INT NOT NULL, action VARCHAR(24) NOT NULL,
  stage_from VARCHAR(48), stage_to VARCHAR(48), status VARCHAR(32),
  safety_rule VARCHAR(48), model_mode VARCHAR(16), guard_rule VARCHAR(48),
  latency_ms BIGINT NOT NULL DEFAULT 0, at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (session_id, turn_no)
);
```

两个刻意的取舍：

1. **列表字段用 TEXT 存 JSON**（`emotions` / `evidence_for` / `evidence_against`）。它们是"一次会话内的短列表"，拆表只会增加 join；等到需要按证据检索时再拆。
2. **整轮覆盖写**：每次 `save` 先删该会话的消息与轨迹再重建。会话是几十条量级，简单换来"内存与库永远一致"；要追加式写入时换实现即可，接口不变。

## 3. 待定项（写 DDL 约束前必须定）

- [ ] `evidence_type` 枚举：先按 1–2 份真实指南试标 20 条/份，枚举稳定后再加 CHECK。
- [ ] `embedding` 维度：取决于所选 Embedding 模型，M1 锁定。
- [ ] HNSW 的 `m` / `ef_construction`：M2 用 Recall@K 与延迟权衡后定。
- [ ] `tsv` 的维护方式：触发器 or 应用侧写入。
- [x] 会话域 DDL：已落地（§2.1），三张表 + 索引。
- [ ] Checkpoint 表：以框架版本自带结构为准，**不自建**（[§6.3](../01-方案/06-交互与状态恢复.md)）。

## 4. 迁移

Flyway，命名 `V{序号}__{说明}.sql`。知识域与会话域分目录，便于单独回放。
