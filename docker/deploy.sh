#!/usr/bin/env bash
# 宝塔 Webhook / 计划任务调用这个脚本：拉 main → 重新打包 → 起容器
# 用法：bash /www/wwwroot/one-agbot/docker/deploy.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ ! -d .git ]; then
  echo "[deploy] $ROOT 不是 git 仓库。先在宝塔终端 clone，再跑本脚本。"
  exit 1
fi

if [ ! -f docker/.env ]; then
  echo "[deploy] 缺少 docker/.env。先复制 docker/.env.example 并填密码。"
  exit 1
fi

echo "[deploy] fetch origin"
git fetch origin

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if git show-ref --verify --quiet "refs/remotes/origin/${BRANCH}"; then
  TARGET="origin/${BRANCH}"
else
  TARGET="origin/main"
fi

echo "[deploy] reset --hard $TARGET"
git reset --hard "$TARGET"

cd docker
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "[deploy] 没有 docker compose。宝塔装「Docker」或「Docker Compose」。"
  exit 1
fi

echo "[deploy] compose up --build（首次 10～20 分钟，宝塔 Webhook 超时请设 1800 秒）"
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1
"${COMPOSE[@]}" up -d --build

echo "[deploy] wait health"
attempts=20
while [ "$attempts" -gt 0 ]; do
  boot_st="$(docker inspect --format '{{.State.Status}}' one_agbot_boot 2>/dev/null || true)"
  sai_st="$(docker inspect --format '{{.State.Status}}' one_agbot_snailai 2>/dev/null || true)"
  if [ "$boot_st" = "running" ] && [ "$sai_st" = "running" ]; then
    echo "[deploy] containers are running"
    "${COMPOSE[@]}" ps
    docker image prune -f >/dev/null || true
    echo "[deploy] done"
    exit 0
  fi
  attempts=$((attempts - 1))
  sleep 6
done

echo "[deploy] boot/snailai 没起来，最近日志："
"${COMPOSE[@]}" logs --tail=80 boot snailai-server || true
exit 1
