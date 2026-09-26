#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E2E 聚合入口：顺序执行 5 个脚本，汇总 PASS/FAIL 统计。
顺序：bootstrap_auth → run_core_flow → run_agent_flow → run_gateway_flow → run_smoke"""
import sys, os, subprocess, time, re

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
FLOWS = [
    ("认证引导",       "bootstrap_auth.py"),
    ("主链路 CRUD",    "run_core_flow.py"),
    ("Agent 编排",     "run_agent_flow.py"),
    ("网关 LLM/Admin", "run_gateway_flow.py"),
    ("补充冒烟",       "run_smoke.py"),
]

def run_flow(name, script):
    path = os.path.join(SCRIPTS_DIR, script)
    print(f"\n{'─'*60}")
    print(f"  ▶ {name}  ({script})")
    print(f"{'─'*60}")
    t0 = time.time()
    r = subprocess.run([sys.executable, path], capture_output=True, text=True, timeout=600)
    # 打印完整 stdout
    print(r.stdout)
    if r.stderr:
        print(r.stderr[-500:])
    elapsed = time.time() - t0
    # 用例计数：数 [PASS] / [FAIL] 行（对完整 stdout 计数）
    p = len(re.findall(r'\[PASS\]', r.stdout))
    f = len(re.findall(r'\[FAIL\]', r.stdout))
    return r.returncode == 0, elapsed, p, f

def main():
    total_start = time.time()
    results = []
    total_p = total_f = 0

    # 从 application-local.yml 读取 internal.secret 并注入 env（run_core_flow 内部接口测试用）
    import re as _re
    _cfg = os.path.join(SCRIPTS_DIR, "..", "..", "AIStoryboardBackend", "src", "main", "resources", "application-local.yml")
    try:
        _txt = open(_cfg, encoding="utf-8").read()
        _m = _re.search(r'internal:\s*\n\s*secret:\s*(\S+)', _txt)
        if _m:
            os.environ["INTERNAL_SECRET"] = _m.group(1)
    except Exception:
        pass

    for name, script in FLOWS:
        ok, elapsed, p, f = run_flow(name, script)
        results.append((name, ok, elapsed, p, f))
        total_p += p
        total_f += f

    # ─── 末屏统计 ───
    total_elapsed = time.time() - total_start
    total_cases = total_p + total_f
    overall = "PASS" if total_f == 0 else "FAIL"

    print(f"\n{'='*60}")
    print(f"  E2E TEST SUMMARY")
    print(f"{'='*60}")
    for name, ok, elapsed, p, f in results:
        tag = "PASS ✓" if ok else "FAIL ✗"
        print(f"  {tag}  {name}  {p}/{p+f} passed  ({elapsed:.1f}s)")
    print(f"{'─'*60}")
    print(f"  TOTAL: {total_cases} cases, {total_p} passed, {total_f} failed  [{overall}]")
    print(f"  Duration: {total_elapsed:.1f}s")
    print(f"{'='*60}\n")
    sys.exit(0 if total_f == 0 else 1)

if __name__ == "__main__":
    main()
