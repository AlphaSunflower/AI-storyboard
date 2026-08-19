#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E2E 全套入口：认证引导 → 主链路 → Agent → 网关，聚合结果。"""
import subprocess
import sys

SCRIPTS = ["bootstrap_auth.py", "run_core_flow.py", "run_agent_flow.py", "run_gateway_flow.py"]
results = []
for s in SCRIPTS:
    print(f"\n{'=' * 20} {s} {'=' * 20}")
    r = subprocess.run([sys.executable, f"scripts/e2e/{s}"], capture_output=True, text=True)
    print(r.stdout[-2500:])
    if r.returncode != 0:
        print(r.stderr[-800:])
    results.append((s, r.returncode))
    print(f"--- {s} exit={r.returncode} ---")

print("\n=== 汇总 ===")
for s, rc in results:
    print(f"{'PASS' if rc == 0 else 'FAIL'} {s}")
sys.exit(0 if all(rc == 0 for _, rc in results) else 1)
