#!/usr/bin/env bash
# 目标机（Linux）一键部署：载入离线镜像 → 校验/解包迁移数据 → 起栈 → 等健康
#
#   bash scripts/load-and-up.sh                  # 用 .env
#   ENV_FILE=.env.verify bash scripts/load-and-up.sh
#   SKIP_LOAD=1 bash scripts/load-and-up.sh      # 镜像已 load 过，跳过 docker load
#
# 前置：整个 deploy/stack 目录已拷到目标机（含 data/、db/*.dump、offline/、.env）。
# 目标机不需要联网、Maven、Node。
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env}"
if [ ! -f "$ENV_FILE" ]; then
  echo "[load][错误] 找不到 $ENV_FILE" >&2
  exit 1
fi
COMPOSE=(docker compose --env-file "$ENV_FILE")

# 宿主 shell 里的同名变量（REDIS_PORT、POSTGRES_PORT…）优先级高于 --env-file，
# 会把端口/密钥悄悄改回宿主值。这里按 env 文件显式覆盖进程环境。
while IFS='=' read -r k v; do
  case "$k" in ''|\#*) continue ;; esac
  export "$k=$v"
done < "$ENV_FILE"

env_val() { grep -E "^$1=" "$ENV_FILE" | head -n1 | cut -d= -f2- | tr -d '\r'; }
PREFIX="$(env_val STACK_PREFIX)"; PREFIX="${PREFIX:-aistoryboard}"
PORT="$(env_val FRONTEND_PORT)"; PORT="${PORT:-80}"

# PUBLIC_ORIGIN 留空/auto 时自动取默认路由的源地址（每台机器不同，不能写死 IP）
PUBLIC_ORIGIN_VAL="$(env_val PUBLIC_ORIGIN)"
if [ -z "$PUBLIC_ORIGIN_VAL" ] || [ "$PUBLIC_ORIGIN_VAL" = "auto" ]; then
  LAN_IP="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
  [ -n "$LAN_IP" ] || LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [ -n "$LAN_IP" ] || LAN_IP="127.0.0.1"
  export PUBLIC_ORIGIN="http://${LAN_IP}"
  echo "[load] PUBLIC_ORIGIN 未显式指定，自动使用 http://${LAN_IP}"
fi

# ── 1) 载入离线镜像 ────────────────────────────────────────────────────
if [ "${SKIP_LOAD:-0}" != "1" ]; then
  shopt -s nullglob
  tars=(offline/images-*.tar)
  if [ ${#tars[@]} -eq 0 ]; then
    echo "[load][错误] offline/ 下没有 images-*.tar；若镜像已导入请用 SKIP_LOAD=1" >&2
    exit 1
  fi
  for f in "${tars[@]}"; do
    echo "[load] docker load -i $f"
    docker load -i "$f"
  done
  shopt -u nullglob
fi

# ── 2) CRLF 兜底（Windows 上准备的包，shell/sql 可能是 CRLF）──────────
if command -v sed >/dev/null 2>&1; then
  for f in db/*.sh db/sql/*.sql scripts/*.sh docker-compose.yml; do
    [ -f "$f" ] || continue
    if grep -qU $'\r' "$f" 2>/dev/null; then
      sed -i 's/\r$//' "$f"
      echo "[load] 已去掉 CRLF: $f"
    fi
  done
fi

# ── 3) 迁移数据 ────────────────────────────────────────────────────────
mkdir -p data/minio data/sandbox-workspaces

if [ -z "$(ls -A data/minio 2>/dev/null)" ]; then
  if [ -f offline/minio-data.tar ]; then
    echo "[load] 解包 MinIO 对象数据 offline/minio-data.tar"
    tar -xf offline/minio-data.tar -C .
  else
    echo "[load][警告] data/minio 为空且没有 offline/minio-data.tar：MinIO 将以空桶启动，"
    echo "             库里引用的历史图片/视频会 404（新生成产物正常）。"
  fi
fi

if [ -z "$(ls -A data/sandbox-workspaces 2>/dev/null)" ]; then
  if [ -f offline/sandbox-workspaces.tar ]; then
    echo "[load] 解包沙箱工作区 offline/sandbox-workspaces.tar"
    tar -xf offline/sandbox-workspaces.tar -C .
  else
    echo "[load][提示] data/sandbox-workspaces 为空：沙箱可用，但历史会话工作区为空（可正常新建）。"
  fi
fi

# 沙箱 runner 以 10001:10001 运行；从 tar/另一台机器拷来的目录属主可能不对
if [ "$(id -u)" = "0" ]; then
  chown -R 10001:10001 data/sandbox-workspaces 2>/dev/null || true
else
  owner="$(stat -c '%u' data/sandbox-workspaces 2>/dev/null || echo '?')"
  if [ "$owner" != "10001" ]; then
    echo "[load][提示] 当前非 root，data/sandbox-workspaces 属主是 ${owner}；"
    echo "             若沙箱写文件报 Permission denied，执行：sudo chown -R 10001:10001 data/sandbox-workspaces"
  fi
fi
chmod -R u+rwX data/minio data/sandbox-workspaces 2>/dev/null || true

# 沙箱工作区用命名卷（bind mount 在 Windows 上物化 12k 个技能文件会慢到卡住 Agent）。
# 首次把 data/sandbox-workspaces 播进卷；卷里已有内容时不动。
SANDBOX_VOL="${PREFIX}-sandbox-workspaces"
docker volume create "$SANDBOX_VOL" >/dev/null
echo "[load] 校验沙箱卷 $SANDBOX_VOL（空则从 data/sandbox-workspaces 播种）"
docker run --rm --entrypoint sh \
  -v "${SANDBOX_VOL}:/w" \
  -v "$PWD/data/sandbox-workspaces:/seed:ro" \
  -v "$PWD/scripts/seed-sandbox.sh:/seed.sh:ro" \
  alpine /seed.sh

# ── 4) 宿主资源提醒 ────────────────────────────────────────────────────
if command -v free >/dev/null 2>&1; then
  mem_mb=$(free -m | awk '/^Mem:/{print $2}')
  swap_mb=$(free -m | awk '/^Swap:/{print $2}')
  echo "[load] 宿主内存 ${mem_mb}MB / swap ${swap_mb}MB"
  if [ "${mem_mb:-0}" -lt 7500 ]; then
    echo "[load][警告] 全栈 12 个容器建议 ≥8G 内存；当前 ${mem_mb}MB，"
    echo "             请按 README 调小 .env 的 *_MEM_LIMIT，或给宿主加 swap。"
  fi
fi

# ── 5) 启动并等待健康 ─────────────────────────────────────────────────
echo "[load] 启动统一栈（不构建）"
"${COMPOSE[@]}" up -d --no-build

DEADLINE=$(( $(date +%s) + 600 ))
while :; do
  rows="$("${COMPOSE[@]}" ps --format '{{.Service}}|{{.State}}|{{.Health}}' 2>/dev/null || true)"
  bad="$(printf '%s\n' "$rows" | awk -F'|' 'NF>=2 && ($2!="running" || ($3!="" && $3!="healthy")) {print $1":"$2"/"$3}' | paste -sd, -)"
  if [ -n "$rows" ] && [ -z "$bad" ]; then
    echo "[load] 全部服务 running/healthy"
    break
  fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "[load][警告] 等待超时，未就绪: ${bad:-unknown}；用 docker compose --env-file $ENV_FILE logs 查看"
    break
  fi
  echo "[load] 等待中… 未就绪: ${bad:-starting}"
  sleep 10
done

"${COMPOSE[@]}" ps
HOST_PART=""
[ "$PORT" = "80" ] || HOST_PART=":$PORT"
echo ""
echo "[load] 完成。访问 http://<目标机IP>${HOST_PART}/"
echo "       初始账号：admin@qq.com / 123456"
echo "       验收：bash scripts/verify.sh（或 powershell -File scripts/verify.ps1）"
