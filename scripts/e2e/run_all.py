#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E2E 全套入口：认证引导 → 主链路 → Agent → 网关，聚合结果。
末屏输出用例级 PASS 统计（供截图/汇报）；每脚本全量输出落 logs/ 目录。"""
import os
import subprocess
import sys

SCRIPTS = ["bootstrap_auth.py", "run_core_flow.py", "run_agent_flow.py", "run_gateway_flow.py"]
os.makedirs("scripts/e2e/logs", exist_ok=True)
results = []
for s in SCRIPTS:
    print(f"\n{'=' * 20} {s} {'=' * 20}")
    r = subprocess.run([sys.executable, f"scripts/e2e/{s}"], capture_output=True, text=True)
    with open(f"scripts/e2e/logs/{s}.log", "w", encoding="utf-8") as f:
        f.write(r.stdout + ("\n--- stderr ---\n" + r.stderr if r.stderr else ""))
    print(r.stdout[-2500:])
    if r.returncode != 0:
        print(r.stderr[-800:])
    results.append((s, r.returncode, r.stdout))
    print(f"--- {s} exit={r.returncode} ---")

print("\n=== 汇总 ===")
for s, rc, _ in results:
    print(f"{'PASS' if rc == 0 else 'FAIL'} {s}")

# 末屏统计：逐脚本用例计数 + 总计；审计实证类（实证方向翻转=已修复）单列，不混入回归
tp = tf = ta = 0
print()
print("=" * 56)
print("             E2E 用例统计（最终结果）")
print("=" * 56)
for s, rc, out in results:
    lines = [l for l in out.splitlines() if l.startswith(("[PASS]", "[FAIL]"))]
    p = sum(1 for l in lines if l.startswith("[PASS]"))
    f = sum(1 for l in lines if l.startswith("[FAIL]"))
    a = sum(1 for l in lines if l.startswith("[FAIL]") and "实证" in l)
    tp += p
    tf += f
    ta += a
    print(f"  {'PASS' if rc == 0 else 'FAIL'}  {s:<24} {p:>3} 通过 / {f} 失败" + (f"（含实证类 {a}）" if a else ""))
print("-" * 56)
reg_fail = tf - ta
verdict = "ALL GREEN" if all(rc == 0 for _, rc, _ in results) and tf == 0 else (
    "全绿(容差内)" if reg_fail == 0 and all(rc == 0 for _, rc, _ in results) else "存在回归失败")
print(f"  TOTAL: {tp + tf} 用例 - {tp} PASS / {tf} FAIL（其中审计实证类 {ta}，回归失败 {reg_fail}）")
print(f"  RESULT: [{verdict}]")
print("=" * 56)

sys.exit(0 if all(rc == 0 for _, rc, _ in results) else 1)
