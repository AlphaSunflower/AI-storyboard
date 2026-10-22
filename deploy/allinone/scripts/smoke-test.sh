#!/usr/bin/env bash
# 目标机（Linux）验收：入口、注册关闭、管理员登录、全量数据行数、MinIO 对象、6 个服务端口、
# 模型网关（含真实模型调用）、Agent 沙箱、资源占用。
#
#   bash scripts/smoke-test.sh
#   SKIP_MODEL_CALL=1 bash scripts/smoke-test.sh     # 不花模型额度
#   ENV_FILE=.env.test bash scripts/smoke-test.sh    # 本机测试栈
set -uo pipefail
cd "$(dirname "$0")/.."

PASS=0
FAIL=0
OK_MSG='\033[32m  [OK]  \033[0m'
NO_MSG='\033[31m  [FAIL]\033[0m'

env_get() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2- ; }
manifest_get() { grep -E "^$1=" db/data-manifest.env 2>/dev/null | head -1 | cut -d= -f2- ; }

ENV_FILE="${ENV_FILE:-.env}"
HTTP_PORT="$(env_get HTTP_PORT)"; HTTP_PORT="${HTTP_PORT:-80}"
GATEWAY_PORT="$(env_get GATEWAY_PORT)"; GATEWAY_PORT="${GATEWAY_PORT:-8083}"
PREFIX="$(env_get STACK_PREFIX)"; PREFIX="${PREFIX:-ai-cloud}"
ADMIN_KEY="$(env_get LLM_GATEWAY_API_KEY_MAIN)"
AGENT_KEY="$(env_get LLM_GATEWAY_API_KEY_AGENT)"
SANDBOX_ENABLED="$(env_get AGENT_SANDBOX_ENABLED)"; SANDBOX_ENABLED="${SANDBOX_ENABLED:-true}"
SANDBOX_SECRET="$(env_get SANDBOX_INTERNAL_SECRET)"
DATA_DIR="$(env_get MINIO_DATA_DIR)"; DATA_DIR="${DATA_DIR:-./data/minio}"
: "${PUBLIC_ORIGIN:=$(env_get PUBLIC_ORIGIN)}"
PUBLIC_ORIGIN="${PUBLIC_ORIGIN:-http://localhost}"
BASE="http://127.0.0.1:${HTTP_PORT}"

EXPECTED_USERS="$(manifest_get USERS)"
EXPECTED_PROJECTS="$(manifest_get PROJECTS)"
EXPECTED_CONVERSATIONS="$(manifest_get CONVERSATIONS)"
EXPECTED_MESSAGES="$(manifest_get AGENT_MESSAGES)"
EXPECTED_ASSETS="$(manifest_get AGENT_ASSETS)"
EXPECTED_MINIO_FILES="$(manifest_get MINIO_FILES)"

# 沙箱开关以容器里的实际值为准（.env 里的值可能被 up 脚本的命令行覆盖）
container_env() {
  docker inspect "$1" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E "^$2=" | head -1 | cut -d= -f2-
}
_effective="$(container_env "${PREFIX}-allinone" AGENT_SANDBOX_ENABLED)"
[ -n "$_effective" ] && SANDBOX_ENABLED="$_effective"

check() {
  local name="$1"; shift
  local detail
  if detail="$("$@" 2>&1)"; then
    printf "$OK_MSG %-46s %s\n" "$name" "$detail"
    PASS=$((PASS + 1))
  else
    printf "$NO_MSG %-46s %s\n" "$name" "$detail"
    FAIL=$((FAIL + 1))
  fi
}

db_count() {
  docker exec "${PREFIX}-postgres" psql -U postgres -d ai_storyboard -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

# ── 各检查项 ────────────────────────────────────────────────────────────
c_healthz() {
  local code; code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$BASE/healthz")"
  [ "$code" = "200" ] || { echo "HTTP $code"; return 1; }
  echo "HTTP 200"
}
c_index() {
  local body; body="$(curl -s --max-time 20 "$BASE/")"
  echo "$body" | grep -q 'id="root"' || { echo "首页里没有 id=\"root\""; return 1; }
  echo "HTTP 200, ${#body} bytes"
}
c_canvas_site() {
  local code; code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$BASE/canvas/")"
  [ "$code" = "200" ] || { echo "HTTP $code"; return 1; }
  echo "HTTP 200"
}
c_register_closed() {
  local out code body
  out="$(curl -s -w '\n%{http_code}' --max-time 20 -X POST "$BASE/api/auth/register" \
        -H 'Content-Type: application/json' \
        -d '{"email":"nobody@qq.com","password":"123456","displayName":"nobody"}')"
  code="$(echo "$out" | tail -n1)"; body="$(echo "$out" | head -n-1)"
  [ "$code" = "403" ] || { echo "HTTP $code（期望 403）$body"; return 1; }
  echo "$body" | grep -q '40301' || { echo "响应体没有 40301: $body"; return 1; }
  echo "HTTP 403 + code 40301"
}
c_login_admin() {
  local out body
  out="$(curl -s --max-time 30 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        -d '{"email":"admin@qq.com","password":"123456"}')"
  echo "$out" | grep -q '"accessToken"' || { echo "登录失败: $out"; return 1; }
  body="$(echo "$out" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
  echo "$body" > /tmp/ai-cloud-admin-token
  echo "displayName=$(echo "$out" | sed -n 's/.*"displayName":"\([^"]*\)".*/\1/p')"
}
c_projects() {
  local token; token="$(cat /tmp/ai-cloud-admin-token 2>/dev/null)"
  [ -n "$token" ] || { echo "admin 未登录"; return 1; }
  local code
  code="$(curl -s -o /tmp/ai-cloud-projects.json -w '%{http_code}' --max-time 30 \
        "$BASE/api/projects" -H "Authorization: Bearer $token")"
  [ "$code" = "200" ] || { echo "HTTP $code"; return 1; }
  local n; n="$(db_count "select count(*) from projects p join users u on u.id=p.user_id where u.email='admin@qq.com'")"
  [ "${n:-0}" -gt 0 ] || { echo "admin 名下项目数为 0"; return 1; }
  echo "HTTP 200, admin 项目数=$n"
}
# 全量数据是本包的核心承诺：逐表对比 db/data-manifest.env
c_data_counts() {
  local table actual expected bad=""
  while IFS='|' read -r label sql expected; do
    [ -n "$label" ] || continue
    actual="$(db_count "$sql")"
    if [ "$actual" != "$expected" ]; then
      bad="$bad ${label}:${actual:-?}/${expected}"
    fi
  done <<EOF
users|select count(*) from public.users|$EXPECTED_USERS
projects|select count(*) from public.projects|$EXPECTED_PROJECTS
conversations|select count(*) from public.conversations|$EXPECTED_CONVERSATIONS
agent_messages|select count(*) from public.agent_messages|$EXPECTED_MESSAGES
agent_assets|select count(*) from public.agent_assets|$EXPECTED_ASSETS
EOF
  [ -z "$bad" ] || { echo "与清单不一致:$bad"; return 1; }
  echo "users=$EXPECTED_USERS projects=$EXPECTED_PROJECTS conversations=$EXPECTED_CONVERSATIONS assets=$EXPECTED_ASSETS"
}
c_accounts_not_pruned() {
  local n; n="$(db_count 'select count(*) from public.users')"
  [ "${n:-0}" -ge 100 ] || { echo "只有 $n 个账号（全量包应 >=100）"; return 1; }
  echo "$n 个账号（未做收敛）"
}
c_minio_objects() {
  [ -d "$DATA_DIR/storyboard" ] || { echo "缺少 $DATA_DIR/storyboard 桶"; return 1; }
  local n; n="$(find "$DATA_DIR" -type f 2>/dev/null | wc -l | tr -d '[:space:]')"
  [ "${n:-0}" -ge 1500 ] || { echo "只有 $n 个对象文件（期望 >=1500）"; return 1; }
  echo "$n 个对象文件（清单 $EXPECTED_MINIO_FILES）"
}
c_route() {
  local path="$1" token; token="$(cat /tmp/ai-cloud-admin-token 2>/dev/null)"
  [ -n "$token" ] || { echo "admin 未登录"; return 1; }
  local code; code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$BASE$path" -H "Authorization: Bearer $token")"
  [ "$code" = "200" ] || { echo "HTTP $code"; return 1; }
  echo "HTTP 200"
}
c_canvas_write() {
  local token project list canvas title code origin
  token="$(cat /tmp/ai-cloud-admin-token 2>/dev/null)"
  [ -n "$token" ] || { echo "admin 未登录"; return 1; }
  origin="${PUBLIC_ORIGIN:-http://localhost}"
  # 只取 admin 名下的画布：全量数据里第一个画布可能属于别的账号
  project="$(db_count "select c.project_id from public.morrow_canvases c \
      join public.projects p on p.id=c.project_id join public.users u on u.id=p.user_id \
      where u.email='admin@qq.com' and c.project_id is not null limit 1")"
  [ -n "$project" ] || { echo "admin 名下没有可测画布，跳过"; return 0; }
  list="$(curl -s --max-time 20 "$BASE/api/canvas/projects/$project/canvases" -H "Authorization: Bearer $token")"
  canvas="$(echo "$list" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)"
  title="$(echo "$list" | sed -n 's/.*"title":"\([^"]*\)".*/\1/p' | head -1)"
  [ -n "$canvas" ] || { echo "该项目没有画布，跳过（列表接口已 200）"; return 0; }
  code="$(curl -s -o /tmp/ai-cloud-cors.txt -w '%{http_code}' --max-time 20 -X PATCH \
      "$BASE/api/canvas/canvases/$canvas" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -H "Origin: $origin" \
      -d "{\"title\":\"${title:-canvas}\"}")"
  if [ "$code" != "200" ]; then
    echo "HTTP $code（Origin: $origin）body=$(head -c 100 /tmp/ai-cloud-cors.txt) → 把 .env 的 PUBLIC_ORIGIN 改成实际访问地址"
    return 1
  fi
  echo "PATCH 200（Origin: $origin）"
}
c_ports() {
  local open="" port
  for port in 8080 8082 8083 8084 8085 9090; do
    if docker exec "${PREFIX}-allinone" bash -c "exec 3<>/dev/tcp/127.0.0.1/$port" 2>/dev/null; then
      open="$open $port"
    fi
  done
  [ "$(echo $open | wc -w | tr -d ' ')" = "6" ] || { echo "只监听了:$open"; return 1; }
  echo "全部在监听:$open"
}
c_gateway_models() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "http://127.0.0.1:${GATEWAY_PORT}/v1/models" \
        -H "Authorization: Bearer $ADMIN_KEY")"
  [ "$code" = "200" ] || { echo "HTTP $code"; return 1; }
  echo "HTTP 200"
}
c_model_call() {
  [ "${SKIP_MODEL_CALL:-0}" = "1" ] && { echo "已跳过（SKIP_MODEL_CALL=1）"; return 0; }
  local payload='{"model":"mimo-v2.6-pro","messages":[{"role":"user","content":"用一句话确认链路正常"}],"max_tokens":64}'
  printf '%s' "$payload" | docker exec -i "${PREFIX}-allinone" sh -c 'cat > /tmp/req.json'
  local code
  code="$(docker exec "${PREFIX}-allinone" sh -c \
    "curl -s -o /tmp/resp.json -w '%{http_code}' --max-time 150 -X POST http://127.0.0.1:8083/v1/chat/completions \
     -H 'Authorization: Bearer ${AGENT_KEY}' -H 'Content-Type: application/json' -d @/tmp/req.json")"
  local body; body="$(docker exec "${PREFIX}-allinone" head -c 200 /tmp/resp.json 2>/dev/null)"
  [ "$code" = "200" ] || { echo "HTTP $code — $body"; return 1; }
  echo "$body"
}
c_sandbox() {
  if [ "$SANDBOX_ENABLED" != "true" ]; then
    echo "沙箱未启用（AGENT_SANDBOX_ENABLED=$SANDBOX_ENABLED），跳过"
    return 0
  fi
  local health write exec_out
  health="$(docker exec "${PREFIX}-allinone" curl -s --max-time 15 http://sandbox-runner:8090/actuator/health)"
  echo "$health" | grep -q '"status":"UP"' || { echo "沙箱健康检查失败: $health"; return 1; }
  write="$(docker exec "${PREFIX}-allinone" curl -s --max-time 20 -X POST \
    "http://sandbox-runner:8090/internal/sandbox/smoke/files/write" \
    -H "X-Internal-Secret: ${SANDBOX_SECRET}" -H 'Content-Type: application/json' \
    -d '{"path":"work/smoke.txt","content":"ok"}')"
  echo "$write" | grep -q '"sizeBytes"' || { echo "写文件失败: $write"; return 1; }
  exec_out="$(docker exec "${PREFIX}-allinone" sh -c \
    "printf '%s' '{\"script\":\"print(6*7)\",\"timeoutSeconds\":60}' > /tmp/ex.json; \
     curl -s --max-time 90 -X POST http://sandbox-runner:8090/internal/sandbox/smoke/execute \
     -H 'X-Internal-Secret: ${SANDBOX_SECRET}' -H 'Content-Type: application/json' -d @/tmp/ex.json")"
  echo "$exec_out" | grep -q '"exitCode":0' || { echo "执行脚本失败: $exec_out"; return 1; }
  echo "健康 + 写文件 + python 执行 OK"
}

echo "验收目标: $BASE（容器前缀=$PREFIX，数据目录=$DATA_DIR）"
echo ''
echo '[1] 入口与静态站'
check 'GET /healthz' c_healthz
check 'GET / 返回主站 HTML' c_index
check 'GET /canvas/ 返回画布站' c_canvas_site

echo ''
echo '[2] 注册已关闭（入口 nginx 拦截，后端代码未改）'
check 'POST /api/auth/register = 403 + 40301' c_register_closed

echo ''
echo '[3] 管理员与全量数据'
check 'admin@qq.com 登录（771038325 改名而来）' c_login_admin
check 'GET /api/projects（admin token）' c_projects
check '账号未被收敛（>=100）' c_accounts_not_pruned
check '全量行数与 data-manifest.env 一致' c_data_counts
check 'MinIO 全量对象在位' c_minio_objects

echo ''
echo '[4] 各服务路由（6 个服务在同一个 JVM 里）'
check 'GET /api/agent/**（moon-agent）' c_route '/api/agent/conversations?projectId=00000000-0000-0000-0000-000000000000'
check 'GET /api/library/**（asset-service）' c_route '/api/library/assets?page=1&size=1'
check 'GET /api/canvas/health（canvas）' c_route '/api/canvas/health'
check '画布写操作 PATCH（带 Origin，CORS 白名单）' c_canvas_write
check '6 个服务端口' c_ports

echo ''
echo '[5] 模型网关与真实模型调用'
check "GET 127.0.0.1:$GATEWAY_PORT/v1/models" c_gateway_models
check '真实模型调用（网关 → 上游厂商）' c_model_call

echo ''
echo '[6] Agent 沙箱（独立容器，默认开启）'
check '沙箱链路' c_sandbox

echo ''
echo '[7] 资源占用（2 核 2G 预算）'
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' | grep "^${PREFIX}-" || true
docker exec "${PREFIX}-allinone" grep -E 'VmRSS|VmHWM|VmSwap' /proc/1/status 2>/dev/null || true

echo ''
echo "结果: $PASS 项通过, $FAIL 项失败"
[ "$FAIL" -eq 0 ] || exit 1
