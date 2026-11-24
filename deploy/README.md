---
title: deploy 说明
status: 定稿
updated: 2026-11-20
---

# deploy

容器化有两档：**全部服务**（应用 + 数据库，可选本地向量）与**只起依赖**（本机跑 jar）。

## 一、全部服务（推荐）

编排文件在仓库根目录，不在这个目录：

| 文件 | 作用 |
|-|-|
| `Dockerfile` | 多阶段构建：Maven 编译 → JRE 运行，非 root 用户，自带健康检查 |
| `docker-compose.yml` | 应用 + PostgreSQL/pgvector |
| `docker-compose.ai.yml` | 叠加层：加 Ollama 与 BGE-M3 拉取任务，把向量换成真模型 |
| `.env.example` | 变量模板，首次启动自动复制成 `.env` 并填随机密钥 |
| `scripts/stack.sh` / `stack.ps1` | 一键起停脚本 |

```bash
bash scripts/stack.sh up            # 起；首次会构建镜像，几分钟
bash scripts/stack.sh up --ai       # 再带本地向量（首次拉 bge-m3，约 1.2 GB）
bash scripts/stack.sh status        # 容器与健康状态
bash scripts/stack.sh logs          # 跟日志
bash scripts/stack.sh psql          # 进数据库
bash scripts/stack.sh down          # 停，保留数据卷
bash scripts/stack.sh reset         # 停并删卷（会二次确认）
```

Windows 原生终端用 `powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 up`，参数换成 `-Ai` / `-Yes`。
脚本只做四件事：检查 Docker 是否真的在跑、生成 `.env`、`docker compose up -d --build`、轮询 `/actuator/health` 直到就绪。

### 端口与数据

| 项 | 默认 | 改法 |
|-|-|-|
| 应用 | `8099` → 容器 8080 | `.env` 的 `APP_PORT` |
| 数据库 | `15432` → 容器 5432 | `.env` 的 `POSTGRES_PORT`（默认避开本机 5432） |
| 数据卷 | `pgdata`、`appconfig` | `docker volume ls \| grep socratic-mind` |

`appconfig` 里是后台改过的规则、话术、流程参数：容器重建不丢，`reset` 才清。
镜像里自带一份 `backend/config/*.json` 作为初始值，所以第一次启动就有可用配置。

### 模型：容器默认桩，本机 jar 默认 Ollama

容器档默认 `PSYCH_AI_PROVIDER=stub`，零成本、不需要任何 key——容器里不该因为"宿主机没装 Ollama"而起不来。
要真实模型，用叠层：`bash scripts/stack.sh up --ai`。它会起容器内 Ollama，拉 `bge-m3`（1.2 GB）
与 `qwen3-8b`（5 GB），并把对话与向量都接过去（1024 维）。

本机 jar 档（`--spring.profiles.active=h2` 或 `pg`）默认接**宿主机** Ollama：
`http://localhost:11434/v1`、`qwen3-8b`、`bge-m3`、`no-think=true`。没有 Ollama 时加 `stub` 档：`h2,stub`。

接云端 OpenAI 兼容服务只需覆盖：

```bash
SPRING_PROFILES_ACTIVE=pg,http
PSYCH_AI_CHAT_PROVIDER=http
PSYCH_AI_BASE_URL=https://api.deepseek.com
PSYCH_AI_API_KEY=sk-...
PSYCH_AI_CHAT_MODEL=deepseek-chat
```

### 换向量模型：迁移而不是清库

PG 的 `vector(n)` 维度在建表时定死：桩 256，BGE-M3 1024。
v0.5.0 起，维度不一致时应用默认**拒绝改动**并打印当前/目标维度；显式打开：

```bash
# .env
PSYCH_AI_VECTOR_MIGRATE=true
```

启动时会删掉 `kb_chunk.embedding` 与 HNSW 索引，按新维度重建，再把所有子块从 `content` 重嵌入。
34 块在本机约 9 秒；大语料会慢，且迁移期间检索不可用。也可以继续用 `bash scripts/stack.sh reset` 清库重来。

### 连接池与线程

- `PSYCH_DB_POOL_MAX`（默认 20）、`PSYCH_DB_POOL_MIN_IDLE`（2）、`PSYCH_DB_POOL_TIMEOUT_MS`（5000）；
- `SERVER_THREADS_MAX`（默认 100）、`SERVER_ACCEPT_COUNT`（100）。

池与线程要一起调：线程再多，也只是在池上排队。本机 PG 17 实测 128 并发、1280 请求、0 错误，
会话域 P95 77 ms、约 650 rps（客户端与 CPU 是瓶颈，不是应用）。

### 语音转写

容器里默认没有 Windows 语音包，`PSYCH_ASR_COMMAND` 留空，`/api/asr` 回 503，页面提示直接打字。
本机 jar 档默认指向 `../scripts/asr_transcribe.ps1`（Windows System.Speech，zh-CN 已验证）。
Linux 想支持 Firefox，把命令换成 Vosk/Whisper 的 CLI 即可，接口不变。

## 二、只起依赖（本机跑 jar）

```bash
cd deploy
cp .env.example .env
docker compose up -d
docker compose exec postgres psql -U psych -d psych -c "SELECT extversion FROM pg_extension WHERE extname='vector';"
```

然后本机启动应用并指向它：

```bash
java -jar backend/psych-app/target/psych-app-0.3.1.jar --spring.profiles.active=pg \
  --psych.db.url=jdbc:postgresql://localhost:5432/psych
```

这一档的 `.env` 只给数据库用（库名、账号、端口），与应用侧那份不是同一个文件。

minio（原始文档存储）与 redis（缓存/限流）都还没接，等真需要再加，不预先摆空容器。

## 验证状态

2026-11-20 在本机 Docker Desktop 29.9 上实跑，Windows 11 + Git Bash。

| 项 | 结果 |
|-|-|
| 镜像构建 | 通过，首次 10 分 38 秒（Maven 依赖要下全）；二次复用缓存后秒级 |
| 基础档启动 | 通过：PG healthy → 应用 healthy → 脚本打印入口，二次 `up` 用 14 秒 |
| 建表与灌语料 | 通过：6 张表（kb/cbt 两域）、3 篇示例文档、34 块（17 父 + 17 子）、`vector(256)` |
| 检索 | 通过：两路命中带 `vectorScore` / `textScore`，`via=both` 生效 |
| 知识问答 | 通过：返回带脚注引用的答案；无关问题判 `insufficient_evidence` 并说明原因 |
| 内部接口鉴权 | 通过：无 `X-Internal-Token` → 401，带令牌 → 200 |
| 会话持久化 | 通过：发消息后 `docker compose restart app`，会话、阶段与轨迹仍在 |
| `down` 后再 `up` | 通过：命名卷保留，库内文档与轮次未丢 |
| 256→1024 迁移 | 通过：容器库 `vector(256)` + 34 块，设 `vector-migrate=true` 后重建列与 HNSW，17 个子块重嵌入，检索/问答正常 |
| 真实模型（本机 Ollama） | 通过：qwen3-8b + bge-m3，正式集 34 条问答、引用校验、拒答后检均跑通；CPU 单轮 30–60 秒 |
| 并发（本机 PG 17） | 通过：128 并发 / 1280 请求，0 错误，P95 77 ms，约 650 rps（会话域） |
| Ollama 容器档（`--ai`） | 本机用宿主 Ollama 验证了同一套端点；容器内拉 5 GB 模型未在本轮重跑 |

实跑中发现并修掉两个问题，都已入库：

- `Dockerfile` 原本写了 `# syntax=docker/dockerfile:1`，会强制从 Docker Hub 拉前端镜像；国内网络直接卡死。现已去掉，用 Docker 内置前端。
- embedding 服务返回空向量时，适配器原来静默生成零长数组，写库才炸成 `vector must have at least 1 dimension`。现在直接报错并带响应片段。

## 约定

- 建表由应用启动时的 `ResourceDatabasePopulator` 执行 `db/schema-pg.sql`、`db/schema-cbt.sql`，**没有 Flyway**；
  两域分开：H2 没有 pgvector，只跑会话三张表。
- 本地库账号密码只写在 `.env`（不入库），模板见根目录与 `deploy/` 下两份 `.env.example`。
- 生产部署不在第一版范围内；届时另立 ADR，不要在这里悄悄加 K8s 清单。
