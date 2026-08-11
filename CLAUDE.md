# AI Storyboard

AI-powered storyboard generation platform. Spring Boot 4 backend + React/TypeScript frontend.

## Tech Stack

- **Backend**: Spring Boot 4.0.0, JDK 21, MyBatis-Plus 3.5.16 (spring-boot4-starter), PostgreSQL, JDK HttpClient
- **Frontend**: React 19 + TypeScript 6 + Vite 8, Zustand 5, Tailwind CSS 4, Axios (timeout 120s), React Router 7
- **AI**: Laozhang API v2 (`api2.laozhang.ai`)，models: `gpt-image-2`, `gemini-3-flash-preview`, `veo-3.1-fast`；视频生成双通道：**MiniMax V2**（默认，`MiniMax-H3`，api.minimaxi.com）+ Laozhang（保留可切回，`ai.video-provider`）
- **Auth**: JWT (jjwt), scrypt password hashing (lambdaworks 1.4.0), cross-system token exchange via `/api/auth/unlogin`
- **Ports**: Backend 8082, Frontend 5173

## Design System

From `claude/DESIGN.md` — Anthropic-inspired warm editorial:

| Token | Value |
|-------|-------|
| Primary (coral) | `#cc785c` |
| Canvas (cream) | `#faf9f5` |
| Ink (dark) | `#141413` / `#181715` |
| Surface card | `#efe9de` |
| Border radius | 8px (buttons/inputs), 12px (cards), 16px (hero) |

## Project Structure

```
AI-storyboard/
├── AIStoryboardBackend/          # Spring Boot backend
│   └── src/main/java/com/storyboard/
│       ├── controller/           # REST controllers
│       ├── service/              # Business logic
│       │   └── ai/               # AI generation services
│       ├── entity/               # JPA entities
│       ├── dto/                  # Request/Response DTOs
│       ├── mapper/               # MyBatis-Plus mappers
│       ├── config/               # Security, CORS, MyBatis config
│       ├── security/             # JWT provider, filters, scrypt
│       └── exception/            # Global exception handler
├── AIStoryboardClient/           # React frontend
│   └── src/
│       ├── pages/                # EditorPage, LoginPage
│       ├── components/
│       │   ├── editor/           # PreviewPanel, SceneListPanel, LeftSidebar
│       │   ├── scene/            # SceneCard
│       │   ├── ai/               # ImageRefineModal, VideoRefineModal
│       │   ├── layout/           # AppHeader
│       │   └── common/           # GenerationProgress, DraftRecoverBanner
│       ├── stores/               # Zustand stores
│       ├── api/                  # Axios API clients
│       └── types/                # TypeScript types
├── docs/                         # Chinese documentation
│   ├── 大模型调用文档.md
│   ├── 系统一接入指南.md
│   └── 系统一调用参数更新.md
└── claude/DESIGN.md              # Design tokens spec
```

## Databases

- **Primary**: PostgreSQL `newworkflow` (postgres/123456), shared `public.users` table
- **Local dev**: Configure via `application-local` profile

## Critical Pitfalls

### 1. PostgreSQL `timestamptz` → `OffsetDateTime`

Database `timestamptz` columns MUST use `OffsetDateTime` in entities, NOT `LocalDateTime`.
PostgreSQL JDBC driver returns `OffsetDateTime` for `timestamptz`; mapping to `LocalDateTime` causes silent SELECT failures → page 500.

```java
// Correct
import java.time.OffsetDateTime;
private OffsetDateTime createdAt;
```

### 2. Spring Boot 4 incompatible with spring-dotenv

`spring-dotenv` does NOT work with SB4. Use manual `.env` reading in `StoryboardApplication.main()` via `System.setProperty()`.

`application.yml` `${VAR:default}` reads system properties first, so `.env` values override defaults. For fixed values (API URLs), hardcode in yml directly.

### 3. Laozhang API v2 differences

- **URL**: `api2.laozhang.ai` (NOT `api.laozhang.ai`)
- **Image size**: OpenAI format `"1024x1024"` (NOT `"2K"`)
- **Image response**: prefer `data[0].b64_json`, fallback to `data[0].url`. URLs may be `data:image/png;base64,...` — strip prefix before Base64 decode
- **Video generation**: Async — POST creates task → returns `taskId` → poll `GET /v1/videos/{taskId}` every 5s, timeout 5min

### 3.5 视频生成双通道（MiniMax V2 默认 + Laozhang 保留）

- **分发**：`VideoGenerationService` 为门面，按 `ai.video-provider`（`minimax` 默认 | `laozhang`）分发到 `MinimaxVideoService` 或原 Laozhang 逻辑；**调用方（AIController / DifyAgentController / AgentGenerationService）零改动**
- **MiniMax V2 链路**（`MinimaxVideoService`，实测 2026-08-06 全通：创建 200 → 轮询 queued/running/succeeded ~2min → 下载 200）：
  - 创建：`POST {minimax-base-url}/v2/video_generation`，Bearer `MINIMAX_API_KEY`（.env，不提交），JSON body `{model:"MiniMax-H3", content:[{type:text,text:prompt}, {type:image_url,image_url:{url},role:first_frame}], resolution:"768P"|"2K", duration:4-15, ratio}` → `task_id`
  - 轮询：`GET /v2/query/video_generation/{task_id}` → `task.status`（queued/running→processing；succeeded→`content.url` **限时链接须立即转存** uploads/videos；failed→`error.message`）
  - **图生视频**：本地图（`/api/files/images/xxx.png`）读文件转 `data:image/png;base64,...` 内联（无需上传接口；请求体 ≤64MB）；图生视频 ratio 恒 `adaptive`
  - **分辨率**：恒用配置默认档 `minimaxVideoResolution`（默认 **768P** 最低档；用户要求默认最低分辨率，调用方传 720p/1080p/4K/2K 一律忽略，不 2K 透传——省钱且生成更快；换档改配置即可 768P|2K）；时长 clamp 4~15
  - 错误结构 OAI 风格 `{error:{message}}`，透传前端；429/5xx 轻量重试 3 次（无需 Laozhang 的 10 次换池）
- **配置**：`ai.video-provider`、`ai.laozhang.minimax-api-key`、`ai.laozhang.minimax-base-url`（默认 api.minimaxi.com）、`minimax-video-model`（MiniMax-H3）、`minimax-video-resolution`（768P）；切回 Laozhang 只需 `ai.video-provider=laozhang`
- **MCP 文档检索**：MiniMax 文档中心 MCP = `https://platform.minimaxi.com/docs/mcp`（HTTP transport，search + 虚拟文件系统查文档/OpenAPI spec），已配置 Hermes `mcp_servers.minimax_docs`

### 4. Image local storage & serving

Generated images/videos download to `uploads/images/` / `uploads/videos/`, served via `/api/files/`:
- `FileStorageService.saveImage(url)` handles both URL download and base64 decode
- `FileController` — `GET /api/files/images/{filename}`
- `SecurityConfig` must permit `/api/files/**`
- `uploads/` in `.gitignore`

### 5. Frontend image URLs need backend prefix

Backend returns `/api/files/images/xxx.png`. Frontend `<img src={...}>` in dev requests `http://localhost:5173/...`.
Always prepend backend URL:

```tsx
const BACKEND = 'http://localhost:8082';
function assetUrl(path: string) {
  if (!path) return '';
  if (path.startsWith('http')) return path;
  return BACKEND + path;
}
```

### 6. Cross-origin downloads

`<a href download>` fails across ports. Use fetch + blob:

```tsx
function downloadAsset(url: string, filename: string) {
  fetch(url).then(r => r.blob()).then(blob => {
    const u = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = u; a.download = filename; a.click();
    URL.revokeObjectURL(u);
  }).catch(() => window.open(url, '_blank'));
}
```

### 7. HttpClient timeouts

AI calls are slow (image 30-60s, video 2-5min). ALL Java HttpClients must set timeouts:

```java
private final HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .build();

// Per-request timeout
HttpRequest request = HttpRequest.newBuilder()
    .timeout(Duration.ofSeconds(120))
    .build();
```

### 8. Error messages to frontend

`GlobalExceptionHandler.handleUnknown` returns `e.getMessage()` (not fixed "server internal error") so frontend sees Laozhang API errors (e.g. content moderation rejections).
`extractReadableError()` recursively unwraps multi-layer nested/escaped JSON errors (Laozhang → Vertex AI) down to the innermost readable `message`, then truncates to 200 chars. Plain business errors pass through untouched.

## Spring AI 2.0 (LLM 调用层，2026-08-11 迁移)

**依赖**：`pom.xml` 引入 `spring-ai-bom:2.0.0`（dependencyManagement import）+ `spring-ai-starter-model-openai`（SB 4.0.0 兼容；泄漏断言正则必须限定 groupId `org\.springframework\.ai:spring-ai[^:]*:jar:(1\.|0\.)`）。

**配置**（`application.yml`，指向 LLM 网关，复用 `ai.gateway` 同源 env 变量）：
```yaml
spring:
  ai:
    openai:
      base-url: ${LLM_GATEWAY_BASE_URL:http://localhost:8083}/v1   # Spring AI 自动拼 /chat/completions
      api-key: ${LLM_GATEWAY_API_KEY:}
```

**已转换服务**（手写 JDK HttpClient → ChatClient，全部保留解析兜底/错误文案/公共签名，调用方零改动）：
- `ScriptGenerationService` / `PromptOptimizeService` / `IntentRecognitionService` / `ConversationTitleService`（纯文本）
- `ImageRefinePromptService` / `VideoPlanService`（多模态：`Media.builder().mimeType(MimeType.valueOf("image/png")).data(dataUri)` + `UserMessage.builder().text().media()`，data 传完整 data URI 字符串直达 image_url；实测网关兼容）

**2.0 API 要点**（spike 字节码级验证）：
- 每服务从注入 `ChatClient.Builder` 构建：`builder.defaultOptions(OpenAiChatOptions.builder().model(X).timeout(Duration)).build()`（**直接传 options builder，不要调 .build()**）；单次覆盖用 `prompt().options(OpenAiChatOptions.builder().model(Y))`
- 自定义顶层参数：`OpenAiChatOptions.builder().extraBody(Map.of("thinking_level", "minimal"))` → merge 进请求体顶层（标题服务"不思考模式"用）
- 结构化输出用**纯解析**（不发 response_format，规避网关不兼容）：`BeanOutputConverter<T>`（`org.springframework.ai.converter`）`convert(content)`，解析失败抛 `tools.jackson.core.JacksonException`（unchecked）→ catch 后走原手写 JSON 兜底解析
- 超时按 ChatClient 粒度（`OpenAiChatOptions.Builder.timeout`），非全局：标题/意图 30s、优化 60s、脚本/视觉 120s

**仍保留 HttpClient 直连**（不在迁移范围）：`AgentChatService`（Dify 编排）、`ImageGenerationService`/`VideoGenerationService`/`MinimaxVideoService`（生图/生视频 REST）、`FileStorageService`（文件下载）、`AIController`。

## Frontend State Management (Zustand)

### `updateProject` double update

Must update BOTH `currentProject` AND `projects` list, otherwise `ProjectHistoryPanel` misses status changes:

```ts
updateProject: async (id, data) => {
    const res = await projectApi.update(id, data);
    const updated = res.data.data;
    set((s) => ({
        currentProject: s.currentProject?.id === id ? updated : s.currentProject,
        projects: s.projects.map(p => p.id === id ? updated : p),
    }));
},
```

### Async polling for video progress

Video generation is async. Store tracks per-scene progress with `Record<string, number>`:

```ts
videoProgress: Record<string, number>; // sceneId -> progress (0-100)
```

Always clear progress in `finally` block (ensures reset on success AND failure).

### `markDirty` — auto-draft on edit

When `currentProject.status === 'active'`, any edit (generate script/image/video, add/delete scene) auto-downgrades to `draft`:

```ts
markDirty: () => {
    const { currentProject, updateProject } = get();
    if (currentProject && currentProject.status === 'active') {
        updateProject(currentProject.id, { status: 'draft' });
    }
},
```

Called after: `generateScript`, `generateImage`, `generateVideo`, `addScene`, `deleteScene`.

### SceneCard button states

| Status | Has URL | Button text | Click action |
|--------|---------|-------------|--------------|
| `completed` | yes | "完善..." (refine) | Open refine modal |
| `completed` | no | "重试" (retry) | Regenerate directly |
| `generating` | any | "重试" (retry) | Regenerate (zombie state) |
| `pending` | any | "生成..." (generate) | Generate directly |
| `failed` | any | "重试" (retry) | Regenerate directly |

### `soundDesign` field reuse — ref image state

`soundDesign` is repurposed as JSON to share reference image state between `SceneCard` and `PreviewPanel`:

```ts
// Storage format
{ images: string[] (base64 data URIs), useForImage: boolean, useForVideo: boolean }

// Default: both checkboxes checked
// Unchecked → don't send refImages; checked + empty → undefined
```

## Resizable Panels

EditorPage three-column layout with 4px drag handle between SceneListPanel (380-600px) and PreviewPanel (flex:1).
Handle: `cursor: col-resize`, hover turns `var(--color-primary)`, `flexShrink: 0`.

## Cross-System JWT Exchange (`/api/auth/unlogin`)

Allows external system (system one) to sign a JWT with shared secret, carry `userId`, and jump into the editor. Backend performs three-way validation (account + password + JWT) then issues access + refresh tokens.

Frontend `EditorPage` detects URL params `?token=...&refresh=...&userId=...&name=...`, stores in localStorage, clears URL via `history.replaceState`, calls `checkAuth()`.

## Project Save/Draft Mechanism

- Projects have `status`: `draft` | `active`. New projects default to `draft`.
- Save button in AppHeader calls `updateProject(id, { status: 'active' })`
- Draft detection: `ProjectMapper.findLatestDraft` + `AND status = 'draft'`; frontend `checkDraft` double-checks `draft.status === 'draft'`
- Draft recovery: `EditorPage` loads → `checkDraft()` → shows `DraftRecoverBanner` if draft exists

## AI Agent 对话模块 (`/api/agent/**`)

### 数据库（V2 migration `V2__agent_conversation.sql`，3 张表，与分镜 scenes 无关）

| 表 | 关键字段 | 说明 |
|----|---------|------|
| `conversations` | user_id + project_id（JWT 归属双键）、title、dify_conversation_id、status | 一个项目可多个会话；project 删除级联 |
| `agent_messages` | conversation_id（CASCADE）、role（user/assistant）、content、dify_message_id | 消息历史 |
| `agent_assets` | conversation_id（**可空**=未归属）、type（**image/video/reference**）、url、task_id、status | Agent 生成的图/视频 + 用户上传的参考图 |

### 端点（全部 JWT 鉴权，`/api/agent/**` 不在 SecurityConfig 白名单）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/conversations` | 创建会话（校验 project.userId 归属） |
| GET | `/api/agent/conversations?projectId=` | 会话列表（updated_at 倒序） |
| GET/DELETE | `/api/agent/conversations/{id}` | 详情（含消息）/ 删除（DB CASCADE 删消息） |
| GET/POST | `/api/agent/conversations/{id}/messages` | 消息列表 / 发送消息（代理 Dify） |
| GET | `/api/agent/conversations/{id}/assets?page=&size=` | 资产列表（**分页**，page 默认 1 / size 默认 20 / 上限 50，返回 `{records, total, page, size}`） |
| POST | `/api/agent/upload` | 传图（可选 conversationId，校验归属）→ 存 uploads + 落库 reference 资产 |
| POST | `/api/agent/conversations/{id}/messages/stream` | SSE 流式发送消息（body `{content, picUrl?}`，`text/event-stream`） |
| POST | `/api/agent/conversations/{id}/form/submit` | HITL 表单提交并续流（body `{formToken, taskId, action}`），SSE 返回 |
| PATCH | `/api/agent/conversations/{id}` | 重命名 title / 归档（status: `active`\|`archived`） |
| DELETE | `/api/agent/assets/{id}` | 删除资产（未归属资产拒绝 40401） |

- 归属校验：`AgentChatService.getOwnedConversation(userId, id)`，所有端点复用；会话不存在与无权访问统一 40401 同文案（防 IDOR 枚举）
- 校验错误用 `BusinessException`（40001/40301/40401），GlobalExceptionHandler 已映射对应 HTTP 状态码

### SSE 事件协议（后端→前端）

事件类型由 SSE `event` 字段承担（转发负载不含 `type` 键），后端裁剪 Dify 原始流后转发；流结束即 `emitter.complete()`：

| event | 负载字段 | 说明 |
|-------|----------|------|
| `message` | `content` | 回答增量（前端逐段拼接打字机效果） |
| `workflow` | `title`, `status` | 节点进度（`node_started` / `node_finished`） |
| `human_input` | `formToken`, `taskId`, `formContent`, `actions`（`[{id,title}]`）, `expirationTime` | HITL 暂停点；**收到后后端立即结束流**，前端渲染确认卡片并停止打字机 |
| `message_end` | `messageId`, `sceneCount`, `content`, `title?` | 流正常结束；`sceneCount` 为当前项目 scenes 总数，供前端互斥判定；`title` 为首条消息异步 AI 重命名的新标题（**一次性**：仅重命名完成的那一轮携带，取走即删，不做轮询） |
| `error` | `code`, `message` | 失败结束；`message` 已脱敏（"Dify 服务异常，请稍后重试"） |

- Dify 原始流中的 `ping`、`node_started/finished` 内部 `inputs`/`outputs`、`tts_message` 等事件一律过滤，不转发到前端

### AgentChatService — Dify 对话代理

- `POST {difyBaseUrl}/v1/chat-messages`，`response_mode: "blocking"`（非流式），`Authorization: Bearer {difyApiKey}`
- **事务语义**（重要）：user 消息用 `TransactionTemplate` + `PROPAGATION_REQUIRES_NEW` 独立事务立即提交；Dify 调用失败时 **user 消息保留**、抛业务异常；Dify 成功后 `transactionTemplate.execute` 内完成"回填 dify_conversation_id + 保存 assistant 消息"（失败整体回滚）
- Dify 上游错误完整信息只进日志，抛给客户端的文案脱敏（"Dify 服务异常，请稍后重试"）
- 配置：`ai.laozhang.dify-base-url`（默认 `http://localhost`）+ `ai.laozhang.dify-api-key`（复用既有字段）

### 首条消息异步 AI 重命名标题

- **触发**：`AgentChatService.maybeScheduleTitleRename`（streamMessage + sendMessage 双路径，**必须在 user 消息落库前调用**——落库后 selectCount 已 +1，"首条"判定 count==0 永远不成立，线上实测踩坑）。三重判定：该消息是会话第一条消息（insert 前 count==0）+ 标题仍为默认值「新对话」+ `titleScheduled` 并发去重成功
- **异步**：`CompletableFuture.runAsync(..., agentExecutor)`（虚拟线程），不阻塞 Dify 主流程；任务体全 try-catch，失败仅 `log.warn`，标题保持「新对话」，对话零影响
- **生成**：`ConversationTitleService`（新建）调 Laozhang chat completions（`baseUrlVision`，超时 30s），模型固定 `gemini-3.5-flash-lite`（**实测**：老张网关对 preview 系模型的一切思考参数均不透传/拒绝，无法关思考；flash-lite 默认零思考 token，即"不思考模式"），请求体显式带 `thinking_level: minimal`（flash-lite 接受，语义自文档化）
- **落库（并发坑）**：Dify 线程持有同一 `AgentConversation` 实体并整实体 updateById（回填 difyConversationId），标题线程**必须**用 `LambdaUpdateWrapper` 只 set title/updatedAt 两列，并带 `.eq(title, "新对话")` 原子条件——整实体更新会把对方刚写的新字段冲掉
- **一次性推送**：落库成功后新标题暂存 `renamedTitleByConversation`，`messageEndPayload` 在 `message_end`（含 workflow_finished 恢复流）发送时 `remove` 取走并附 `title` 字段；**只推一次**（取走即删），不做轮询/持续推送；极端时序（标题未生成完流已结束）该轮不推送，前端下次拉取会话列表自然可见
- **前端**：`agentStore.ts` 两个 `message_end` 分支（sendMessage / submitHumanInput）收到 `e.title` 就地更新 `conversations` 列表（守卫 `c.title !== e.title` 防重复 set）；`SseEvent.title` 类型已存在，零类型改动

### 提示词优化（/api/agent/prompt/optimize）

- **端点**：`POST /api/agent/prompt/optimize`，请求 `{content}`，响应 `{optimized: string}`；JWT 鉴权（`/api/agent/**` 非白名单）；**不落库、不关联会话**（纯文本转换工具）
- **校验**：`content.trim().length < 6` → 40001「内容至少 6 个字符才能优化」
- **实现**：`PromptOptimizeService` 调 Laozhang chat completions（`baseUrlVision` + `defaultVisionModel` 质量优先，不传 thinking_level；超时 60s）；**优化方向由 LLM 自行判断**（草稿可能是剧情/图片/视频或综合需求），单文本输出不强制 JSON——规避解析失败风险
- **前端**：已取消「✨ 优化」按钮（AgentChatPanel 输入区只保留发送按钮）；接口保留备用，前端不再调用

### DifyAgentController 改造（消灭孤儿 scene）

- **无 sceneId 时不再创建临时 scene**（旧 `scene_number=0` 孤儿记录），改写入 `agent_assets`：
  - `sceneId` 非空 → 写 scene（原行为不变）
  - `sceneId` 空 + `conversationId` 非空 → 写 agent_assets（关联会话；**先校验 conversation 存在，不存在降级未归属**，避免 FK 违例 500）
  - `sceneId` 空 + 无 `conversationId` → 写 agent_assets（conversation_id=NULL 未归属）
- DTO（`DifyGenerateImageRequest`/`DifyGenerateVideoRequest`）新增字段：`conversationId`、`picUrl`
- **PicUrl 图生图/图生视频**：`picUrl`（用户上传图 URL，来自 `/api/agent/upload`）优先于 `generatedImageUrl`，Controller 合并后作为 `generatedImageUrl` 传入 Service（本地读文件逻辑已存在，零额外改造）
- generateVideo 无 sceneId 分支响应只返回 `taskId/assetId/status`（**不把 assetId 塞进 sceneId 键**——否则 Dify 工作流回传 assetId 当 sceneId 导致"分镜不存在"）

### AI Service 解耦（sceneId 可空）

- `ImageGenerationService.generateImage`：sceneId 为 null 时不查/不写 scene 表，只用局部变量 `localPath` 返回
- `VideoGenerationService.createVideoTask`：同样支持 sceneId 为 null；`pollVideoTask` **双通道反查**——先按 `videoTaskId` 查 scene，查不到再按 `taskId` 查 agent_assets 并更新其 url/status/error（failed 分支先解析上游 message/error 再 setError）

### 完善图片自动生成（无 HITL 信号触发，2026-08-07 重构）

**背景**：旧链路 Dify 用 deepseek（无视觉能力）盲猜改图方案 + HITL 人工确认，方案与实际图片脱节。重构后：
- **Dify 工作流删掉「完善图片设计方案」LLM 与「人工介入」HITL**，完善路径改为：`user_finishing(code 写 storage_pic_talk)` → `赋值` → answer 节点「后端执行识别图片加人工介入流程」（文案"结合用户输入理解图片优化提示词中..."）即结束（无确认）
- **后端触发**：`AgentChatService.forwardDifySse` 监听 `node_finished` 事件，`data.title == "后端执行识别图片加人工介入流程"`（常量 `AUTO_REFINE_SIGNAL_TITLE`，**必须与 Moon智能体.yml 该 answer 节点 title 完全一致**）→ `triggerAutoImageRefine` 自动生成：
  1. `ImageRefinePromptService.buildRefinedPrompt(source, userRequest)`：**视觉模型（gemini-3-flash-preview，baseUrlVision）看图 + 用户诉求 → 结构化 JSON（image_analysis/modifications/refined_prompt）**，源图从本地 uploads 读转 base64 data URI 内联（参照 MiniMax 图生视频）；`refined_prompt` 直接投喂图生图
  2. `AgentGenerationService.generateImage(sceneId=null, mode=edit, prompt=refined_prompt, generatedImageUrl=source)` → 落 agent_assets
  3. `pushGenerationResult` 推图 + confirm_result 卡片（「继续完善/满意完成」交互保留）
- **数据来源**：源图 = `lastPicUrlByConversation`（本轮 PicUrl）；用户诉求 = 最近一条 user 消息（streamMessage 已落库，等价 sys.query）
- **SSE 时序坑**：信号触发后置 `autoGenerate` 标志，forwardDifySse 内**所有 `!deferComplete` complete 判断都要加 `&& !autoGenerate.get()`**（message_end / EOF / 异常分支），否则 Dify 流一结束 emitter 就被 complete，图片还没生成完
- **Dify 侧配套**：`storage_pic_talk.user_finishing` 必须由 code 节点写入（sys.query），并确保「传到公共变量」类 code 输出 lis **含 user_finishing**（over-write 整体替换，不加会丢）

### 智能体窗口前端约定

- **入口**：编辑器右下角悬浮球 ☾ → 右侧抽屉（62vw，minWidth 480）：左会话栏（180px，顶部为「☾ Moon 智能体」标题）+ 右对话区（顶部显示当前会话标题，无会话占位「未选择对话」；右侧 📁 产出素材 + 🧹 清除聊天记录按钮）
- **底部输入栏**：可拖拽上下伸缩（min 90 / max 40vh，顶部 4px 把手 hover 变主色）；右侧为「发送」按钮（「✨ 优化」已取消）
- **命名**：用户可见文案统一「产出素材」（原「资产」，仅文案，store/字段名 assets/AgentAsset 不变）
- **互斥规则**：`message_end.sceneCount` > 会话开始时 `scenes.length` → `agentGeneratedScenes = true`（仅内存态，刷新恢复）→ `LeftSidebar` 剧本输入禁用
- **inputs 适配 Moon 工作流**：`{ currentProjectId, PicUrl }`（替换旧 `project_id`/`project_name`）
- **参考图**：`POST /api/agent/upload` → 返回 `url` → 作为 `PicUrl` 随消息发送（图生图/图生视频）
- **配置**：`AI_DIFY_API_KEY`（`AIStoryboardBackend/.env`，不提交）+ `DIFY_BASE_URL`（默认 `http://localhost`）

## Verification Commands

```bash
# Backend (MUST use Windows paths for JAVA_HOME)
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

# Frontend（tsconfig 为 solution-style，必须 -p tsconfig.app.json 才真正检查；裸 `npx tsc --noEmit` 不检查任何文件）
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```

**Maven gotcha**: `JAVA_HOME` must use Windows path format (`C:\\...`), not POSIX (`/c/...`). `mvn.cmd` is a Windows batch file — cmd.exe doesn't understand POSIX paths. Direct invocation (not bash `mvn` alias) required or `ClassNotFoundException: plexus-classworlds`.

## Coding Conventions

- **Backend**: DTOs use Java records; services are `@Service`; mappers extend `BaseMapper<T>`; entities use Lombok `@Data`
- **Frontend**: Functional components with hooks; Zustand for global state; Axios instances with interceptors for auth token injection
- **API responses**: Wrapped in `ApiResponse<T>` with `code`, `message`, `data`
- **Auth**: JWT access token (short-lived) + refresh token; `JwtAuthenticationFilter` on all routes except `/api/auth/**` and `/api/files/**`
- **CORS**: Configured in `SecurityConfig` via `CorsConfigurationSource` bean
