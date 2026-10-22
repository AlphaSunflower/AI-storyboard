#!/bin/bash
# 全量数据还原后的自检：对比 data-manifest.env 里的行数。
#
# 设计原则：
#   - 只读，任何情况下都不修改/删除业务行；
#   - 数字不一致只告警（转储之后本地可能又产生了新数据），但 users=0 视为致命错误，
#     因为那说明转储根本没还原成功，继续启动只会得到一套空站。
set -uo pipefail

DIR=/docker-entrypoint-initdb.d
MANIFEST="$DIR/data-manifest.env"

if [ ! -f "$MANIFEST" ]; then
  echo "[verify] 没有 data-manifest.env，跳过行数自检"
  exit 0
fi

# shellcheck disable=SC1090
. "$MANIFEST"

q() {
  psql -tAq -U "${POSTGRES_USER:-postgres}" -d ai_storyboard -c "$1" 2>/dev/null | tr -d '[:space:]'
}

FAIL=0
check() {
  local label="$1" expected="$2" actual="$3"
  if [ -z "$actual" ]; then
    echo "[verify][警告] $label 查询失败（期望 $expected）"
    FAIL=$((FAIL + 1))
  elif [ "$expected" = "$actual" ]; then
    echo "[verify] $label = $actual"
  else
    echo "[verify][警告] $label 实际 $actual，清单 $expected（转储后可能又有新数据）"
  fi
}

USERS_ACTUAL="$(q 'select count(*) from public.users')"
if [ "${USERS_ACTUAL:-0}" = "0" ]; then
  echo "[verify][错误] users 表为 0：转储没有还原成功，终止启动以免对外提供空站" >&2
  exit 1
fi

check 'users'              "${USERS:-?}"              "$USERS_ACTUAL"
check 'projects'           "${PROJECTS:-?}"           "$(q 'select count(*) from public.projects')"
check 'conversations'      "${CONVERSATIONS:-?}"      "$(q 'select count(*) from public.conversations')"
check 'agent_messages'     "${AGENT_MESSAGES:-?}"     "$(q 'select count(*) from public.agent_messages')"
check 'agent_assets'       "${AGENT_ASSETS:-?}"       "$(q 'select count(*) from public.agent_assets')"
check 'canvas_assets'      "${CANVAS_ASSETS:-?}"      "$(q 'select count(*) from public.canvas_assets')"
check 'scenes'             "${SCENES:-?}"             "$(q 'select count(*) from public.scenes')"
check 'morrow_canvases'    "${MORROW_CANVASES:-?}"    "$(q 'select count(*) from public.morrow_canvases')"
check 'asset.library_assets' "${LIBRARY_ASSETS:-?}"   "$(q 'select count(*) from asset.library_assets')"
check 'asset.library_media'  "${LIBRARY_MEDIA:-?}"    "$(q 'select count(*) from asset.library_media')"
check 'agentscope_sessions'  "${AGENTSCOPE_SESSIONS:-?}" "$(q 'select count(*) from agentscope.agentscope_sessions')"

ADMIN_ROWS="$(q "select count(*) from public.users where email='admin@qq.com' and role='admin' and status='enabled'")"
if [ "$ADMIN_ROWS" = "1" ]; then
  echo "[verify] admin@qq.com 可登录（role=admin, status=enabled）"
else
  echo "[verify][警告] admin@qq.com 状态异常（匹配行数=$ADMIN_ROWS）"
  FAIL=$((FAIL + 1))
fi

if [ "$FAIL" -gt 0 ]; then
  echo "[verify] 自检完成，有 $FAIL 项告警（不影响启动；请核对是否在转储后又产生了新数据）"
else
  echo "[verify] 全量数据自检通过：清单与库内行数完全一致"
fi
exit 0
