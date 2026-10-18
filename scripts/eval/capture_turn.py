#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从一条真实 turn 生成 eval case，避免从零编造 prompt。

用法：
  set MOON_EMAIL=...
  set MOON_PASSWORD=...
  python scripts/eval/capture_turn.py --conversation <cid> --turn <turnId> \
      --name storyboard-real [--prompt "原始用户输入"] [--out scripts/eval/cases.real.jsonl]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("MOON_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
EMAIL = os.environ.get("MOON_EMAIL", "")
PASSWORD = os.environ.get("MOON_PASSWORD", "")


def request(path: str, token: str) -> dict:
    req = urllib.request.Request(BASE + path)
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"GET {path} -> {exc.code}: {exc.read()[:300]!r}")


def login() -> str:
    if not EMAIL or not PASSWORD:
        raise SystemExit("需要 MOON_EMAIL / MOON_PASSWORD")
    data = json.dumps({"email": EMAIL, "password": PASSWORD}).encode("utf-8")
    req = urllib.request.Request(BASE + "/api/auth/login", data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())["data"]["accessToken"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--conversation", required=True)
    parser.add_argument("--turn", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--prompt", default="")
    parser.add_argument("--out", default="scripts/eval/cases.real.jsonl")
    args = parser.parse_args()

    token = login()
    messages = request(
        f"/api/agent/conversations/{args.conversation}/messages", token).get("data") or []
    events = request(
        f"/api/agent/conversations/{args.conversation}/turns/{args.turn}/events",
        token).get("data") or []

    prompt = args.prompt
    if not prompt:
        users = [m.get("content", "") for m in messages if m.get("role") == "user"]
        if not users:
            raise SystemExit("没有用户消息，请用 --prompt 指定")
        prompt = users[-1]

    tools: list[str] = []
    status = "unknown"
    for envelope in events:
        payload = envelope.get("payload") or {}
        if envelope.get("type") == "TOOL_CALL_START":
            name = payload.get("toolName")
            if name and name not in tools:
                tools.append(str(name))
        elif envelope.get("type") == "turn_completed":
            status = "completed"
        elif envelope.get("type") == "turn_paused":
            status = "paused"
        elif envelope.get("type") == "turn_failed":
            status = "failed"

    case = {
        "name": args.name,
        "prompt": prompt,
        "expectStatus": status,
        "expectTools": tools,
        "maxDurationMs": 240000,
    }
    line = json.dumps(case, ensure_ascii=False)
    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")
    print(line)
    print(f"appended -> {path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
