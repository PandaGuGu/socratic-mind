---
title: backend 说明
status: 定稿
---

# backend

Maven 多模块（模块化单体）。设计依据：[第 3 章 模块拆分](../docs/01-方案/03-模块拆分.md)。

**当前状态：M1 + M2 + M4 已跑通**（知识 RAG 全链路、CBT 五阶段与中断确认），证据见
[链路验证记录](../docs/05-评估/链路验证记录.md)。

## 跑起来

```bash
cd backend
mvn -B test                        # 19 个测试
mvn -B -DskipTests package
java -jar psych-app/target/psych-app-0.1.0-SNAPSHOT.jar
```

默认档：内存存储 + 桩模型，**不需要数据库、不需要 API key**，启动即灌入示例语料。

```bash
# 知识链路验收（另开终端）
bash ../scripts/e2e.sh
# CBT 会话演示：五阶段 → 中断 → 确认 → 复盘
python ../scripts/cbt-demo.py
```

## 两套业务接口

| 知识 | 说明 |
|-|-|
| `POST /api/knowledge/chat` | 带引用 + 引用校验的问答 |
| `POST /api/knowledge/search` | 检索调试 |
| `POST /api/knowledge/documents` | 上传（默认草稿） |
| `POST /api/knowledge/documents/{id}/review` | 审核放行 |
| `GET  /api/knowledge/stats` | 统计 |

| CBT | 说明 |
|-|-|
| `POST /api/cbt/sessions` | 开一轮 |
| `POST /api/cbt/sessions/{id}/messages` | 发消息（幂等键 clientMessageId） |
| `POST /api/cbt/sessions/{id}/confirm` | **唯一的确认入口**，普通消息推不动 |
| `GET  /api/cbt/sessions/{id}` | 会话状态（含候选/已确认两个字段） |
| `GET  /api/cbt/sessions/{id}/history` | 完整对话记录 |
| `POST /api/cbt/sessions/{id}/end` | 结束 |

## 三种运行档

| 档位 | 启动方式 | 需要什么 |
|-|-|-|
| dev（默认） | 直接启动 | 无 |
| pg | `--spring.profiles.active=pg` | Docker 起 PG + pgvector（`deploy/docker-compose.yml`） |
| http | `--spring.profiles.active=http` | `PSYCH_AI_BASE_URL` / `PSYCH_AI_API_KEY` / `PSYCH_AI_CHAT_MODEL` |

http 档已用 DeepSeek 联调通过。注意它**只换对话模型，不换向量**——DeepSeek 没有
`/embeddings`，向量仍是哈希桩（`psych.ai.embedding-provider=stub`）。语义检索要等接上
真实 embedding 服务才成立。

pg 档代码已写但**未在本机验证**（Docker 未启动），见验证记录 §5。

## 模块

```
backend/
├── pom.xml                    # 聚合 POM，版本锁定 Spring Boot 3.5.6 / Java 21
├── psych-common/              # 领域模型 + 端口（EmbeddingPort / ChatPort / KnowledgeStore）
├── psych-infrastructure/      # 存储实现（内存 / pgvector）、模型适配（桩 / HTTP）
├── psych-knowledge/           # Markdown 解析、父子块切分、证据抽取、入库编排
├── psych-rag/                 # 混合检索、RRF、证据组装、引用校验、提示词
├── psych-agent/               # KnowledgeGraph 七节点 + CbtFlow 五阶段 + 安全分流
├── psych-session/             # CBT 会话状态、消息、用户确认字段
└── psych-app/                 # 启动入口、REST 接口、示例语料
```

依赖方向单向向下：`app → agent → rag → common`、`app → knowledge → common`、
`app → infrastructure → common`、`agent → session → common`。**`rag` 不依赖 `agent`**，
其公开签名里没有 `KnowledgeState` 一类的流程类型（[ADR-0005](../docs/03-adr/ADR-0005-双Graph独立State.md)）。

## 刻意没做的事

| 没做 | 原因 |
|-|-|
| 引入 Spring AI 依赖 | 版本组合需按官方示例核对，且 M1–M2 的验收点与它无关；先用 `ChatPort`/`EmbeddingPort` 隔离，替换成本是一个类（见 [ADR-0002](../docs/03-adr/ADR-0002-Java栈与Graph选型.md) 备注） |
| 引入 Spring AI Alibaba Graph | 流程只有一条直线，上框架不增加能力；等 M4 引入中断与 Checkpoint 时替换 |
| Flyway | 第一版用一次性 DDL 脚本；表结构稳定后换 Flyway |
| 连接池 | `DriverManagerDataSource` 够用；并发上来前不引入 HikariCP |

## 本地起 PostgreSQL

```bash
cd deploy && docker compose up -d
cd ../backend && java -jar psych-app/target/psych-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=pg
```
