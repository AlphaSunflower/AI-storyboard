#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""视频链路联调：video 意图 → 视频方案 → 生成视频确认 HITL → generate_video → 轮询生成结果"""
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

def sse_send(cid, token, content, label, max_wait=240, hitl_out=None):
    body = json.dumps({"content": content}).encode()
    r = urllib.request.Request(BASE + f"/api/agent/conversations/{cid}/messages/stream", data=body, method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    resp = urllib.request.urlopen(r, timeout=200)
    events, buf, start = [], b"", time.time()
    print(f"=== {label} ===")
    while time.time() - start < max_wait:
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
                tag = "★" if evt == "human_input" else " "
                print(f"    {tag} <{evt}> {d[:130]}")
                if evt == "human_input" and hitl_out is not None:
                    hitl_out.update(json.loads(d))
            if evt == "human_input" and hitl_out is not None:
                return events
    return events

def form_submit(cid, token, hitl, action, max_wait=1200):
    body = {"formToken": hitl["formToken"], "taskId": hitl["taskId"], "action": action}
    r = urllib.request.Request(BASE + f"/api/agent/conversations/{cid}/form/submit", data=json.dumps(body).encode(), method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    print(f"=== 提交 {action}（视频生成可能 2-5 分钟）===")
    try:
        resp = urllib.request.urlopen(r, timeout=180)
        events, buf, start = [], b"", time.time()
        while time.time() - start < max_wait:
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
                    print(f"    <{evt}> {d[:150]}")
        print(f"    流结束 {time.time()-start:.0f}s, 事件 {len(events)}")
        return events
    except Exception as e:
        print("    提交异常:", e)
        return []

if __name__ == "__main__":
    st, raw = req("POST", "/api/auth/register", {"email": EMAIL, "password": PASSWORD, "displayName": "视频测"})
    if st not in (200, 201):
        print("注册失败:", raw[:300]); sys.exit(1)
    tok = json.loads(raw)["data"]["accessToken"]
    st, raw = req("POST", "/api/projects", {"name": "视频测项目", "description": "auto", "creationType": "manual", "aspectRatio": "16:9"}, tok)
    pid = json.loads(raw)["data"]["id"]
    st, raw = req("POST", "/api/agent/conversations", {"projectId": pid, "title": "视频测会话"}, tok)
    cid = json.loads(raw)["data"]["id"]
    print(f"用户={EMAIL} 会话={cid}")
    hitl = {}
    sse_send(cid, tok, "帮我生成一个视频：一只橘猫趴在屋顶上看日落，画面温馨", "视频意图", hitl_out=hitl)
    if not hitl.get("formToken"):
        print("✗ 未到达 HITL"); sys.exit(1)
    print(f"✓ HITL actions={hitl.get('actions')}")
    with open("/tmp/hitl_video.json", "w", encoding="utf-8") as f:
        json.dump(hitl, f, ensure_ascii=False)
    form_submit(cid, tok, hitl, "generate_video")
