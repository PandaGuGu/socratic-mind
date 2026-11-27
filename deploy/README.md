---
title: deploy 说明
status: 定稿
updated: 2026-11-27
---

# deploy

容器化有两档：**全部服务**（应用 + 数据库，可选模型与反代）与**只起依赖**（本机跑 jar）。
编排文件在仓库根目录，`deploy/` 只放这一档的 `.env.example`、`Caddyfile` 与本文。

## 一、全部服务（推荐）

```bash
bash scripts/stack.sh up            # 首次会构建镜像，几分钟
bash scripts/stack.sh up --ai       # 再带本地模型（首次拉 bge-m3 1.2 GB + qwen3-8b 5 GB）
bash scripts/stack.sh status        # 容器与健康状态
bash scripts/stack.sh logs          # 跟日志
bash scripts/stack.sh psql          # 进数据库
bash scripts/stack.sh down          # 停，保留数据卷
bash scripts/stack.sh reset         # 停并删卷（二次确认）
```

Windows 双击根目录 `start.cmd`（参数 `ai` / `edge` / `down` / `status` / `logs` / `psql` / `reset`），
或 `powershell -ExecutionPolicy Bypass -File scripts\stack.ps1 up`。

| 文件 | 作用 |
|-|-|
| `Dockerfile` | 多阶段构建：Maven 编译 → JRE 运行，非 root，带健康检查 |
| `docker-compose.yml` | 应用 + PostgreSQL 16/pgvector |
| `docker-compose.ai.yml` | 叠加层：Ollama 与模型拉取 |
| `docker-compose.edge.yml` + `deploy/Caddyfile` | 叠加层：Caddy 反代，TLS / 压缩 / 安全头 |
| `.env.example` | 变量模板，首次启动复制成 `.env` 并填随机密钥 |

### 进容器与不进容器

| 依赖 | 容器 | 说明 |
|-|-|-|
| 应用（Spring Boot + 静态页面） | 是 | 页面打在 jar 的 `static/` 里，不是独立服务 |
| PostgreSQL 16 + pgvector | 是 | 唯一外部存储：会话、轨迹、知识库、限流都在里面 |
| Ollama | 可选 | `--ai` 才起；默认桩模型，不起也能跑完两个流程 |
| Caddy | 可选 | `--edge` 才起，对外暴露时才需要 |
| 语音转写 ASR | 否 | 进程钩子 `psych.asr.command`，用宿主语音包 |
| 评测 / 压测 / 语料导入 | 否 | 一次性任务，用宿主机 Python 跑 |

### 为什么没有 Redis、消息队列

不是漏了，是刻意的：

- **限流**用数据库表的原子 upsert，代价是固定窗口、边界可能瞬时放行两倍配额，
  换来"每个请求只碰一行、不引入第二个存储"；
- **没有消息队列**：流程是同步状态机，一个会话在同一进程里走完，没有扇出与跨进程解耦；
- **反代**只在对外时有意义，本机直连更快，所以做成叠加层。

默认档两个容器就是完整可用的系统。

### 边缘层

```bash
start.cmd edge                     # Windows
bash scripts/stack.sh up --edge    # 其他平台
```

- 默认 `SITE_ADDRESS=localhost`，Caddy 出**自签内部证书**，浏览器提示一次不安全；内网够用。
- 换成真域名（写进 `.env`）且 80/443 可达时，Caddy 自动申请 Let's Encrypt。
- 端口冲突改 `.env` 的 `EDGE_HTTP_PORT` / `EDGE_HTTPS_PORT`；应用的 `8099` 仍发布，便于绕过代理对比。

### 后台登录

容器档默认 `PSYCH_INTERNAL_DEV_HINT=false`，登录页没有内置账号，首次启动按 `.env` 播种：

| 项 | 默认 | 说明 |
|-|-|-|
| 用户名 | `admin` | `PSYCH_OPERATOR_USER` |
| 口令 | 首次随机生成 | `PSYCH_OPERATOR_PASSWORD`，启动结束时打印 |

口令 12 位，字符集去掉了 `0 O 1 l I`——这几个在常见字体里分不清，抄错一位只看到"用户名或口令不对"。
只在库里没有该用户名时写入一次，之后在后台改口令不会被重启覆盖；要按 `.env` 重来就 `reset`。

### 端口与数据

| 项 | 默认 | 改法 |
|-|-|-|
| 应用 | `8099` → 容器 8080 | `.env` 的 `APP_PORT` |
| 数据库 | `15432` → 容器 5432 | `.env` 的 `POSTGRES_PORT`（默认避开本机 5432） |
| 数据卷 | `pgdata`、`appconfig` | `docker volume ls \| grep socratic-mind` |

`appconfig` 存后台改过的规则、话术、流程参数：容器重建不丢，`reset` 才清。
镜像自带一份 `backend/config/*.json` 作为初始值。

### 模型

容器默认 `PSYCH_AI_PROVIDER=stub`，零成本、不需要 key——容器不该因为"宿主机没装 Ollama"而起不来。
`--ai` 起容器内 Ollama 并接管对话与向量（1024 维）。

本机 jar 档默认接**宿主机** Ollama：`http://localhost:11434/v1`、`qwen3-8b`、`bge-m3`、`no-think=true`；
没有 Ollama 时加 `stub` 档。接云端 OpenAI 兼容服务只需覆盖：

```bash
SPRING_PROFILES_ACTIVE=pg,http
PSYCH_AI_CHAT_PROVIDER=http
PSYCH_AI_BASE_URL=https://api.deepseek.com
PSYCH_AI_API_KEY=sk-...
PSYCH_AI_CHAT_MODEL=deepseek-chat
```

### 换向量模型：迁移而不是清库

`vector(n)` 的维度在建表时定死：桩 256，bge-m3 1024。维度不一致时应用默认**拒绝改动**并打印当前/目标维度；
显式打开 `PSYCH_AI_VECTOR_MIGRATE=true` 后，启动时删掉 `kb_chunk.embedding` 与 HNSW 索引，按新维度重建并重嵌入。
34 块约 9 秒；大语料会慢，迁移期间检索不可用。也可以 `reset` 清库重来。

### 连接池与线程

`PSYCH_DB_POOL_MAX`（20）、`PSYCH_DB_POOL_MIN_IDLE`（2）、`PSYCH_DB_POOL_TIMEOUT_MS`（5000）、
`SERVER_THREADS_MAX`（100）、`SERVER_ACCEPT_COUNT`（100）。池与线程要一起调：
线程再多也只是在池上排队。

### 语音

容器里没有 Windows 语音包，`PSYCH_ASR_COMMAND` 留空，`/api/asr` 回 503，页面提示直接打字。
本机 jar 档默认指向 `../scripts/asr_transcribe.ps1`；Linux 想支持 Firefox 就换成 Vosk/Whisper 的 CLI，接口不变。

## 二、只起依赖（本机跑 jar）

```bash
cd deploy
cp .env.example .env
docker compose up -d
docker compose exec postgres psql -U psych -d psych -c "SELECT extversion FROM pg_extension WHERE extname='vector';"
```

只起数据库，容器名 `psych-postgres-dev`、端口 `5432`；根目录那套是 `psych-postgres` + `15432`。
名字与端口都错开，两档可同时存在。然后：

```bash
java -jar backend/psych-app/target/psych-app-0.6.1.jar \
  --spring.profiles.active=pg \
  --psych.db.url=jdbc:postgresql://localhost:5432/psych
```

minio 与 redis 都还没接，等真需要再加，不预先摆空容器。

## 约定

- 建表由应用启动时的 `ResourceDatabasePopulator` 执行 `db/schema-pg.sql` 与 `db/schema-cbt.sql`，
  **没有 Flyway**；H2 没有 pgvector，只跑会话域。
- 本地库账号密码只写在 `.env`（不入库），模板见根目录与 `deploy/` 两份 `.env.example`。
- 生产部署不在第一版范围；届时另立 ADR，不要在这里悄悄加 K8s 清单。
