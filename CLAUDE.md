# AI Storyboard

AI-powered storyboard generation platform. Spring Boot 4 backend + React/TypeScript frontend.

## Tech Stack

- **Backend**: Spring Boot 4.0.0, JDK 21, MyBatis-Plus 3.5.16 (spring-boot4-starter), PostgreSQL, JDK HttpClient
- **Frontend**: React 19 + TypeScript 6 + Vite 8, Zustand 5, Tailwind CSS 4, Axios (timeout 120s), React Router 7
- **AI**: 全部模型调用统一走 **LLM 网关**（AILLMGateway，:8083，`LLM_GATEWAY_BASE_URL` / `LLM_GATEWAY_API_KEY`）；chat/文生图/图改图/视频均经网关转发（生图 `gpt-image-2`、视觉 `gemini-3-flash-preview`、视频 `MiniMax-H3`），协议转换与上游密钥下沉网关
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
├── CommonCore/                   # 公共模块（独立 jar，本地 install：com.storyboard:common-core:0.1.0）
│   └── src/main/java/com/storyboard/common/   # ApiResponse / BusinessException / GlobalExceptionHandler / MultipartBuilder
│                                              # 网关（AILLMGateway）保留自有契约（code=0 无 timestamp），不引用
├── AIStoryboardBackend/          # Spring Boot 后端
│   └── src/main/java/com/storyboard/
│       ├── controller/           # REST controllers
│       ├── service/              # 业务逻辑
│       ├── ai/                   # AI 生成服务（原 service/ai）
│       │   └── impl/             # AI 服务实现
│       ├── entity/               # JPA entities
│       ├── dto/                  # Request/Response DTOs
│       ├── mapper/               # MyBatis-Plus mappers
│       ├── config/               # Security, CORS, MyBatis config
│       ├── security/             # JWT provider, filters, scrypt
│       └── exception/            # 网关/本地异常（GlobalExceptionHandler 已抽公共）
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

## Docker 部署目录（deploy/docker/）

统一管理基础设施 Docker 部署（scripts/ 下有 setup/up/down/status 脚本）：

- **nacos** `nacos/nacos-server:3.2.3` — standalone 服务发现（8848/9848/8850，数据卷 nacos-data）
- **vosk** `alphacephei/vosk-server:latest` — 离线语音识别（2700，挂载 vosk-model-cn-0.22）

首次部署：`./scripts/setup.sh`（下载 1.3GB 中文模型）→ `./scripts/up.sh`（自动接管旧容器）。

## Databases

- **Primary**: PostgreSQL `newworkflow` (postgres/123456), shared `public.users` table
- **Local dev**: Configure via `application-local` profile；生产 `application-prod`（见 Pitfall #2）

## Critical Pitfalls

### 1. PostgreSQL `timestamptz` → `OffsetDateTime`

Database `timestamptz` columns MUST use `OffsetDateTime` in entities, NOT `LocalDateTime`.
PostgreSQL JDBC driver returns `OffsetDateTime` for `timestamptz`; mapping to `LocalDateTime` causes silent SELECT failures → page 500.

```java
// Correct
import java.time.OffsetDateTime;
private OffsetDateTime createdAt;
```

### 2. Spring Boot 4 incompatible with spring-dotenv（已改用 Profile，2026-08-17）

`spring-dotenv` does NOT work with SB4。环境配置统一走 Spring Profile，**不再读 `.env`**：

- `application.yml`：共享无密钥配置 + `spring.profiles.active: local`（默认本地；生产用 `--spring.profiles.active=prod` 或 `SPRING_PROFILES_ACTIVE=prod` 覆盖）
- `application-local.yml` / `application-prod.yml`：环境专属配置（数据源、JWT 密钥、网关地址与 Key、网关 AES / 管理员自举密码）；两文件均被 `.gitignore` 忽略，生产值硬编码后迁移 Nacos 热配置
- 两主类（`StoryboardApplication` / `LLMGatewayApplication`）`main()` 不再调 `loadDotEnv()`；网关 `AdminInitRunner` 自举密码由 `System.getProperty("LLM_GATEWAY_ADMIN_INIT_PASSWORD")` 改为 `gateway.admin-init-password`（`GatewayConfig` 绑定）

### 3. Laozhang API v2 differences

- **URL**: `api2.laozhang.ai` (NOT `api.laozhang.ai`)
- **Image size**: OpenAI format `"1024x1024"` (NOT `"2K"`)
- **Image response**: prefer `data[0].b64_json`, fallback to `data[0].url`. URLs may be `data:image/png;base64,...` — strip prefix before Base64 decode
- **Video generation**: Async — POST creates task → returns `taskId` → poll `GET /v1/videos/{taskId}` every 5s, timeout 5min

### 3.5 视频生成（统一走 LLM 网关，2026-08-12 重构）

- **链路**：`VideoGenerationService` / `MinimaxVideoService` 创建/轮询/下载全部直连 **LLM 网关**（不再直连 MiniMax/Laozhang，上游密钥与协议转换下沉网关）：
  - 创建：`POST {gateway}/v1/videos`，Bearer `LLM_GATEWAY_API_KEY`，OpenAI 风格 JSON body `{model, prompt, size, resolution, aspectRatio, duration, negativePrompt?, seed?, imageUrl?}` → 响应解析 `task_id` / `id` / `taskId` 任一
  - 轮询：`GET {gateway}/v1/videos/{taskId}`（queued/running→processing；succeeded→下载；failed→error）
  - 下载：`GET {gateway}/v1/videos/{taskId}/content` → 转存 uploads/videos（限时链接须立即转存）
  - **图生视频**：业务侧保留转换——本地图读文件转 `data:image/png;base64,...` 内联（网关无 uploads 目录权限，设计 §6.2）
- **模型别名**：业务侧保留 `video-model-aliases` 映射（veo 简称 → 网关实际模型名）；默认模型 `minimax-video-model`（MiniMax-H3）
- **配置**：仅 `ai.laozhang.minimax-video-model`（默认模型名）+ `video-model-aliases`（别名映射）；分辨率/时长/尺寸默认值在 `default-video-*`（Agent 卡片人工选值经 `/form/submit` 透传，优先级：用户提交 > LLM 推荐 > plan 原值 > config 默认）
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
- `ImageGenerationService` 纯文生图分支 → Spring AI `ImageModel`（自动装配 OpenAiImageModel，吃 spring.ai.openai.* → 网关 /v1/images/generations；`OpenAiImageOptions.builder().model().size().n().timeout(180s).maxRetries(1)`；size 白名单 normalizeImageSize 保留；实测 1024x1024/1536x1024/非法 2K 降级全通）；**edits 图改图分支保留 HttpClient 直连**（multipart octet-stream workaround，Spring AI 无对应适配）

**2.0 API 要点**（spike 字节码级验证）：
- 每服务从注入 `ChatClient.Builder` 构建：`builder.defaultOptions(OpenAiChatOptions.builder().model(X).timeout(Duration)).build()`（**直接传 options builder，不要调 .build()**）；单次覆盖用 `prompt().options(OpenAiChatOptions.builder().model(Y))`
- 自定义顶层参数：`OpenAiChatOptions.builder().extraBody(Map.of("thinking_level", "minimal"))` → merge 进请求体顶层（标题服务"不思考模式"用）
- 结构化输出用**纯解析**（不发 response_format，规避网关不兼容）：`BeanOutputConverter<T>`（`org.springframework.ai.converter`）`convert(content)`，解析失败抛 `tools.jackson.core.JacksonException`（unchecked）→ catch 后走原手写 JSON 兜底解析
- 超时按 ChatClient 粒度（`OpenAiChatOptions.Builder.timeout`），非全局：标题/意图 30s、优化 60s、脚本/视觉 120s

**仍保留 HttpClient 直连**（不在迁移范围）：`AgentGenerationService`（生图/生视频 REST）、`FileStorageService`（文件下载）、`AIController`。
**编排层（2026-08-11 起）**：Agent 对话改 Spring AI 2.0 应用层编排（`AgentOrchestrator` 状态机 + `AgentTools` @Tool 工具面），不再依赖 Dify。

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
| `conversations` | user_id + project_id（JWT 归属双键）、title、status | 一个项目可多个会话；project 删除级联 |
| `agent_messages` | conversation_id（CASCADE）、role（user/assistant）、content | 消息历史 |
| `agent_assets` | conversation_id（**可空**=未归属）、type（**image/video/reference**）、url、task_id、status | Agent 生成的图/视频 + 用户上传的参考图 |
| `agent_checkpoints` | conversation_id（CASCADE）、action、form_token（一次性）、plan（方案 JSON 文本）、step、status（pending/used/expired）、expiration_time | HITL 确认点（V4 migration，替代原内存 Map 快照） |

> **Migration 手动执行**：项目未引入 Flyway，`db/migration/V*.sql` 为留档，需 psql 手动应用（V2 会话表 → V3 资产 url 可空 → V4 checkpoints → V5 删 dify 列）

### 端点（全部 JWT 鉴权，`/api/agent/**` 不在 SecurityConfig 白名单）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/conversations` | 创建会话（校验 project.userId 归属） |
| GET | `/api/agent/conversations?projectId=` | 会话列表（updated_at 倒序） |
| GET/DELETE | `/api/agent/conversations/{id}` | 详情（含消息）/ 删除（DB CASCADE 删消息） |
| GET/POST | `/api/agent/conversations/{id}/messages` | 消息列表 / 发送消息（编排回答） |
| GET | `/api/agent/conversations/{id}/assets?page=&size=` | 资产列表（**分页**，page 默认 1 / size 默认 20 / 上限 50，返回 `{records, total, page, size}`） |
| POST | `/api/agent/upload` | 传图（可选 conversationId，校验归属）→ 存 uploads + 落库 reference 资产 |
| POST | `/api/agent/conversations/{id}/messages/stream` | SSE 流式发送消息（body `{content, picUrl?}`，`text/event-stream`） |
| POST | `/api/agent/conversations/{id}/form/submit` | HITL 表单提交并续流（body `{formToken, taskId, action, content?}`，`content` 为 action=custom 自定义输入文本），SSE 返回；video 确认（action=generate_video）→ `task_accepted` 立即返回 |
| GET | `/api/agent/tasks/{taskId}` | 视频异步任务状态（前端 5s 轮询：`{taskId, assetId, status(queued/running/completed/failed), url, error}`；归属校验，未归属/无权 40401） |
| PATCH | `/api/agent/conversations/{id}` | 重命名 title / 归档（status: `active`\|`archived`） |
| DELETE | `/api/agent/assets/{id}` | 删除资产（未归属资产拒绝 40401） |
| POST | `/api/agent/stt` | 语音识别（multipart `file`=WAV，16kHz 单声道）→ `{text}`；Moon 智能体语音输入，WebSocket 转发 vosk-server（`vosk.ws-url`，默认 ws://localhost:2700） |

- 归属校验：`AgentChatService.getOwnedConversation(userId, id)`，所有端点复用；会话不存在与无权访问统一 40401 同文案（防 IDOR 枚举）
- 校验错误用 `BusinessException`（40001/40301/40401），GlobalExceptionHandler 已映射对应 HTTP 状态码

### SSE 事件协议（后端→前端）

事件类型由 SSE `event` 字段承担（负载不含 `type` 键），后端编排（AgentOrchestrator）直接发射；流结束即 `emitter.complete()`：

| event | 负载字段 | 说明 |
|-------|----------|------|
| `message` | `content` | 回答增量（前端逐段拼接打字机效果） |
| `workflow` | `title`, `status` | 节点进度（`node_started` / `node_finished`） |
||| `human_input` | `formToken`, `taskId`, `formContent`, `actions`（`[{id,title}]`）, `expirationTime` | HITL 暂停点；**收到后后端立即结束流**，前端渲染确认卡片并停止打字机。`actions` 可含 `{id:"custom", title:"✍ 自定义输入"}`——前端渲染内联输入框，提交时 `POST /form/submit` body 携带 `content` 为用户自定义文本 |
|| `task_accepted` | `taskId`, `message` | 视频异步任务已受理（resume/generate_video 或图生视频确认后立即返回）；前端转 5s 轮询 `GET /api/agent/tasks/{taskId}` 取结果 |
|| `message_end` | `messageId`, `sceneCount`, `content`, `title?` | 流正常结束；`sceneCount` 为当前项目 scenes 总数，供前端互斥判定；`title` 为首条消息异步 AI 重命名的新标题（**一次性**：仅重命名完成的那一轮携带，取走即删，不做轮询） |
| `error` | `code`, `message` | 失败结束；`message` 已脱敏 |

- `workflow` 事件仅发 `{title, status}`（node_started/node_finished），不携带节点 inputs/outputs

### Spring AI 2.0 编排（2026-08-11 迁移，替代 Dify；2026-08-11 二次重构：策略注册 + HITL 模板）

- **编排器**：`AgentOrchestratorImpl` 瘦身为**纯分发器**（策略模式注册）：`run()` = 意图识别（规则前置 → LLM + confidence → 阈值判断）→ 按 intentType 查 `Map<String, IntentHandler>` 分发；`resume()` = checkpoint 校验+一次性消费 → 按提交 action 查 handler 恢复。处理器注册表由 Spring 自动收集（`List<IntentHandler>` + `@PostConstruct buildRegistry`），**新增意图 = 新实现类 + @Component，核心零改动**。resume 分发前 `request.setAction(action)` 透传提交的选项 id；**动态选项卡片**（选项 id 由 LLM/场景生成，不进 byAction 注册表）按 checkpoint action 特判转对应 handler：`intent-clarify`（意图澄清）/ `clarify-option`（gate 澄清）/ `scene-mode`（分镜处理方式）/ `scene-regenerate`（分镜调整意见）
- **处理器**（`ai/agent/handler/` 包）：`IntentHandler` 接口（intentType/resumeActions/handle/resume）+ `OrchestrationRequest` 上下文（含 per-request lastMessage，修复多会话并发互踩）+ `AgentOrchestratorSupport` 共享组件（SSE 发送/checkpoint 落库/LLM 调用/瞬态重试）+ 4 个实现：AisplitIntentHandler / PicIntentHandler / VideoIntentHandler / OtherIntentHandler
- **HITLStage 通用模板**：`runHITLStage`（workflow → 方案消息 → checkpoint 落库 → human_input/video_plan 事件）+ `resumeStage`（workflow → 结果消息 → confirm_result → message_end）；三链只填「方案生成逻辑」与「执行工具」两个钩子（StagePlan record 承载差异），HITL/checkpoint/resume 全复用
- **意图识别增强**：规则前置匹配（强关键词如「分镜/剧本」「生成视频」「生成图片」直接路由，免一次 LLM 调用，confidence=1.0）+ LLM 输出 JSON `{type, confidence}`；**低于 `ai.agent.intent-threshold`（默认 0.6）不硬路由，走澄清分支**；失败兜底 intent-other；LLM 调用瞬态失败（429/5xx/超时）重试 1 次（流式与 writeScenes 刻意不重试——非幂等/重复 delta）
- **意图路由（决策 4，2026-08-11 全链实现）**：
  - aisplit：**现有分镜检测（handle 入口一次，防重入死循环）**——项目已有分镜 → 卡片1「处理方式」（`scene-mode`：基于现有优化/全新创建/不生成）→ 剧本优化 gate（type=0 追问澄清/type=1 继续）→ 分镜方案 → 分镜 JSON（ScriptGenerationService）→ 方案确认卡片（checkpoint action 恒 `agree`，选项动态：**有现有分镜**→ [replace 覆盖导入][append 追加][disagree 不满意][cancel 不生成] + 覆盖警告文案；**无**→ [agree 满意][disagree 不满意]）→ resume 写库；**不满意（disagree）→ 调整意见卡片（`scene-regenerate`，选项=✍自定义输入）→ 意见+上一轮方案重走生成链（可循环）**，连续不满意达 `ai.agent.max-regenerate-rounds`（默认 3）后不再弹卡片，提示直接输入新需求（regenCount 内存计数，写库成功清零）；**澄清追问上限 `ai.agent.max-clarify-rounds`（默认 2）**——连续 type=0 达上限后不再追问，直接按原始需求出默认方案让用户选（内存计数，type=1/非 aisplit 轮清零）；写库策略：replace → `AgentTools.replaceScenes`（同事务清空+写入，中途失败不丢现有分镜）/ append → 追加（现状）/ cancel → 不写库 message_end（sceneCount=-1 不触发前端刷新）
  - pic：有参考图 → `ImageRefinePromptService.buildRefinedPrompt`（视觉模型）→ HITL（generate_image/refine）→ resume 图改图；无图 → `callImagePrompt`（LLM）→ 直接文生图自动完成
  - video：有参考图 → `VideoPlanService.buildVideoPlan`（视觉模型）→ video_plan 卡片；无图 → `callVideoPlan`（LLM 方案）→ 同样 video_plan 卡片；**「开始生成视频」统一走 `/form/submit`（action=generate_video → VideoIntentHandler.resume）→ `task_accepted` 立即返回，后台虚拟线程轮询 MiniMax（~2min）更新 agent_assets 行**，前端轮询 `GET /api/agent/tasks/{taskId}`（旧 `/video/plan/generate` 端点保留为兼容壳，同一异步路径）
  - other：`AgentAnswerService` 主回答（ChatClient 拼历史，流式打字机）
- **会话级互斥**：`ConversationLock`（Semaphore，无所有权跨线程安全）——同一 conversation 同时只允许一个活跃编排实例；streamMessage / submitFormAndResume / generateVideoFromPlan / sendMessage 四入口统一 tryAcquire，忙碌返回 40901（SSE error 事件 / blocking 抛 BusinessException，GlobalExceptionHandler 已映射 CONFLICT）；**complete 由调用方统一执行且先释放锁再 complete**（防前端收到 EOF 立即发下一条撞锁）
- **工具面**：`AgentTools`（@Component 工具组件，不接口化）：writeScenes（复用 `AgentGenerationService.writeScript`）/ replaceScenes（复用 `replaceScript`，同事务清空+写入）/ refineImage / generateVideo；当前由编排（handler 链）直接调用，自动模式 `ChatClient.tools()` 工具循环为后续增强；**工具异常不向上抛**（LLM 消化为文本），业务错误须在 @Tool 内返回错误对象
- **主回答**：`AgentAnswerService`（intent-other 闲聊）ChatClient 拼历史流式（打字机）；D3 定案流式（message 增量 + message_end 携完整文本）
- **HITL checkpoint**（表 `agent_checkpoints`，V4 migration）：form_token 一次性消费（status pending→used 原子条件）、30 分钟过期、plan 存方案 JSON 文本（TEXT 列，实体 String 直插）；替代原内存 Map 快照（formSnapshots/videoPlanSnapshots 已删）
- **事务语义**（沿用 I1）：user 消息 `TransactionTemplate` + `REQUIRES_NEW` 独立事务立即提交；编排失败 user 消息保留；编排成功后 `persistAssistant` 落库 assistant 消息
- **LLM 配置**：复用 `spring.ai.openai.*`（网关），编排 ChatClient 超时 60-120s；`ai.agent.*` 为编排行为参数（intent-threshold / max-clarify-rounds / max-regenerate-rounds）

### 首条消息异步 AI 重命名标题

- **触发**：`AgentChatService.maybeScheduleTitleRename`（streamMessage + sendMessage 双路径，**必须在 user 消息落库前调用**——落库后 selectCount 已 +1，"首条"判定 count==0 永远不成立，线上实测踩坑）。三重判定：该消息是会话第一条消息（insert 前 count==0）+ 标题仍为默认值「新对话」+ `titleScheduled` 并发去重成功
- **异步**：`CompletableFuture.runAsync(..., agentExecutor)`（虚拟线程），不阻塞编排主流程；任务体全 try-catch，失败仅 `log.warn`，标题保持「新对话」，对话零影响
- **生成**：`ConversationTitleService`（新建）调 Laozhang chat completions（`baseUrlVision`，超时 30s），模型固定 `gemini-3.5-flash-lite`（**实测**：老张网关对 preview 系模型的一切思考参数均不透传/拒绝，无法关思考；flash-lite 默认零思考 token，即"不思考模式"），请求体显式带 `thinking_level: minimal`（flash-lite 接受，语义自文档化）
- **落库（并发坑）**：编排线程持有同一 `AgentConversation` 实体并整实体 updateById，标题线程**必须**用 `LambdaUpdateWrapper` 只 set title/updatedAt 两列，并带 `.eq(title, "新对话")` 原子条件——整实体更新会把对方刚写的新字段冲掉
- **一次性推送**：落库成功后新标题暂存 `renamedTitleByConversation`，`messageEndPayload` 在 `message_end`（含 workflow_finished 恢复流）发送时 `remove` 取走并附 `title` 字段；**只推一次**（取走即删），不做轮询/持续推送；极端时序（标题未生成完流已结束）该轮不推送，前端下次拉取会话列表自然可见
- **前端**：`agentStore.ts` 两个 `message_end` 分支（sendMessage / submitHumanInput）收到 `e.title` 就地更新 `conversations` 列表（守卫 `c.title !== e.title` 防重复 set）；`SseEvent.title` 类型已存在，零类型改动

### 提示词优化（/api/agent/prompt/optimize）

- **端点**：`POST /api/agent/prompt/optimize`，请求 `{content}`，响应 `{optimized: string}`；JWT 鉴权（`/api/agent/**` 非白名单）；**不落库、不关联会话**（纯文本转换工具）
- **校验**：`content.trim().length < 6` → 40001「内容至少 6 个字符才能优化」
- **实现**：`PromptOptimizeService` 调 Laozhang chat completions（`baseUrlVision` + `defaultVisionModel` 质量优先，不传 thinking_level；超时 60s）；**优化方向由 LLM 自行判断**（草稿可能是剧情/图片/视频或综合需求），单文本输出不强制 JSON——规避解析失败风险
- **前端**：已取消「✨ 优化」按钮（AgentChatPanel 输入区只保留发送按钮）；接口保留备用，前端不再调用

### AI Service 解耦（sceneId 可空）


- `ImageGenerationService.generateImage`：sceneId 为 null 时不查/不写 scene 表，只用局部变量 `localPath` 返回
- `VideoGenerationService.createVideoTask`：同样支持 sceneId 为 null；`pollVideoTask` **双通道反查**——先按 `videoTaskId` 查 scene，查不到再按 `taskId` 查 agent_assets 并更新其 url/status/error（failed 分支先解析上游 message/error 再 setError）

### 图改图/图生视频（编排链，2026-08-11 起）

- **图改图**：`AgentTools.refineImage` 复用 `ImageRefinePromptService.buildRefinedPrompt`（视觉模型看图+诉求→refined_prompt）+ `AgentGenerationService.generateImage(mode=edit)`；源图=本轮 PicUrl（streamMessage 传入），诉求=用户消息；确认卡片「继续完善/满意完成」交互保留，`confirmImageDone` 清会话图片上下文
- **图生视频**：`VideoPlanService.buildVideoPlan`（视觉模型）+ MiniMax 图生视频链路；`generateVideoFromPlan` 读 checkpoint（plan 存 `{message,duration,source}`，兼容 items 包裹），confirm_result 卡片保留
### 智能体窗口前端约定

- **入口**：编辑器右下角悬浮球 ☾ → 右侧抽屉（62vw，minWidth 480）：左会话栏（180px，顶部为「☾ Moon 智能体」标题）+ 右对话区（顶部显示当前会话标题，无会话占位「未选择对话」；右侧 📁 产出素材 + 🧹 清除聊天记录按钮）
- **底部输入栏**：可拖拽上下伸缩（min 90 / max 40vh，顶部 4px 把手 hover 变主色）；右侧为「发送」按钮（「✨ 优化」已取消）
- **命名**：用户可见文案统一「产出素材」（原「资产」，仅文案，store/字段名 assets/AgentAsset 不变）
- **互斥规则**：`message_end.sceneCount` > 会话开始时 `scenes.length` → `agentGeneratedScenes = true`（仅内存态，刷新恢复）→ `LeftSidebar` 剧本输入禁用
- **inputs 适配 Moon 工作流**：`{ currentProjectId, PicUrl }`（替换旧 `project_id`/`project_name`）
- **卡片模型/参数选择（2026-08-12 起）**：`human_input`/`video_plan` 事件可带 `models`（网关 `/v1/models` 模型列表，含各模型 params 能力枚举+默认值）、`recommended`（LLM 方案生成时预选的参数值）、`reasons`（每参数推荐理由，≤15 字）——前端 `AgentParamSelector` 渲染模型下拉+参数联动，**默认选中 LLM 推荐值并展示理由，用户可改**；提交 `/form/submit` body 带 `params`（`{model,resolution,duration,aspectRatio,size,quality}`），经 `OrchestrationRequest.params` → handler → 生成服务透传，**取值优先级：用户提交 > LLM 推荐 > checkpoint plan 原值 > config 默认**；无 `models` 字段时前端不渲染选择器（零回归）。LLM 选参选项文本由 `AgentOrchestratorSupport.buildModelOptionsText(type)` 组装（网关不可用返回空，LLM 不选参直接出默认方案）
- **参考图**：`POST /api/agent/upload` → 返回 `url` → 作为 `PicUrl` 随消息发送（图生图/图生视频）
- **LLM 配置**：复用 `LLM_GATEWAY_BASE_URL` / `LLM_GATEWAY_API_KEY`（.env，Spring AI 网关）

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

- **Backend 分层规范（2026-08-11 重构，严格执行）**：
  - Controller 层：只做接收参数、参数校验、调用 Service、封装 `ApiResponse`，**禁止任何业务逻辑**（不持有 HttpClient/Mapper/对象组装）
  - Service 层：必须「接口 `XxxService` + 实现 `XxxServiceImpl`」，接口在 `service` / `ai` / `ai/agent` 原路径，Impl 在对应 `impl/` 子包；接口只放 public 方法 + 中文 javadoc，私有方法/常量/内部 record 留 Impl
  - 依赖注入：lombok `@RequiredArgsConstructor`（final 字段），禁止字段 `@Autowired`、禁止手写构造器（例外：`AiConfigProperties` 构造器 `@Autowired` 是 SB4 配置绑定需要，保留；`JwtTokenProvider` 用 `@RequiredArgsConstructor(onConstructor_ = @Autowired)`——多构造器歧义消解，测试构造器不标注）
  - Mapper 层：`mapper/` 包独立隔离，Service 通过 Mapper 访问数据，Controller 禁止直接注入 Mapper
  - DTO/VO：前端参数一律 `dto/request` 的 record 接收；返回一律 `dto/response` 的 VO/record，**禁止 Controller 直接返回 entity**（例外：生成类接口返回 `Map<String,String>` 属数据封装，可接受）
  - 单文件单类：嵌套 record 若被接口方法签名引用，提取为同包顶层类
- **AILLMGateway（LLM 网关，8083，2026-08-11 同套规范重构完成）**：分层规范与主后端一致——12 个 service 接口+impl（`service/` + `service/impl/`，UpstreamClient/GeminiFormatConverter 为 @Component 工具组件不接口化）；11 个 controller 全部薄层（@RequiredArgsConstructor，无 Mapper/HttpClient/业务逻辑，/v1/models 组装下沉 GatewayRoutingService.fetchModels）；admin 模块 4 个新 service（ChannelService/ModelRouteService/ModelParamsService/AdminUserService）+ 6 个 VO（dto/vo，敏感密钥字段不包含）；RouteResult/VideoResult 为 service 包顶层 record；`/v1/**` 由 StaticApiKeyFilter 鉴权、`/admin/**` 由 AdminJwtFilter 鉴权（均不变）
- **Backend**: DTOs use Java records; services are `@Service`; mappers extend `BaseMapper<T>`; entities use Lombok `@Data`
- **Frontend**: Functional components with hooks; Zustand for global state; Axios instances with interceptors for auth token injection
- **API responses**: Wrapped in `ApiResponse<T>` with `code`, `message`, `data`
- **Auth**: JWT access token (short-lived) + refresh token; `JwtAuthenticationFilter` on all routes except `/api/auth/**` and `/api/files/**`
- **CORS**: Configured in `SecurityConfig` via `CorsConfigurationSource` bean