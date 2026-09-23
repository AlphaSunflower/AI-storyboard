#!/usr/bin/env bash
# llm_gateway e2e 测试数据：默认造表（e2eadmin + e2e- 临时 key），--clean 删除。约定测完删除。
set -e
cd "$(dirname "$0")"
PSQL="/d/Program Files/PostgreSQL/18/bin/psql"
export PGPASSWORD=123456
PSQL_ARGS="-h localhost -U postgres -d llm_gateway"
ADMIN_ID="e2eadmin000000000000000000000000"   # 32 位，对齐 ASSIGN_UUID
KEY_ID="e2ekey000000000000000000000000000"

if [ "$1" = "--clean" ]; then
  "$PSQL" $PSQL_ARGS \
    -c "DELETE FROM admin_user WHERE username='e2eadmin';" \
    -c "DELETE FROM gateway_api_key WHERE name='e2e-test-key';"
  echo "cleaned: e2eadmin / e2e-test-key"
  exit 0
fi

# 1) scrypt 哈希（与网关 SCryptUtil.scrypt(pwd,16384,8,1) 一致，jar 从 fat jar 抽出）
SCRYPT=$(/e/Development/jdk25/bin/java.exe -cp "seed_tmp/BOOT-INF/lib/scrypt-1.4.0.jar" GenHash.java 'E2Eadmin123!')
[ -n "$SCRYPT" ] || { echo "scrypt 生成失败"; exit 1; }

# 2) e2e key：明文 e2e-<32hex>，存 Base64(SHA-256(明文))（与 KeyServiceImpl.sha256 一致）
KEY="e2e-$(od -An -N16 -tx /dev/urandom | tr -d ' \n')"
KHASH=$(printf %s "$KEY" | openssl dgst -sha256 -binary | openssl base64 -A)

# 3) key 文件双写：python 的 "/tmp" 按盘符解析，bash 的 /tmp 是 MSYS tmp
PYTMP=$(python -c "import os;print(os.path.abspath('/tmp/e2e_key_plain.txt'))")
printf %s "$KEY" > "$PYTMP"
printf %s "$KEY" > /tmp/e2e_key_plain.txt

# 4) 造表（先删幂等）
"$PSQL" $PSQL_ARGS \
  -c "DELETE FROM admin_user WHERE username='e2eadmin'; DELETE FROM gateway_api_key WHERE name='e2e-test-key';" \
  -c "INSERT INTO admin_user (id, username, password_hash, role, status, created_at, updated_at, deleted) VALUES ('$ADMIN_ID', 'e2eadmin', '$SCRYPT', 'admin', 'enabled', now(), now(), FALSE);" \
  -c "INSERT INTO gateway_api_key (id, name, key_hash, enabled, created_at, updated_at, deleted) VALUES ('$KEY_ID', 'e2e-test-key', '$KHASH', TRUE, now(), now(), FALSE);"
echo "seeded: admin=e2eadmin(E2Eadmin123!), key已写入 $PYTMP（len=${#KEY}）"
