#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""智能体生成后端化联调：多轮推进 初始需求→剧本确认→分镜方案→HITL 暂停"""
import json, sys, time, urllib.request, urllib.error

BASE = "http://localhost:8082"
TS = str(int(time.time()))[-6:]
EMAIL = f"agenttest_{TS}@test.local"
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

def parse(raw):
    try:
        return json.loads(raw)
    except Exception:
        return {"raw": raw[:300]}

def sse_send(cid, token, content, label, max_wait=240):
    body = json.dumps({"content": content}).encode()
    r = urllib.request.Request(BASE + f"/api/agent/conversations/{cid}/messages/stream", data=body, method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    events = []
    resp = urllib.request.urlopen(r, timeout=200)
    buf = b""
    start = time.time()
    print(f"\n=== {label}: {content[:40]} ===")
    while time.time() - start < max_wait:
        chunk = resp.read(4096)
        if not chunk:
            break
        buf += chunk
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
                if evt == "workflow":
                    d = parse(data)
                    print(f"    <{evt}> {d.get('status','?')} {d.get('title','?')}")
                elif evt == "human_input":
                    print(f"    ★ <{evt}> formToken={parse(data).get('formToken','?')[:12]}...")
                    return events, parse(data)
                elif evt == "error":
                    print(f"    ✗ <{evt}> {data[:200]}")
                    return events, {"error": data}
                else:
                    print(f"    <{evt}> {data[:100]}")
    print(f"    流结束 {time.time()-start:.0f}s，事件 {len(events)}")
    return events, {}

if __name__ == "__main__":
    print(f"== 用户: {EMAIL}")
    st, raw = req("POST", "/api/auth/register", {"email": EMAIL, "password": PASSWORD, "displayName": "联调"})
    if st not in (200, 201):
        print("注册失败:", raw[:300]); sys.exit(1)
    tok = parse(raw)["data"]["accessToken"]
    print(f"[1] register 200, userId={parse(raw)['data']['userId']}")
    st, raw = req("POST", "/api/projects", {"name": "联调项目", "description": "auto", "creationType": "manual", "aspectRatio": "16:9"}, tok)
    pid = parse(raw).get("data", {}).get("id")
    print(f"[2] project {st}, id={pid}")
    st, raw = req("POST", "/api/agent/conversations", {"projectId": pid, "title": "分镜联调"}, tok)
    cid = parse(raw).get("data", {}).get("id")
    print(f"[3] conversation {st}, id={cid}")
    if not cid:
        print("会话创建失败:", raw[:400]); sys.exit(1)

    # 轮 1：初始需求
    ev1, h1 = sse_send(cid, tok, "帮我设计一个分镜方案：一只橘猫傍晚趴在屋顶上看日落，画面温馨", "轮1 初始需求")
    # 轮 2：确认剧本（推进到分镜设计）
    ev2, h2 = sse_send(cid, tok, "剧本不错，我很满意，开始设计分镜吧", "轮2 确认剧本")
    if h2.get("formToken"):
        with open("/tmp/hitl.json", "w", encoding="utf-8") as f:
            json.dump(h2, f, ensure_ascii=False)
        print(f"\n✓ 到达 HITL 暂停点！formToken={h2['formToken'][:12]}... actions={h2.get('actions')}")
        print("  已保存 /tmp/hitl.json（供提交表单步骤使用）")
    else:
        print("\n✗ 未到达 HITL。轮1 事件:", [e[0] for e in ev1], "轮2 事件:", [e[0] for e in ev2])
