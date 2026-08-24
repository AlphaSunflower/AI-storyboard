# -*- coding: utf-8 -*-
"""Verify Pass1 output: dump Heading1/Heading2 sequence, caption sequence (图/表), detect anomalies."""
from docx import Document
OUT = r"E:\Desktop\项目计划书2_评委优化版.docx"
d = Document(OUT)

print("=== Heading 1 / 2 / 3 sequence ===")
for i, p in enumerate(d.paragraphs):
    st = p.text.strip()
    try:
        sty = p.style.name
    except Exception:
        sty = ""
    if sty.startswith("Heading") and st:
        lvl = sty.replace("Heading ", "H")
        print(f"{lvl}  {st}")

print("\n=== caption sequence (图/表) ===")
for i, p in enumerate(d.paragraphs):
    st = p.text.strip()
    if st[:1] in ("图", "表") and len(st) > 3 and st[:2] not in ("图表",):
        print(f"  {st}")
