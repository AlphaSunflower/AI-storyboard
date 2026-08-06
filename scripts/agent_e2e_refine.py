#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""完善循环联调：refine 带 PicUrl → pic_refine 意图 → 完善方案 → 人工介入4 → generate_image（图改图）"""
import json, sys, time, urllib.request, urllib.error

BASE = "http://localhost:8082"

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

def sse_send(cid, token, content, pic_url, label, max_wait=240):
    body = {"content": content}
    if pic_url:
        body["picUrl"] = pic_url
    r = urllib.request.Request(BASE + f"/api/agent/conversations/{cid}/messages/stream", data=json.dumps(body).encode(), method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    resp = urllib.request.urlopen(r, timeout=200)
    events, buf, start, hitl = [], b"", time.time(), {}
    print(f"=== {label}（picUrl={pic_url[:40] if pic_url else '无'}）===")
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
                if evt == "human_input":
                    hitl = json.loads(d)
            if evt == "human_input":
                return events, hitl
    return events, hitl

def form_submit(cid, token, hitl, action, max_wait=240):
    body = {"formToken": hitl["formToken"], "taskId": hitl["taskId"], "action": action}
    r = urllib.request.Request(BASE + f"/api/agent/conversations/{cid}/form/submit", data=json.dumps(body).encode(), method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    print(f"=== 提交 {action} ===")
    resp = urllib.request.urlopen(r, timeout=200)
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

if __name__ == "__main__":
    EMAIL, CID = sys.argv[1], sys.argv[2]
    IMG = "/api/files/images/bd2ebc43-801b-4122-9abd-57de8eb3637b.png"
    st, raw = req("POST", "/api/auth/login", {"email": EMAIL, "password": "password123"})
    tok = json.loads(raw)["data"]["accessToken"]
    print(f"登录 {EMAIL} ok, 会话 {CID[:8]}")
    # 完善消息（带当前图 PicUrl）
    events, hitl = sse_send(CID, tok, "请基于这张图片继续完善，把色调调得更暖一点", IMG, "完善意图")
    if not hitl.get("formToken"):
        print("✗ 未到达 HITL（可能意图未识别为完善）"); sys.exit(1)
    print(f"✓ HITL actions={hitl.get('actions')}")
    # 提交 generate_image（图改图）
    form_submit(CID, tok, hitl, "generate_image")
