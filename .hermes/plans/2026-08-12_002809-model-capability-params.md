# 模型参数能力接入 Agent 卡片 + 人工选参透传 实施计划（v2，纠偏后）

> **For Hermes:** 按本计划逐任务实现。任务粒度 = 一次提交。
> **v2 纠偏（2026-08-12）**：v1 曾计划在主后端新建 `model_capabilities` 表——**错误**。用户指出能力信息权威源已在 LLM 网关：`model_params` 表（V5 migration，一行一模型、枚举列+默认值列、admin CRUD）+ `/v1/models?type=X` 下发 params + 主后端 `GatewayModelService.fetchModels` 已透传 + 前端编辑器（LeftSidebar/ScriptInputPanel/VideoPresetSelector）已消费。**唯一缺口：Agent 对话链 HITL 卡片未接入**。本计划纯增量，零新表。

**Goal:** Moon 智能体 HITL 卡片（video_plan / human_input）支持人工选择生成模型与参数（分辨率/时长/画幅/尺寸/质量），所选值经 `/form/submit` 透传到图片/视频生成服务执行（显式选择 > checkpoint plan > config 兜底）。

**Architecture:** 后端在卡片事件下发网关模型列表（含各模型 params 能力），前端卡片渲染模型下拉 + 参数联动选择器（复用编辑器侧已验证的交互模式），提交 `params` 随 `/form/submit` 进 `OrchestrationRequest` → handler → 生成服务。网关请求转发为纯透传（待 Phase 3 代码验证），所选值直达上游。

**Tech Stack:** Spring Boot 4 + 现有 SSE 协议、React 19 + Zustand 5（复用 `config.ts` 类型与静态兜底）

---

## 现状（已核实）

| 层 | 状态 |
|---|---|
| 网关 `model_params` 表 + admin CRUD | ✅ 已有（V5 migration；`ModelParamsController`/`Service`/`VO`） |
| 网关 `/v1/models?type=X` 返回 params | ✅ 已有（`GatewayRoutingServiceImpl.buildParams`：image→n/sizes/sizeDefault/qualities/qualityDefault/styles；video→durations/durationDefault/resolutions/resolutionDefault/aspectRatios/aspectRatioDefault） |
| 主后端 `GatewayModelService.fetchModels(type)` 透传 params（JSON 字符串） | ✅ 已有（`GatewayModelServiceImpl`） |
| 主后端 `AIController /ai/models` 暴露 imageModels/videoModels | ✅ 已有 |
| 前端 `config.ts`：`ImageModelParams`/`VideoModelParams`/`ModelOption` 类型 + 静态兜底数组 | ✅ 已有 |
| 前端编辑器侧模型+参数选择（网关 params 驱动，未配置回退静态） | ✅ 已有（LeftSidebar / ScriptInputPanel / VideoPresetSelector / projectStore.fetchAiModels） |
| **Agent 卡片模型/参数选择** | ❌ 缺失（HumanInputCard/VideoPlanCard 只有 actions 按钮；VideoIntentHandler 只传 prompt/duration） |
| Agent 生成调用支持显式模型/参数 | ❌ 缺失（AgentTools.generateVideo 无 model/resolution 参数；handler resume 不携带） |

---

## Phase 1: 后端——卡片参数组装 + 选择透传（一次提交）

**Objective:** video_plan / human_input 事件带 `models`（网关模型列表含 params），`/form/submit` 接收 `params` 并透传生成服务

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/OrchestrationRequest.java`
  - 加 `Map<String, String> params`（selectedParams）字段 + getter/setter
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/AgentOrchestratorSupport.java`
  - `StagePlan` record 加 `List<Map<String, Object>> models` 字段（默认 `List.of()`，现有 3 处调用零改动）
  - `runHITLStage`：`models` 非空时随 `human_input`/`video_plan` 事件下发（字段名 `models`，结构 = 网关 fetchModels 返回的 `[{value,label,params}]`，params 原样透传）
  - `startVideoGenerationAsync` 签名加 `Map<String,String> params` → 转给 `createVideoTask`（见 Step 3）
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/VideoIntentHandler.java`
  - `handle`：`support.buildModels("video")`（调 `GatewayModelService.fetchModels("video")`，转换/去重后传入 StagePlan）→ video_plan 卡片带模型选项
  - `resume`：从 `request.getParams()` 取 model/resolution/duration/aspectRatio，覆盖 checkpoint plan 值 → `startVideoGenerationAsync(..., params)`
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/PicIntentHandler.java`
  - 图片确认卡片（human_input）同带 `models`（`fetchModels("image")`）；resume 时 params 的 model/size/quality 透传 `generateImage`
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java` + `impl/AgentChatServiceImpl.java`
  - `/form/submit` body record 加 `Map<String,String> params` → `request.setParams(params)`
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/impl/MinimaxVideoServiceImpl.java`（:74-82）
  - 取值顺序改为：显式 params > 原逻辑（alias/config 默认）——`alias/resolution/duration/aspectRatio` 各参数判空优先用 params
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/impl/ImageGenerationServiceImpl.java`（:98 附近）
  - 同上：`model/size/quality` 显式优先（现已是 model 显式优先，size/quality 需补）
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentTools.java`
  - `generateVideo` 加 `@ToolParam` model/resolution/aspectRatio 可选；`refineImage` 加 model/size/quality 可选（预留 LLM 自主调用，本期 handler 直调不强制）
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java` + `resources/application.yml` + `.env.example`
  - 新增图生图默认模型配置：`defaultImageEditModel`（yml `default-image-edit-model: ${DEFAULT_IMAGE_EDIT_MODEL:gpt-image-2}`，与文生图默认模型 `defaultImageModel` 分开）——用户 2026-08-12 要求图生图默认模型可经环境变量填写
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/impl/ImageGenerationServiceImpl.java`
  - edits/参考图分支（:111 附近）默认模型用 `config.getDefaultImageEditModel()`（显式 model 仍优先）；纯文生图分支保持 `defaultImageModel`

**Step 1: 卡片事件协议扩展（向后兼容）**
```json
"models": [
  {"value": "MiniMax-H3", "label": "MiniMax-H3", "params": {"durations": [4,5,6,8,10,15], "durationDefault": 8, "resolutions": ["768P","2K"], "resolutionDefault": "768P", "aspectRatios": ["16:9","9:16","adaptive"], "aspectRatioDefault": "16:9"}},
  {"value": "veo-3.1-fast", "label": "veo-3.1-fast", "params": {...}}
]
```
- 网关不可用/空列表 → 不发 `models` 字段，前端回退静态兜底（与编辑器一致）

**Step 2: `AgentOrchestratorSupport.buildModels(type)`**
- `fetchModels(type)` 返回的 `List<Map<String,String>>`（value/label/params-JSON 字符串）→ `List<Map<String,Object>>`，params JSON 字符串解析为对象后原样携带（**选直接传字符串**，与 `/ai/models` 现状一致，前端 `GatewayModelOption.params` 本就是 string 待解析）

**Step 3: LLM 推荐选参（2026-08-12 用户追加需求）**
- 方案生成阶段（video 链两处 LLM 调用）输出扩展：每个参数大模型预选一个值 + 一句推荐理由，卡片默认选中推荐值并展示理由，用户可改
- 输出形状：
```json
{"message":"...","duration":8,
 "params":{"model":"MiniMax-H3","resolution":"768P","aspectRatio":"16:9"},
 "reasons":{"model":"默认模型稳定性最好","resolution":"768P 生成更快成本更低","aspectRatio":"16:9 适合剧情叙事"}}
```
- 实现点：
  - Modify `service/ai/VideoPlanService.java` + `impl/VideoPlanServiceImpl.java`：`VideoPlan` record 加 `Map<String,String> params` / `Map<String,String> reasons`（解析兜底：缺失→null，行为=现状）；`buildVideoPlan(imagePath, userRequest, String modelOptionsText)` 签名加选项文本；system prompt 追加「从给定选项中为每个参数选最合适的值并给出简短理由（≤15 字）」
  - Modify `AgentOrchestratorSupport.java`：`VideoPlanResult` record 同扩展；`callVideoPlan(content, String modelOptionsText)` 同处理
  - 选项文本由 handler 组装：`fetchModels("video")` → 一段人类可读文本（模型名 + 该模型 params 枚举，如「可选模型：MiniMax-H3（分辨率 768P,2K；时长 4,5,6,8,10,15；画幅 16:9,9:16,adaptive）…」）
  - `StagePlan` record 加 `Map<String,String> recommended` / `Map<String,String> recommendationReasons`（默认空 Map，现有调用零改动）→ `runHITLStage` 随 `video_plan`/`human_input` 事件下发（字段 `recommended`/`reasons`）
  - `VideoIntentHandler`：把 LLM 返回的 params/reasons 传入 StagePlan；resume 时**默认采用推荐值**（checkpoint plan 携带推荐 params，用户未显式提交 params 时用推荐值）——即取值顺序：用户显式 params > LLM 推荐 params > checkpoint 原值 > config
- 图片链（PicIntentHandler）：本期只下发 models（不做 LLM 推荐，refine 场景参数影响小；`ponytail:` 需要时按同机制补）

**Step 4: resume 取值合并**
```java
// VideoIntentHandler.resume：params 优先于 checkpoint plan
String model = params.getOrDefault("model", checkpoint 原 alias);   // alias 逻辑保留
String resolution = params.getOrDefault("resolution", planField(plan, "resolution") 或 null);
String duration = params.getOrDefault("duration", plan 原 duration);
```
- `startVideoGenerationAsync` 透传 → `MinimaxVideoServiceImpl.createVideoTask(alias=model, resolution, duration, ...)` 显式参数非空即用，空走原 config 默认
- 取值优先级（全链统一）：**用户提交 params > LLM 推荐 params > checkpoint plan 原值 > config 默认**

**验证:** `mvn compile`；冒烟走 video 链：`streamMessage` → `video_plan` 事件含 `models`（MiniMax-H3 + veo-3.1-fast 带 params）→ `POST /form/submit` body 带 `{"params":{"model":"MiniMax-H3","resolution":"2K","duration":"10"}}` → 网关 `/v1/videos` 请求体（或后端日志）断言 resolution=2K、duration=10

**Commit:** `feat(agent): HITL 卡片模型参数选项下发与所选值透传`

---

## Phase 2: 前端——卡片参数选择器（一次提交）

**Objective:** HumanInputCard / VideoPlanCard 渲染模型下拉 + 参数联动选择，提交携带所选

**Files:**
- Modify: `AIStoryboardClient/src/api/agent.ts`
  - `SseEvent` 加 `models?: GatewayModelOption[]`（复用 `api/ai.ts` 的 `GatewayModelOption`，避免新类型）
  - `SseEvent` 加 `recommended?: Record<string, string>`（LLM 推荐参数值）+ `reasons?: Record<string, string>`（推荐理由）
  - `submitHumanInput` / form submit body 加 `params?: Record<string, string>`
- Modify: `AIStoryboardClient/src/stores/agentStore.ts`
  - `SseFormInfo` 加 `models`/`recommended`/`reasons`；`submitHumanInput(actionId, customText?, params?)` 透传
- Create: `AIStoryboardClient/src/components/agent/AgentParamSelector.tsx`
  - 模型下拉（默认 = `recommended.model` 或 models[0].value）+ 参数联动：选中模型后按 `params.sizes/qualities`（图片）或 `resolutions/durations/aspectRatios`（视频）渲染下拉
  - **参数默认值 = `recommended[key]`（LLM 推荐）→ 否则 `params.*Default`**；推荐理由非空时参数旁显示「✨ 推荐值：xxx —— 理由」（小字，样式沿用卡片主题 #cc785c）
  - 模型无 params → 该参数区不渲染；切换模型时参数重置为该模型推荐/默认值
  - 受控组件：`value: Record<string,string>` + `onChange`
- Modify: `AIStoryboardClient/src/components/agent/VideoPlanCard.tsx`
  - 有 `info.models` 时渲染 `<AgentParamSelector>`，提交按钮把所选并入 `submitHumanInput('generate_video', undefined, selectedParams)`
- Modify: `AIStoryboardClient/src/components/agent/HumanInputCard.tsx`
  - 同 VideoPlanCard（图片确认卡片；仅当 models 存在且非空渲染）

**Step 1: 状态管理**
- 选择状态放卡片组件内 `useState`（卡片即会话，无跨组件共享需求；`ponytail:` 不引入全局 store 字段）
- 默认参数提交时：`params` 只携带用户改动过的键或全量键？——**全量键**（模型+各参数），后端按显式优先合并，语义清晰

**验证:** `cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build`；手工冒烟：video 链卡片出现模型/分辨率/时长下拉，切换模型参数联动，提交后后端按所选值创建任务（Phase 1 冒烟复用）

**Commit:** `feat(agent-ui): HITL 卡片模型参数选择器`

---

## Phase 3: 全链路验证 + 文档（一次提交）

**Objective:** 确认网关转发纯透传 + 端到端冒烟 + 文档同步

**Files:**
- Modify: `CLAUDE.md`（「智能体窗口前端约定」补 `models`/`params` 协议；「视频生成双通道」注明显式参数优先）
- Read-only 核对: `AILLMGateway .../service/impl/GatewayRoutingServiceImpl.java` video/image 转发段

**Step 1: 网关转发透传性核对（读代码，不改）**
- 确认 `/v1/videos` 与 `/v1/images/generations` 转发是原样透传 body（无 resolution/size 白名单/改写）——若是，本期所选值直达上游，**无需改网关**；若网关有改写，先修网关再走冒烟

**Step 2: 端到端冒烟（8085 隔离实例配方，不碰用户 8082/8083）**
1. `GET /api/agent/.../streamMessage`（video 意图）→ video_plan 事件断言 `models` 数组含 MiniMax-H3（params 含 resolutions/durations）
2. `POST /form/submit {"action":"generate_video","params":{"model":"MiniMax-H3","resolution":"2K","duration":"10"}}` → task_accepted → 后台日志/网关 call_log 断言请求体
3. 图片链：确认卡片带 models（image 类型）→ 提交带 size=1536x1024 → 生成请求断言
4. 不提交 params（老前端/无 models）→ 行为与现状完全一致（回归）

**Step 3: 文档**
- CLAUDE.md 补协议；`.hermes/plans/` 本计划归档

**Commit:** `docs(agent): 模型参数卡片协议与端到端验证记录`

---

## 验证命令汇总

```bash
# 后端编译
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

# 前端（solution-style tsconfig 必须 -p tsconfig.app.json）
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build

# 冒烟：8085 隔离实例（自签 JWT + 网关 key 插表，配方见 skill agent-orchestrator-refactor-2026-08-11.md）
```

---

## 风险 / 取舍 / 开放问题

1. **网关转发非透传**（唯一阻塞风险）：若 `GatewayRoutingServiceImpl` 对 resolution/duration 有白名单或默认值改写，所选值到不了上游——Phase 3 Step 1 读代码定论；有改写则网关小改（去掉/放开）
2. **MiniMax 分辨率可选（用户 2026-08-12 确认）**：默认 768P、允许人工改选（不再「恒 768P」）。上游对 2K 的实际接受度以网关转发核对 + 冒烟为准；上游拒绝时错误经 `extractReadableError` 透传前端（现状语义）。能力枚举（resolutions 含哪些档）由 admin 在网关 model_params 维护，默认值 768P 已在配置中
3. **卡片默认模型与编辑器当前模型不一致**：卡片是会话内权威入口，提交值显式覆盖——符合「人工介入选择」需求本意
4. **params 全量提交**：模型切换后旧参数（如 2K）可能不适配新模型——前端联动在切换模型时重置参数为默认（Phase 2 实现点）
5. **不做**：生成服务默认值改读网关 params 默认（未选时维持 config 现状，行为不变）；管理 UI（网关 /admin-ui 已有）；主后端新表（v1 错误方向已废弃）
