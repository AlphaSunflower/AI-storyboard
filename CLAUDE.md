# AI Storyboard

AI-powered storyboard generation platform. Spring Boot 4 backend + React/TypeScript frontend.

## Tech Stack

- **Backend**: Spring Boot 4.0.0, JDK 21, MyBatis-Plus 3.5.16 (spring-boot4-starter), PostgreSQL, JDK HttpClient
- **Frontend**: React 19 + TypeScript 6 + Vite 8, Zustand 5, Tailwind CSS 4, Axios (timeout 120s), React Router 7
- **AI**: Laozhang API v2 (`api2.laozhang.ai`), models: `gpt-image-2`, `gemini-3-flash-preview`, `veo-3.1-fast`
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
| `message_end` | `messageId`, `sceneCount` | 流正常结束；`sceneCount` 为当前项目 scenes 总数，供前端互斥判定 |
| `error` | `code`, `message` | 失败结束；`message` 已脱敏（"Dify 服务异常，请稍后重试"） |

- Dify 原始流中的 `ping`、`node_started/finished` 内部 `inputs`/`outputs`、`tts_message` 等事件一律过滤，不转发到前端

### AgentChatService — Dify 对话代理

- `POST {difyBaseUrl}/v1/chat-messages`，`response_mode: "blocking"`（非流式），`Authorization: Bearer {difyApiKey}`
- **事务语义**（重要）：user 消息用 `TransactionTemplate` + `PROPAGATION_REQUIRES_NEW` 独立事务立即提交；Dify 调用失败时 **user 消息保留**、抛业务异常；Dify 成功后 `transactionTemplate.execute` 内完成"回填 dify_conversation_id + 保存 assistant 消息"（失败整体回滚）
- Dify 上游错误完整信息只进日志，抛给客户端的文案脱敏（"Dify 服务异常，请稍后重试"）
- 配置：`ai.laozhang.dify-base-url`（默认 `http://localhost`）+ `ai.laozhang.dify-api-key`（复用既有字段）

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

### 智能体窗口前端约定

- **入口**：编辑器右下角悬浮球 ☾ → 右侧抽屉（480px）：左会话栏（138px）+ 右对话区 + 底部资产面板
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
