#!/bin/bash
# 首次初始化：账号收敛为 admin@qq.com，并清理其它测试账号的数据
set -euo pipefail

DIR=/docker-entrypoint-initdb.d

if [ ! -f "$DIR/admin-scrypt.txt" ] || [ ! -f "$DIR/ai_storyboard.dump" ]; then
  echo "[post-migrate] 跳过（无转储或未生成 admin 密码哈希）"
  exit 0
fi

psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER:-postgres}" \
     --dbname ai_storyboard \
     --set admin_hash="$(cat "$DIR/admin-scrypt.txt")" \
     --file "$DIR/sql/post-migrate.sql"
