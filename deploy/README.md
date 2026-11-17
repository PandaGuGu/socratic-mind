---
title: deploy 说明
status: 草案
---

# deploy

本地开发与部署所需的最小环境。M1 的目标就是让 `docker compose up` 之后能连上库、能建表、能跑一次检索。

## 本地依赖

| 服务 | 镜像 | 用途 |
|-|-|-|
| postgres | `pgvector/pgvector:pg16` | 业务表 + 向量 + 全文索引 |
| minio（P1） | `minio/minio` | 原始文档存储，M2 之后需要 |
| redis（P1） | `redis:7-alpine` | 缓存 / 限流，可后加 |

```bash
cd deploy
docker compose up -d
# 校验 pgvector 可用
docker compose exec postgres psql -U psych -d psych -c "CREATE EXTENSION IF NOT EXISTS vector; SELECT extversion FROM pg_extension WHERE extname='vector';"
```

## 约定

- 本地库账号密码只写在 `.env`（不入库），模板见 `.env.example`。
- 迁移由 Flyway 在应用启动时执行，不手工建表。
- 生产部署不在第一版范围内；届时另立 ADR，不要在这里悄悄加 K8s 清单。
