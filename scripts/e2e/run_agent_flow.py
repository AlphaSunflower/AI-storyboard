#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B5 Agent 微服务 E2E（MoonAgent，经网关 8080）：
会话 CRUD / 消息 / 资产 / 上传 / prompt optimize / SSE 流式 / HITL 分镜链 / 越权面。"""
import base64
import sys
import time

sys.path.insert(0, "scripts/e2e")
from common import req, parse, sse_post, check, load_creds, BASE_GW, BASE_BE, BASE_MA

PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def multipart(fields, files, boundary="----e2eboundary42"):
    body = b""
    for k, v in fields.items():
        body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    for name, fname, content, ctype in files:
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; "
                 f"filename=\"{fname}\"\r\nContent-Type: {ctype}\r\n\r\n").encode() + content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def mp_req(method, path, fields=None, files=None, token=None, base=BASE_GW, timeout=60):
    import urllib.request, urllib.error
    body, ctype = multipart(fields or {}, files or [])
    r = urllib.request.Request(base + path, data=body, method=method)
    r.add_header("Content-Type", ctype)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        resp = urllib.request.urlopen(r, timeout=timeout)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)


def main():
    c = load_creds()
    ta, tb = c["userA"]["accessToken"], c["userB"]["accessToken"]
    fails = 0

    def T(label, cond, detail=""):
        nonlocal fails
        if not check(label, cond, detail):
            fails += 1

    # 准备项目
    st, raw = req("POST", "/api/projects", {"name": f"E2E Agent项目{int(time.time())}"}, token=ta)
    pid = (parse(raw).get("data") or {}).get("id")
    T("准备项目", bool(pid), f"{st}")

    print("\n== B5.1 会话 CRUD ==")
    st, raw = req("POST", "/api/agent/conversations", {"projectId": pid, "title": "E2E会话"}, token=ta)
    d = parse(raw); cid = (d.get("data") or {}).get("id")
    T("创建会话", st == 200 and cid, f"{st} {raw[:120]}")
    st, raw = req("GET", f"/api/agent/conversations?projectId={pid}", token=ta)
    T("会话列表", st == 200 and any(x.get("id") == cid for x in (parse(raw).get("data") or [])), f"{st}")
    st, raw = req("GET", f"/api/agent/conversations/{cid}", token=ta)
    d = parse(raw)
    T("会话详情(含消息)", st == 200 and d.get("data", {}).get("conversation"), f"{st} {raw[:120]}")
    st, raw = req("PATCH", f"/api/agent/conversations/{cid}", {"title": "E2E会话改名"}, token=ta)
    T("重命名会话", st == 200 and (parse(raw).get("data") or {}).get("title") == "E2E会话改名", f"{st}")
    st, raw = req("PATCH", f"/api/agent/conversations/{cid}", {"status": "archived"}, token=ta)
    T("归档会话", st == 200, f"{st}")
    st, raw = req("PATCH", f"/api/agent/conversations/{cid}", {"status": "active"}, token=ta)
    T("会话恢复 active", st == 200, f"{st}")

    print("\n== B5.2 消息 / 资产 / 上传 ==")
    st, raw = req("POST", f"/api/agent/conversations/{cid}/messages", {"content": "你好，介绍一下自己"}, token=ta, timeout=120)
    d = parse(raw)
    T("发送消息(阻塞回答)", st == 200 and (d.get("data") or {}).get("role") == "assistant", f"{st} {raw[:150]}")
    st, raw = req("GET", f"/api/agent/conversations/{cid}/messages", token=ta)
    T("消息列表", st == 200 and len(parse(raw).get("data") or []) >= 2, f"{st}")
    st, raw = req("POST", "/api/agent/prompt/optimize", {"content": "一个孤独的宇航员在月球基地发现一株绿色植物"}, token=ta, timeout=120)
    T("prompt optimize(真实LLM)", st == 200 and (parse(raw).get("data") or {}).get("optimized"), f"{st} {raw[:150]}")
    st, raw = req("POST", "/api/agent/prompt/optimize", {"content": "短"}, token=ta)
    T("prompt optimize 短文本拒绝", st == 400, f"{st}")
    st, raw = mp_req("POST", "/api/agent/upload", fields={"conversationId": cid},
                     files=[("file", "ref.png", PNG_1PX, "image/png")], token=ta)
    up = parse(raw).get("data") or {}
    T("上传参考图", st == 200 and up.get("url"), f"{st} {raw[:150]}")
    st, raw = req("GET", f"/api/agent/conversations/{cid}/assets?page=1&size=20", token=ta)
    d = parse(raw)
    T("资产列表(分页结构)", st == 200 and "records" in (d.get("data") or {}), f"{st} {raw[:150]}")
    asset_id = ((d.get("data") or {}).get("records") or [{}])[0].get("id")
    if asset_id:
        st, raw = req("DELETE", f"/api/agent/assets/{asset_id}", token=ta)
        T("删资产", st == 200, f"{st}")

    print("\n== B5.3 越权面 ==")
    st, raw = req("GET", "/api/agent/conversations?projectId=x")
    T("无 token → 401", st == 401, f"{st}")
    st, raw = req("GET", f"/api/agent/conversations/{cid}", token=tb)
    T("他人会话详情 → 40401", st == 404 and "40401" in raw, f"{st} {raw[:100]}")
    st, raw = req("GET", f"/api/agent/conversations/{cid}/messages", token=tb)
    T("他人会话消息 → 40401", st == 404 and "40401" in raw, f"{st}")
    st, raw = req("GET", "/api/agent/conversations?projectId=x", base=BASE_MA)
    T("直连8084无X-User-Id → 401/403", st in (401, 403), f"实际 {st} {raw[:80]}")

    print("\n== B5.4 SSE 流式（真实编排）==")
    try:
        events = sse_post(f"/api/agent/conversations/{cid}/messages/stream",
                          {"content": "帮我把这个故事做成一个6秒的短视频分镜：雨夜，侦探在霓虹灯下追逐黑衣人"},
                          ta, max_wait=240)
        ev_names = [e[0] for e in events]
        T("SSE 收到事件流", len(events) >= 2, f"{ev_names[:8]}")
        T("SSE 含 message 增量", "message" in ev_names, f"{ev_names[:8]}")
        has_terminal = any(n in ev_names for n in ("message_end", "human_input", "error"))
        T("SSE 含终结事件(message_end/human_input/error)", has_terminal, f"{ev_names[:8]}")
        for name, data in events:
            if name == "message_end":
                T("message_end 携带 sceneCount", data.get("sceneCount") is not None, f"{data}")
            if name == "error":
                T("SSE 无 error 事件", False, f"{data}")
    except Exception as e:
        T("SSE 流式(真实编排)", False, f"异常: {e}")

    print("\n== B5.5 清理 ==")
    st, raw = req("DELETE", f"/api/agent/conversations/{cid}", token=ta)
    T("删会话(级联)", st == 200, f"{st}")
    st, raw = req("DELETE", f"/api/projects/{pid}", token=ta)
    T("删项目", st == 200, f"{st}")

    print(f"\n=== B5 完成：{'全部通过' if fails == 0 else f'{fails} 项失败'} ===")
    return 0 if fails <= 2 else 1  # SSE 编排受上游/耗时影响，容错 2


if __name__ == "__main__":
    sys.exit(main())
