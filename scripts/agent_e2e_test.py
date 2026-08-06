#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""智能体生成后端化联调：API 层端到端验证（分镜链路）"""
import json, sys, time, urllib.request, urllib.error, re

BASE = "http://localhost:8082"
TS = str(int(time.time()))[-6:]
EMAIL = f"agenttest_{TS}@test.local"
PASSWORD = "password123"

def req(method, path, body=None, token=None, timeout=30, stream=False):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        resp = urllib.request.urlopen(r, timeout=timeout)
        raw = resp.read().decode("utf-8", "replace")
        return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)

def parse(raw):
    try:
        return json.loads(raw)
    except Exception:
        return {"raw": raw[:300]}

print(f"== 测试用户: {EMAIL}")
# 1. 注册
st, raw = req("POST", "/api/auth/register", {"email": EMAIL, "password": PASSWORD, "displayName": "联调测试"})
print(f"[1] register -> {st}")
if st not in (200, 201):
    print("   失败:", raw[:300]); sys.exit(1)
tok = parse(raw)["data"]["accessToken"]
uid = parse(raw)["data"]["userId"]
print(f"    userId={uid} token={'ok' if tok else 'MISSING'}")
# 2. 创建项目
st, raw = req("POST", "/api/projects", {"name": "联调测试项目", "description": "auto", "creationType": "manual", "aspectRatio": "16:9"}, tok)
print(f"[2] create project -> {st}")
pj = parse(raw).get("data", {})
pid = pj.get("id")
print(f"    projectId={pid}")
# 3. 创建会话
st, raw = req("POST", "/api/agent/conversations", {"projectId": pid, "title": "联调会话"}, tok)
print(f"[3] create conversation -> {st}")
conv = parse(raw).get("data", {})
cid = conv.get("id")
print(f"    conversationId={cid}")
if not cid:
    print("    FAILED:", raw[:400]); sys.exit(1)
# 4. SSE 发消息（分镜意图）
print(f"[4] SSE send message (分镜意图)...")
body = json.dumps({"content": "帮我设计一个分镜方案：一只橘猫傍晚趴在屋顶上看日落，画面温馨"}).encode()
r = urllib.request.Request(BASE + f"/api/agent/conversations/{cid}/messages/stream", data=body, method="POST")
r.add_header("Content-Type", "application/json")
r.add_header("Authorization", "Bearer " + tok)
try:
    resp = urllib.request.urlopen(r, timeout=30)
    events = []
    start = time.time()
    buf = b""
    while time.time() - start < 100:
        chunk = resp.read(4096)
        if not chunk:
            break
        buf += chunk
        # 按行切分 SSE 块
        while b"\n\n" in buf:
            block, buf = buf.split(b"\n\n", 1)
            evt, data = "", ""
            for line in block.decode("utf-8", "replace").split("\n"):
                if line.startswith("event:"):
                    evt = line[6:].strip()
                elif line.startswith("data:"):
                    data = line[5:].strip()
            if evt:
                events.append((evt, data))
                print(f"    <{evt}> {data[:150]}")
            if evt == "human_input":
                print("    ★ 到达 HITL 暂停点")
                form = json.loads(data) if data else {}
                with open("/tmp/hitl.json", "w", encoding="utf-8") as f:
                    json.dump({"formToken": form.get("formToken"), "taskId": form.get("taskId"),
                               "actions": form.get("actions"), "formContent": (form.get("formContent") or "")[:200]}, f, ensure_ascii=False)
                print("    已保存 HITL 信息到 /tmp/hitl.json")
                sys.exit(0)
    print(f"    流结束（{time.time()-start:.0f}s），事件数={len(events)}")
    if not events:
        print("    无事件！检查后端日志")
except Exception as e:
    print("    SSE 异常:", e)
