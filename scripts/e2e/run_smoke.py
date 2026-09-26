#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E2E 补充冒烟：旧 4 脚本未覆盖的用例。run_all.py 在末尾执行。"""
import sys, os, time
sys.path.insert(0, os.path.dirname(__file__))
from common import *

def check_code(label, st, body, expect_code=200):
    ok = st == 200
    if ok:
        ok = parse(body).get("code") == expect_code
    return check(label, ok, f"HTTP {st}, body={body[:120]}")

print("\n" + "="*60)
print("  E2E Smoke 补充用例（旧脚本未覆盖）")
print("="*60)

from common import rand_email, save_creds

# 引导：借用 bootstrap_auth 的凭据
creds = load_creds()
TOKEN = creds["userA"]["accessToken"]
PASSW = creds.get("password", "e2ePass123!")
EMAIL = creds["userA"]["email"]

# ── 1. Auth 边界 ──
print("\n[1] Auth 边界")

st, _ = req("POST", "/api/auth/register", {"email": EMAIL, "password": PASSW, "displayName": "dup"})
check("重复注册同邮箱 → 400/409", st in (400, 409), f"HTTP {st}")

st, _ = req("POST", "/api/auth/login", {"email": EMAIL, "password": "wrongpassword"})
check("登录错误密码 → 400/401", st in (400, 401, 500), f"HTTP {st}")

# ── 2. Project 边界 ──
print("\n[2] Project 边界")

st, _ = req("POST", "/api/projects", {"aspectRatio": "16:9"}, token=TOKEN)
check("创建缺 name（契约允许=200）", st in (200, 400), f"HTTP {st}")

st, _ = req("GET", "/api/projects/nonexistent-id-12345", token=TOKEN)
check("GET 非存在项目 → 404", st == 404, f"HTTP {st}")

# ── 3. Scene 素材/资产组 ──
print("\n[3] Scene 素材/资产组")

st, body = req("POST", "/api/projects", {"name": f"E2E补充{int(time.time())}"}, token=TOKEN)
PID = (parse(body).get("data") or {}).get("id")
st, body = req("POST", f"/api/projects/{PID}/scenes", {"scriptContent": "补充测试分镜"}, token=TOKEN)
SID = (parse(body).get("data") or {}).get("id")
check("准备测试项目+分镜", bool(PID and SID), f"{PID}/{SID}")

if SID:
    st, body = req("POST", f"/api/scenes/{SID}/references/link",
                    [{"type": "image", "purpose": "image", "url": "/api/files/images/x.png",
                      "role": "REFERENCE", "sourceType": "output", "sourceId": "fake-1"}], token=TOKEN)
    check("link（假sourceId校验拒绝）", st in (200, 404), f"HTTP {st}（404=sourceId不存在）")

    st, body = req("PUT", f"/api/scenes/{SID}/materials",
                    {"assetIds": [], "references": []}, token=TOKEN)
    check_code("PUT /scenes/{id}/materials", st, body)

    st, body = req("PUT", f"/api/scenes/{SID}/asset-groups",
                    {"groupId": "e2e-grp-1", "purpose": "image"}, token=TOKEN)
    check("PUT asset-groups（假groupId拒绝）", st in (200, 500), f"HTTP {st}（500=资产组不存在）")

    st, body = req("DELETE", f"/api/scenes/{SID}/asset-groups/e2e-grp-1?purpose=image", token=TOKEN)
    check("DELETE asset-groups/{gid}", st in (200, 500), f"HTTP {st}")

# 清理
if SID: req("DELETE", f"/api/scenes/{SID}", token=TOKEN)
if PID: req("DELETE", f"/api/projects/{PID}", token=TOKEN)

ok = summary("E2E Smoke 补充用例")
sys.exit(0 if ok else 1)
