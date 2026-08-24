# -*- coding: utf-8 -*-
"""Probe structure of the optimized docx: dump paragraph index/style/text, tables,
and image placements (inline vs floating anchor)."""
import sys, re
from docx import Document
from docx.oxml.ns import qn

SRC = r"E:\Desktop\项目计划书1_已润色版.docx"
d = Document(SRC)

print("=== paragraphs (idx | style | text-first-48) ===")
for i, p in enumerate(d.paragraphs):
    t = p.text.strip().replace("\n", " ")
    if t:
        try:
            sty = p.style.name
        except Exception:
            sty = "?"
        if len(t) > 48:
            t = t[:48] + "…"
        print(f"{i:4d} | {sty:22s} | {t}")

print("\n=== tables ===")
for ti, t in enumerate(d.tables):
    print(f"table {ti}: rows={len(t.rows)} cols={len(t.columns)} first_cell={t.rows[0].cells[0].text.strip()[:20]!r}")
    # find the paragraph index right before this table (for anchoring)
print("\ntables count:", len(d.tables))

print("\n=== inline shapes (images inline) ===")
print("inline count:", len(d.inline_shapes))
for i, sh in enumerate(d.inline_shapes):
    print(f"  inline {i}: type={sh.type} w_in={sh.width} h_in={sh.height}")

# Count floating (anchored) drawings: scan raw XML
print("\n=== floating (anchored) drawings ===")
from docx.oxml.ns import nsmap
body_xml = d.element.body.xml
anchors = body_xml.count("<wp:anchor")
inlines = body_xml.count("<wp:inline")
print("wp:anchor:", anchors, "wp:inline:", inlines)

# List which paragraph contains a float by walking paragraphs' XML drawings
print("\n=== paragraph -> drawing type ===")
for i, p in enumerate(d.paragraphs):
    pxml = p._p.xml
    n_anchor = pxml.count("<wp:anchor")
    n_inline = pxml.count("<wp:inline")
    if n_anchor or n_inline:
        has_w = "A" * n_anchor + "I" * n_inline
        print(f"  para {i}: drawings='{has_w}' text={p.text.strip()[:30]!r}")
