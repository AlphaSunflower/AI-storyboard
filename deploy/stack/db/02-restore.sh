#!/bin/bash
# 首次初始化：把两个 pg_dump(-Fc) 还原进容器内新库
set -eu

DIR=/docker-entrypoint-initdb.d

restore_one() {
  # 注意：不能用 `local db="$1" log="/tmp/${db}.log"` —— 同一条 local 里的变量会先展开后赋值，
  # 在 set -u 下会直接报 unbound variable（曾导致还原整段被跳过）。
  local db file log tables
  db="$1"
  file="$2"
  log="/tmp/restore-${db}.log"
  if [ ! -f "$DIR/$file" ]; then
    echo "[restore] 跳过 $file（转储不存在，保留空库）"
    return 0
  fi
  echo "[restore] $file -> $db"
  if pg_restore --no-owner --role="${POSTGRES_USER:-postgres}" --dbname="$db" "$DIR/$file" >"$log" 2>&1; then
    echo "[restore] $db 还原完成"
  else
    echo "[restore] $db 还原返回非零（通常是已存在对象告警），末尾日志："
    tail -n 8 "$log"
  fi
  tables=$(psql -tAq -U "${POSTGRES_USER:-postgres}" -d "$db" -c \
    "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema')" | tr -d '[:space:]')
  echo "[restore] $db 现有表数量: ${tables:-0}"
  if [ "${tables:-0}" = "0" ]; then
    echo "[restore][错误] $db 没有还原出任何表，请检查转储文件" >&2
    return 1
  fi
}

restore_one ai_storyboard ai_storyboard.dump
restore_one llm_gateway llm_gateway.dump
