#!/usr/bin/env bash
# deploy/docker/scripts/status.sh
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose ps
echo "--- 端口探测 ---"
for p in 8848 9848 8850 2700; do
  if curl -s -o /dev/null --max-time 2 "http://localhost:$p"; then
    echo "localhost:$p 可达"
  else
    echo "localhost:$p 未响应（非 TCP 层探测，仅供参考）"
  fi
done
