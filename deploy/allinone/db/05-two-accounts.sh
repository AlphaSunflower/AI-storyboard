#!/bin/bash
# 首次初始化：把账号收敛成「恰好两个」——admin@qq.com + 第二个业务账号
# 第二个账号默认 test@qq.com / 123456（与 admin 相同口令，密码哈希复用 admin-scrypt.txt）；
# 要换账号或口令就改 .env 的 SECOND_ACCOUNT_* 并重新初始化数据卷，或直接跑 sql/two-accounts.sql。
set -euo pipefail

DIR=/docker-entrypoint-initdb.d

if [ ! -f "$DIR/ai_storyboard.dump" ] || [ ! -f "$DIR/admin-scrypt.txt" ]; then
  echo "[two-accounts] 跳过（无转储或未生成密码哈希）"
  exit 0
fi

second_email="${SECOND_ACCOUNT_EMAIL:-test@qq.com}"
second_name="${SECOND_ACCOUNT_NAME:-测试用户}"
second_hash="${SECOND_ACCOUNT_HASH:-$(cat "$DIR/admin-scrypt.txt")}"

echo "[two-accounts] 收敛为两个账号: admin@qq.com + ${second_email}"

psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER:-postgres}" \
     --dbname ai_storyboard \
     --set admin_hash="$(cat "$DIR/admin-scrypt.txt")" \
     --set second_email="$second_email" \
     --set second_name="$second_name" \
     --set second_hash="$second_hash" \
     --file "$DIR/sql/two-accounts.sql"
