#!/usr/bin/env bash
# 目标机（Linux）使用：载入离线镜像 → 幂等创建数据卷 → 直接启动，无需联网/构建
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[load] 载入离线镜像包"
shopt -s nullglob
tars=(offline/images-*.tar)
if [ ${#tars[@]} -eq 0 ]; then
  echo "[load][错误] offline/ 下没有 images-*.tar" >&2
  exit 1
fi
for f in "${tars[@]}"; do
  echo "[load] $f"
  docker load -i "$f"
done

MINIO_DIR="../docker/minio/data"
if [ ! -d "$MINIO_DIR/storyboard" ]; then
  echo "[load][警告] 未发现 ${MINIO_DIR}/storyboard，MinIO 将以空库启动；"
  echo "             迁移时请把宿主目录 deploy/docker/minio/data 一并拷贝到目标机同位置。"
fi

echo "[load] 启动统一栈（不构建）"
docker compose --env-file .env up -d --no-build
docker compose --env-file .env ps

PORT="$(grep -E '^FRONTEND_PORT=' .env | cut -d= -f2)"
echo "[load] 完成，访问 http://<目标机IP>:${PORT:-80}/"
