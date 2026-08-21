#!/usr/bin/env bash
# deploy/docker/scripts/up.sh [nacos|vosk|all]
# 幂等接管旧 nacos 容器（存在则停删）→ 对应子目录 compose up → 健康检查
set -euo pipefail
cd "$(dirname "$0")/.."
SVC="${1:-all}"

up_nacos() {
  # 旧容器接管：仅当 compose 未管理该服务、且存在同名旧容器时才停删（避免重复重建）
  if [ -z "$(docker compose -f nacos/docker-compose.yml ps -q nacos 2>/dev/null)" ] && docker ps -a --format '{{.Names}}' | grep -qx nacos-standalone; then
    echo "[接管] 停止并删除 compose 未管理的旧 nacos-standalone 容器"
    docker stop nacos-standalone >/dev/null
    docker rm nacos-standalone >/dev/null
  fi
  (cd nacos && docker compose up -d)
  echo "[等待] nacos 健康检查…"
  for i in $(seq 1 30); do
    if curl -s -o /dev/null --max-time 2 "http://localhost:8848"; then
      echo "[OK] nacos 已就绪"
      break
    fi
    [ "$i" = 30 ] && echo "[警告] nacos 30s 内未就绪，可稍候重试 status.sh"
    sleep 1
  done
}

up_vosk() {
  (cd vosk && docker compose up -d)
  echo "[等待] vosk 容器健康检查…"
  for i in $(seq 1 30); do
    # ws 端口 HTTP 探测：websocket 可能返回非 200，只看 curl exit code（连接成功即就绪）
    if curl -s -o /dev/null --max-time 2 "http://localhost:${VOSK_PORT:-2700}"; then
      echo "[OK] vosk 已就绪"
      break
    fi
    if ! docker ps --format '{{.Names}}' | grep -qx vosk-stt; then
      echo "[错误] vosk 容器未运行，cd vosk && docker compose logs vosk 查看" >&2
      exit 1
    fi
    [ "$i" = 30 ] && echo "[警告] vosk 30s 内未就绪（模型加载约需数秒~数十秒，可稍候重试 status.sh）"
    sleep 1
  done
}

case "$SVC" in
  nacos) up_nacos ;;
  vosk)  up_vosk ;;
  all|"") up_nacos; up_vosk ;;
  *) echo "用法: $0 [nacos|vosk|all]"; exit 1 ;;
esac

./scripts/status.sh "$SVC"
