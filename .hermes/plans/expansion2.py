# -*- coding: utf-8 -*-
"""项目计划书1.docx 第二轮扩充：12 张表 + 文字段落（表号最后统一重排）"""
import copy, re
from docx import Document
from docx.shared import Cm, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH

SRC = r"E:\Desktop\项目计划书1.docx"
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

T_H1 = find("一、项目概述")
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
    if kind == "h1": return new_para(T_H1, payload)._p
    if kind == "h2": return new_para(T_H2, payload)._p
    if kind in ("p", "b"): return new_para(T_BODY, payload)._p
    if kind == "cap": return new_caption(payload)._p
    if kind == "table": return payload._tbl
    raise ValueError(kind)

def insert_before(anchor_el, blocks):
    for kind, payload in blocks:
        anchor_el._p.addprevious(_to_el(kind, payload))

# ---------- 2.1 四服务职责表 + 部署运维段 ----------
insert_before(find("2.2 功能模块设计"), [
    ("p", "四服务的职责划分与部署端口如表所示："),
    ("table", new_table([
        ["服务", "端口", "核心职责", "技术栈"],
        ["API Gateway", "8080", "JWT 统一验签、路由分发、CORS、内部接口屏蔽", "Spring Cloud Gateway + Nacos"],
        ["主后端", "8082", "项目/分镜/资产/文件/认证等核心业务", "Spring Boot 4 + MyBatis-Plus + PostgreSQL"],
        ["MoonAgent", "8084", "意图编排、HITL、会话管理、多模态生成", "Spring AI 2.0 + JDK 21 虚拟线程"],
        ["LLM 网关", "8083", "模型路由、协议转换、密钥管理、统一计费点", "Spring Boot + JDK HttpClient"],
    ])),
    ("p", "部署与运维设计：全服务 Docker 化交付，配置经 Spring Profile（local/prod）与环境变量注入；日志统一输出与采集，AI 调用耗时/失败率按模型、渠道维度监控；网关渠道 QPS 限流（令牌桶）保障上游稳定，服务注册于 Nacos 支持多实例水平扩展与滚动发布。"),
])

# ---------- 2.2 模块关键接口表 ----------
insert_before(find("2.3 数据库设计"), [
    ("p", "各功能模块的关键接口如表所示（均经 API Gateway 统一入口）："),
    ("table", new_table([
        ["模块", "关键端点", "说明"],
        ["认证", "/api/auth/login、/refresh、/unlogin", "登录、刷新 token、跨系统无登录跳转"],
        ["项目", "/api/projects", "项目 CRUD 与草稿状态管理"],
        ["分镜", "/api/scenes", "分镜场景 CRUD 与批量写库"],
        ["Agent", "/api/agent/conversations/**", "会话/消息/资产/SSE 流式/HITL 提交"],
        ["生成", "/api/ai/**", "文生图/图改图/视频任务创建与轮询"],
        ["文件", "/api/files/**", "图片/视频静态资源服务"],
        ["网关管理", "/admin/**（网关 8083）", "渠道/模型/参数 CRUD（Admin JWT）"],
    ])),
])

# ---------- 2.4 SSE 事件协议表 ----------
insert_before(find("2.5 用户交互设计"), [
    ("p", "SSE 流式事件协议由后端编排器直接发射，事件类型与负载字段如表所示："),
    ("table", new_table([
        ["事件", "负载字段", "说明"],
        ["message", "content", "回答增量（前端逐段拼接打字机效果）"],
        ["workflow", "title, status", "节点进度（node_started / node_finished）"],
        ["human_input", "formToken, taskId, formContent, actions", "HITL 暂停点，收到后流结束，前端渲染确认卡片"],
        ["task_accepted", "taskId, message", "视频异步任务受理，前端转 5s 轮询"],
        ["message_end", "messageId, sceneCount, content, title?", "流正常结束；title 为异步重命名的一次性新标题"],
        ["error", "code, message", "失败结束，message 已脱敏"],
    ])),
])

# ---------- 2.6 异常处理对照表 ----------
insert_before(find("2.7 安全设计"), [
    ("p", "业务异常码与前端提示的对照关系如表所示："),
    ("table", new_table([
        ["错误码", "HTTP 状态", "典型场景", "前端提示"],
        ["40001", "400", "参数错误", "请检查输入内容"],
        ["40101", "401", "未登录 / token 过期", "请重新登录"],
        ["40301", "403", "无操作权限", "您无权执行该操作"],
        ["40401", "404", "资源不存在或无权访问", "内容不存在或已删除"],
        ["40901", "409", "会话并发冲突", "上一个任务正在进行中，请稍候"],
        ["50201", "502", "上游模型服务失败", "服务繁忙，请稍后重试"],
    ])),
])

# ---------- 2.7 安全控制点表 ----------
insert_before(find("2.8 模型调用成本管控与计费实现"), [
    ("p", "安全控制点与实施机制汇总如表所示："),
    ("table", new_table([
        ["控制点", "实施机制"],
        ["身份认证", "JWT 双 token，网关层统一验签"],
        ["服务间通信", "X-Internal-Token 鉴权，内部接口对外屏蔽"],
        ["密钥存储", "上游 API Key AES 加密，仅网关持有"],
        ["越权防护", "资源归属校验，40401 统一文案防 IDOR"],
        ["密码安全", "scrypt 加盐哈希存储"],
        ["内容合规", "上游审核 + 私有化数据不出域"],
    ])),
])

# ---------- 3.1 技术选型对比表 ----------
insert_before(find("3.2 排期规划"), [
    ("p", "关键技术选型与备选方案对比如表所示："),
    ("table", new_table([
        ["技术点", "选用方案", "备选方案", "选型理由"],
        ["AI 编排", "Spring AI 2.0", "手写 HttpClient", "统一抽象、流式、结构化输出、多模态"],
        ["前端框架", "React 19 + TypeScript", "Vue 3", "生态成熟、类型安全"],
        ["状态管理", "Zustand", "Redux", "轻量、hooks 友好"],
        ["后端框架", "Spring Boot 4", "—", "微服务生态、虚拟线程"],
        ["数据库", "PostgreSQL", "MySQL", "JSONB、事务、索引能力"],
        ["流式推送", "SSE", "WebSocket", "单向流更简单、断连静默"],
    ])),
])

# ---------- 3.3 定价矩阵表 ----------
insert_before(find("3.4 功能演示"), [
    ("p", "各版本定价矩阵如表所示（价格为建议值，可按市场反馈调整）："),
    ("table", new_table([
        ["版本", "价格", "包含额度", "核心功能"],
        ["免费版", "0 元", "每月 20 点", "基础创作体验、水印输出"],
        ["专业版", "49-99 元/月", "每月 500 点起", "全部高级功能、无广告、高清出图"],
        ["团队版", "299 元/月起（按席位）", "共享额度池", "共享资产库、项目协作、统一账单"],
        ["企业版", "授权 5-20 万元 + 年费", "私有化部署", "专属部署、渠道接入、定制功能"],
    ])),
])

# ---------- 4.2 竞品定价对比表 ----------
insert_before(find("4.3 竞品对比矩阵"), [
    ("p", "主要竞品的定价与核心能力对比如表所示（数据为公开渠道 2025 年价格，仅供参考）："),
    ("table", new_table([
        ["产品", "定价（月）", "核心能力"],
        ["Midjourney", "$10-120", "文生图"],
        ["Runway", "$12-76", "视频生成"],
        ["即梦 AI", "约 30-68 元", "一站式 AIGC 创作"],
        ["可灵 AI", "约 66-666 元", "视频生成"],
        ["FrameForge", "$149 买断", "专业分镜（人工）"],
        ["本平台", "0-99 元 + 点数", "分镜 + 图片 + 视频全流程"],
    ])),
])

# ---------- 5.3 内容营销选题表 ----------
insert_before(find("5.4 增长策略与商业转化路径"), [
    ("p", "分季度的内容营销选题规划如表所示："),
    ("table", new_table([
        ["季度", "主题", "主阵地", "阶段目标"],
        ["Q1", "产品上线、AI 分镜入门教程", "B站 / 小红书", "注册用户 5,000"],
        ["Q2", "真实创作案例拆解系列", "抖音 / 视频号", "付费转化率 5%"],
        ["Q3", "分镜模板创作大赛", "全平台", "模板生态启动"],
        ["Q4", "企业私有化案例白皮书", "直销 / 行业社群", "POC 10 单"],
    ])),
])

# ---------- 6.2 收入结构表 ----------
insert_before(find("6.3 三年财务预测"), [
    ("p", "按第三年预测，三大收入通道的占比结构如表所示："),
    ("table", new_table([
        ["收入通道", "第三年占比", "特点"],
        ["SaaS 订阅", "约 35%", "经常性收入、可预期"],
        ["按量点数", "约 14%", "弹性补充、低门槛"],
        ["企业私有化", "约 51%", "高客单价、毛利优"],
    ])),
])

# ---------- 7.2 应急预案表 ----------
insert_before(find("7.3 风险矩阵"), [
    ("p", "针对高风险场景制定的应急预案如表所示："),
    ("table", new_table([
        ["场景", "响应措施", "响应时限"],
        ["上游服务宕机", "渠道自动降级切换 + 瞬态重试", "5 分钟"],
        ["模型下线/停服", "路由表热切换替代模型", "30 分钟"],
        ["生成质量事故", "暂停相关模型调用 + 提示词调整", "2 小时"],
        ["数据安全事件", "隔离受影响服务 + 审计 + 通报", "1 小时"],
        ["用户投诉/纠纷", "客服响应 + 点数补偿", "24 小时"],
    ])),
])

# ---------- 9.2 自动化测试覆盖表 ----------
insert_before(find("9.3 性能测试"), [
    ("p", "自动化测试覆盖矩阵如表所示（✓ 已覆盖，— 不适用）："),
    ("table", new_table([
        ["模块", "单元测试", "集成测试", "E2E 测试", "说明"],
        ["认证鉴权", "✓", "✓", "✓", "双 token 全流程"],
        ["项目/分镜", "✓", "✓", "✓", "含批量写库"],
        ["Agent 编排", "✓", "✓", "✓", "四意图链 + HITL"],
        ["多模态生成", "—", "✓", "✓", "真实模型冒烟"],
        ["LLM 网关", "✓", "✓", "—", "协议转换与路由"],
        ["覆盖率目标", "80%", "90%", "关键路径", "—"],
    ])),
])

# ---------- 文字段落补充 ----------
insert_before(find("2.5 用户交互设计"), [
    ("b", "● 无障碍与国际化：关键操作支持键盘快捷键；文案统一中文并预留 i18n 结构；对比度满足 WCAG AA 标准，为视力障碍用户提供可用的创作体验。"),
])
insert_before(find("4.2 主要竞品"), [
    ("p", "行业趋势：2024-2025 年，多模态大模型能力快速成熟，AI 创作工具从“单点生成”走向“工作流化”与“协作化”；企业对内容资产一致性、数据合规与私有化部署的需求显著上升，为具备全流程能力与私有化方案的平台提供了结构性机会。"),
])
insert_before(find("6.2 收入模型"), [
    ("p", "成本优化路径：① 分级调度——简单任务（意图识别/标题生成）固定走低成本模型；② 渠道比价——网关按实时价格与成功率路由最优渠道；③ 批量与缓存——同场景生成参数复用、结果缓存去重；④ 协议压缩——上下文窗口裁剪与提示词精简，控制 token 消耗。"),
])
insert_before(find("10.1 总结"), [
    ("b", "● 项目成果回顾：从立项到当前版本，完成了四服务微服务架构落地、Agent 四意图链端到端打通、多模态生成实测、LLM 网关管理后台、HITL 协作闭环等核心里程碑，并通过真实模型调用验证了全链路可用性。"),
])

# ---------- 表号重排：按文档顺序给所有表补 caption 并统一编号 ----------
cap_re = re.compile(r"^表 \d+\s+")
new_no = 1
# 先收集所有表格 XML 元素（文档顺序）
tbl_els = [t._tbl for t in d.tables]
cap_map = {}  # 临时编号 -> 正式编号
for p in d.paragraphs:
    m = cap_re.match(p.text.strip())
    if m:
        cap_map[int(m.group(1))] = new_no
        rename(p, re.sub(r"^表 \d+", f"表 {new_no}", p.text.strip()))
        new_no += 1

# 为尚无 caption 的表格补 caption（插在表格前一个元素之后）
titles = [
    "四服务职责与端口", "模块关键接口", "核心数据库表结构", "SSE 事件协议",
    "设计 Token", "页面清单", "场景卡片状态机", "异常处理对照",
    "安全控制点", "技术选型对比", "版本定价矩阵", "目标市场规模与内容需求",
    "竞品定价对比", "竞品对比矩阵", "SWOT 分析", "用户画像",
    "营销渠道矩阵", "内容营销选题规划", "转化漏斗测算", "单点成本与定价测算",
    "收入结构占比", "三年财务预测（中性情景）", "三情景收入对比", "敏感性分析",
    "应急预案", "风险矩阵", "技术壁垒对比", "功能测试用例",
    "自动化测试覆盖矩阵", "性能测试结果",
]
cap_re2 = re.compile(r"^表 \d+ ")
i = 0
for tbl in d.tables:
    el = tbl._tbl
    prev = el.getprevious()
    # 前一个元素已是本表 caption 或章节标题（修订历史/排期表等）→ 不补
    has_cap = False
    if prev is not None and prev.tag.endswith("}p"):
        from docx.text.paragraph import Paragraph
        pp = Paragraph(prev, tbl._parent)
        txt = pp.text.strip()
        if cap_re2.match(txt) and "所示" not in txt:
            has_cap = True
        if pp.style is not None and pp.style.name.startswith("Heading"):
            has_cap = True
    if not has_cap:
        cap_p = new_caption(f"表 {new_no}  {titles[i]}")._p
        prev.addnext(cap_p) if prev is not None else el.addprevious(cap_p)
        new_no += 1
        i += 1

d.save(SRC)
print("saved. 表格数:", len(d.tables), "图:", len(d.inline_shapes), "表号到:", new_no - 1)
