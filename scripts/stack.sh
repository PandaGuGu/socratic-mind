#!/usr/bin/env bash
#
# 一键起停全部服务（应用 + PostgreSQL/pgvector，可选 Ollama）。
#
#   bash scripts/stack.sh up            # 起：生成 .env → 构建镜像 → 等健康 → 打印地址
#   bash scripts/stack.sh up --ai       # 再加本地向量模型 BGE-M3（首次约 1.2 GB）
#   bash scripts/stack.sh status        # 看容器与健康状态
#   bash scripts/stack.sh logs          # 跟日志
#   bash scripts/stack.sh psql          # 进数据库
#   bash scripts/stack.sh down          # 停（保留数据卷）
#   bash scripts/stack.sh reset --yes   # 停并删卷：会话、知识库、后台配置全部清空
#
# Git Bash / WSL / Linux / macOS 都能跑；Windows 原生终端用 scripts/stack.ps1。

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

CMD="${1:-up}"
shift || true

AI=0
ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    --ai) AI=1 ;;
    -y|--yes) ASSUME_YES=1 ;;
    *) echo "未知参数：$arg（可用：--ai、--yes）" >&2; exit 2 ;;
  esac
done

die() { echo "✗ $*" >&2; exit 1; }
info() { echo "· $*"; }

require_docker() {
  command -v docker >/dev/null 2>&1 || die "没找到 docker 命令：先装 Docker Desktop（https://www.docker.com/products/docker-desktop/）"
  docker compose version >/dev/null 2>&1 || die "docker compose 不可用：Docker Desktop 需要开启 Compose v2"
  docker info >/dev/null 2>&1 || die "Docker 守护进程没起来：先打开 Docker Desktop，等右下角鲸鱼图标变成运行中，再重跑本脚本"
}

gen_secret() {
  if command -v python >/dev/null 2>&1; then
    python -c "import secrets; print(secrets.token_urlsafe(32))"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c "import secrets; print(secrets.token_urlsafe(32))"
  elif command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    die "需要 python 或 openssl 来生成密钥"
  fi
}

ensure_env() {
  if [ -f .env ]; then
    return
  fi
  info "首次运行：从 .env.example 生成 .env，并写入随机密钥"
  cp .env.example .env
  local db_pass user_secret internal_token
  db_pass="$(gen_secret)"
  user_secret="$(gen_secret)"
  internal_token="$(gen_secret | cut -c1-24)"
  # 就地替换空值；只替换模板里留空的那三行
  python - "$db_pass" "$user_secret" "$internal_token" <<'PY'
import sys, io
db_pass, user_secret, internal_token = sys.argv[1:4]
path = ".env"
lines = io.open(path, encoding="utf-8").read().splitlines()
out = []
for line in lines:
    if line.startswith("POSTGRES_PASSWORD=") and not line.split("=", 1)[1].strip():
        line = "POSTGRES_PASSWORD=" + db_pass
    elif line.startswith("PSYCH_USER_SECRET=") and not line.split("=", 1)[1].strip():
        line = "PSYCH_USER_SECRET=" + user_secret
    elif line.startswith("PSYCH_INTERNAL_TOKEN=") and not line.split("=", 1)[1].strip():
        line = "PSYCH_INTERNAL_TOKEN=" + internal_token
    out.append(line)
io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
PY
  chmod 600 .env 2>/dev/null || true
}

load_env() {
  [ -f .env ] || die "没有 .env：先跑 bash scripts/stack.sh up"
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
  APP_PORT="${APP_PORT:-8099}"
}

compose() {
  if [ "$AI" -eq 1 ]; then
    docker compose -f docker-compose.yml -f docker-compose.ai.yml "$@"
  else
    docker compose -f docker-compose.yml "$@"
  fi
}

wait_healthy() {
  local url="http://127.0.0.1:${APP_PORT}/actuator/health"
  local waited=0
  info "等应用健康（最长 240 秒，首次要构建镜像与灌示例语料）"
  while [ "$waited" -lt 240 ]; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "✓ 应用已就绪：$url"
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
    if [ $((waited % 30)) -eq 0 ]; then
      echo "  …已等 ${waited}s"
    fi
  done
  echo "✗ 240 秒内没等到健康检查，最近的日志：" >&2
  compose ps >&2 || true
  compose logs --tail 60 app >&2 || true
  exit 1
}

summary() {
  load_env
  echo
  echo "  产品房间      http://localhost:${APP_PORT}/room/"
  echo "  知识问答      http://localhost:${APP_PORT}/knowledge/"
  echo "  内部后台      http://localhost:${APP_PORT}/internal"
  echo "  内部令牌      ${PSYCH_INTERNAL_TOKEN}"
  echo "  数据库        localhost:${POSTGRES_PORT}  (${POSTGRES_USER}/${POSTGRES_DB})"
  if [ "$AI" -eq 1 ]; then
    echo "  向量模型      ${OLLAMA_EMBED_MODEL:-bge-m3}（容器内 Ollama，1024 维）"
  else
    echo "  向量模型      桩（哈希 256 维，零成本）；要真向量加 --ai"
  fi
  echo
  echo "  跟日志：bash scripts/stack.sh logs      停止：bash scripts/stack.sh down"
}

case "$CMD" in
  up)
    require_docker
    ensure_env
    info "构建并启动容器（首次较慢：要下 JDK/Maven/PG 镜像并编译）"
    compose up -d --build
    wait_healthy
    summary
    ;;
  down|stop)
    require_docker
    compose down
    echo "✓ 已停止（数据卷保留；要清空用 reset）"
    ;;
  reset)
    require_docker
    if [ "$ASSUME_YES" -ne 1 ]; then
      echo "这会删除数据库与后台配置的全部数据，不可恢复。"
      read -r -p "确认清空？输入 yes 继续：" answer
      [ "$answer" = "yes" ] || die "已取消"
    fi
    compose down -v
    echo "✓ 已停止并删除数据卷"
    ;;
  status|ps)
    require_docker
    compose ps
    ;;
  logs)
    require_docker
    compose logs -f --tail 100
    ;;
  psql)
    require_docker
    load_env
    docker compose -f docker-compose.yml exec postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}"
    ;;
  *)
    die "未知命令：$CMD（可用：up、down、reset、status、logs、psql）"
    ;;
esac
