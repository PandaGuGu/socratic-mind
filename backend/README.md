---
title: backend 说明
status: 定稿
updated: 2026-11-19
---

# backend

Maven 多模块（模块化单体）。设计依据 [模块拆分](../docs/01-方案/03-模块拆分.md)；
怎么跑、跑成什么样在 [根 README](../README.md)，这里只说结构。

## 模块与依赖

| 模块 | 职责 |
|-|-|
| `psych-common` | 领域模型 + 端口：`CbtSessionStore`、`KnowledgeStore`、`ChatPort`、`EmbeddingPort` |
| `psych-session` | 会话状态、消息、轮次轨迹 |
| `psych-knowledge` | Markdown 解析、父子块切分、证据抽取、入库编排 |
| `psych-rag` | 混合检索、RRF、引用校验、提示词 |
| `psych-agent` | `CbtFlow` 流程执行、安全分流、流程/规则/参数配置 |
| `psych-infrastructure` | 存储实现（内存 / JDBC）、模型适配（桩 / HTTP）、输出闸门 |
| `psych-app` | 启动入口、REST 接口、页面、种子数据 |

依赖只向下：`app → agent / infrastructure → knowledge / rag / session → common`。
`rag` 不依赖 `agent`（[ADR-0005](../docs/03-adr/ADR-0005-双Graph独立State.md)），
换库或换模型只改 `infrastructure` 里的适配器。

## 常用命令

```bash
cd backend
mvn -o test                 # 108 项
mvn -o package -DskipTests

# 本机：H2 文件库，重启数据还在
java -jar psych-app/target/psych-app-0.3.0.jar --server.port=8099 --spring.profiles.active=h2

# 需要 PostgreSQL + pgvector 时（代码已写，本机未实跑）
java -jar psych-app/target/psych-app-0.3.0.jar --spring.profiles.active=pg
```

默认档（不带 profile）用内存存储 + 桩模型，不需要数据库也不需要 API key，启动即灌入示例语料。

## 三种档位

| 档位 | 存储 | 模型 | 用途 |
|-|-|-|-|
| 默认 | 内存 | 桩 | 只验证流程，重启即清空 |
| `h2` | H2 文件 | 桩 | 本机验收、看后台、验证重启存活 |
| `pg` | PostgreSQL + pgvector | 桩 | 生产形态；本机未实跑 |
| `http` | 依前两者 | OpenAI 兼容接口 | 接真实模型（`psych.ai.*`） |

`http` 只换对话模型，不换向量：DeepSeek 没有 `/embeddings`，向量仍是哈希桩，语义检索不成立。

## 接口

产品接口见 [API 契约](../docs/02-设计/API契约.md)，内部接口见 [内部后台](../docs/02-设计/内部后台.md)。
两套鉴权互不通用：`/api/cbt/**` 用匿名会话令牌，`/api/internal/**` 用内部令牌。
