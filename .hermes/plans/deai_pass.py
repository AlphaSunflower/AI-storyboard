# -*- coding: utf-8 -*-
"""Pass 3 (去AI味): ① ● -> ①②③ 组内编号  ② 无数字夸赞词 -> 数字/大白话  ③ 高曝光句口语化。
只作用于文本层，保留 run/图片结构（安全 set_text）。运行于 renumber_captions.py 之后。"""
import re
from docx import Document
from docx.oxml.ns import qn

SRC = r"E:\Desktop\项目计划书2_评委优化版.docx"
OUT = r"E:\Desktop\项目计划书3_去AI版.docx"

d = Document(SRC)


def has_drawing(r):
    return r._r.find(qn("w:drawing")) is not None or r._r.find(qn("w:pict")) is not None


def set_text(p, text):
    runs = list(p.runs)
    first_text = None
    for r in runs:
        if not has_drawing(r):
            first_text = r
            break
    if first_text is None:
        return
    first_text.text = text
    for r in runs:
        if r is first_text or has_drawing(r):
            continue
        r._r.getparent().remove(r._r)


def para_text(p):
    return p.text


# ---------- ① 分点标志：● -> ①②③（按组，先算后改） ----------
CIRCLES = "①②③④⑤⑥⑦⑧⑨"


def circle(n):
    if 1 <= n <= 9:
        return CIRCLES[n - 1]
    return f"({n})"


paras = list(d.paragraphs)
assign = []
group_pos = 0
prev_was_bullet = False
for i, p in enumerate(paras):
    t = p.text.strip()
    if t.startswith("●"):
        group_pos = group_pos + 1 if prev_was_bullet else 1
        assign.append((p, circle(group_pos) + " " + t[1:].lstrip()))
        prev_was_bullet = True
    else:
        prev_was_bullet = False
bullet_groups = assign
for p, new in assign:
    set_text(p, new)

# ---------- ② 无数字夸赞词 -> 数字/大白话（精确整词，防止误伤技术实义） ----------
PHRASES = {
    "平台以智能分镜为核心场景，同时提供文生图、图改图、视频生成等通用 AI 创作能力——这些能力可独立使用，也可组合成完整创作流水线，服务于短视频、电商、广告、影视、游戏、教育等全行业的视觉内容生产。":
        "平台的核心是智能分镜，顺手也把文生图、图改图、视频生成一起做了——每样都能单独用，也能串成一条流水线，短视频、电商、广告、影视、游戏、教育都能用上。",
    "让每个创作者都拥有专业级的分镜与视觉内容生产能力":
        "让每个创作者都做得出专业级分镜，哪怕不会画、不懂镜头",
    "效率：需求到分镜方案分钟级完成，图片/视频小时级出片，相比人工外包效率提升显著":
        "效率：3-5 天的分镜活，现在 30 分钟出首稿；图片、视频也按小时出片，快得很",
    "相比人工外包效率提升显著": "比找外包快一个数量级",
    "把专业分镜制作成本从3000-8000元/分钟大幅降低": "把 3000-8000 元/分钟的专业分镜成本，降到几元/分钟",
    "成本大幅下降，让中小团队与个人创作者获得专业级视觉内容生产能力":
        "成本降到几元/分钟，中小团队和个人创作者也用得起专业级制作",
    "成本降低一个数量级": "成本降到原来的百分之一以下",
    "效率提升一个数量级": "效率快了一个数量级",
    "全链路一站式闭环": "一个平台从需求走到出片，一竿子到底",
    "一站式 AI 创作工作台": "一个平台全搞定",
    "一站式非线性编辑体验": "一个平台里来回调，不用切工具",
    "全流程一站式": "从说到片一个平台走到底",
    "全流程一站式详细对比情况": "全流程到底怎么比",
    "一站式 AIGC 创作平台": "一个平台全都能做",
    "精准匹配用户意图": "听懂了你要什么",
    "确保 AI 生成结果精准匹配用户意图": "让 AI 听懂了你要什么",
    "模型参数智能推荐，确保生成结果精准可控": "AI 帮你把参数选好，生成结果拿得准",
    "可控高效工具链": "好用、又在你掌控里的工具链",
    "高效": "快",
    "强大": "顶用",
    "具备较大的市场发展潜力": "市场空间不小",
    "具备技术创新性与商业价值": "技术上真做了东西，商业上也立得住",
    "赋能": "帮",
    "助力短视频、电商、广告、影视等产业降本增效": "让短视频、电商、广告、影视这些行业少花钱、出活快",
    "为'AI有创造力且可控'提供行业级解决方案参考":
        "给'AI 能干活又听人话'立了个样板",
    "用自然语言对话为主入口": "用大白话对话就行",
    "AI 有创造力但始终在用户掌控中": "AI 能干活，但每步都听你的",
    "让专业创作者获得专业级视觉内容生产能力": "让普通创作者也能做出专业级的片子",
    "品牌知名度低；团队规模小": "没名气；人少",
    "提升效率显著": "快了不少",
    "精准理解你的意图": "听懂你的意图",
    "确保精准理解": "确保听懂你要什么",
    "生成结果精准可控": "生成结果拿得准",
    "确保 AI 输出精准匹配意图": "让 AI 听懂你要什么",
    "从3000-8000元/分钟大幅降低": "从 3000-8000 元/分钟 降到几元/分钟",
    "助力短视频、电商、广告、影视等全行业降本增效": "让短视频、电商、广告、影视这些行业少花钱、出活快",
    "指标": "指标",
    "降低专业级内容获取门槛": "让专业制作的门槛低下来",
    "缩短营销上线周期": "让东西更快上线",
}
for p in d.paragraphs:
    t = p.text
    nt = t
    for old, new in PHRASES.items():
        if old in nt:
            nt = nt.replace(old, new)
    if nt != t:
        set_text(p, nt)

# 表格单元格（价值主张/画像等夸赞词）
for tbl in d.tables:
    for row in tbl.rows:
        for cell in row.cells:
            for p in cell.paragraphs:
                t = p.text
                nt = t
                for old, new in PHRASES.items():
                    if old in nt:
                        nt = nt.replace(old, new)
                if nt != t:
                    set_text(p, nt)

# ---------- ③ 高曝光句口语化 ----------
SENT = {
    "AI Storyboard 是一个 AI 驱动的多模态视觉内容创作平台。":
        "AI Storyboard 就干一件事：让你用一句话做出专业分镜。",
    "AI Storyboard 针对全行业视觉内容创作效率低、门槛高、迭代难的核心痛点，提出\"对话式需求理解 + AI 智能编排 + 多模态内容生成\"的一体化解决方案，不止服务 AI 分镜，文生图、图改图、视频生成等能力面向短视频、电商、广告、影视、游戏等全行业场景。":
        "AI Storyboard 要解决的问题很实在：全行业做视觉内容，效率低、门槛高、改起来费劲。我们把\"你说需求、AI 编排、多模态生成\"串成一条线，不止做分镜——文生图、图改图、视频生成也一起覆盖短视频、电商、广告、影视、游戏这些行业。",
    "让每个创作者都拥有专业级的分镜与视觉内容生产能力":
        "让每个创作者都做得出专业级分镜，哪怕不会画、不懂镜头",
}
for p in d.paragraphs:
    t = p.text
    nt = t
    for old, new in SENT.items():
        if old in nt:
            nt = nt.replace(old, new)
    if nt != t:
        set_text(p, nt)

d.save(OUT)
print("PASS3 de-AI done. bullet->circle count:", len(assign))
print("saved:", OUT)
