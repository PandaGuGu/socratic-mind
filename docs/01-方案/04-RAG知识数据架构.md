---
title: 第 4 章 RAG 知识数据架构
status: 定稿
updated: 2026-11-17
---

# 第 4 章 RAG 知识数据架构

第一版建议只维护**一套公共知识库**，但在数据模型中区分文档、知识块、来源、证据和版本。

## 4.1 知识库的数据流

```mermaid
flowchart TD
    A["上传知识资料<br/>PDF / DOCX / HTML / Markdown"] --> B["解析文档<br/>提取标题、正文、表格、页码、引用"]
    B --> C["人工审核与元数据补充<br/>来源、版本、适用人群、授权、审核状态"]
    C --> D["语义切分<br/>父块、子块、章节、证据单元"]
    D --> E1["向量索引<br/>Embedding"]
    D --> E2["全文索引<br/>PostgreSQL FTS"]
    E1 --> F["发布知识库版本"]
    E2 --> F
```

> **注意：上传文档不等于知识已经可以被 Agent 使用。** 只有经过审核、解析和索引验证的文档才能进入可检索状态。

这条规则要落到代码里：`kb_document.status` 不达到 `published` 的文档，一律不进检索候选集，而不是"检索出来再过滤"。

## 4.2 数据库表设计

第一版建议建立以下核心表。

| 表名 | 作用 | 关键字段 |
|-|-|-|
| kb_collection | 知识库 | id、name、description |
| kb_document | 原始文档 | id、title、source、version、status |
| kb_document_version | 文档版本 | document_id、version、published_at |
| kb_chunk | 知识块 | id、document_version_id、content、embedding |
| kb_evidence | 证据单元 | chunk_id、evidence_type、population、recommendation |
| kb_citation | 引用定位 | chunk_id、page、section、url |
| kb_ingestion_job | 文档处理任务 | status、error、started_at |
| kb_review_record | 审核记录 | reviewer、decision、reason |

其中最重要的是 **kb_evidence**。它可以把普通文本转化成具有明确证据属性的知识单元：

```json
{
  "evidence_id": "ev_001",
  "chunk_id": "chunk_001",
  "evidence_type": "guideline_recommendation",
  "condition": "适用条件原文",
  "population": "指南规定的人群",
  "intervention": "指南提及的干预",
  "recommendation_strength": "原文记录",
  "evidence_quality": "原文记录",
  "limitations": [
    "指南明确指出的限制"
  ],
  "review_status": "approved"
}
```

这些字段是**数据结构**示例，不应让模型自行编造推荐强度或证据质量。无法确认的字段就保存为 `null` 或 `unknown`。

保留这张"重"表的理由见 [ADR-0006](../03-adr/ADR-0006-保留kb_evidence证据表.md)：它是与 Wysa "临床审核话术"对齐的最小代价方案——用结构化字段替代人力审核的规模。

## 4.3 kb_chunk 必须保留的字段

```
id
document_id
document_version_id
parent_chunk_id
chunk_index
title_path
content
embedding
page_start
page_end
section_path
language
created_at
```

**特别注意 `document_version_id`。** 如果指南更新了，不能直接覆盖旧文档的知识块；需要保留历史版本，并让查询默认使用当前已发布版本。这一条同时是"引用可追溯"和"证据可回滚"的前提。

## 4.4 知识块切分策略

心理学和医学知识不适合单纯按照 500 或 1000 个 Token 固定切分。建议采用三级结构：

| 层级 | 内容 | 用途 |
|-|-|-|
| 文档层 | 整份指南或教材 | 来源与上下文 |
| 父块 | 一个完整章节或主题 | 补充上下文 |
| 子块 | 一个完整知识点或证据单元 | 向量检索 |

示例：

```
《认知行为疗法指南》
  └── 第 3 章：治疗实施
       ├── 3.1 治疗适用范围
       ├── 3.2 治疗结构
       ├── 3.3 治疗技术
       └── 3.4 治疗效果评估
```

切分时必须遵守：

- 不能把推荐意见与其适用条件拆开；
- 不能把否定条件与肯定结论拆开；
- 表格必须保留标题和列名；
- 每个子块必须能追溯到原文页码或章节；
- 子块需要保留父块引用，检索后可以补充上下文。

前两条是硬约束：一条"对 X 人群不推荐 Y"被切成两半，检索出来就是一句错误的推荐。这类错误比召回率低严重得多。

## 4.5 检索流程

第一版的 RAG 查询流程共八步：

```mermaid
flowchart TD
    S1["1 问题分类<br/>科普 / 理论 / CBT 技术 / 量表 / 疗法适用范围"]
    S2["2 查询改写<br/>保留原意，补充必要的专业术语"]
    S3["3 混合检索<br/>向量检索 + BM25 + 元数据过滤"]
    S4["4 结果融合<br/>RRF"]
    S5["5 重排序<br/>Cross-Encoder 选择更相关的证据"]
    S6["6 证据整理<br/>合并父块、检查版本和来源"]
    S7["7 生成回答<br/>只根据证据生成并保留引用"]
    S8["8 引用校验<br/>检查引用是否真的支持对应陈述"]
    S1 --> S2 --> S3 --> S4 --> S5 --> S6 --> S7 --> S8
```

第一版可以先实现 **向量检索 + PostgreSQL 全文检索 + RRF**。Reranker 可以在检索质量测试后再加入（见 [第 11 章](11-评估指标.md)）。

## 4.6 用框架到什么程度

Spring AI 已经提供 `VectorStore`、`QuestionAnswerAdvisor` 和 `RetrievalAugmentationAdvisor` 等 RAG 组件，也支持 PostgreSQL/PGVector。可以先用它们实现基础检索，再把需要复杂控制的部分封装成自己的 `RagService`。

> **建议：不要把整个 RAG 写成一个 `QuestionAnswerAdvisor` 就结束。** 它适合简单问答，但这个系统还需要检索来源过滤、证据结构化、引用定位和输出验证。

分界线建议这样切：

| 用框架现成的 | 自己写 |
|-|-|
| `VectorStore` 的写入与相似度查询 | 检索候选集的白名单与版本过滤 |
| `RetrievalAugmentationAdvisor` 的简单拼装 | 证据结构化、父子块合并 |
| Advisor 的 prompt 模板注入 | 引用定位与引用校验（`verify_citations`） |

参考：
- [Spring AI RAG 文档](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
