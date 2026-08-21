#!/usr/bin/env bash
# deploy/docker/scripts/down.sh
# 停止全部服务（保留 nacos-data 卷与模型文件）
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down
