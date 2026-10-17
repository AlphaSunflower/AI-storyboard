#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
画布 turn 探针：造一次真实的画布编辑 turn，用于观察工具调用行为。

用途：验证工具面收敛后模型实际调用哪些画布工具（apply_plan 采纳率、
首轮读取次数、create_edge 失败是否能一轮自修）。

链路（全部经网关 :8080，与前端同路径）：
  1) 登录/注册测试用户                  -> accessToken / userId
  2) 建项目（可复用）                   -> projectId
  3) 建画布 /api/canvas/projects/{id}/canvases（可复用）-> canvasId
  4) 建会话（可复用）                   -> conversationId
  5) 发 turn（带 canvasContext）        -> SSE 事件流

用法：
  # 全新一轮（新建用户/项目/画布/会话）
  python scripts/canvas_turn_probe.py "自定义提示词"

  # 复用已有画布做增量编辑（画布上必须已有节点）
  $env:PROBE_EMAIL="canvasprobe_xxx@test.local"
  $env:PROBE_PROJECT="<projectId>"
  $env:PROBE_CANVAS="<canvasId>"
  $env:PROBE_CONVERSATION="<conversationId>"   # 可选
  python scripts/canvas_turn_probe.py "把第 2 个节点标题改成主图"
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

GATEWAY = "http://localhost:8080"
CANVAS = "http://localhost:8080/api/canvas"
TIMEOUT_S = 300

DEFAULT_PROMPT = (
    "在画布上搭一条文生图流程：一个提示词节点（文案：一只橘猫坐在窗台上），"
    "连到图片生成节点，再给图片生成节点接 1 个结果槽。建完告诉我用了几次工具。"
)


def call(method, url, body=None, headers=None, timeout=60):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return -1, str(exc)


def parse(raw):
    try:
        return json.loads(raw)
    except Exception:  # noqa: BLE001
        return {"raw": raw[:400]}


def unwrap(payload):
    """后端统一 ApiResponse{code,message,data}；画布服务直接返回对象。"""
    if isinstance(payload, dict) and "code" in payload and "data" in payload:
        return payload.get("data")
    return payload


def authenticate(email, password):
    status, raw = call("POST", f"{GATEWAY}/api/auth/login",
                       {"email": email, "password": password})
    if status not in (200, 201):
        status, raw = call("POST", f"{GATEWAY}/api/auth/register",
                           {"email": email, "password": password, "displayName": "画布探针"})
    if status not in (200, 201):
        raise SystemExit(f"登录/注册失败 {status}: {raw[:300]}")
    data = unwrap(parse(raw))
    return data["accessToken"], data["userId"]


def ensure_project(token, ts):
    auth = {"Authorization": "Bearer " + token}
    status, raw = call("POST", f"{GATEWAY}/api/projects",
                       {"name": f"探针项目{ts}", "description": "canvas probe",
                        "creationType": "manual", "aspectRatio": "16:9"}, auth)
    if status not in (200, 201):
        raise SystemExit(f"建项目失败 {status}: {raw[:300]}")
    return unwrap(parse(raw))["id"]


def ensure_canvas(token, project_id, ts):
    auth = {"Authorization": "Bearer " + token}
    status, raw = call("POST", f"{CANVAS}/projects/{project_id}/canvases",
                       {"title": f"探针画布{ts}"}, auth)
    if status not in (200, 201):
        raise SystemExit(f"建画布失败 {status}: {raw[:300]}")
    canvas = unwrap(parse(raw))
    return canvas.get("id") or canvas.get("canvasId")


def ensure_conversation(token, project_id):
    auth = {"Authorization": "Bearer " + token}
    status, raw = call("POST", f"{GATEWAY}/api/agent/conversations",
                       {"projectId": project_id, "title": "画布探针会话"}, auth)
    if status not in (200, 201):
        raise SystemExit(f"建会话失败 {status}: {raw[:300]}")
    return unwrap(parse(raw))["id"]


def stream_turn(token, conversation_id, prompt, project_id, canvas_id, ts):
    body = {
        "content": prompt,
        "clientRequestId": f"probe-{ts}",
        "canvasContext": {
            "mode": "canvas-edit",
            "projectId": project_id,
            "canvasId": canvas_id,
            "mediaRefs": [],
        },
    }
    req = urllib.request.Request(
        f"{GATEWAY}/api/agent/conversations/{conversation_id}/turns/stream",
        data=json.dumps(body).encode("utf-8"), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream")
    req.add_header("Authorization", "Bearer " + token)

    counts = {}
    started = time.time()
    try:
        resp = urllib.request.urlopen(req, timeout=TIMEOUT_S)
        for line in resp:
            text = line.decode("utf-8", "replace").strip()
            if not text.startswith("data:"):
                continue
            obj = parse(text[5:].strip())
            event_type = obj.get("type", "?")
            counts[event_type] = counts.get(event_type, 0) + 1
            payload = obj.get("payload") or {}
            if event_type == "agent_event":
                event_name = payload.get("eventType") or "?"
                if "TOOL" in str(event_name).upper():
                    data = payload.get("data", {}) or {}
                    name = data.get("name") or data.get("toolName") or ""
                    print(f"   [{time.time() - started:6.1f}s] {event_name} {name}")
            if event_type in ("turn_completed", "turn_failed", "turn_paused"):
                print(f"   [{time.time() - started:6.1f}s] 终态: {event_type}")
                if event_type == "turn_failed":
                    print(f"      {json.dumps(payload, ensure_ascii=False)[:300]}")
                break
    except Exception as exc:  # noqa: BLE001
        print(f"   SSE 中断/超时: {exc}")

    print("\n== 事件类型统计 ==")
    for key, value in sorted(counts.items(), key=lambda item: -item[1]):
        print(f"   {key:26} {value}")


def main():
    prompt = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PROMPT
    env = os.environ
    ts = str(int(time.time()))[-6:]
    email = env.get("PROBE_EMAIL") or f"canvasprobe_{ts}@test.local"
    password = env.get("PROBE_PASSWORD") or "password123"

    print(f"== 1) 登录/注册 {email}")
    token, user_id = authenticate(email, password)
    print(f"   userId={user_id}")

    project_id = env.get("PROBE_PROJECT")
    if project_id:
        print(f"== 2) 复用项目 {project_id}")
    else:
        print("== 2) 建项目")
        project_id = ensure_project(token, ts)
        print(f"   projectId={project_id}")

    canvas_id = env.get("PROBE_CANVAS")
    if canvas_id:
        print(f"== 3) 复用画布 {canvas_id}")
    else:
        print("== 3) 建画布（经网关 /api/canvas）")
        canvas_id = ensure_canvas(token, project_id, ts)
        print(f"   canvasId={canvas_id}")

    conversation_id = env.get("PROBE_CONVERSATION")
    if conversation_id:
        print(f"== 4) 复用会话 {conversation_id}")
    else:
        print("== 4) 建会话")
        conversation_id = ensure_conversation(token, project_id)
        print(f"   conversationId={conversation_id}")

    print("== 5) 发画布 turn（SSE）")
    stream_turn(token, conversation_id, prompt, project_id, canvas_id, ts)

    print(f"\nPROBE_EMAIL={email}")
    print(f"PROBE_PROJECT={project_id}")
    print(f"PROBE_CANVAS={canvas_id}")
    print(f"PROBE_CONVERSATION={conversation_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
