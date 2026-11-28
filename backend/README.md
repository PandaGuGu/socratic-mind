---
title: backend 说明
status: 定稿
updated: 2026-11-28
---

# backend

Maven 多模块（模块化单体）。结构与依赖方向见 [模块与技术栈](../docs/02-架构/01-模块与技术栈.md)；
怎么跑、跑成什么样见根 [README](../README.md)。这里只放本目录的操作速查。

## 模块

| 模块 | 职责 |
|-|-|
| `psych-common` | 领域对象 + 端口：`ChatPort`、`EmbeddingPort`、`CbtSessionStore`、`KnowledgeStore` |
| `psych-session` | 会话状态、消息、逐轮轨迹 |
| `psych-knowledge` | Markdown 解析、父子块切分、证据抽取 |
| `psych-rag` | 混合检索、RRF、重排、引用校验、提示词 |
| `psych-agent` | `CbtFlow`、安全分流、流程/规则/参数配置 |
| `psych-infrastructure` | 存储（内存 / JDBC）、模型（桩 / HTTP）、输出闸门 |
| `psych-app` | 启动入口、REST、页面、种子数据、账号与审核 |

依赖只向下：`app → agent / infrastructure → knowledge / rag / session → common`。
`rag` 不依赖 `agent`，换库或换模型只改 `infrastructure` 里的适配器。

## 命令

```bash
cd backend
mvn -o test                      # 全部测试，不依赖外部模型与数据库
mvn -o package -DskipTests       # 产出 psych-app/target/psych-app-<version>.jar

# 本机：H2 文件库 + 宿主 Ollama
java -jar psych-app/target/psych-app-0.7.0.jar --server.port=8099 --spring.profiles.active=h2

# 完全离线
java -jar psych-app/target/psych-app-0.7.0.jar --server.port=8099 --spring.profiles.active=h2,stub
```

## 档位

| 档位 | 存储 | 模型 | 用途 |
|-|-|-|-|
| 默认 | 内存 | 桩 | 只验证流程，重启即清空 |
| `h2` | H2 文件 | 宿主 Ollama | 单机验收、验证重启存活；知识库仍走内存 |
| `h2,stub` | H2 文件 | 桩 | 完全离线 |
| `pg` | PostgreSQL + pgvector | 可配 | 生产形态 |
| `http` | 依前两者 | OpenAI 兼容 | 接云端模型（`psych.ai.*`） |

`http` 只换对话模型，不换向量：DeepSeek 没有 `/embeddings`，向量仍是哈希桩，语义检索不成立。

## 配置

`src/main/resources/application*.yml` 是默认值与配置项说明；
运行期可改的规则、话术、流程在 `backend/config/`，后台保存即写回这里（[内部后台](../docs/03-设计/03-内部后台.md)）。

接口：产品接口见 [接口契约](../docs/02-架构/05-接口契约.md)，内部接口见
[内部后台](../docs/03-设计/03-内部后台.md)。两套鉴权互不通用。
