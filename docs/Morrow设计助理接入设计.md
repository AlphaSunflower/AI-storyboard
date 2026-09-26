# Morrow 设计助理接入 设计文档（v2 定稿）

日期：2026-08-26
范围：把 `XiaoYueCanvasAgent`（Morrow 画布）右侧「设计助理」面板的 AI 后端接入本系统，复用共享库 + LLM 网关；MoonAgent 纯化为编排+技能 hub；XiaoYueCanvas 独立画布 job-service。
状态：**待评审；部分架构已被取代**（2026-09-26 起，其中 PlanGraph /
`AgentNodeClients` 编排结构不再是目标方案，权威说明见
[`superpowers/specs/2026-09-26-agentscope-architecture.md`](superpowers/specs/2026-09-26-agentscope-architecture.md)）

---

## 1. 背景与目标

Morrow 是独立 React/TS 设计画布，其「设计助理」已是**作业驱动**前端：
- `POST /jobs` → jobId → SSE 订阅 `job.queued/progress/agent.message/asset.created/job.completed/...`
- `asset.created` → 下载真实文件 → IndexedDB → 落成站点/派生节点
- 未配置 `VITE_JOB_API_URL` 时全部远程能力 disabled

目标：让设计助理跑起来，AI 后端统一走**共享库 + LLM 网关**；**不进 ai分镜**、**不做画布工具 approval 循环**；MoonAgent 只做编排+技能 hub；XiaoYueCanvas 独立存在。

### 明确不做（YAGNI）
- 不进 ai分镜编排器（不接 Aisplit/SceneReview/… 意图，不写 scenes、不做分镜 HITL 卡片）。
- 画布工具 approval 循环：`agent.request` = 流式问答；作业服务**不发 `agent.tool_call`**。
- 不合并 Morrow 到 AIStoryboardClient（前端独立 SPA）。
- 不动 AIStoryboardBackend 自身（它有自己一份 agent 栈，继续用它）。
- 源图改图（map/redraw/enhance/cutout/expand/erase/angle/relight 带源图）本期通过 `ImageRefinePromptService` 接能力，但**浏览器素材上传接口留二期**（见 §11 风险#1）。

---

## 2. 架构总览

```
[Morrow 画布（独立 SPA）]  JWT / VITE_JOB_API_URL
        │
        ▼
[XiaoYueCanvas（独立画布 job-service，Spring Boot 4.1.1/JDK21）]
        │  依赖 agent-core
        │  连 ai_storyboard（复用 conversations/agent_assets + 新 canvas_jobs）
        │  作业分发 → agent-core AI 服务（无双跳）
        ▼
[LLM 网关 8083]  ←—— 统一 AI 调用/模型路由/密钥下沉（与 ai分镜同源）
        ▲
[MoonAgent（编排+技能 hub）]  依赖 agent-core
   ├── agent/   编排/工具/specialists/plans/技能
   ├── controller/  service/  config/  security/  dto/
   └── resources/skills/（所有 SKILL.md） + prompts/
```

- **共享代码**：`agent-core`（被 MoonAgent + XiaoYueCanvas 依赖）。
- **共享库**：`ai_storyboard`（conversations / agent_assets / canvas_jobs）。
- **共享网关**：LLM 网关 8083。

---

## 3. 模块划分

### 3.1 `agent-core`（新共享库，独立 jar 本地 install）
> 依赖 `common-core`（ApiResponse/BusinessException/GlobalExceptionHandler）+ Spring/MyBatis-Plus + Spring AI；走 `spring.ai.openai.*` → 网关。

- **数据层**：`entity/`（AgentConversation/AgentMessage/AgentCheckpoint/AgentAsset）+ `mapper/`（四个 MyBatis-Plus Mapper）+ 归属/CRUD 服务（`getOwnedConversation` 40401 防 IDOR、listAssets、uploadReferenceImage、clearMessages 等）。
- **AI 生成服务**（`ai/`）：`AgentAnswerService`（流式问答）、`ImageRefinePromptService`、`ImageGenerationService`、`VideoPlanService`、`VideoGenerationService`、`MinimaxVideoService`、`ScriptGenerationService`、`PromptOptimizeService`、`GatewayModelService`（网关选参）。
- **公共**：`api/`/`exception/`（或沿用 common-core）。

### 3.2 `MoonAgent`（编排+技能 hub，纯化）
- `agent/`：orchestrator（handlers/intents）、answer 编排、tools（分镜/画布工具）、specialists、plans、memory（ConversationLock）。
- `controller/`、`service/`、`config/`、`security/`、`dto/`。
- `resources/skills/*`（全部 SKILL.md）、`resources/prompts/*`。
- 依赖 `agent-core`（数据 + 生成服务）；自身不再持有 entity/mapper/AI生成服务实现。

### 3.3 `XiaoYueCanvas`（独立画布 job-service）
- `controller/`：`/health`、`/capabilities`、`POST /jobs`、`GET /jobs/{id}/events`(SSE)、`POST /jobs/{id}/cancel`、`POST /jobs/{id}/tool-results`、`GET /assets/{id}`。
- `service/`：job 分发（按 capabilityId → agent-core AI 服务）、作业落 `canvas_jobs`、生成资产落 `agent_assets`、同源代理 `GET /assets/{id}`。
- 依赖 `agent-core`，连 `ai_storyboard`，鉴权走 JWT（网关验签透传 `X-User-Id`）。

### 3.4 Morrow 前端（独立 SPA）
- 组件/状态零改动；仅 `HttpJobAdapter`（`src/services/capabilities.ts`）改 **JWT Bearer** + `VITE_JOB_API_URL`；SSE 用 `fetch+ReadableStream`（EventSource 不能带自定义头）。

### 3.5 `CommonCore`（沿用，不动）
- ApiResponse / BusinessException / GlobalExceptionHandler / MultipartBuilder。

---

## 4. 数据模型（`ai_storyboard` 共享库）

- **复用表**：`conversations`（userId+projectId+title+status）、`agent_assets`（conversation_id 可空、type、url、task_id、status）。归属校验 `getOwnedConversation`（40401）。
- **新表 `canvas_jobs`**（设计助理一次作业；幂等/进度/审计/结果）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | uuid PK | jobId |
| client_request_id | uuid 唯一 | 幂等键 |
| conversation_id | uuid FK→conversations | 归属会话 |
| project_id | varchar | 画布项目作用域 |
| capability_id | varchar | agent.request / image.generate / … |
| node_ids | jsonb | 画布节点上下文 |
| selected_node_id | varchar | 选中节点 |
| source_asset_ids | jsonb | 源资产（映射后；二期图改图用） |
| params | jsonb | prompt/model/resolution/duration/size/quality |
| status | varchar | queued/running/completed/failed/cancelled |
| progress | int | 0-100 |
| error | text | 失败原因 |
| result_asset_id | uuid | 生成结果 → agent_assets.id |
| created_at / updated_at | timestamptz | |

- **projectId 语义**：Morrow 画布项目是本地 ID，作为 `project_id` 作用域键（不绑 ai分镜项目行）。

---

## 5. 能力 → agent-core AI 服务映射

| Morrow capabilityId | agent-core 服务 |
|---|---|
| `agent.request` | `AgentAnswerService`（流式问答，打字机） |
| `image.generate` | `ImageGenerationService`（文生图） |
| `image.redraw/enhance/cutout/expand/erase/angle/relight` | `ImageRefinePromptService`(视觉)→`ImageGenerationService(edit)`（带源图；源图上传二期） |
| `video.generate` | `VideoPlanService`(视觉)→`VideoGenerationService`/Minimax 异步链 |
| （prompt 优化） | `PromptOptimizeService` |
| （选参） | `GatewayModelService`（网关 /v1/models）+ 优先级：用户提交 > LLM 推荐 > 方案原值 > config 默认 |

---

## 6. 作业服务公开契约（前端面向，锁死）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | `{label:"Design Agent"}` |
| GET | `/capabilities` | 远程能力 `[{id,label,category:'remote',available,unavailableReason?}]` |
| POST | `/jobs` | `{clientRequestId,conversationId,capabilityId,sourceAssetIds[],context{projectId,nodeIds[],selectedNodeId},params{prompt,...}}` → `{jobId}` |
| GET | `/jobs/{jobId}/events` | SSE `job.queued/started/progress`、`agent.message`、`asset.created`、`job.completed/failed/cancelled` |
| POST | `/jobs/{jobId}/cancel` | 取消，以 `job.cancelled` 确认 |
| POST | `/jobs/{jobId}/tool-results` | 本期只预留（后端不发 tool_call） |
| GET | `/assets/{assetId}` | 同源代理返回文件，≤256MB（不直连三方带凭据） |

---

## 7. 鉴权与网关一致性

- 统一 JWT（Morrow 改 Bearer；参考 `/api/auth/unlogin` token 交换/登录）。
- 作业服务鉴权：网关验签透传 `X-User-Id`（仿 `GatewayAuthenticationFilter`），或自校验 JWT（§11 待定）。
- AI 调用统一走 LLM 网关 8083（`LLM_GATEWAY_BASE_URL` / `LLM_GATEWAY_API_KEY`），前端零密钥。

---

## 8. MoonAgent 目录规范化（目标结构）

生成服务/实体/mapper 下沉 agent-core 后，MoonAgent 纯化为编排+技能 hub：

```
com/moon/moonagent/
├── config/        # SecurityConfig / MyBatisPlusConfig / OpenAiHttpConfig(ChatClient) / RestTemplateConfig / PromptConfig / AiConfigProperties
├── controller/    # AgentConversationController（+未来画布/工具控制器）
├── service/       # AssetService / FileStorageService
├── security/      # GatewayAuthenticationFilter
├── dto/request/   response/
├── agent/         # 编排+技能 hub
│   ├── orchestrator/   # AgentOrchestrator + handler/intent + OrchestrationRequest
│   ├── answer/         # AgentAnswerService 编排（数据/流式来自 agent-core）
│   ├── tools/          # AgentTools（分镜/画布工具）
│   ├── specialists/    # SpecialistAgent / Registry / Context / Result
│   ├── skills/         # SkillLoader / AgentSkill / SkillConsistencyValidator
│   ├── agent-runtime/  # AgentScope runtime、skills、tools 与 permission policy
│   └── memory/         # ConversationLock / CheckpointCleanupService
└── MoonAgentApplication.java
resources/
├── skills/        # 所有 SKILL.md（storyboard/aisplit/pic/video/delete/other）
└── prompts/       # 提示词模板（orchestrator/script/services）
```

---

## 9. 鉴权/归属一致性
- 会话不存在与无权统一 40401（防 IDOR 枚举）。
- 生成参数优先级沿用 ai分镜规范（§5）。
- OpenAI 大小写/白名单、错误友好化、视频异步轮询——照抄 agent-core 已验证写法。

---

## 10. 实施阶段（评审通过后按序执行）

- **P1 建共享库 `agent-core`**：建 maven 模块；把 MoonAgent 的 entity/mapper + AI 生成服务（含流式问答）+ 归属/CRUD 服务迁入；agent-core 依赖 common-core + mybatis-plus + spring-ai。
- **P2 重构 MoonAgent**：删除其 entity/mapper/AI生成服务副本，改依赖 agent-core；按 §8 目录规范化重排（orchestrator/answer/tools/specialists/skills/plans/memory）；改 Spring 包扫描/`@Mapper`/配置。
- **P3 XiaoYueCanvas job-service**：建 `canvas_jobs` 表；实现 /jobs + SSE + /assets/:id + /capabilities + /health；作业分发 → agent-core；JWT 鉴权。
- **P4 Morrow 前端**：adapter 改 JWT Bearer + `fetch+ReadableStream` SSE；设 `VITE_JOB_API_URL`。
- **P5 冒烟**：本地起 gateway 8083 + agent-core + MoonAgent + XiaoYueCanvas；Morrow 连 XiaoYueCanvas 发一条生图/问答；验证 `asset.created` 落节点。

---

## 11. 风险/卡点
1. **源图改图**：redraw/enhance/… 需浏览器素材上传 + 服务端 id 映射；本期先落生图/问答/视频生成，源图改图用 `ImageRefinePromptService` 接能力但上传接口留二期。
2. **`asset.created` 缺口**：作业服务必须补发 `asset.created`（agent-core/MoonAgent 现役事件没有）。
3. **EventSource 不支持自定义头**：SSE 鉴权若走 Bearer 需 `fetch+ReadableStream`。
4. **会话生命周期**：Morrow 本地刷新即失 conversationId；作业服务按 `client_request_id` 幂等 + 会话映射，避免孤儿会话。
5. **重构副作用**：MoonAgent 迁库到 agent-core 后，注意 Spring 包扫描/`@MapperScan`/配置加载路径变化；`AIStoryboardBackend` 不受影响（自有副本）。
