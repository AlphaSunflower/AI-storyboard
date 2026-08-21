#!/usr/bin/env bash
# deploy/docker/scripts/down.sh [nacos|vosk|all] — 默认 all
# 停止对应服务（保留 nacos-data 卷与模型文件）
set -euo pipefail
cd "$(dirname "$0")/.."
SVC="${1:-all}"

case "$SVC" in
  nacos) (cd nacos && docker compose down) ;;
  vosk)  (cd vosk && docker compose down) ;;
  all|"") (cd nacos && docker compose down); (cd vosk && docker compose down) ;;
  *) echo "用法: $0 [nacos|vosk|all]"; exit 1 ;;
esac
