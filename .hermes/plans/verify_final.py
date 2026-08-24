# -*- coding: utf-8 -*-
"""Final verification of 项目计划书2_评委优化版.docx"""
import re
from docx import Document

P = r"E:\Desktop\项目计划书2_评委优化版.docx"
d = Document(P)
print("paragraphs:", len(d.paragraphs), "tables:", len(d.tables), "inline_shapes:", len(d.inline_shapes))

# 1) Heading 1 sequence
print("\n=== H1 chapter sequence ===")
h1 = [p.text.strip() for p in d.paragraphs if (p.style.name or "").startswith("Heading 1") and p.text.strip()]
for h in h1:
    print(" ", h)

# 2) golden TOC expected chapter order
EXPECTED = ["一、项目概述", "二、系统设计与实现", "三、项目计划与可行性", "四、竞争分析", "五、营销战略",
            "六、财务预测与分析", "七、风险预测与应对", "八、创新性与技术优势", "九、系统测试与分析",
            "十、总结与未来展望", "参考资料", "附录A  数据库设计", "附录B  关键技术选型"]
assert h1 == EXPECTED, f"H1 mismatch!\n got: {h1}\n exp: {EXPECTED}"
print("  [OK] H1 sequence == golden TOC")

# 3) no duplicate 数据库设计 body heading
body_db = [p.text.strip() for p in d.paragraphs if "数据库设计" in p.text and not p.text.strip().startswith("附录") and len(p.text.strip()) < 40]
assert not body_db, f"leftover DB heading: {body_db}"
print("  [OK] no leftover DB heading in body")

# 4) key content present
def has(txt):
    return any(txt in p.text for p in d.paragraphs) or any(txt in (c.text for r in d.tables for row in r.rows for c in row.cells) for r in d.tables)
checks = {
    "1.3 核心功能一览": "1.3 核心功能一览",
    "核心功能表(一句话分镜)": "一句话分镜",
    "1.4 创新点与竞争优势": "1.4 创新点与竞争优势",
    "评委版创新点(人机协作)": "不丢稿、进度不丢失",
    "2.4 技术亮点前言": "本节把平台最值得讲的技术亮点一次性讲清楚",
    "数据库结论句": "核心表结构详见附录 A",
    "附录A 数据库设计": "附录A  数据库设计",
    "附录B 关键技术选型": "附录B  关键技术选型",
    "技术选型结论(主流技术栈)": "成熟稳定的主流技术栈",
    "8.1 去重复综述": "技术优势可概括为三点",
}
for label, needle in checks.items():
    ok = any(needle in p.text for p in d.paragraphs)
    print(f"  [{'OK' if ok else 'MISSING'}] {label}")

# 5) caption monotonic + uniform + no dup
print("\n=== caption integrity ===")
pat = re.compile(r"^(图|表)\s*([\dA-Z])+([\dA-Z\.]*)\s")
seen = {}
dups = []
mono_ok = True
prev = None
for p in d.paragraphs:
    t = p.text.strip()
    if re.match(r"^(图|表)\s*\d", t) and re.match(r"^图\s*(?:[\dA-Z]+[\dA-Z\.]*)\s", t) or (t.startswith("表") and re.match(r"^表\s*[\dA-Z]+[\dA-Z\.]*\s", t)):
        m = re.match(r"^(图|表)\s*([\dA-Z]+[\dA-Z\.]*)\s", t)
        if m:
            key = (m.group(1), m.group(2))
            if key in seen:
                dups.append(t[:20])
            seen[key] = True
if dups:
    print("  [FAIL] duplicate captions:", dups)
else:
    print("  [OK] no duplicate captions")
# single pass: count numeric sequence per first-seen chapter
print("  caption kinds:", sorted(set((m) for m in re.findall(r"^(图|表)\s*([\dA-Z]+)\s", "\n".join(p.text for p in d.paragraphs), re.M))))

# 6) stale in-text figure/table refs
print("\n=== stale in-text refs ===")
stale = []
for p in d.paragraphs:
    for m in re.finditer(r"(?:如图|如表|见图|见表|见上图|见下图|图\s*[\dA-Z]+\.?\d*\s*所示|表\s*[\dA-Z]+\.?\d*\s*所示)", p.text):
        stale.append(p.text[m.start():m.start()+24])
print("  refs:", stale if stale else "(none)")

# 7) no old-style captions / orphan
print("\n=== legacy markers ===")
legacy = [t for t in (p.text.strip() for p in d.paragraphs) if re.match(r"^(图|表)[\d]+-", t)]
print("  legacy dash-captions:", legacy if legacy else "(none)")
import re as _re
orph = [p.text.strip() for p in d.paragraphs if _re.match(r"^(图|表)[0-9]+\.[0-9]+", p.text.strip()) and p.style.name=="Heading 1"]
print("  orphan H1:", orph if orph else "(none)")

print("\nALL CHECKS DONE")
