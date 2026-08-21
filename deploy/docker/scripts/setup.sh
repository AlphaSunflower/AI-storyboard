#!/usr/bin/env bash
# deploy/docker/scripts/setup.sh
# 首次部署：下载 vosk-model-cn-0.22（约 1.3GB）到 data/vosk/models/
set -euo pipefail
cd "$(dirname "$0")/.."

command -v curl >/dev/null || { echo "[错误] 需要 curl"; exit 1; }
command -v unzip >/dev/null || { echo "[错误] 需要 unzip"; exit 1; }

MODEL_NAME=vosk-model-cn-0.22
MODEL_DIR="data/vosk/models/$MODEL_NAME"
URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"

if [ -d "$MODEL_DIR" ] && [ -f "$MODEL_DIR/conf/model.conf" ]; then
  echo "[skip] 模型已存在: $MODEL_DIR"
  exit 0
fi

mkdir -p data/vosk/models
echo "[下载] $URL"
curl -L -o data/vosk/models/${MODEL_NAME}.zip "$URL"
echo "[解压]"
unzip -q -o data/vosk/models/${MODEL_NAME}.zip -d data/vosk/models
rm -f data/vosk/models/${MODEL_NAME}.zip

if [ -d "$MODEL_DIR" ] && [ -f "$MODEL_DIR/conf/model.conf" ]; then
  echo "[OK] 模型就绪: $MODEL_DIR"
else
  echo "[错误] 模型目录结构异常，请检查" >&2
  exit 1
fi
