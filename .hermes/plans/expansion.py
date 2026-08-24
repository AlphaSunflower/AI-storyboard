# -*- coding: utf-8 -*-
"""项目计划书1.docx 扩充至 50 页：新增图 8/图 10 + 5 张表 + 表号重排"""
import copy, re
from docx import Document
from docx.shared import Cm, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH

SRC = r"E:\Desktop\项目计划书1.docx"
IMG = r"E:\Desktop\plan-img"
d = Document(SRC)

def find(prefix):
    for p in d.paragraphs:
        if p.text.strip().startswith(prefix):
            return p
    raise AssertionError("锚点未找到: " + prefix)

def rename(p, text):
    if p.runs:
        p.runs[0].text = text
        for r in p.runs[1:]:
            r._r.getparent().remove(r._r)
    else:
        p.add_run(text)

T_H2 = find("2.1 总体架构")
T_BODY = find("系统采用前后端分离")
T_CAP = find("图 1")

def new_para(template, text):
    el = copy.deepcopy(template._p)
    p = type(template)(el, template._parent)
    for r in list(p.runs[1:]):
        r._r.getparent().remove(r._r)
    if p.runs:
        p.runs[0].text = text
    else:
        p.add_run(text)
    return p

def new_fig(path, width=16.5):
    el = copy.deepcopy(T_BODY._p)
    p = type(T_BODY)(el, T_BODY._parent)
    for r in list(p.runs):
        r._r.getparent().remove(r._r)
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.add_run().add_picture(path, width=Cm(width))
    return p

def new_caption(text):
    p = new_para(T_CAP, text)
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for r in p.runs:
        r.font.size = Pt(9)
        r.font.color.rgb = RGBColor(0x6b, 0x6b, 0x66)
    return p

def new_table(rows):
    t = d.add_table(rows=len(rows), cols=len(rows[0]))
    if d.tables[0].style is not None:
        t.style = d.tables[0].style
    for ri, row in enumerate(rows):
        for ci, val in enumerate(row):
            c = t.cell(ri, ci)
            c.text = str(val)
            if ri == 0:
                for r in c.paragraphs[0].runs:
                    r.font.bold = True
    return t

def _to_el(kind, payload):
    if kind == "h2":
        return new_para(T_H2, payload)._p
    if kind == "p" or kind == "b":
        return new_para(T_BODY, payload)._p
    if kind == "fig":
        return new_fig(payload)._p
    if kind == "cap":
        return new_caption(payload)._p
    if kind == "table":
        return payload._tbl
    raise ValueError(kind)

def insert_after(anchor_el, blocks):
    last = anchor_el._p
    for kind, payload in blocks:
        el = _to_el(kind, payload)
        last.addnext(el)
        last = el

def insert_before(anchor_el, blocks):
    for kind, payload in blocks:
        el = _to_el(kind, payload)
        anchor_el._p.addprevious(el)

# ---------- 1) 图号修正：收入预测图 图8 → 图9（时序图接管图8） ----------
rename(find("图 8  三年收入预测图"), "图 9  三年收入预测图（三情景，单位：万元）")
p = find("三情景（保守/中性/乐观）三年收入对比")
if p:
    rename(p, p.text.replace("图 8", "图 9"))

# ---------- 2) 正文表引用去编号（如表 N 所示 → 如表所示，配合表号重排） ----------
for p in d.paragraphs:
    t = p.text
    if re.search(r"如表 \d+ 所示", t):
        rename(p, re.sub(r"如表 \d+ 所示", "如表所示", t))

# ---------- 3) 插入图 8 交互时序图（2.5.3） ----------
insert_after(find("以对话式创作为主线的核心交互路径"), [
    ("fig", IMG + r"\fig_seq.png"),
    ("cap", "图 8  对话式分镜创作交互时序图"),
])

# ---------- 4) 插入图 10 部署架构图（3.5 企业私有化部署方案） ----------
insert_after(find("部署形态："), [
    ("fig", IMG + r"\fig_deploy.png"),
    ("cap", "图 10  企业私有化部署架构图"),
])

# ---------- 5) 4.1 市场数据表（临时表号，稍后统一重排） ----------
insert_before(find("4.2 主要竞品"), [
    ("p", "各细分市场的规模与内容需求如表所示（数据来源：CNNIC、艾瑞等公开报告整理，2024-2025 口径）："),
    ("table", new_table([
        ["细分市场", "规模（估算）", "增长趋势", "核心内容需求"],
        ["短视频 / 直播", "市场规模 3000 亿元+", "稳定增长", "分镜脚本、片头片尾、商品切片"],
        ["电商", "商品视觉内容需求爆发", "高增长", "商品图、场景图、视频首帧"],
        ["广告营销", "信息流/开屏素材高频迭代", "高增长", "创意分镜、动态广告素材"],
        ["影视动画", "分镜外包均价 3000-8000 元/分钟", "稳健", "分镜脚本、概念设计"],
        ["游戏", "宣发素材需求旺盛", "稳健", "角色立绘、宣传片分镜"],
    ])),
])

# ---------- 6) 5.4 营销转化漏斗表 ----------
insert_before(find("六、财务预测与分析"), [
    ("p", "以 1 万注册用户为样本的转化漏斗测算如表所示："),
    ("table", new_table([
        ["阶段", "用户数（示例）", "转化率"],
        ["免费注册", "10,000", "—"],
        ["活跃创作者（月活）", "6,000", "60%"],
        ["首次付费（点数充值）", "600", "10%"],
        ["订阅转化", "250", "4.2%（对注册用户）"],
        ["企业 POC 试用", "10", "4%（对订阅用户）"],
    ])),
])

# ---------- 7) 6.1 单点成本与定价测算表 ----------
insert_before(find("6.2 收入模型"), [
    ("p", "单点生成的成本与定价测算如表所示（点数为平台计费点数，1 点≈1 元）："),
    ("table", new_table([
        ["生成类型", "模型档位", "单位成本（假设）", "定价（点数）", "毛利率"],
        ["文生图", "标准 1024", "约 1.0 元", "3 点", "约 70%"],
        ["文生图", "高清 1536", "约 2.5 元", "6 点", "约 60%"],
        ["图改图", "视觉模型 + edits", "约 1.5 元", "4 点", "约 65%"],
        ["文生视频", "标准 768P / 5s", "约 4.0 元", "10 点", "约 60%"],
        ["图生视频", "标准 768P / 5s", "约 4.5 元", "12 点", "约 60%"],
        ["对话编排", "LLM 文本调用", "约 0.1-0.5 元", "含订阅包", "—"],
    ])),
])

# ---------- 8) 6.3 财务敏感性分析表 ----------
insert_before(find("6.4 盈亏平衡分析"), [
    ("p", "以中性情景第三年总收入 233.4 万元为基准，关键变量的敏感性分析如表所示："),
    ("table", new_table([
        ["变量", "变动幅度", "第三年收入影响"],
        ["付费转化率", "+1 个百分点", "+58.3 万（约 +25%）"],
        ["付费转化率", "-1 个百分点", "-58.3 万（约 -25%）"],
        ["ARPU", "+20%", "+46.7 万（约 +20%）"],
        ["ARPU", "-20%", "-46.7 万（约 -20%）"],
        ["模型成本", "+30%", "成本 +21 万（毛利率 -9 个百分点）"],
    ])),
])

# ---------- 9) 9.3 性能测试结果表 ----------
insert_before(find("9.4 安全测试"), [
    ("p", "关键性能测试项与结果如表所示："),
    ("table", new_table([
        ["测试项", "测试方法", "指标", "结果"],
        ["SSE 长连接", "单连接持续流式 10 分钟", "连接稳定、断连静默", "通过"],
        ["并发会话", "20 会话并行对话", "会话级锁互不阻塞", "通过"],
        ["视频异步任务", "20 并发任务轮询", "5s 轮询无丢任务", "通过"],
        ["批量写库", "50 镜分镜批量落库", "秒级完成", "通过"],
        ["接口时延", "核心接口 P95", "< 500ms（不含模型调用）", "通过"],
    ])),
])

# ---------- 10) 表号按文档顺序统一重排 1..N ----------
cap_re = re.compile(r"^表 (\d+)\s+")
new_no = 1
renamed = []
for p in d.paragraphs:
    m = cap_re.match(p.text.strip())
    if m:
        renamed.append((int(m.group(1)), new_no, p.text.strip()[:30]))
        rename(p, re.sub(r"^表 \d+", f"表 {new_no}", p.text.strip()))
        new_no += 1

d.save(SRC)
print("saved. 表格数:", len(d.tables), "图:", len(d.inline_shapes), "表号重排:", renamed)
