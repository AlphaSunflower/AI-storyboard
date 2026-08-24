# -*- coding: utf-8 -*-
"""Pass 1: 评委向重构 —— 章节重排 + 章节/小节重编号 + 数据库降附录 + 技术选型降附录
+ 新增 1.3 核心功能一览 / 1.4 创新点评委版 / 2.4 技术亮点摘要 / 8.1 去重复
输出到新文件，源文件不动。图表 caption 重编号在 Pass 2。"""
import copy
from docx import Document
from docx.shared import Cm, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH

SRC = r"E:\Desktop\项目计划书1_已润色版.docx"
OUT = r"E:\Desktop\项目计划书2_评委优化版.docx"

d = Document(SRC)
body = d.element.body


def kids():
    return list(body)


def find_exact(txt):
    for p in d.paragraphs:
        if p.text.strip() == txt:
            return p
    raise AssertionError("exact not found: " + txt)


def find_prefix(prefix):
    for p in d.paragraphs:
        if p.text.strip().startswith(prefix):
            return p
    raise AssertionError("prefix not found: " + prefix)


# ---------- 模板段落 ----------
T_H1 = find_exact("一、项目概述")
T_H2 = find_exact("1.1 项目背景")
T_H3 = find_exact("3.5.1 设计原则与设计系统")
T_BODY = find_prefix("随着短视频")
T_CAP = find_exact("图1.1 行业痛点")


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


def new_bullet(template, text):
    p = new_para(template, text)
    p.paragraph_format.left_indent = None
    return p


def new_caption(text):
    return new_para(T_CAP, text)


def new_table(rows):
    t = d.add_table(rows=len(rows), cols=len(rows[0]))
    base = d.tables[0]
    if base.style is not None:
        t.style = base.style
    for ri, row in enumerate(rows):
        for ci, val in enumerate(row):
            cell = t.cell(ri, ci)
            cell.text = str(val)
            if ri == 0:
                for r in cell.paragraphs[0].runs:
                    r.font.bold = True
    return t


def insert_before(anchor_el, blocks):
    for kind, payload in blocks:
        if kind == "h1":
            el = new_para(T_H1, payload)._p
        elif kind == "h2":
            el = new_para(T_H2, payload)._p
        elif kind == "h3":
            el = new_para(T_H3, payload)._p
        elif kind == "p":
            el = new_para(T_BODY, payload)._p
        elif kind == "b":
            el = new_bullet(T_BODY, payload)._p
        elif kind == "cap":
            el = new_caption(payload)._p
        elif kind == "table":
            el = payload._tbl
        else:
            raise ValueError(kind)
        anchor_el.addprevious(el)


# =========================================================
# 0) 捕获全部锚点（先于任何修改——对象引用稳定）
# =========================================================
orphan_h1 = find_exact("项目市场前景分析")
cap_h1 = find_exact("五、竞争分析")
sys_h1 = find_exact("三、系统设计与实现")
plan_h1 = find_exact("四、项目计划")
test_h1 = find_exact("五、系统测试与分析")
mkt_h1 = find_exact("六、营销战略")
fin_h1 = find_exact("七、财务预测与分析")
risk_h1 = find_exact("八、风险预测与应对")
innov_h1 = find_exact("九、创新性与技术优势")
sum_h1 = find_exact("十一、总结与未来展望")
ref_h1 = find_exact("参考资料")

db_h2 = find_exact("3.3 数据库设计")
kb_h2 = find_exact("3.4 关键技术实现")
tech_intro = find_prefix("在上述条件下，项目优先选择成熟")
tech_cap = find_prefix("表 4-2")
plan41_h2 = find_exact("4.2 排期规划")

# 1.3 核心功能一览 插入锚点：原「1.3项目创新点...」标题前
anchor_13 = find_prefix("1.3项目创新点")
# 1.4/1.5 相关标题（用于改名）
h_inn = find_prefix("1.3项目创新点及同行竞争优势对比")
h_future = find_prefix("1.4项目未来展望")
h_future1 = find_prefix("1.4.1项目总目标")
h_future2 = find_prefix("1.4.2项目价值")
h_inn1 = find_prefix("1.3.1创新点")
h_inn2 = find_prefix("1.3.2同行竞争优势对比")
# 2.4 技术亮点摘要 插入锚点：原「3.4 关键技术实现」引言段前
anchor_tech_sum = find_prefix("平台核心技术要点如下")
# 8.1 去重复锚点
innov_lead = find_prefix("平台在 AI 编排、系统架构、人机协作、模型管理四个层面")
# 参考资料最后一个条目
ref_last = find_prefix("[14] iCAN")

# =========================================================
# 1) 删除孤立块「项目市场前景分析」（空壳标题，无正文）
# =========================================================
ob = kids()
i = ob.index(orphan_h1._p)
j = ob.index(cap_h1._p)
for el in ob[i:j]:
    body.remove(el)

# =========================================================
# 2) 章节块搬移
#   物理顺序(删孤立后): 一 竞 系统 计划 测试 营销 财务 风险 创新 总结 参考
#   目标顺序:          一 系统 计划 竞 营销 财务 风险 创新 测试 总结 参考
# =========================================================
def move_block_before(start_el, end_el, dest_el):
    bb = kids()
    si = bb.index(start_el)
    ei = bb.index(end_el)
    block = bb[si:ei]
    for el in block:
        body.remove(el)
    for el in block:
        dest_el.addprevious(el)

# 移动 1：测试章(五、系统测试与分析) 移到 总结 前
move_block_before(test_h1._p, mkt_h1._p, sum_h1._p)
# 移动 2：竞争章(五、竞争分析) 移到 营销 前
move_block_before(cap_h1._p, sys_h1._p, mkt_h1._p)

# =========================================================
# 3) 数据库 3.3 整块 移至 附录A（剔除其标题，用新附录标题）
#    正文原位置插入一句结论
# =========================================================
db_block = kids()[kids().index(db_h2._p) + 1: kids().index(kb_h2._p)]
concl_db = new_para(T_BODY, "系统数据按业务功能与模型网关职责分为两库（业务库 / LLM 网关配置库），核心表结构详见附录 A。")
kb_h2._p.addprevious(concl_db._p)
for el in db_block:
    body.remove(el)
body.remove(db_h2._p)  # 删除原"3.3 数据库设计"标题本身（已由附录A标题替代）

# =========================================================
# 4) 技术选型表（表4-2 + 引言）移至 附录B；正文插一句结论
# =========================================================
tech_block = kids()[kids().index(tech_intro._p): kids().index(plan41_h2._p)]
concl_tech = new_para(T_BODY, "项目优先采用成熟稳定的主流技术栈（Java + React + PostgreSQL），以保证可维护性与扩展性；完整选型见附录 B。")
plan41_h2._p.addprevious(concl_tech._p)
for el in tech_block:
    body.remove(el)

# =========================================================
# 5) 新增「1.3 核心功能一览」+ 表格
# =========================================================
core_rows = [
    ["功能", "它是什么 / 做什么", "解决什么痛点", "创新点", "优势"],
    ["一句话分镜", "说一句需求，AI 自动生成专业分镜脚本（镜头 / 画面 / 运镜 / 台词 / 时长）", "不会画分镜、不懂镜头语言也能做", "对话式按需生成 + 关键节点人工确认", "把 3000-8000 元/分钟的专业分镜变成几分钟、近乎零成本"],
    ["AI 智能体对话", "跟一个懂分镜的 AI 帮手聊天，它实时回复、追问细节、给方案", "传统工具要学参数、翻文档才能上手", "SSE 流式，像打字一样逐字实时回复", "会打字就会用，零学习门槛"],
    ["文生图 / 图改图", "文字直接生成图片；上传参考图生成风格贴近的新图", "画图靠外包、改图要整张重做", "图改图“继续完善 / 满意完成”循环", "一个平台打通文字→图，不用跨多个工具"],
    ["图生视频", "分镜画面直接变成视频，异步后台生成", "视频制作周期长、成本高", "后台异步生成 + 进度提醒，不卡界面", "分钟级出片，全流程不离开平台"],
    ["资产库×一致性", "角色 / 道具 / 场景存成素材库，跨镜头自动复用", "AI 视频最怕“主角变脸、道具前后不一”", "素材自动关联 + 视频首帧锁定", "人物形象从头到尾一致，作品更专业"],
    ["多模型统一网关", "一套接口接入文、图、视频、理解四类 AI 模型", "单一工具绑死一个模型，涨价就挨宰", "模型热切换，上游变更零侵入", "哪家好用 / 便宜就切哪家，成本可控"],
    ["参数智能推荐", "AI 自动选好清晰度 / 时长，并说明理由，用户可改", "用户不懂参数，一填就错", "LLM 预选 + 推荐理由 + 可改", "既降低门槛，又控制生成成本"],
    ["私有化部署", "整套系统装进企业自己的内网，数据不出门", "政企怕数据泄露、不满足合规", "全容器化、开箱即用", "满足政企合规审计，打开 B 端市场"],
]
t_core = new_table(core_rows)
insert_before(anchor_13._p, [
    ("h2", "1.3 核心功能一览"),
    ("p", "为了让评委快速了解平台，这里把每一个核心功能用一句话讲清楚：它是什么、做什么、解决什么痛点、创新点在哪、优势在哪。某一行看不懂没关系，看到“优势”就能明白这个功能的价值。"),
    ("table", t_core),
    ("p", "整体来看，一句话分镜 + 图生视频 + 资产一致性三个能力构成平台的核心差异化，其余能力负责让产品更好用、更省钱、更合规。"),
])

# =========================================================
# 6) 1.4 创新点评委版：替换 1.3.1 五个技术参数为评委语言
# =========================================================
innov_judge = [
    "人机协作的创作闭环：AI 每个关键决定都先让你确认，方案不丢稿、进度不丢失——这是和“一键批量生成”类竞品最大的区别。",
    "即插即用的扩展架构：每加一个新创作能力，不用改动核心系统，像装插件一样简单，所以平台能不断接入新模型、新玩法。",
    "不绑死单一 AI：一套接口统一接入了文、图、视频、视觉理解四类模型，哪家效果更好 / 更便宜就切哪家，上游涨价用户无感。",
    "精准理解你的意图：先用关键词快速判断需求（省时省钱），再用 AI 深度确认；拿不准就多问两句，保证做出来的正是你要的。",
    "跨镜头形象一致：把角色 / 道具存成素材库自动复用，确保人物从头到尾脸、衣服、造型不变——这正是 AI 视频最容易翻车的地方。",
]
# 找到 1.3.1 下五个 bullet 段（依次替换）
inn_bullets = [p for p in d.paragraphs if p.text.strip().startswith(("Spring AI 2.0应用层编排", "微服务化架构", "HITL人机协作闭环", "LLM网关统一抽象", "意图识别双保险"))]
for idx, p in enumerate(inn_bullets):
    if idx < len(innov_judge):
        if p.runs:
            p.runs[0].text = innov_judge[idx]
            for r in p.runs[1:]:
                r._r.getparent().remove(r._r)

# =========================================================
# 7) 2.4 技术亮点摘要：在原「3.4 关键技术实现」引言前插入一段「本页统一讲一次」
# =========================================================
insert_before(anchor_tech_sum._p, [
    ("p", "本节把平台最值得讲的技术亮点一次性讲清楚，后文不再重复展开；其中每一项都来自真实模型调用与多用户并发场景的实测验证。"),
])

# =========================================================
# 8) 8.1 技术创新 去重复：删掉与 1.4/2.4 重复的五个技术 bullet + 重复的「行业痛点」图，替换为一段综述
# =========================================================
innov_para = new_para(
    T_BODY,
    "平台的技术优势可概括为三点（已在 §1.4 创新点 与 §2.4 关键技术要点 详解，此处不再重复）：① 创作能力可插拔——策略模式编排 + 多模型网关，新增能力不动核心，扩展成本低；② 全程可控不丢稿——HITL 确认点 + 资产一致性 + 参数推荐，AI 有创造力但始终在用户掌控中；③ 落地即真实——四服务微服务架构 + 全链路真实模型实测 + 容器化私有化交付，稳定、安全、可扩展。",
)
innov_lead._p.addprevious(innov_para._p)
# 删除 8.1 的五个技术 bullet 段 + 首段引言
for p in d.paragraphs:
    t = p.text.strip()
    if t.startswith(("平台在 AI 编排、系统架构、人机协作、模型管理四个层面",
                     "● Spring AI 2.0 应用层编排",
                     "● 四服务微服务架构",
                     "● HITL Checkpoint 持久化",
                     "● LLM 网关统一抽象",
                     "● 会话级互斥与异步任务",
                     "图8.1 行业痛点")):
        # 若为「图8.1 行业痛点」，把它上面的图片段一起删
        if t.startswith("图8.1 行业痛点"):
            par_el = p._p
            bb = kids()
            pi = bb.index(par_el)
            if pi > 0:
                prev = bb[pi - 1]
                if prev.tag.endswith("}p"):
                    body.remove(prev)
        p._p.getparent().remove(p._p)

# =========================================================
# 9) 补全 10.2 展望（原稿缺失，补齐总结章 10.1-10.4）
# =========================================================
anchor_102 = find_exact("10.3 项目价值与意义")
h102 = new_para(T_H2, "10.2 展望")
p102 = new_para(T_BODY, "本项目不会止步于当前版本。短期（1-3 个月）将完善视频编辑能力，如多镜头拼接、转场效果与背景音乐，推出“一键出片”；中期（3-6 个月）接入更多 AI 模型（如 Sora、Kling），通过 LLM 网关热切换丰富创作能力；长期（6-12 个月）构建分镜模板市场，支持优质分镜方案的分享与复用，并探索 AI 实时多人协作编辑，逐步形成创作者社区。")
anchor_102._p.addprevious(h102._p)
anchor_102._p.addprevious(p102._p)

# =========================================================
# 10) 章 / 节 重编号（标题段文本替换，锚点已捕获，先移到后改名）
# =========================================================
RENAMES = [
    ("五、竞争分析", "四、竞争分析"),
    ("三、系统设计与实现", "二、系统设计与实现"),
    ("四、项目计划", "三、项目计划与可行性"),
    ("五、系统测试与分析", "九、系统测试与分析"),
    ("六、营销战略", "五、营销战略"),
    ("七、财务预测与分析", "六、财务预测与分析"),
    ("八、风险预测与应对", "七、风险预测与应对"),
    ("九、创新性与技术优势", "八、创新性与技术优势"),
    ("十一、总结与未来展望", "十、总结与未来展望"),
    ("1.2项目概述", "1.2 项目概述"),
    ("1.3项目创新点及同行竞争优势对比", "1.4 创新点与竞争优势"),
    ("1.4项目未来展望", "1.5 项目未来展望"),
    ("1.4.1项目总目标", "1.5.1 项目总目标"),
    ("1.4.2项目价值", "1.5.2 项目价值"),
    ("1.3.1创新点", "1.4.1 创新点"),
    ("1.3.2同行竞争优势对比", "1.4.2 竞争优势对比"),
    ("3.1 总体架构", "2.1 总体架构"),
    ("3.1.1 架构分层", "2.1.1 架构分层"),
    ("3.1.2 应用服务职责", "2.1.2 应用服务职责"),
    ("3.1.3 AI 能力与服务治理", "2.1.3 AI 能力与服务治理"),
    ("3.1.2微服务框架设计", "2.1.4 微服务框架设计"),
    ("3.2 功能模块设计", "2.2 功能模块设计"),
    ("4.4 功能演示", "2.3 功能演示"),
    ("3.4 关键技术实现", "2.4 关键技术要点"),
    ("3.5 用户交互设计", "2.5 用户交互设计"),
    ("3.5.1 设计原则与设计系统", "2.5.1 设计原则与设计系统"),
    ("3.5.2 信息架构与页面布局", "2.5.2 信息架构与页面布局"),
    ("3.5.3 核心交互流程", "2.5.3 核心交互流程"),
    ("3.5.4 关键交互组件规范", "2.5.4 关键交互组件规范"),
    ("3.5.5 移动端适配设计", "2.5.5 移动端适配设计"),
    ("3.5.6 可用性与容错设计", "2.5.6 可用性与容错设计"),
    ("3.5.7 多端产品规划（Web → 桌面 → 移动 App）", "2.5.7 多端产品规划（Web → 桌面 → 移动 App）"),
    ("3.6 性能、并发与异常处理", "2.6 性能、并发与异常处理"),
    ("3.7 安全设计", "2.7 安全设计"),
    ("3.8 模型调用成本管控与计费实现", "2.8 模型调用成本管控与计费"),
    ("3.9 项目原型完成度", "2.9 项目原型完成度"),
    ("4.1 可行性分析", "3.1 可行性分析"),
    ("4.2 排期规划", "3.2 排期规划"),
    ("4.3 商业模式与付费机制", "3.3 商业模式与付费机制"),
    ("4.4 企业私有化部署方案", "3.4 企业私有化部署方案"),
    ("5.1 行业背景与市场现状", "4.1 行业背景与市场现状"),
    ("5.2 主要竞品", "4.2 主要竞品"),
    ("5.3 竞品对比矩阵", "4.3 竞品对比矩阵"),
    ("5.4 差异化定位与 SWOT 分析", "4.4 差异化定位与 SWOT 分析"),
    ("6.1 目标市场与用户画像", "5.1 目标市场与用户画像"),
    ("6.2 市场定位与价值主张", "5.2 市场定位与价值主张"),
    ("6.3 营销渠道与推广策略", "5.3 营销渠道与推广策略"),
    ("6.4 增长策略与商业转化路径", "5.4 增长策略与商业转化路径"),
    ("7.1 成本结构分析", "6.1 成本结构分析"),
    ("7.2 收入模型", "6.2 收入模型"),
    ("7.3 三年财务预测", "6.3 三年财务预测"),
    ("7.4 盈亏平衡分析", "6.4 盈亏平衡分析"),
    ("8.1 已识别风险与应对措施", "7.1 已识别风险与应对措施"),
    ("8.2 SWOT分析", "7.2 SWOT 分析"),
    ("8.3 风险分类分析", "7.3 风险分类分析"),
    ("7.4 风险矩阵", "7.4 风险矩阵"),
    ("9.1 技术创新", "8.1 技术创新"),
    ("9.2 功能创新", "8.2 功能创新"),
    ("9.3 技术壁垒对比", "8.3 技术壁垒对比"),
    ("9.4 知识产权规划", "8.4 知识产权规划"),
    ("10.1 测试策略", "9.1 测试策略"),
    ("10.2 功能测试", "9.2 功能测试"),
    ("10.3 性能测试", "9.3 性能测试"),
    ("10.4 安全测试", "9.4 安全测试"),
]
for p in d.paragraphs:
    st = p.text.strip()
    for old, new in RENAMES:
        if st == old:
            if p.runs:
                p.runs[0].text = new
                for r in p.runs[1:]:
                    r._r.getparent().remove(r._r)
            else:
                p.add_run(new)
            break

# =========================================================
# 10) 附录A / 附录B（追加到参考资料之后）
# =========================================================
last = ref_last._p
appA_h1 = new_para(T_H1, "附录A  数据库设计")
last.addnext(appA_h1._p)
last = appA_h1._p
for el in db_block:
    last.addnext(el)
    last = el

appB_h1 = new_para(T_H1, "附录B  关键技术选型")
last.addnext(appB_h1._p)
last = appB_h1._p
for el in tech_block:
    last.addnext(el)
    last = el

# =========================================================
d.save(OUT)
print("PASS1 saved:", OUT)
print("paragraphs:", len(d.paragraphs), "tables:", len(d.tables), "inline_shapes:", len(d.inline_shapes))
