# -*- coding: utf-8 -*-
"""全量验证：zip/XML/关键词/标题顺序/表格/图片/页数估算"""
import zipfile, re, sys
from docx import Document
from lxml import etree

SRC = r"E:\Desktop\项目计划书1.docx"
fails = []

# 1) zip 完整性
z = zipfile.ZipFile(SRC)
bad = z.testzip()
if bad: fails.append("zip corrupt: " + bad)
xml = z.read("word/document.xml")
etree.fromstring(xml)  # XML 可解析

# 2) python-docx 打开
d = Document(SRC)

# 3) 关键词集
kws = ["二、系统设计与实现", "2.3 数据库设计", "2.5 用户交互设计", "2.7 安全设计",
       "四、竞争分析", "五、营销战略", "六、财务预测与分析", "七、风险预测与应对",
       "7.3 风险矩阵", "八、创新性与技术优势", "8.4 知识产权", "九、系统测试与分析",
       "9.2 功能测试", "十、总结与未来展望", "10.3 项目价值与意义", "10.4 致谢",
       "SWOT", "盈亏平衡", "图 5", "图 6", "图 7", "图 8", "图 9", "图 10",
       "表 30", "表 1", "对话式分镜创作交互时序图", "企业私有化部署架构图",
       "如表所示", "四服务职责", "SSE 事件协议", "应急预案", "敏感性分析", "转化漏斗"]
texts = [p.text for p in d.paragraphs]
full = "\n".join(texts)
for t in d.tables:
    for r in t.rows:
        for c in r.cells:
            full += "\n" + c.text
for kw in kws:
    if kw not in full: fails.append("缺关键词: " + kw)

# 4) Heading1 顺序（过滤空段与封面残留，章节按序出现即可）
h1s = [p.text.strip() for p in d.paragraphs if p.style.name == "Heading 1" and p.text.strip()]
expect = ["一、项目概述", "二、系统设计与实现", "三、项目计划", "四、竞争分析", "五、营销战略",
          "六、财务预测与分析", "七、风险预测与应对", "八、创新性与技术优势", "九、系统测试与分析",
          "十、总结与未来展望", "参考资料"]
pos = 0
for e in expect:
    if e not in h1s:
        fails.append("H1 缺: " + e)
    else:
        pos = h1s.index(e, pos)  # 按序出现

# 5) Heading2 小节号连续性（二章内）
h2s = [p.text.strip() for p in d.paragraphs if p.style.name == "Heading 2" and p.text.strip().startswith(("2.", "7.", "10."))]
need = ["2.1 总体架构", "2.2 功能模块设计", "2.3 数据库设计", "2.4 关键技术实现", "2.5 用户交互设计",
        "2.6 性能、并发与异常处理", "2.7 安全设计", "2.8 模型调用成本管控与计费实现", "2.9 项目原型完成度",
        "7.1 已识别风险与应对措施", "7.2 风险分类分析", "7.3 风险矩阵", "10.1 总结", "10.2 展望",
        "10.3 项目价值与意义", "10.4 致谢"]
for n in need:
    if n not in h2s: fails.append("缺小节: " + n)

# 6) 表格断言（按内容定位，不按索引——新表插在排期表之前）
def find_table(kw):
    for t in d.tables:
        if kw in "\n".join(c.text for r in t.rows for c in r.cells):
            return t
    return None
rev_t = find_table("修订原因")
if rev_t is None or "1.4.0" not in "\n".join(c.text for r in rev_t.rows for c in r.cells):
    fails.append("修订历史表缺 1.4.0 行")
if find_table("里程碑") is None: fails.append("排期表丢失")
t5 = None
for t in d.tables:
    txt = "\n".join(c.text for r in t.rows for c in r.cells)
    if "Midjourney" in txt and "Runway/Pika" in txt and "结构化分镜脚本" in txt:
        t5 = t
        break
if t5 is None or len(t5.rows) != 9 or len(t5.columns) != 9:
    fails.append(f"竞品矩阵尺寸异常 {None if t5 is None else (len(t5.rows), len(t5.columns))}")
t13 = find_table("登录鉴权")
if t13 is None or len(t13.rows) != 13: fails.append(f"测试用例行数异常 {None if t13 is None else len(t13.rows)}")
t11 = find_table("恶意刷量")
if t11 is None or len(t11.rows) != 9: fails.append(f"风险矩阵行数异常 {None if t11 is None else len(t11.rows)}")

# 7) 图片数
n_shapes = len(d.inline_shapes)
if n_shapes < 24: fails.append(f"图片数不足 {n_shapes}")
# 表格数 32、修订历史无编号 caption
if len(d.tables) != 32: fails.append(f"表格数异常 {len(d.tables)}")
if "表 1 修订历史" in full: fails.append("修订历史表被错误加 caption")
# 修订历史第 5 行（表 1.4.0）
if "1.4.0" not in "\n".join(c.text for r in d.tables[0].rows for c in r.cells):
    fails.append("修订历史缺 1.4.0")

# 8) 页数估算：正文段数*0.22 + 表格行/6 + 图片高度占比（粗估）
n_para = len([p for p in d.paragraphs if p.text.strip()])
n_tbl_rows = sum(len(t.rows) for t in d.tables)
n_figs = n_shapes
est = n_para * 0.20 + n_tbl_rows / 6 + n_figs * 0.9
print(f"para={n_para} tbl_rows={n_tbl_rows} figs={n_figs} 估算页数≈{est:.0f}")
print(f"H1: {' | '.join(h1s)}")
print("FAILS:", fails if fails else "无 ✅")
sys.exit(1 if fails else 0)
