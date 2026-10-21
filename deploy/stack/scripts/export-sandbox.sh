#!/bin/sh
# 把沙箱命名卷导出回 ./data/sandbox-workspaces（备份/出包用）
#   /w    命名卷（只读）
#   /out  宿主机 data/sandbox-workspaces
set -eu

rm -rf /out/* /out/.[!.]* 2>/dev/null || true
cp -a /w/. /out/
echo "[export] sandbox volume -> /out"
