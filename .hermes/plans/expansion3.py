# -*- coding: utf-8 -*-
"""项目计划书1.docx 第三轮扩充：/chat 界面设计 + 移动端适配扩写 + 多端产品规划"""
import copy
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

T_H3 = find("2.5.1 设计原则与设计系统")
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

def new_caption(text):
    p = new_para(T_CAP, text)
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for r in p.runs:
        r.font.size = Pt(9)
        r.font.color.rgb = RGBColor(0x6b, 0x6b, 0x66)
    return p

def new_fig(path, width=16.5):
    el = copy.deepcopy(T_BODY._p)
    p = type(T_BODY)(el, T_BODY._parent)
    for r in list(p.runs):
        r._r.getparent().remove(r._r)
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.add_run().add_picture(path, width=Cm(width))
    return p

def insert_before(anchor_el, blocks):
    for kind, payload in blocks:
        if kind == "h3":
            el = new_para(T_H3, payload)._p
        elif kind in ("p", "b"):
            el = new_para(T_BODY, payload)._p
        elif kind == "fig":
            el = new_fig(payload)._p
        elif kind == "cap":
            el = new_caption(payload)._p
        else:
            raise ValueError(kind)
        anchor_el._p.addprevious(el)

# ---------- 1) 收入预测图 图9 → 图11（移动端图接管图9） ----------
rename(find("图 9  三年收入预测图"), "图 11  三年收入预测图（三情景，单位：万元）")
p = find("三情景（保守/中性/乐观）三年收入对比")
if p:
    rename(p, p.text.replace("图 9", "图 11"))

# ---------- 2) 页面清单表加 /chat 行 ----------
page_t = None
for t in d.tables:
    cells = [c.text for c in t.rows[0].cells]
    if "页面" in cells and "入口" in cells:
        page_t = t
        break
assert page_t is not None, "页面清单表未找到"
row = page_t.add_row()
for ci, v in enumerate(["AI 对话主页", "/chat", "仿 DeepSeek 独立对话、会话管理、产出素材"]):
    row.cells[ci].text = v

# ---------- 3) 2.5.2 后插 /chat 界面设计段（锚点 2.5.3 前） ----------
insert_before(find("2.5.3 核心交互流程"), [
    ("p", "独立 AI 对话主页（/chat）：平台默认路由，仿 DeepSeek 桌面端设计——左侧会话栏可收起（展开为完整列表、收起为 48px 图标 rail），中部为居中对话流（SSE 打字机逐字输出），底部为多行自适应输入卡；首次进入时展示“产品名居中 + 输入卡居中”的 hero 空态，引导用户直接开始创作。会话顶部提供模型切换与“产出素材”资源库入口（独立一行，DeepSeek ghost 风格）。"),
    ("p", "/chat 独立对话页与编辑器内智能体抽屉（62vw）构成“独立页 + 轻量抽屉”双入口：深度创作场景进入编辑器三栏工作台，快速问答与多会话管理场景使用 /chat 独立页，两者共享同一套会话/消息/资产数据模型，切换零成本。"),
])

# ---------- 4) 2.5.5 移动端扩写 + 图 9 移动端示意图（锚点 2.5.6 前） ----------
insert_before(find("2.5.6 可用性与容错设计"), [
    ("fig", IMG + r"\fig_mobile.png"),
    ("cap", "图 9  移动端界面示意图（/chat 对话页，≤768px 断点）"),
    ("b", "● 页面级适配：/chat 与 /editor 均提供移动布局——底部导航（聊天/创作/项目/我的）跨页常驻复用，编辑器三栏收敛为单栏流式，智能体抽屉全屏模态化；"),
    ("b", "● 触控与阅读：目标热区不小于 44px，输入区底部按安全区（Safe Area）避让，对话列表随键盘弹起自动滚动，适配全面屏与手势操作；"),
    ("b", "● 能力不缩水：模型/参数选择器、HITL 确认卡片、SSE 打字机在移动端完整保留（选择器改为底部抽屉式），不因端而阉割创作能力；"),
    ("b", "● 性能：移动端组件按需加载、图片渐进式渲染，弱网下流式输出仍保持可读。"),
])

# ---------- 5) 2.5.7 多端产品规划（锚点 2.6 前） ----------
insert_before(find("2.6 性能、并发与异常处理"), [
    ("h3", "2.5.7 多端产品规划（Web → 桌面 → 移动 App）"),
    ("p", "项目以 Web 端为当前形态，后续按“软件产品”路线持续演进：① 桌面客户端——基于 Electron/Tauri 封装现有前端，支持离线创作与本地素材库，满足专业用户高频使用；② 移动 App——基于 React Native / uni-app 复用现有 API 与设计语言，实现随时随地创作与确认；③ 跨端体验——账号、会话、资产全量云端同步，多端无缝切换。前端已按跨端设计（响应式布局、Zustand 状态管理、生成逻辑与 DOM 解耦），为多端复用预留了架构基础。"),
])

d.save(SRC)
print("saved. 表格数:", len(d.tables), "图:", len(d.inline_shapes))
