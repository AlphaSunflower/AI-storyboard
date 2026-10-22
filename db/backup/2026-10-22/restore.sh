#!/usr/bin/env bash
# 重建 ai_storyboard / llm_gateway 两个库的表结构（2026-10-22 备份）
#
# 用法：
#   ./restore.sh                       # 默认 127.0.0.1:5432 postgres
#   ./restore.sh 127.0.0.1 5432 postgres
#   FORCE=1 ./restore.sh               # 目标库已存在时先删除
#
# 依赖：psql / createdb / dropdb 在 PATH

set -euo pipefail

PGHOST="${1:-127.0.0.1}"
PGPORT="${2:-5432}"
PGUSER="${3:-postgres}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

restore_one() {
  local db="$1" file="$2"
  local exists
  exists="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -tAc "select 1 from pg_database where datname='$db'")"
  if [ "$exists" = "1" ]; then
    if [ "${FORCE:-0}" != "1" ]; then
      echo "[skip] $db 已存在（FORCE=1 可重建）"
      return 0
    fi
    echo "[drop] $db"
    dropdb -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" --if-exists "$db"
  fi

  echo "[create] $db"
  createdb -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" "$db"
  echo "[apply ] $file"
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 -q -f "$HERE/$file"
  local count
  count="$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$db" -tAc \
    "select count(*) from information_schema.tables where table_type='BASE TABLE' and table_schema not in ('pg_catalog','information_schema')")"
  echo "[ok    ] $db 表数 = $count"
}

restore_one ai_storyboard ai_storyboard_schema.sql
restore_one llm_gateway   llm_gateway_schema.sql

echo
echo "可选：灌种子数据"
echo "  psql -d ai_storyboard -f ../deploy/stack/db/sql/post-migrate.sql"
echo "  psql -d llm_gateway   -f ../deploy/stack/db/sql/seed-models.sql"
