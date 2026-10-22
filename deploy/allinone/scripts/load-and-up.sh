#!/usr/bin/env bash
# 目标机（Linux）使用：载入离线镜像 → 起栈。目标机不需要联网、不需要 Maven/Node。
#
#   bash scripts/load-and-up.sh                      # 核心栈（不含 Agent 沙箱）
#   WITH_SANDBOX=1 bash scripts/load-and-up.sh       # 额外启动 Agent 沙箱 profile
#
# 前置：整个 deploy/allinone 目录（含 offline/*.tar、db/*.dump、.env）已拷到目标机。
set -euo pipefail
cd "$(dirname "$0")/.."

compose=(docker compose --env-file .env)
if [ "${WITH_SANDBOX:-0}" = "1" ]; then
  compose+=(--profile sandbox)
  if ! grep -qE '^AGENT_SANDBOX_ENABLED=(true|1)' .env; then
    echo "[load][提示] WITH_SANDBOX=1 但 .env 里 AGENT_SANDBOX_ENABLED 不是 true，moon-agent 不会调用沙箱"
  fi
fi

# ── 1) 载入镜像 ────────────────────────────────────────────────────────
shopt -s nullglob
tars=(offline/images-*.tar)
if [ ${#tars[@]} -eq 0 ]; then
  echo "[load][错误] offline/ 下没有 images-*.tar，请先把离线镜像包拷进来" >&2
  exit 1
fi
for f in "${tars[@]}"; do
  echo "[load] $f"
  docker load -i "$f"
done

# ── 2) 数据目录 ────────────────────────────────────────────────────────
mkdir -p data/minio
if [ -z "$(ls -A data/minio 2>/dev/null)" ]; then
  echo "[load][提示] data/minio 是空的：MinIO 将以空桶启动。"
  echo "             库里的历史图片/视频对象不会存在（新生成的产物一切正常）。"
  echo "             想带上历史对象，把 offline/minio-data.tar 解到这个目录即可："
  echo "               tar -xf offline/minio-data.tar -C .   # 解出 data/minio/..."
fi

# ── 2b) CRLF 兜底 ──────────────────────────────────────────────────────
# 在 Windows 上准备部署包时，db/*.sh 很容易被检出成 CRLF；那会让 postgres 的
# docker-entrypoint-initdb.d 报 "/bin/bash^M: bad interpreter" 并静默跳过建库/还原（空库启动）。
# 这里在起栈前统一去掉 \r，幂等且无副作用。
if command -v sed >/dev/null 2>&1; then
  for f in db/*.sh db/sql/*.sql; do
    [ -f "$f" ] || continue
    if grep -qU $'\r' "$f" 2>/dev/null; then
      sed -i 's/\r$//' "$f"
      echo "[load] 已去掉 CRLF: $f"
    fi
  done
fi

# ── 3) 宿主机资源提醒（2 核 2G 的关键前提是 swap）─────────────────────
if command -v free >/dev/null 2>&1; then
  mem_mb=$(free -m | awk '/^Mem:/{print $2}')
  swap_mb=$(free -m | awk '/^Swap:/{print $2}')
  echo "[load] 宿主内存 ${mem_mb}MB / swap ${swap_mb}MB"
  if [ "${swap_mb:-0}" -lt 1024 ]; then
    echo "[load][警告] swap 不足 1G。6 个服务在同一个 JVM 里，峰值会超过 2G 物理内存，"
    echo "             强烈建议先加 4G swap（见 README 的『swap』一节），否则可能被 OOM Killer 杀掉。"
  fi
fi

# ── 4) 启动 ────────────────────────────────────────────────────────────
echo "[load] 启动（不构建）"
"${compose[@]}" up -d --no-build
"${compose[@]}" ps

PORT="$(grep -E '^HTTP_PORT=' .env | cut -d= -f2)"
echo ''
echo "[load] 完成。访问 http://<目标机公网IP>:${PORT:-80}/"
echo "       账号：admin@qq.com / 123456、$(grep -E '^SECOND_ACCOUNT_EMAIL=' .env | cut -d= -f2) / 123456"
echo "       首次启动 6 个 Spring 上下文要 1~3 分钟，先看日志：docker compose --env-file .env logs -f allinone"
