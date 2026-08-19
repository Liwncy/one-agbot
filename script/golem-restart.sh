#!/usr/bin/env bash
# 拷到服务器：/www/wwwroot/bot/restart.sh
# 用法：bash /www/wwwroot/bot/restart.sh
set -euo pipefail
cd /www/wwwroot/bot

pids="$(pgrep -f '[g]olem-linux-amd64' || true)"
if [ -n "$pids" ]; then
  echo "[golem] kill $pids"
  # shellcheck disable=SC2086
  kill $pids || true
  sleep 2
  pids="$(pgrep -f '[g]olem-linux-amd64' || true)"
  if [ -n "$pids" ]; then
    echo "[golem] kill -9 $pids"
    # shellcheck disable=SC2086
    kill -9 $pids || true
    sleep 1
  fi
fi

nohup ./golem-linux-amd64 > myapp.log 2>&1 &
echo "[golem] started pid=$!"
echo "[golem] 扫码看日志："
sleep 5
tail -n 80 myapp.log
