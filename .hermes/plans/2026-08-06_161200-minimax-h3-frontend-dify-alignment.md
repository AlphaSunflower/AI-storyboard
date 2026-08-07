# MiniMax-H3 切换后：前端检查项 + Dify 提示词对齐 实施计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 后端视频生成已默认切换到 MiniMax-H3（2026-08-06 完成），将前端展示与 Dify 工作流提示词从 Laozhang/Veo 时代对齐到 MiniMax-H3，消除虚假选项与过时约束。

**Architecture:** 后端 `VideoGenerationService` 门面按 `ai.video-provider`（默认 minimax）分发到 `MinimaxVideoService`，调用方透传参数，MiniMax 通道忽略 model/alias/negativePrompt/seed/resolution/size，恒用配置档 768P、时长 clamp 4~15、ratio 白名单校验。因此前端与 Dify 只需要**展示层与提示词层**对齐，后端零改动。

**Tech Stack:** React 19 + TS 6 + Zustand（前端）；Dify 工作流 YAML（Moon智能体 v4 → v5）；Spring Boot 4（不动）。

---

## 现状调研（已核实）

### MiniMax-H3 官方约束（2026-08-06 抓取官方文档核实）

来源：https://platform.minimaxi.com/docs/guides/video-generation（中文开放平台文档中心）+ https://platform.minimax.io/docs/api-reference/video-generation-v2-create（API 参考）

| 参数 | 官方约束 | 与本项目的关系 |
|------|----------|----------------|
| `duration` | 必填整数 **4~15 秒**（可用值 4,5,6,7,8,9,10,11,12,13,14,15） | 后端 clamp 4~15 正确；「只允许 6/8 秒」的说法**不成立**，那是网页端/第三方渠道的快捷档，API 层 4~15 全支持 |
| `resolution` | 枚举仅 **`768P` / `2K`** 两档（必填） | 后端恒用配置默认档 768P = 官方最低档，合法；2K 是另一合法档（默认不用是省钱策略，非上游限制） |
| `ratio` | `adaptive` + `21:9`/`16:9`/`4:3`/`1:1`/`3:4`/`9:16`；**文生视频必填且不可 adaptive**；**图生视频恒 adaptive**（传其他值被忽略） | 后端 `RATIOS` 白名单与官方一致；图生视频强制 adaptive 正确 |
| 提示词 | ≤ 7000 字符 | 前端/Dify 提示词远低于此，无影响 |
| 首帧图 | 1 张（可加 1 张尾帧）；宽高 [256,5760]；宽高比 0.4~2.5；单图 ≤ 30MB | 后端仅用首帧（图生视频），生成图尺寸在范围内，无影响 |
| 请求体 | ≤ 64MB（推荐 URL 传素材） | 后端 base64 内联图生视频 ≤64MB 内安全（CLAUDE.md 已记录） |
| 时长×分辨率 | **无组合限制**（文档示例 2K + 5s 合法） | 任意 4~15 整数 × 768P/2K 均可 |

结论：**后端 MinimaxVideoService 的参数处理与官方 API 完全一致，零后端改动**；前端预设与 Dify 提示词只需对齐「4~15 整数 + 768P/2K 两档 + 6 种比例」这一事实。

### 后端已就绪（不改）

- `AIStoryboardBackend/.../service/ai/MinimaxVideoService.java`：
  - 模型固定 `MiniMax-H3`（`config.getMinimaxVideoModel()`）
  - 分辨率恒用 `config.getMinimaxVideoResolution()`（默认 768P），**调用方传 720p/1080p/4K/2K 一律忽略**
  - 时长 clamp 4~15，默认 8
  - 文生视频 ratio 白名单 `{21:9,16:9,4:3,1:1,3:4,9:16}`（非法降级 16:9）；图生视频恒 `adaptive`
  - 忽略 alias/negativePrompt/seed；错误 OAI 风格透传
- `DifyAgentController.generateVideo` / `AgentGenerationService.createVideoTask` 透传 resolution/size/aspectRatio/duration → 门面 → MiniMax 通道，**删掉 Dify 侧 resolution 字段后传 null 也安全**（sanitize + 忽略）。

### 前端残留（需改）

| 文件 | 残留 | 问题 |
|------|------|------|
| `AIStoryboardClient/src/config.ts:48-57` | `VIDEO_MODELS` = veo-3.1-fast / veo-3.1 / runway-gen3 / kling-2 / sora | 假模型下拉：后端忽略 model，选了也白选，误导用户 |
| `AIStoryboardClient/src/config.ts:73-81` | `VIDEO_PRESETS` = 4s-720p / 8s-720p / 8s-1080p / 8s-1080p-v / 8s-4k | 1080p/4K 是虚假承诺（后端恒 768P）；仅 9:16 竖屏项真实有效（ratio 白名单） |
| `AIStoryboardClient/src/components/editor/LeftSidebar.tsx:298-316` | 「生视频模型」下拉 + 「时长和分辨率」标签 | 标签名过时；模型下拉为假选项 |
| `AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx:286-299` | 同上 | 同上 |
| `AIStoryboardClient/src/components/ai/VideoRefineModal.tsx:169-181` | 「生成模型」下拉（VIDEO_MODELS） | 假选项 |
| `AIStoryboardClient/src/stores/projectStore.ts:224-275` | `generateVideo` 从 preset 取 resolution/size/aspectRatio/duration 传后端 | 逻辑无需改（后端忽略多余字段），仅默认值/注释同步 |

前端残留引用点已全量 grep 确认（`veo|720p|1080|4k|2k|768` 只命中 config.ts；`VIDEO_MODELS|VIDEO_PRESETS` 消费方 = VideoPresetSelector、LeftSidebar、ScriptInputPanel、VideoRefineModal、projectStore）。

### Dify 工作流残留（需改，新建 v5 文件）

`AIStoryboardDify/Moon智能体v4.yml` 仅两处需动：

1. **「视频方案设计」LLM 节点**（节点 id `1785288460056`，文件行 ~1294-1356）：
   - system 提示词第一行：`你是视频生成方案设计师。根据用户需求为 Veo 3.1 设计视频生成方案…` → 需改为 MiniMax-H3
   - `duration`：`只能输出 4 或 8。720p 可选 4 或 8;1080p/4k 只能用 8` → MiniMax 支持 4~15，需放宽
   - `resolution`：`只能输出 "720p"、"1080p"、"4k" 三者之一…` → 后端恒 768P 忽略该字段，需删除或固定
   - 输出示例（~1319 行 JSON 示例）同步
   - `structured_output.schema`（~1325-1353）：`resolution` enum [720p,1080p,4k] + `duration` description 需同步
2. **「分镜JSON生成」节点**（行 ~1046-1078）：`videoPrompt` 字段说明「不要写分辨率/时长参数」已正确，仅可补「不要写画幅」（保持兼容也可不改）。

HITL「生成视频确认」节点 form_content = 视频方案 `message`（行 1849），**无需改**（可选增强：把 aspectRatio/duration 拼进表单展示，见开放问题）。

意图识别节点（行 790-820）intent-video 描述无模型引用，不改。

---

## Part A — 前端改动

### Task A1: 重写 config.ts 视频模型与预设

**Objective:** `VIDEO_MODELS` 收敛为单一 MiniMax H3；`VIDEO_PRESETS` 重构为「时长 × 画幅」真实档位，去掉虚假分辨率。

**Files:**
- Modify: `AIStoryboardClient/src/config.ts:48-81`

**Step 1:** 替换 `VIDEO_MODELS`（保留数组结构，UI 组件零改动，切回 Laozhang 时只需恢复数组）：

```ts
export const VIDEO_MODELS = [
  { value: 'MiniMax-H3', label: 'MiniMax H3' },
] as const;

export const DEFAULT_VIDEO_MODEL: string = VIDEO_MODELS[0].value;
```

**Step 2:** 替换 `VIDEO_PRESETS`（去掉分辨率谎言，保留 aspectRatio 真实生效；size/resolution 字段保留但标注为「后端忽略，仅兼容保留」；时长档位 = 4/6/8 三档覆盖用户认知的 6/8 档 + 4s 省钱档，12/15s 长档留给 Dify 场景不入 UI 默认）：

```ts
export const VIDEO_PRESETS: VideoPreset[] = [
  // 注意：分辨率由后端统一为配置默认档（768P），size/resolution 字段后端忽略，仅兼容保留；
  // 真实生效参数 = duration + aspectRatio（MiniMax ratio 白名单 21:9/16:9/4:3/1:1/3:4/9:16；
  // 官方 duration 合法范围 4~15 整数，此处 UI 提供常用 4/6/8 秒档）
  { value: '4s-16:9',  label: '4秒 横屏',   seconds: '4', duration: '4',  size: '1280x720',  resolution: '720p', aspectRatio: '16:9' },
  { value: '6s-16:9',  label: '6秒 横屏',   seconds: '6', duration: '6',  size: '1280x720',  resolution: '720p', aspectRatio: '16:9' },
  { value: '8s-16:9',  label: '8秒 横屏',   seconds: '8', duration: '8',  size: '1280x720',  resolution: '720p', aspectRatio: '16:9' },
  { value: '4s-9:16',  label: '4秒 竖屏',   seconds: '4', duration: '4',  size: '720x1280',  resolution: '720p', aspectRatio: '9:16' },
  { value: '6s-9:16',  label: '6秒 竖屏',   seconds: '6', duration: '6',  size: '720x1280',  resolution: '720p', aspectRatio: '9:16' },
  { value: '8s-9:16',  label: '8秒 竖屏',   seconds: '8', duration: '8',  size: '720x1280',  resolution: '720p', aspectRatio: '9:16' },
];

export const DEFAULT_VIDEO_PRESET: string = VIDEO_PRESETS[2].value; // 8秒 横屏（与官方/原系统默认一致）
```

**Step 3:** 验证旧 value 兼容：`videoPreset` 为内存态（无 persist middleware，已核实），旧值不存在时走 `find` 失败兜底。**注意 projectStore 的兜底是写死的 `|| VIDEO_PRESETS[1]`**（现在 = 6s 横屏，语义漂移），需顺手改为以 `DEFAULT_VIDEO_PRESET` 为准：

```ts
// AIStoryboardClient/src/stores/projectStore.ts:227 与 generateVideo 内同样逻辑
const preset = VIDEO_PRESETS.find(p => p.value === get().videoPreset)
  || VIDEO_PRESETS.find(p => p.value === DEFAULT_VIDEO_PRESET)!;
```

（`LeftSidebar.tsx:99,108` 与 `ScriptInputPanel.tsx:93,101` 各有同样兜底，一并同步——共 4 处，grep `VIDEO_PRESETS[1]` 定位。）

### Task A2: 同步侧边栏标签文案

**Objective:** 「时长和分辨率」→「时长和画幅」，消除分辨率错觉。

**Files:**
- Modify: `AIStoryboardClient/src/components/editor/LeftSidebar.tsx:314`
- Modify: `AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx:297`

**Step 1:** 两处 `<label style={labelStyle}>时长和分辨率</label>` → `时长和画幅`。
**Step 2:** LeftSidebar「生视频模型」下拉（~302-309）与 ScriptInputPanel 模型下拉：代码不动（VIDEO_MODELS 只剩一项后自然显示 MiniMax H3）。

### Task A3: VideoRefineModal 模型下拉验证

**Objective:** 弹窗「生成模型」下拉自动显示单一 MiniMax H3，无假选项。

**Files:**
- Verify: `AIStoryboardClient/src/components/ai/VideoRefineModal.tsx:169-181`

**Step 1:** 确认 `VIDEO_MODELS.map(...)` 渲染后下拉只有「MiniMax H3」一项即可，无需改代码。
**Step 2:** 若希望更强提示，可在下拉旁加一行小字说明（可选）：`分辨率由系统统一（默认 768P），时长 4~15 秒`。

### Task A4: 前端验证

**Objective:** 类型检查 + 构建通过。

**Step 1:**
```bash
cd E:\Desktop\AI-storyboard\AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```
Expected: 0 errors，构建产物生成（tsconfig 为 solution-style，必须 `-p tsconfig.app.json`）。

**Step 2:** 手动冒烟（可选，启动前后端）：生成视频 → 确认预设下拉无 1080p/4K、模型下拉仅 MiniMax H3、生成成功且结果正常。

---

## Part B — Dify 工作流 v5（新建文件，绝不动 v4）

### Task B1: 复制 v4 → v5

**Objective:** 建立可回滚的新版本文件（遵循「修改现有工作流创建新文件」偏好）。

**Step 1:**
```bash
cp "E:\Desktop\AI-storyboard\AIStoryboardDify\Moon智能体v4.yml" "E:\Desktop\AI-storyboard\AIStoryboardDify\Moon智能体v5.yml"
```

### Task B2: 改「视频方案设计」节点 system 提示词（v5 内）

**Objective:** 模型引用、duration、resolution 约束对齐 MiniMax-H3。

**Files:**
- Modify: `AIStoryboardDify/Moon智能体v5.yml`（原 v4 行 ~1294-1322 区域，节点 id `1785288460056`）

**Step 1:** 替换 system 提示词为（保留 message 六要素要求不变）：

```text
你是视频生成方案设计师。根据用户需求为 MiniMax-H3 设计视频生成方案,只输出结构化 JSON,不要输出任何其他文字。

【字段规则】

- message(字符串,必填):完整视频生成 prompt,中文 50~120 字,必须包含:①画面主体与动作 ②环境与背景 ③光线、色调与氛围
④运镜(从推/拉/摇/移/跟/升/降中明确选择,写明起幅到落幅)⑤景别与视角 ⑥风格(电影感/写实/动画等)。该文本将直接作为视频生成提示词。
不要写入分辨率、时长、画幅等参数(这些由单独字段控制)。

- duration(数字,必填):4~15 之间的整数（官方合法范围）,常用档位 4/6/8/12/15。用户未指定时默认 8。

- aspectRatio(字符串,必填):只能输出 "16:9"(横屏)或 "9:16"(竖屏)。用户未指定时默认 16:9。
(系统内部能力:文生视频支持 21:9/16:9/4:3/1:1/3:4/9:16;图生视频画幅自动匹配原图,无需指定)

- resolution:系统已统一使用默认分辨率(768P),不需要也不允许输出该字段。

【组合约束】

- 运镜必须具体(如"镜头缓慢推近""从右向左横摇"),禁止笼统写"镜头运动"。

输出示例:

{"message":"清晨薄雾中的江南水乡,乌篷船缓缓划过石桥,阳光透过柳枝洒下斑驳光影,镜头从桥上缓慢推近至船头老者,中景,电影感暖色调,写实风格。","duration":8,"aspectRatio":"16:9"}
```

**Step 2:** 同步 `structured_output.schema`（原 v4 行 ~1325-1353）：
- `properties` 中**删除 `resolution`**（或保留但 `enum: ["768p"]` + description 标注「系统默认，仅占位」）
- `duration.description` → `视频时长(秒,4~15)`
- `required` 数组删除 `resolution`（若删除字段）

### Task B3: 分镜 JSON 生成节点 videoPrompt 说明微调（可选）

**Objective:** 提示词与 MiniMax 语义一致。

**Files:**
- Modify: `AIStoryboardDify/Moon智能体v5.yml`（原 v4 行 ~1054）

**Step 1:** `videoPrompt` 字段说明 `可直接用于视频生成,不要写分辨率/时长参数` → 追加 `/画幅`：
`可直接用于视频生成,不要写分辨率/时长/画幅参数。`
（可选；不改也不影响功能，因该字段说明本就正确。）

### Task B4: 后端零改动确认

**Objective:** 确认 Dify 侧删掉 resolution 字段后后端链路安全。

**Step 1:** 阅读确认 `DifyAgentController.generateVideo`（~160-195）：`sanitize(request.resolution())` 对 null 返回 null → `VideoGenerationService` 门面 → `MinimaxVideoService` 忽略 resolution → 恒用配置 768P。**无需任何后端改动。**
**Step 2:** 运行后端编译兜底验证（确保工作区无意外破坏）：
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
Expected: BUILD SUCCESS（无输出即成功）。

### Task B5: Dify 验证（手动，用户亲自操作）

**Objective:** 在 Dify UI 导入 v5 并验证视频链路。

**Step 1:** Dify 工作流导入 `Moon智能体v5.yml`（用户偏好亲自在 Dify UI 操作验证）。
**Step 2:** 冒烟路径：发「生成一个 8 秒竖屏的城市夜景视频」→ 意图识别 intent-video → 视频方案设计输出 JSON（含 message/duration/aspectRatio，无 resolution）→ HITL 确认表单展示方案 → 「开始生成视频」→ 后端生成 → SSE 续流返回视频。

---

## 验证汇总

| 项 | 命令/操作 | 期望 |
|----|-----------|------|
| 前端类型+构建 | `cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build` | 0 错误 |
| 前端冒烟 | 手动生成视频 | 预设无 1080p/4K；模型下拉仅 MiniMax H3；生成成功 |
| 后端编译（兜底） | `mvn.cmd compile -q`（JAVA_HOME Windows 路径） | BUILD SUCCESS |
| Dify | 导入 v5 + 全链路冒烟（用户操作） | 视频方案无 resolution 字段、时长 4~15 可输出 |

## 风险 / 权衡 / 开放问题

1. **前端模型下拉收敛为单一 MiniMax H3**：若未来切回 Laozhang（`ai.video-provider=laozhang`），模型下拉需恢复多选项——这是后端配置切换，前端届时另行处理；当前收敛符合「后端固定模型，前端不提供假选项」的原则。
2. **`resolution` 字段删除 vs 保留占位**：删除更干净（structured_output 变更需 Dify 重新发布版本）；保留 `enum:["768p"]` 兼容性更好但仍是假字段。**建议删除**，后端已确认 null 安全。
3. **时长档位**：官方 API 支持 4~15 整数全档（已核实，见「MiniMax-H3 官方约束」）。前端预设取 4/6/8 三档 × 横竖两种画幅共 6 项（覆盖用户认知的 6/8 档 + 4s 省钱档）；12/15s 长档不放入前端 UI（生成慢、按秒计费贵），保留在 Dify 提示词常用档中供 LLM 按用户需求选择。
4. **竖屏语义**：9:16 在 MiniMax ratio 白名单内真实生效，必须保留竖屏档位；图生视频时后端强制 `adaptive`，画幅自动匹配首帧图，前端/Dify 无需感知。
5. **前端「时长和分辨率」标签**：改「时长和画幅」后与 Dify「视频方案设计」提示词字段名一致（aspectRatio），避免认知分裂。
6. **HITL 表单展示**（开放问题）：目前 form_content 仅展示 `message`（无 duration/aspectRatio 信息）。若希望用户在确认卡片上看到时长/画幅，需在 Dify「变量赋值」或表单内容模板中拼入 `{{#1785288460056.structured_output.duration#}}` 等——默认不做，保持现状。
7. **文档同步**（超出本次范围，可后续）：`docs/大模型调用文档.md` 与 `CLAUDE.md` 已描述 MiniMax 通道，无需前端相关修订；如需更新 Dify 工作流说明文档可另起任务。

## 文件变更总览

- Modify: `AIStoryboardClient/src/config.ts`（VIDEO_MODELS + VIDEO_PRESETS）
- Modify: `AIStoryboardClient/src/components/editor/LeftSidebar.tsx`（1 行标签 + 预设兜底 2 处）
- Modify: `AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx`（1 行标签 + 预设兜底 2 处）
- Modify: `AIStoryboardClient/src/stores/projectStore.ts`（预设兜底 2 处改为以 DEFAULT_VIDEO_PRESET 为准）
- Verify-only: `AIStoryboardClient/src/components/ai/VideoRefineModal.tsx`
- Create: `AIStoryboardDify/Moon智能体v5.yml`（复制 v4 后改 2 节点）
- No change: 后端全部（`MinimaxVideoService` / `VideoGenerationService` / `DifyAgentController` / `AgentGenerationService`）
