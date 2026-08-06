#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提交 HITL 表单（agree）→ 验证后端分发写 scenes"""
import json, sys, time, urllib.request, urllib.error, os

BASE = "http://localhost:8082"
# 用法: python scripts/agent_form_submit.py <email> <conversationId>
EMAIL = sys.argv[1] if len(sys.argv) > 1 else "agenttest_918573@test.local"
CID = sys.argv[2] if len(sys.argv) > 2 else "625b19cbbd8c5fcb31ab7cac76085117"
PASSWORD = "password123"

def req(method, path, body=None, token=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        resp = urllib.request.urlopen(r, timeout=timeout)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)

# 1. 登录拿 token
st, raw = req("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD})
tok = json.loads(raw)["data"]["accessToken"]
print(f"[1] login -> {st}, token ok")

# 2. 读 HITL 信息
hitl_path = "/tmp/hitl.json"
if not os.path.exists(hitl_path):
    # MSYS /tmp vs Windows 路径
    alt = os.path.join(os.environ.get("TEMP", "C:\\Temp"), "hitl.json")
    if os.path.exists(alt):
        hitl_path = alt
hitl = json.load(open(hitl_path, encoding="utf-8"))
print(f"[2] hitl: formToken={hitl['formToken'][:12]}... taskId={str(hitl.get('taskId'))[:12]}...")

# 3. 提交表单（agree）
body = {"formToken": hitl["formToken"], "taskId": hitl.get("taskId"), "action": "agree"}
print(f"[3] POST form/submit action=agree ...")
data = json.dumps(body).encode()
r = urllib.request.Request(BASE + f"/api/agent/conversations/{CID}/form/submit", data=data, method="POST")
r.add_header("Content-Type", "application/json")
r.add_header("Authorization", "Bearer " + tok)
try:
    resp = urllib.request.urlopen(r, timeout=120)
    buf = b""
    start = time.time()
    events = []
    while time.time() - start < 90:
        chunk = resp.read(4096)
        if not chunk:
            break
        buf += chunk
        while b"\n\n" in buf:
            block, buf = buf.split(b"\n\n", 1)
            evt, d = "", ""
            for line in block.decode("utf-8", "replace").split("\n"):
                if line.startswith("event:"):
                    evt = line[6:].strip()
                elif line.startswith("data:"):
                    d = line[5:].strip()
            if evt:
                events.append((evt, d))
                print(f"    <{evt}> {d[:160]}")
    print(f"    流结束 {time.time()-start:.0f}s, 事件 {len(events)}")
except Exception as e:
    print("    提交异常:", e)
