# AI 应用挑战赛参赛材料（答辩 PPT → 项目书 → 演示视频）实施计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 为 AI Storyboard（AI 分镜生成平台）准备 AI 应用挑战赛冲国赛的三件套材料，**当前阶段优先交付答辩 PPT**（存至 `ppt/` 目录），随后产出项目书与演示视频。

**Architecture:** PPT 采用 pptxgenjs 从零生成（`powerpoint` 技能链路），视觉体系沿用项目自身的设计语言（Claude DESIGN.md：coral/cream/ink 温暖编辑风），以真实产品截图与架构图为素材，QA 走 validate.py + LibreOffice 渲染 + vision 逐页检查。演示视频与项目书在 PPT 定稿后按同一叙事线产出，素材共用。

**Tech Stack:** pptxgenjs (Node)、LibreOffice (soffice 渲染 QA)、poppler (pdftoppm 转图)、markitdown（内容提取）、headless Chrome（前端 UI 截图）、React 项目本体（演示素材来源）。

---

## 当前上下文（已核实）

- 项目：AI Storyboard — AI 分镜生成平台。后端 Spring Boot 4 + PostgreSQL，前端 React 19 + Zustand + Tailwind，AI 链路 Laozhang API + MiniMax V2 视频 + Dify Agent 对话 + 自建 LLM 网关（AILLMGateway/8083）。已有 `claude/DESIGN.md` 完整设计令牌。
- `ppt/` 目录已创建（空）。
- **技能说明**：已安装 `ppt-master`（hugohe3/ppt-master v4.5.0，MIT，productivity/ppt-master）。安装要点：`hermes skills install` 只装入口文件（SKILL.md + attribution_guard.py），**必须用 git sparse-checkout 从 GitHub 拉取 `skills/ppt-master` 全量补全**（12704 文件）；依赖装进 ai-infra/.venv（`python -m pip install -r requirements.txt`，numpy 曾损坏需 force-reinstall）；Windows 上 `python3` 是 stub，脚本一律用 `python` 调用；`skia-pathops` import 名是 `pathops`。技能自带完整性门 `attribution_guard.py`（每次使用前必跑，须从 skill 目录执行）。若用户持有比赛官方模板或指定风格，需在 Phase 0 提供，否则按本计划的设计方案执行。
- 前端已构建产物在 `AIStoryboardClient/dist/`（可 headless 起服务截图）。
- 真实生成素材（图片/视频）依赖 .env 中 API Key，演示视频阶段需确认可用。

## 前置确认（Phase 0，影响 PPT 结构，执行前向用户索取）

1. **比赛名称与赛制**：挑战赛全称、赛道（应用类/创意类？）、评分维度（创新性/技术难度/商业价值/演示效果占比）。
2. **答辩时长**：5 / 8 / 10 分钟？决定 PPT 页数与叙事密度（默认按 8 分钟、16-18 页设计）。
3. **PPT 格式要求**：官方模板 or 自由设计；16:9 默认；是否需要中英双语页。
4. **团队/公司信息**：封面署名（公司名、参赛队名、成员）。

---

## 阶段一：PPT 设计方案（先出方案，用户确认后执行）

> 依据用户偏好「UI/设计类任务先出设计方案，确认后再实现」，本阶段产出设计文档 `ppt/DESIGN.md`，含以下决策点，**评审通过才进入生成**。

### 1.1 叙事线（国赛答辩逻辑）

1. 封面（项目名 + 一句话定位 + 公司署名）
2. 痛点：影视/广告/短视频制作中分镜环节耗时、专业门槛高、创意与执行脱节
3. 定位：AI 分镜生成平台——从剧本到可预览成片的全链路 AI 协作
4. 核心演示（3-4 页）：剧本生成 → 分镜拆解 → 图生图/文生图 → 图生视频 → 预览成片
5. 亮点：Dify Agent 人机协作（交互式完善，拒绝一次性批量生成）
6. 技术架构：网关 + 多模型编排（Laozhang/MiniMax）+ Dify 工作流 + 双通道视频
7. 创新点（3 条）：多模型能力参数联动、Agent 会话级资产沉淀、HITL 人机协作工作流
8. 落地价值：公司真实业务系统、跨系统 JWT 打通、成本控制（768P 默认档）
9. 未来规划 + 结束页

### 1.2 视觉设计（沿用项目设计体系，motif 统一）

- **配色**（来自 claude/DESIGN.md，非通用蓝）：
  - 主色 Coral `CC785C`（CTA/强调，60-70% 视觉权重之外的点睛）
  - 画布 Cream `FAF9F5`（内容页底）/ 深色 Ink `181715`（封面+结束页，"sandwich" 结构）
  - 卡片 Surface Card `EFE9DE`、正文 Ink `141413`、弱化 Muted `6C6A64`
  - 点缀 Accent Teal `5DB8A6`（技术页数据点/状态）
- **字体**（QA 安全字体）：标题 Cambria（衬线，编辑气质，替代 Copernicus），正文 Calibri；代码/参数用 Consolas（仅技术页少量）。
- **Motif**：圆角卡片（12px 圆角）+ 图标圆底（白/teal 圆底小图标）+ 深色产品窗口卡（模仿产品 UI chrome），贯穿全部内容页；**禁用**底部色条/标题下划线等 AI 味元素。
- **版式节奏**：封面/结束页深色全出血；内容页 cream 底，左标题右内容，或 2x2 卡片网格；每页必有视觉元素（截图/图标/流程箭头/大数字）。
- **尺寸**：16:9（LAYOUT_WIDE 13.3" × 7.5"）。

### 1.3 页面清单（初稿 17 页，答辩 8 分钟）

| # | 页 | 版式 | 视觉元素 |
|---|----|------|----------|
| 1 | 封面（深色） | 居中 | 项目 logo 字标 + 一句话定位 + 署名 |
| 2 | 痛点（cream） | 左文案右图标列 | 3 个痛点图标卡片 |
| 3 | 定位（cream） | 左文右 mockup | 产品首页截图卡（深色窗口） |
| 4 | 工作流总览 | 横向 5 步流程箭头 | 步骤图标圆底 |
| 5 | 剧本→分镜（演示） | 左截图右说明 | 编辑器截图 + 参数滑块特写 |
| 6 | 生图演示 | 大图卡 | 生成图片 grid（真实素材） |
| 7 | 生视频演示 | 左视频帧右说明 | 视频帧 + 双通道标注 |
| 8 | Agent 人机协作 | 聊天界面截图 | 会话截图 + 确认卡片 |
| 9 | 技术架构（深色） | 分层框图 | 网关/模型/工作流/数据层 |
| 10 | 创新点 | 3 张卡片 2x1 | 大数字 01/02/03 |
| 11 | 落地价值 | 2x2 卡片 | 指标大数字 |
| 12 | 未来规划 | 时间线 | 3 段箭头 |
| 13 | 结束页（深色） | 居中 | 感谢 + 联系方式 |

*（页面清单为初稿，Phase 0 赛制信息到达后调整页数与详略。）*

---

## 阶段二：素材准备

**任务 2.1：产品截图**（headless Chrome 拍摄，真实 UI 而非示意图）
- 起服务：后端 8082 + 前端 5173（或 `npm run preview` 用 dist）+ LLM 网关 8083（如需要真实接口数据）。
- 截图清单：登录页、编辑器三栏（分镜列表/预览/参数面板）、生图结果、Agent 抽屉会话、产出素材面板。
- 命令示例：`chrome --headless --screenshot=/ppt/assets/shot-editor.png --window-size=1600,1000 http://localhost:5173/...`
- 图片统一放 `ppt/assets/`。

**任务 2.2：架构图**（SVG → PNG，深色底，与 PPT 深色页风格一致）
- 绘制五层：接入层（Web/跨系统 JWT）→ AI 编排层（LLM 网关 8083）→ 模型层（Laozhang 文/图/视频 + MiniMax 视频双通道）→ 工作流层（Dify Agent/HITL）→ 数据层（PostgreSQL/上传资产）。

**任务 2.3：真实生成素材**（可选，依赖 API Key）
- 若 Key 可用，跑通一次「剧本→分镜→生图→生视频」产出真实图/视频帧供 PPT 与演示视频共用；Key 不可用则用截图+标注替代，并在计划中注明。

---

## 阶段三：PPT 生成（powerpoint 技能，逐页构建）

- 写 `ppt/generate.js`（pptxgenjs）：按 DESIGN.md 的 palette/字号/间距常量构建 13-18 页。
- 关键约束（powerpoint 技能 gotchas）：
  - 颜色一律 6 位无 `#`；`pres.layout = LAYOUT_WIDE` 先设；不共用 shadow 对象；bullet 用 `bullet:true` + `breakLine`；图表仅用原生 `addChart`（本 PPT 以卡片/大数字为主，图表极少）。
  - 演讲者备注 `addNotes()`：每页写 30-60 字答辩台词要点。
- 输出：`ppt/AI-Storyboard-答辩.pptx`。

---

## 阶段四：QA（必做，四道关）

1. **结构校验**：`python scripts/office/validate.py ppt/AI-Storyboard-答辩.pptx`（powerpoint 技能目录下脚本）。
2. **内容校验**：`markitdown ppt/...pptx`，检查错字/顺序/占位符（grep lorem|TODO|x{3,}）。
3. **视觉校验**：soffice 转 PDF → `pdftoppm -jpeg -r 150` → 逐页 `vision_analyze`（新会话子代理执行更客观），重点：文字溢出、元素重叠、边距 <0.5"、低对比、motif 一致性。
4. **修复循环**：改 `generate.js` 重新生成，重跑 1-3，直到全部通过；修复后重新渲染 PDF 再查。

---

## 阶段五：后续三件套（PPT 定稿后，概述）

- **项目书**：`docs/` 下已有技术文档可直接引用；结构 = 项目概述/背景痛点/方案设计/技术实现/创新点/应用价值/未来展望。可复用 PPT 叙事线与架构图。
- **演示视频**：3-5 分钟，脚本基于 PPT 叙事线；用 OBS/录屏工具录制真实操作流程（登录→建项目→生成剧本→生图→生视频→Agent 完善）；建议用 `ppt/assets/` 素材 + 真实生成素材混剪。
- **答辩演练**：PPT 备注页即答辩稿；整理 Q&A 预测清单（评委常见问题：成本、效果对比、商业化、技术壁垒）。

---

## 文件清单

```
ppt/
├── DESIGN.md                  # 设计方案（阶段一产出，先评审）
├── assets/                    # 截图/架构图/生成素材
│   ├── shot-login.png
│   ├── shot-editor.png
│   ├── shot-agent.png
│   ├── shot-image-grid.png
│   ├── arch-dark.png
│   └── frame-video-*.png
├── generate.js                # pptxgenjs 生成脚本
└── AI-Storyboard-答辩.pptx    # 最终交付物
```

---

## 验证命令

```bash
# 生成
cd ppt && node generate.js

# 结构校验（powerpoint 技能目录）
python "C:\Users\38632\AppData\Local\hermes\skills\productivity\powerpoint\scripts\office\validate.py" AI-Storyboard-答辩.pptx

# 内容校验
markitdown AI-Storyboard-答辩.pptx | grep -iE "lorem|todo|xxx|\[insert"

# 视觉校验
python "C:\Users\38632\AppData\Local\hermes\skills\productivity\powerpoint\scripts\office\soffice.py" --headless --convert-to pdf AI-Storyboard-答辩.pptx
pdftoppm -jpeg -r 150 AI-Storyboard-答辩.pdf slide
```

---

## 风险、取舍与开放问题

| 风险/取舍 | 说明 |
|-----------|------|
| 无 ppt-master 技能 | 用 powerpoint 技能替代；若用户有官方模板/风格文件，Phase 0 提供后改走模板编辑路线 |
| 赛制信息未知 | 页数/时长/评分侧重依赖 Phase 0 确认，方案先按 8 分钟 17 页设计 |
| 真实生成素材依赖 API Key | Key 不可用则截图+标注兜底，PPT 先行不受阻 |
| LibreOffice 字体替换 | 只用安全字体（Cambria/Calibri/Consolas），QA 可信 |
| 演示视频录屏环境 | 需后端+前端+网关+Dify 同时在线，安排专用演示账号与演示项目（预置数据） |

**Open Questions（需用户回答）**：
1. 比赛全称、赛道、评分标准？答辩时长？
2. 有无官方 PPT 模板/格式要求？
3. 封面署名信息（公司名/参赛队名/成员）？
4. 演示视频是否需要真人配音/字幕？（阶段五再定）
