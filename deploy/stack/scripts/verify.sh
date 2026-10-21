#!/usr/bin/env bash
# 目标机（Linux）验收：健康、入口、admin 登录、迁移数据量、MinIO、沙箱
#
#   bash scripts/verify.sh
#   ENV_FILE=.env.verify bash scripts/verify.sh
#   SKIP_MODEL_CALL=1 bash scripts/verify.sh
set -uo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] || { echo "找不到 $ENV_FILE" >&2; exit 1; }
# 宿主 shell 同名变量优先级高于 --env-file，先按 env 文件覆盖（否则可能连错项目/端口）
while IFS='=' read -r k v; do
  case "$k" in ''|\#*) continue ;; esac
  export "$k=$v"
done < "$ENV_FILE"
val() { grep -E "^$1=" "$ENV_FILE" | head -n1 | cut -d= -f2- | tr -d '\r'; }

PREFIX="$(val STACK_PREFIX)"; PREFIX="${PREFIX:-aistoryboard}"
PORT="$(val FRONTEND_PORT)"; PORT="${PORT:-80}"

# 与 load-and-up.sh 同一口径：PUBLIC_ORIGIN 留空/auto 时自动探测本机内网 IP
ORIGIN="$(val PUBLIC_ORIGIN)"
if [ -z "$ORIGIN" ] || [ "$ORIGIN" = "auto" ]; then
  LAN_IP="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
  [ -n "$LAN_IP" ] || LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [ -n "$LAN_IP" ] || LAN_IP="127.0.0.1"
  if [ "$PORT" = "80" ]; then ORIGIN="http://${LAN_IP}"; else ORIGIN="http://${LAN_IP}:${PORT}"; fi
fi
BASE="${ORIGIN%/}"
PG="${PREFIX}-postgres"; MINIO="${PREFIX}-minio"; GW="${PREFIX}-llm-gateway"
SANDBOX_PORT="$(val SANDBOX_PORT)"; SANDBOX_PORT="${SANDBOX_PORT:-8090}"
SANDBOX_SECRET="$(val SANDBOX_INTERNAL_SECRET)"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@qq.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-123456}"

PASS=0; FAIL=0
ok()   { echo "[PASS] $1 — $2"; PASS=$((PASS+1)); }
bad()  { echo "[FAIL] $1 — $2"; FAIL=$((FAIL+1)); }
check(){ local name="$1"; shift; local out; if out="$("$@" 2>&1)"; then ok "$name" "$out"; else bad "$name" "$out"; fi; }

sql() { docker exec "$PG" psql -tAq -U postgres -d "$1" -c "$2" 2>/dev/null | tr -d '\r'; }

# 1) 容器健康
health() {
  rows="$(docker compose --env-file "$ENV_FILE" ps --format '{{.Service}}|{{.State}}|{{.Health}}' 2>/dev/null)"
  [ -n "$rows" ] || { echo "没有运行中的服务"; return 1; }
  bad_rows="$(printf '%s\n' "$rows" | awk -F'|' '($2!="running" || ($3!="" && $3!="healthy")) {print $1":"$2"/"$3}')"
  [ -z "$bad_rows" ] || { echo "未就绪: $bad_rows"; return 1; }
  echo "$(printf '%s\n' "$rows" | wc -l) 个服务 running/healthy"
}
check '容器健康' health

# 2) 入口
http_200() { code="$(curl -s -o /dev/null -w '%{http_code}' "$1")"; [ "$code" = "200" ] && echo "HTTP 200" || { echo "HTTP $code"; return 1; }; }
check '前端入口 /' http_200 "$BASE/"
check '画布入口 /canvas/' http_200 "$BASE/canvas/"

# 3) 登录（必须直接调用：放进 check 的命令替换里会开子 shell，TOKEN 传不出来）
TOKEN=""
LOGIN_MSG=""
login() {
  resp="$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")"
  TOKEN="$(printf '%s' "$resp" | grep -o '"accessToken":"[^"]*"' | head -n1 | cut -d'"' -f4)"
  if [ -z "$TOKEN" ]; then
    LOGIN_MSG="登录失败: $(printf '%s' "$resp" | head -c 300)"
    return 1
  fi
  LOGIN_MSG="$ADMIN_EMAIL 登录成功"
  return 0
}
if login; then ok "登录 $ADMIN_EMAIL" "$LOGIN_MSG"; else bad "登录 $ADMIN_EMAIL" "$LOGIN_MSG"; fi

# 4) 项目
projects() {
  [ -n "$TOKEN" ] || { echo "未登录"; return 1; }
  body="$(curl -s "$BASE/api/projects" -H "Authorization: Bearer $TOKEN")"
  # 每个项目对象只有一个 userId；场景数组里的 id 不能用来数项目
  n="$(printf '%s' "$body" | grep -o '"userId"' | wc -l | tr -d ' ')"
  echo "$n 个项目"
}
check 'GET /api/projects' projects

# 5) 账号与数据量
account() {
  users="$(sql ai_storyboard 'select count(*) from users')"
  admin="$(sql ai_storyboard 'select email||$$/$$||role||$$/$$||status from users')"
  [ "$users" = "1" ] || { echo "users=$users（期望 1）"; return 1; }
  case "$admin" in admin@qq.com/admin/enabled) echo "$admin" ;; *) echo "账号异常: $admin"; return 1 ;; esac
}
check '账号收敛为 admin@qq.com' account

volume() {
  row="$(sql ai_storyboard "select (select count(*) from projects)||'/'||(select count(*) from conversations)||'/'||(select count(*) from agent_messages)||'/'||(select count(*) from scenes)")"
  # 干净交付包基线为 0/0/0/0；导入数据后只如实展示
  echo "$row"
}
check '当前业务数据量' volume

gwcfg() {
  row="$(sql llm_gateway "select (select count(*) from channel)||'/'||(select count(*) from model_route)||'/'||(select count(*) from gateway_api_key)")"
  ch="$(echo "$row" | cut -d/ -f1)"; rt="$(echo "$row" | cut -d/ -f2)"; k="$(echo "$row" | cut -d/ -f3)"
  [ "${ch:-0}" -ge 12 ] && [ "${rt:-0}" -ge 20 ] && [ "${k:-0}" -ge 10 ] || { echo "渠道/路由/Key=$row"; return 1; }
  echo "channel/model_route/key = $row"
}
check '网关配置' gwcfg

# 6) MinIO 对象
minio_objects() {
  out="$(docker exec "$MINIO" sh -c "mc alias set local http://127.0.0.1:9000 '$(val MINIO_ROOT_USER)' '$(val MINIO_ROOT_PASSWORD)' >/dev/null 2>&1 && mc ls local/storyboard | wc -l" | tr -d '[:space:]')"
  echo "storyboard 顶层对象/前缀 ${out:-0} 个"
}
check 'MinIO 桶可读' minio_objects

# 7) 沙箱
sandbox_exec() {
  resp="$(curl -s -X POST "http://127.0.0.1:${SANDBOX_PORT}/internal/sandbox/deploy-check/execute" \
    -H "X-Internal-Secret: ${SANDBOX_SECRET}" -H 'Content-Type: application/json' \
    -d '{"script":"print(\"sandbox-ok\")","args":[],"timeoutSeconds":30}')"
  case "$resp" in *sandbox-ok*) echo "stdout=sandbox-ok" ;; *) echo "响应: $(printf '%s' "$resp" | head -c 300)"; return 1 ;; esac
}
check '沙箱 Python 执行' sandbox_exec

skill_pkg() {
  n="$(docker exec "${PREFIX}-sandbox-runner" sh -c 'find /opt/moon-skills/ppt-master -type f | wc -l' | tr -d '[:space:]')"
  [ "${n:-0}" -ge 1000 ] || { echo "镜像内 ppt-master 技能包文件数 ${n:-0}"; return 1; }
  echo "ppt-master 技能包 ${n} 个文件"
}
check '沙箱技能包保留' skill_pkg

workspace_writable() {
  vol="${PREFIX}-sandbox-workspaces"
  docker run --rm --user 10001:10001 --entrypoint sh -v "${vol}:/w" alpine \
    -c 'touch /w/.verify && rm /w/.verify' >/dev/null 2>&1 \
    || { echo "卷 ${vol} 不可写"; return 1; }
  echo "卷 ${vol} 可写（10001）"
}
check '沙箱卷可写' workspace_writable

# 8) 真实模型调用（可选）
if [ "${SKIP_MODEL_CALL:-0}" != "1" ]; then
  model_call() {
    key="$(val LLM_GATEWAY_API_KEY_AGENT)"
    code="$(docker exec "$GW" sh -c "curl -s -o /tmp/resp.json -w '%{http_code}' -X POST http://127.0.0.1:8083/v1/chat/completions -H 'Authorization: Bearer $key' -H 'Content-Type: application/json' -d '{\"model\":\"mimo-v2.6-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"只回复两个字：正常\"}],\"max_tokens\":32}'")"
    body="$(docker exec "$GW" sh -c 'head -c 300 /tmp/resp.json')"
    [ "$code" = "200" ] || { echo "HTTP $code — $body"; return 1; }
    echo "HTTP 200 — $body"
  }
  check '真实模型调用' model_call
fi

echo ""
echo "验收结果: $PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ] || exit 1
