#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MoonAgent 真实回合 eval runner（标准库，无第三方依赖）。

用法：
  set MOON_EMAIL=...
  set MOON_PASSWORD=...
  set MOON_PROJECT_ID=...
  set MOON_CANVAS_ID=...   # 可选，跑 canvas case 时需要
  python scripts/eval/run_eval.py --cases scripts/eval/cases.jsonl

每个 case 新建一个会话、发一次 stream；支持：
  - canvasContext（case.canvasId）
  - HITL 暂停后 reject/approve 续流（case.hitl）
  - 某个事件到达时插入 steer（case.steer）

退出码 1 表示有 case 失败，可直接接 CI。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

BASE = os.environ.get("MOON_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
EMAIL = os.environ.get("MOON_EMAIL", "")
PASSWORD = os.environ.get("MOON_PASSWORD", "")
PROJECT_ID = os.environ.get("MOON_PROJECT_ID", "")
CANVAS_ID = os.environ.get("MOON_CANVAS_ID", "")


def request(method: str, path: str, body: dict | None = None, token: str | None = None,
            timeout: int = 60) -> tuple[int, bytes]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def login() -> str:
    if not EMAIL or not PASSWORD:
        raise SystemExit("需要 MOON_EMAIL / MOON_PASSWORD")
    status, raw = request("POST", "/api/auth/login",
                          {"email": EMAIL, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败: {status} {raw[:300]!r}")
    return json.loads(raw)["data"]["accessToken"]


def create_conversation(token: str, project_id: str, title: str) -> str:
    last = b""
    for attempt in range(3):
        status, raw = request("POST", "/api/agent/conversations",
                              {"projectId": project_id, "title": title}, token)
        if status == 200:
            return json.loads(raw)["data"]["id"]
        last = raw
        if status < 500:
            break
        time.sleep(1 + attempt)
    raise SystemExit(f"创建会话失败: {last[:300]!r}")


def delete_conversation(token: str, conversation_id: str) -> None:
    try:
        request("DELETE", f"/api/agent/conversations/{conversation_id}", token=token)
    except Exception:
        pass


def open_turn(token: str, conversation_id: str, body: dict):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        BASE + f"/api/agent/conversations/{conversation_id}/turns/stream",
        data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    req.add_header("Authorization", "Bearer " + token)
    return urllib.request.urlopen(req, timeout=240)


def open_resume(token: str, conversation_id: str, turn_id: str, form_token: str, action: str):
    data = json.dumps({"formToken": form_token, "action": action}).encode("utf-8")
    req = urllib.request.Request(
        BASE + f"/api/agent/conversations/{conversation_id}/turns/{turn_id}/resume/stream",
        data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    req.add_header("Authorization", "Bearer " + token)
    return urllib.request.urlopen(req, timeout=240)


def post_steer(token: str, conversation_id: str, turn_id: str, content: str) -> dict:
    status, raw = request(
        "POST",
        f"/api/agent/conversations/{conversation_id}/turns/{turn_id}/steer",
        {"content": content, "clientMessageId": f"eval-steer-{int(time.time_ns())}"},
        token)
    if status != 200:
        return {"status": f"http-{status}"}
    return json.loads(raw).get("data", {})


def parse_sse(response):
    event_type = ""
    for raw_line in response:
        line = raw_line.decode("utf-8", "replace").rstrip("\r\n")
        if line.startswith("event:"):
            event_type = line[6:].strip()
        elif line.startswith("data:"):
            try:
                data = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                continue
            payload = data.get("payload") if isinstance(data, dict) else None
            yield event_type, data, payload if isinstance(payload, dict) else {}


def stream_turn(token: str, conversation_id: str, case: dict) -> dict:
    body = {"content": case["prompt"],
            "clientRequestId": f"eval-{int(time.time_ns())}"}
    canvas_id = case.get("canvasId") or CANVAS_ID
    if canvas_id:
        body["canvasContext"] = {
            "mode": "canvas-edit",
            "projectId": case.get("projectId") or PROJECT_ID,
            "canvasId": canvas_id,
            "mediaRefs": [],
        }

    final = ""
    status = "unknown"
    tools: list[str] = []
    metrics: dict = {}
    turn_id = ""
    form_token = ""
    steer_sent = False
    hitl_done = False
    started = time.time()

    response = open_turn(token, conversation_id, body)
    try:
        while response is not None:
            resume_response = None
            for event_type, envelope, payload in parse_sse(response):
                turn_id = str(envelope.get("turnId") or turn_id)
                if event_type == "TOOL_CALL_START":
                    name = payload.get("toolName")
                    if name:
                        tools.append(str(name))
                elif event_type == "turn_completed":
                    final = str(payload.get("content") or "")
                    status = "completed"
                    metrics = (payload.get("metadata") or {}).get("metrics", {}) or {}
                elif event_type == "turn_paused":
                    status = "paused"
                    hitl = case.get("hitl") or {}
                    form_token = str(
                        payload.get("formToken")
                        or (payload.get("hitl") or {}).get("formToken")
                        or "")
                    if hitl and not hitl_done and form_token and turn_id:
                        hitl_done = True
                        resume_response = open_resume(
                            token, conversation_id, turn_id, form_token,
                            str(hitl.get("action") or "approve"))
                elif event_type == "turn_failed":
                    status = "failed"
                    final = str(payload.get("content") or "")

                steer = case.get("steer") or {}
                if (steer and not steer_sent and turn_id
                        and event_type == str(steer.get("onEvent") or "SUBAGENT_STARTED")):
                    result = post_steer(token, conversation_id, turn_id,
                                        str(steer.get("content") or ""))
                    steer_sent = result.get("status") == "steered"
            if resume_response is not None:
                response = resume_response
                continue
            break
    finally:
        try:
            response.close()
        except Exception:
            pass

    return {
        "status": status,
        "final": final,
        "tools": tools,
        "metrics": metrics,
        "durationMs": int((time.time() - started) * 1000),
        "steerSent": steer_sent,
        "hitlHandled": hitl_done,
    }


def evaluate(case: dict, result: dict) -> list[str]:
    failures: list[str] = []
    if case.get("expectStatus", "completed") != result["status"]:
        failures.append(f"status={result['status']} 期望 {case.get('expectStatus', 'completed')}")
    for needle in case.get("expectFinalContains", []) or []:
        if needle not in result["final"]:
            failures.append(f"最终回复缺少 {needle!r}")
    for expected in case.get("expectTools", []) or []:
        if expected not in result["tools"]:
            failures.append(f"缺少工具 {expected}")
    for forbidden in case.get("forbidTools", []) or []:
        if forbidden in result["tools"]:
            failures.append(f"出现了禁止工具 {forbidden}")
    if case.get("steer") and not result["steerSent"]:
        failures.append("steer 未触发或未成功")
    if case.get("hitl") and not result["hitlHandled"]:
        failures.append("HITL 未暂停或未续流")
    if case.get("expectNoTools") and result["tools"]:
        failures.append(f"不应调用工具，实际调了 {result['tools']}")
    max_duration = case.get("maxDurationMs")
    if max_duration and result["durationMs"] > max_duration:
        failures.append(f"耗时 {result['durationMs']}ms 超过 {max_duration}ms")
    max_tokens = case.get("maxTokens")
    total = result["metrics"].get("totalTokens")
    if max_tokens and isinstance(total, int) and total > max_tokens:
        failures.append(f"tokens {total} 超过 {max_tokens}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", default="scripts/eval/cases.jsonl")
    parser.add_argument("--report-dir", default="scripts/eval/reports")
    args = parser.parse_args()

    token = login()
    cases = [json.loads(line) for line in Path(args.cases).read_text("utf-8-sig").splitlines()
             if line.strip() and not line.lstrip().startswith("#")]
    if not cases:
        raise SystemExit("没有 case")

    report = {"base": BASE, "startedAt": datetime.now(timezone.utc).isoformat(), "cases": []}
    failed = 0
    skipped = 0
    for case in cases:
        project_id = case.get("projectId") or PROJECT_ID
        if case.get("canvasId") or case.get("requiresCanvas"):
            canvas_id = case.get("canvasId") or CANVAS_ID
            if not canvas_id:
                print(f"[SKIP] {case['name']}  (需要 MOON_CANVAS_ID)")
                skipped += 1
                continue
        if not project_id:
            raise SystemExit("需要 MOON_PROJECT_ID 或 case.projectId")

        conversation_id = create_conversation(token, project_id, f"eval:{case['name']}")
        try:
            result = stream_turn(token, conversation_id, case)
        finally:
            delete_conversation(token, conversation_id)
        failures = evaluate(case, result)
        failed += 1 if failures else 0
        mark = "FAIL" if failures else "PASS"
        print(f"[{mark}] {case['name']}  status={result['status']}  "
              f"tools={result['tools']}  steer={result['steerSent']}  "
              f"{result['durationMs']}ms")
        for failure in failures:
            print(f"       - {failure}")
        report["cases"].append({"name": case["name"], "result": result,
                                "failures": failures, "passed": not failures})

    report["passed"] = len(cases) - failed - skipped
    report["failed"] = failed
    report["skipped"] = skipped
    out_dir = Path(args.report_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    (out_dir / f"eval-{stamp}.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), "utf-8")
    print(f"\n{report['passed']}/{len(cases) - skipped} passed, {skipped} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
