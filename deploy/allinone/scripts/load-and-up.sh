#!/usr/bin/env bash
# 目标机（Linux）使用：校验全量数据 → 载入离线镜像 → 起栈。
# 目标机不需要联网、不需要 Maven/Node。
#
#   bash scripts/load-and-up.sh                      # 默认连 Agent 沙箱一起起
#   WITH_SANDBOX=0 bash scripts/load-and-up.sh       # 只起核心栈（省 ~350MB 内存）
#
# 前置：
#   1) 整个 deploy/allinone 目录（含 offline/*.tar、db/*.dump、data/minio 或
#      offline/minio-data.tar、.env）已拷到目标机；
#   2) 目标机 80 端口没有被旧栈占用（旧栈先 `docker compose down`，卷和数据目录不要删）。
#
# 本脚本刻意不做任何 `down -v`：数据卷只增不减；要重置请人工确认后执行。
set -euo pipefail
cd "$(dirname "$0")/.."

WITH_SANDBOX="${WITH_SANDBOX:-1}"
ENV_FILE="${ENV_FILE:-.env}"
MINIO_TAR="offline/minio-data.tar"

env_get() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2- ; }

PREFIX="$(env_get STACK_PREFIX)"; PREFIX="${PREFIX:-ai-cloud}"
HTTP_PORT="$(env_get HTTP_PORT)"; HTTP_PORT="${HTTP_PORT:-80}"
PUBLIC_ORIGIN="$(env_get PUBLIC_ORIGIN)"
DATA_DIR="$(env_get MINIO_DATA_DIR)"; DATA_DIR="${DATA_DIR:-./data/minio}"
AGENT_SANDBOX_ENABLED="$(env_get AGENT_SANDBOX_ENABLED)"; AGENT_SANDBOX_ENABLED="${AGENT_SANDBOX_ENABLED:-true}"

fail() { echo "[load][错误] $*" >&2; exit 1; }

echo "[load] 栈前缀=$PREFIX 入口端口=$HTTP_PORT 沙箱=$([ "$WITH_SANDBOX" = "1" ] && echo 开 || echo 关)"
echo "[load] 数据目录=$DATA_DIR 配置=$ENV_FILE"

# ── 1) 全量数据前置校验（缺数据就拒绝起栈，避免“起来了但历史数据全 404”）──
[ -s db/ai_storyboard.dump ] || fail "缺少 db/ai_storyboard.dump（全量业务库转储）"
[ -s db/llm_gateway.dump ] || fail "缺少 db/llm_gateway.dump（模型网关转储）"
[ -s db/admin-scrypt.txt ] || fail "缺少 db/admin-scrypt.txt（admin 密码哈希）"

if [ ! -d "$DATA_DIR" ] || [ -z "$(ls -A "$DATA_DIR" 2>/dev/null)" ]; then
  if [ -f "$MINIO_TAR" ]; then
    echo "[load] $DATA_DIR 为空，从 $MINIO_TAR 解出全量 MinIO 对象"
    tmp="$(mktemp -d)"
    tar -xf "$MINIO_TAR" -C "$tmp"
    if [ -d "$tmp/data/minio" ]; then
      mkdir -p "$(dirname "$DATA_DIR")"
      rm -rf "$DATA_DIR"
      mv "$tmp/data/minio" "$DATA_DIR"
    elif [ -d "$tmp/minio" ]; then
      mkdir -p "$(dirname "$DATA_DIR")"
      rm -rf "$DATA_DIR"
      mv "$tmp/minio" "$DATA_DIR"
    else
      rm -rf "$tmp"
      fail "$MINIO_TAR 结构不认识（期望 data/minio/... 或 minio/...）"
    fi
    rm -rf "$tmp"
  else
    fail "$DATA_DIR 为空且没有 $MINIO_TAR：没有历史图片/视频对象，拒绝起栈"
  fi
fi

OBJECTS="$(find "$DATA_DIR" -type f 2>/dev/null | wc -l | tr -d '[:space:]')"
if [ "${OBJECTS:-0}" -lt 1500 ]; then
  fail "$DATA_DIR 只有 $OBJECTS 个文件（期望 >=1500）：MinIO 数据不完整，先补齐再起栈"
fi
echo "[load] MinIO 对象文件数=$OBJECTS（含 .minio.sys 元数据）"

# PUBLIC_ORIGIN 必须是浏览器真实访问地址：写 localhost 上公网会让画布写操作静默 403
if [ -n "$PUBLIC_ORIGIN" ] && echo "$PUBLIC_ORIGIN" | grep -qE '^https?://(localhost|127\.0\.0\.1)([:/]|$)'; then
  if [ "${ALLOW_LOCALHOST_ORIGIN:-0}" != "1" ]; then
    fail "PUBLIC_ORIGIN=$PUBLIC_ORIGIN 是本机地址。改成 $ENV_FILE 里的公网地址（如 http://122.51.12.200）后再起栈；仅本机测试可设 ALLOW_LOCALHOST_ORIGIN=1"
  fi
fi

# 磁盘：镜像解包 + 数据卷至少还要 6GB
AVAIL_KB="$(df -Pk . | awk 'NR==2 {print $4}')"
if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -lt 6291456 ]; then
  fail "当前目录可用空间 $((AVAIL_KB / 1024))MB < 6GB，镜像/数据卷会写满磁盘"
fi
echo "[load] 可用磁盘=$((AVAIL_KB / 1024))MB"

# ── 2) CRLF 兜底（Windows 准备的包经常把 *.sh 带成 CRLF）────────────────
if command -v sed >/dev/null 2>&1; then
  for f in db/*.sh db/sql/*.sql scripts/*.sh; do
    [ -f "$f" ] || continue
    if grep -qU $'\r' "$f" 2>/dev/null; then
      sed -i 's/\r$//' "$f"
      echo "[load] 已去掉 CRLF: $f"
    fi
  done
fi

# ── 3) 宿主资源提醒（2 核 2G 的关键前提是 swap）─────────────────────────
if command -v free >/dev/null 2>&1; then
  mem_mb=$(free -m | awk '/^Mem:/{print $2}')
  swap_mb=$(free -m | awk '/^Swap:/{print $2}')
  echo "[load] 宿主内存 ${mem_mb}MB / swap ${swap_mb}MB"
  if [ "${swap_mb:-0}" -lt 1024 ]; then
    echo "[load][警告] swap 不足 1G。6 个 Spring 上下文 + 沙箱在 2G 物理内存上峰值会超限，"
    echo "             强烈建议先加 4G swap（见 README『swap』一节），否则可能被 OOM Killer 杀掉。"
  fi
fi

# ── 4) 载入镜像 ────────────────────────────────────────────────────────
shopt -s nullglob
tars=(offline/images-*.tar)
[ ${#tars[@]} -gt 0 ] || fail "offline/ 下没有 images-*.tar"
for f in "${tars[@]}"; do
  echo "[load] docker load -i $f"
  docker load -i "$f"
done

# ── 5) 起栈 ────────────────────────────────────────────────────────────
# 通过 COMPOSE_PROFILES 控制 sandbox profile：WITH_SANDBOX=0 时显式清空，绝不拉起沙箱服务
if [ "$WITH_SANDBOX" = "1" ]; then
  export COMPOSE_PROFILES="sandbox"
  if [ "$AGENT_SANDBOX_ENABLED" != "true" ]; then
    echo "[load][警告] WITH_SANDBOX=1 但 $ENV_FILE 的 AGENT_SANDBOX_ENABLED=$AGENT_SANDBOX_ENABLED，moon-agent 不会调用沙箱"
  fi
else
  export COMPOSE_PROFILES=""
fi

compose=(docker compose --env-file "$ENV_FILE")
echo "[load] 启动（不构建）"
"${compose[@]}" up -d --no-build
"${compose[@]}" ps

# ── 6) 等后端可用（6 个上下文在 2 核上要 1~3 分钟）──────────────────────
# 不能等 nginx 的 /healthz：它只证明静态站起来了，后端 502 时也会返回 200。
# /api/auth/me 无 token 应为 401（说明 nginx → api-gateway → backend 整条链路已通）。
echo -n "[load] 等待后端 /api/auth/me"
for i in $(seq 1 90); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:${HTTP_PORT}/api/auth/me" || true)"
  if [ "$code" = "401" ] || [ "$code" = "403" ]; then
    echo " -> OK（约 $((i * 2))s）"
    break
  fi
  echo -n "."
  sleep 2
done

echo ''
echo "[load] 完成。访问 http://<目标机公网IP>:${HTTP_PORT}/"
echo "       账号：admin@qq.com / 123456（由 771038325@qq.com 改名，保留其全部数据）"
echo "       库里还有 156 个历史账号，未做任何删除"
echo "       验收：bash scripts/smoke-test.sh"
echo "       日志：docker compose --env-file $ENV_FILE logs -f allinone"
