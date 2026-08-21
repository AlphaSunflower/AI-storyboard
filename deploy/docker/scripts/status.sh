#!/usr/bin/env bash
# deploy/docker/scripts/status.sh [nacos|vosk|all] — 默认 all
set -euo pipefail
cd "$(dirname "$0")/.."
SVC="${1:-all}"

case "$SVC" in
  nacos) (cd nacos && docker compose ps) ;;
  vosk)  (cd vosk && docker compose ps) ;;
  all|"") echo "=== nacos ==="; (cd nacos && docker compose ps); echo; echo "=== vosk ==="; (cd vosk && docker compose ps) ;;
  *) echo "用法: $0 [nacos|vosk|all]"; exit 1 ;;
esac

echo "--- 端口探测 ---"
case "$SVC" in
  nacos) PORTS="8848 9848 8850" ;;
  vosk)  PORTS="2700" ;;
  all|"") PORTS="8848 9848 8850 2700" ;;
esac
for p in $PORTS; do
  if curl -s -o /dev/null --max-time 2 "http://localhost:$p"; then
    echo "localhost:$p 可达"
  else
    echo "localhost:$p 未响应（非 TCP 层探测，仅供参考）"
  fi
done
