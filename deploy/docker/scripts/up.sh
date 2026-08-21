#!/usr/bin/env bash
# deploy/docker/scripts/up.sh
# 幂等接管旧 nacos 容器（存在则停删）→ compose up → 健康检查
set -euo pipefail
cd "$(dirname "$0")/.."

# 旧容器接管：仅当 compose 未管理该服务、且存在同名旧容器时才停删（避免重复重建）
if [ -z "$(docker compose ps -q nacos 2>/dev/null)" ] && docker ps -a --format '{{.Names}}' | grep -qx nacos-standalone; then
  echo "[接管] 停止并删除 compose 未管理的旧 nacos-standalone 容器"
  docker stop nacos-standalone >/dev/null
  docker rm nacos-standalone >/dev/null
fi

docker compose up -d

echo "[等待] vosk 容器健康检查…"
for i in $(seq 1 30); do
  # ws 端口 HTTP 探测：websocket 可能返回非 200，只看 curl exit code（连接成功即就绪）
  if curl -s -o /dev/null --max-time 2 "http://localhost:${VOSK_PORT:-2700}"; then
    echo "[OK] vosk 已就绪"
    break
  fi
  if ! docker ps --format '{{.Names}}' | grep -qx vosk-stt; then
    echo "[错误] vosk 容器未运行，docker compose logs vosk 查看" >&2
    exit 1
  fi
  [ "$i" = 30 ] && echo "[警告] vosk 30s 内未就绪（模型加载约需数秒~数十秒，可稍候重试 status.sh）"
  sleep 1
done

./scripts/status.sh
