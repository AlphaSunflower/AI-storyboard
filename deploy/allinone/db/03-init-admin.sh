#!/bin/bash
# 全量数据模式：恢复完成后只做一件事——确保 admin@qq.com 可登录。
#
# 与旧版 03-post-migrate.sh 的关键区别：旧版会把 771038325@qq.com 改名后**删除其它所有账号
# 及其项目/会话**；这里只改这一个账号（或新建），其余数据一行不删。
set -euo pipefail

DIR=/docker-entrypoint-initdb.d

if [ ! -f "$DIR/ai_storyboard.dump" ] || [ ! -f "$DIR/admin-scrypt.txt" ]; then
  echo "[init-admin] 跳过（缺少 ai_storyboard.dump 或 admin-scrypt.txt）"
  exit 0
fi

echo "[init-admin] 确保 admin@qq.com 可登录（全量数据，不做任何删除）"
psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER:-postgres}" \
     --dbname ai_storyboard \
     --set admin_hash="$(cat "$DIR/admin-scrypt.txt")" \
     --file "$DIR/sql/init-admin.sql"

echo "[init-admin] 完成"
