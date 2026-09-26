#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E2E 公共助手：HTTP/SSE 请求、ApiResponse 解析、凭据读写。
复用 scripts/agent_e2e_*.py 的 urllib 零依赖模式。"""
import json
import time
import urllib.request
import urllib.error

BASE_GW = "http://localhost:8080"   # ApiGateway 统一入口
BASE_BE = "http://localhost:8082"   # 主后端直连（越权/内部接口测试）
BASE_MA = "http://localhost:8084"   # MoonAgent 直连（X-User-Id 伪造测试）
BASE_MN = BASE_MA                    # 别名（run_smoke.py 用）
BASE_LLM = "http://localhost:8083"  # LLM 网关
CREDS = "scripts/e2e/.creds.json"


def req(method, path, body=None, token=None, headers=None, base=BASE_GW, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(base + path, data=data, method=method)
    if body is not None:
        r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
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


def data(raw):
    return parse(raw).get("data")


def sse_post(path, body, token, max_wait=300):
    """POST SSE 端点，返回 [(event, data_dict), ...]。"""
    data = json.dumps(body).encode()
    r = urllib.request.Request(BASE_GW + path, data=data, method="POST")
    r.add_header("Content-Type", "application/json")
    r.add_header("Authorization", "Bearer " + token)
    events = []
    resp = urllib.request.urlopen(r, timeout=max_wait)
    buf = b""
    start = time.time()
    while time.time() - start < max_wait:
        chunk = resp.read(4096)
        if not chunk:
            break
        buf += chunk
        while b"\n\n" in buf:
            block, buf = buf.split(b"\n\n", 1)
            ev, dt = "message", ""
            for line in block.split(b"\n"):
                if line.startswith(b"event:"):
                    ev = line[6:].strip().decode()
                elif line.startswith(b"data:"):
                    dt = line[5:].strip().decode()
            try:
                events.append((ev, json.loads(dt)))
            except Exception:
                events.append((ev, {"raw": dt[:300]}))
    return events


def rand_email():
    return f"e2e_{int(time.time())}_{__import__('random').randint(100, 999)}@test.local"


def save_creds(data):
    with open(CREDS, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def load_creds():
    with open(CREDS, encoding="utf-8") as f:
        return json.load(f)


# ── 统计计数器（run_smoke.py 用） ──
_pass_count = _fail_count = 0


def check(label, cond, detail=""):
    global _pass_count, _fail_count
    tag = "PASS" if cond else "FAIL"
    if cond:
        _pass_count += 1
    else:
        _fail_count += 1
    print(f"  [{tag}] {label}" + (f"  ({detail})" if detail else ""))
    return cond


def summary(suite_name):
    total = _pass_count + _fail_count
    status = "PASS" if _fail_count == 0 else "FAIL"
    print(f"\n  {'='*56}")
    print(f"  {suite_name}: {_pass_count}/{total} passed  [{status}]")
    print(f"  {'='*56}\n")
    return _fail_count == 0
