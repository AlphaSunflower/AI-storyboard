#!/bin/bash
# 首次初始化：在还原转储之后追加模型种子（幂等）
set -euo pipefail

DIR=/docker-entrypoint-initdb.d

if [ ! -f "$DIR/llm_gateway.dump" ]; then
  echo "[seed-models] 跳过（未提供 llm_gateway 转储）"
  exit 0
fi

psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER:-postgres}" \
     --dbname llm_gateway \
     --file "$DIR/sql/seed-models.sql"
