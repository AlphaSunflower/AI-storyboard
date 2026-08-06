#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""智能体生成后端化联调：多轮对话推进到 HITL 暂停点"""
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

def sse_send(cid, token, content, max_wait=150):
    """发送消息并读 SSE 流，返回事件列表；遇 human_input 提前结束"""
    body = json.dumps({"content": content}).encode()
    r = urllib.request.Request(BASE + f"/api/agent/conversations/{cid}/messages/stream", data=body, method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    events = []
    resp = urllib.request.urlopen(r, timeout=30)
    buf = b""
    start = time.time()
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
                tag = "★" if evt == "human_input" else " "
                print(f"    {tag} <{evt}> {data[:120]}")
            if evt == "human_input":
                return events, json.loads(data) if data else {}
            if evt == "error":
                return events, {"error": data}
    return events, {}

if __name__ == "__main__":
    # 复用已创建的会话？从环境变量或参数
    cid = sys.argv[1] if len(sys.argv) > 1 else None
    token = sys.argv[2] if len(sys.argv) > 2 else None
    content = sys.argv[3] if len(sys.argv) > 3 else None
    if not (cid and token and content):
        print("用法: python scripts/agent_e2e_step.py <conversationId> <token> <消息内容>")
        sys.exit(1)
    events, hitl = sse_send(cid, token, content)
    print(f"事件数={len(events)}")
    if hitl.get("formToken"):
        with open("/tmp/hitl.json", "w", encoding="utf-8") as f:
            json.dump(hitl, f, ensure_ascii=False)
        print(f"★ HITL 暂停点: formToken={hitl.get('formToken')[:12]}... actions={hitl.get('actions')}")
        print("  已保存 /tmp/hitl.json")
