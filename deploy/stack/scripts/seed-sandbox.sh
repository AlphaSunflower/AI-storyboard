#!/bin/sh
# 沙箱工作区命名卷的首次播种（在 alpine 容器里执行）
#   /seed  只读：部署包里的 data/sandbox-workspaces
#   /w     可写：命名卷 aistoryboard-sandbox-workspaces
# 卷里已有内容时跳过，绝不覆盖运行时数据。
set -eu

if ls /w 2>/dev/null | grep -q .; then
  echo "[seed] sandbox volume already populated, skip"
  exit 0
fi

cp -a /seed/. /w/
chown -R 10001:10001 /w 2>/dev/null || true
echo "[seed] sandbox volume seeded from /seed"
