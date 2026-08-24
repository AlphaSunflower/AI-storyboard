# -*- coding: utf-8 -*-
"""Pass 2 (fixed): 图表 caption 统一重编号（章节式）。
安全 set_text：只改写文本，绝不删除含图片(run 内含 drawing)的 run，避免误删图。
处理 附录A/B 用 A./B. 前缀。修正常见 in-text 交叉引用。"""
import re
from docx import Document
from docx.oxml.ns import qn

P = r"E:\Desktop\项目计划书2_评委优化版.docx"
d = Document(P)

NUMS = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}


def chap_of(t):
    for k, v in NUMS.items():
        if t.startswith(k + "、"):
            return v
    return None


def has_drawing(r):
    return r._r.find(qn("w:drawing")) is not None or r._r.find(qn("w:pict")) is not None


def set_text(p, text):
    """改写段落文字为 text，保留所有含图片的 run，删除其余文本 run。"""
    runs = list(p.runs)
    keep = [r for r in runs if has_drawing(r)]
    # 首个非图片 run 承载整段文字
    first_text = None
    for r in runs:
        if not has_drawing(r):
            first_text = r
            break
    if first_text is None:
        # 全是图片 run，无法改写文字，返回
        return
    first_text.text = text
    for r in runs:
        if r is first_text or has_drawing(r):
            continue
        r._r.getparent().remove(r._r)


FIG_RE = re.compile(r"^图\s*[\dA-Za-z]+[\dA-Za-z\.\-]*\s+")
TBL_RE = re.compile(r"^表\s*[\dA-Za-z]+[\dA-Za-z\.\-]*\s+")

cur = None
fig_n = 0
tbl_n = 0
counts = {"fig": 0, "tbl": 0}

for p in d.paragraphs:
    try:
        sty = p.style.name
    except Exception:
        sty = ""
    t = p.text.strip()
    if sty == "Heading 1":
        cv = chap_of(t)
        if cv:
            cur = cv
            fig_n = 0
            tbl_n = 0
            continue
        if t.startswith("附录A"):
            cur = "A"
            fig_n = 0
            tbl_n = 0
            continue
        if t.startswith("附录B"):
            cur = "B"
            fig_n = 0
            tbl_n = 0
            continue
    if t.startswith("图") and re.match(r"^图\s*\d", t):
        m = FIG_RE.match(t)
        if m and cur is not None:
            fig_n += 1
            counts["fig"] += 1
            set_text(p, f"图 {cur}.{fig_n} {t[m.end():].strip()}")
        continue
    if t.startswith("表") and re.match(r"^表\s*\d", t):
        m = TBL_RE.match(t)
        if m and cur is not None:
            tbl_n += 1
            counts["tbl"] += 1
            set_text(p, f"表 {cur}.{tbl_n} {t[m.end():].strip()}")

IN_TEXT = [
    ("（图 5）", "（见下图）"),
    ("如表 4 所示", "如下表所示"),
    ("如表 6.4 与图 6.1 所示", "如下表、下图所示"),
    ("如表 6.1 所示", "如下表所示"),
]
for p in d.paragraphs:
    t = p.text
    for old, new in IN_TEXT:
        if old in t:
            set_text(p, t.replace(old, new))
    # 数据库 in-text 交叉引用（编号可变）→ 通用描述
    if re.search(r"关系如图\s*[\dA-Z\.]+\s*所示", t):
        set_text(p, re.sub(r"关系如图\s*[\dA-Z\.]+\s*所示", "核心表关系如下表、下图所示", t))

d.save(P)
print("PASS2 fixed done. figures:", counts["fig"], "tables:", counts["tbl"])
