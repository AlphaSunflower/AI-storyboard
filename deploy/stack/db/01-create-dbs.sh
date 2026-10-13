#!/bin/bash
# 首次初始化：创建业务库（还原 dumps 之前必须先建库）
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" --dbname postgres <<-EOSQL
SELECT 'CREATE DATABASE ai_storyboard'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ai_storyboard')\gexec
SELECT 'CREATE DATABASE llm_gateway'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'llm_gateway')\gexec
EOSQL

echo "[init] 数据库就绪: ai_storyboard / llm_gateway"
