# AI Storyboard

AI-powered storyboard generation platform. Spring Boot 4 backend + React/TypeScript frontend.

## 当前工程状态（2026-10-10）

- MoonAgent 是 Spring Boot 4.1.1 + Java 25 + AgentScope 2.0.4 服务，监听 `8084`；`MoonAgentRuntime` 是唯一智能体执行器。
- MoonSandbox 是独立常驻执行服务，监听宿主 `127.0.0.1:8090`，工作目录只存在于 Docker 命名卷 `sandbox-workspaces`；MoonAgent 通过内部 HTTP 调用，不挂载 Docker socket。
- MoonSandbox 不注册 Nacos。MoonAgent 通过 `AGENT_SANDBOX_ENABLED`、`MOON_SANDBOX_BASE_URL`、`MOON_SANDBOX_INTERNAL_SECRET` 连接；Nacos 只承载 Skill、全局提示词和 MCP 元数据。
- 会话隔离采用“`/workspaces/{conversationId}` 独立目录 + Redis 会话任务锁 + runner 内文件锁”。同一会话严格串行，不同会话共享容器并可并发。
- Markdown、TXT、DOCX、XLSX、PDF 和图表等最终产物必须上传 MinIO，并写入 `agent_assets`；聊天消息通过 `metadata.sandboxAttachments` 展示文件卡片。
- 2026-09-21 的“项目库统一改造”仍是已批准方向，但已转入存量架构要求：保留 `canvas_assets.id`、`/api/assets` 只读兼容与统一 `asset.library_*` 数据映射，相关权限、上传、快照、迁移和跨服务故障测试仍须补齐。

## AgentScope / Sandbox 不可回归红线（最高优先级）

> 以下问题已经在真实运行中造成“界面卡住、思考链不出现、实时文字延迟、产物看不见、取消卡片反复弹出”。这些不是普通体验问题，而是关键链路事故。任何触及事件流、HITL、推理展示、沙箱调用或产物发布的改动，都必须把本节当作回归验收标准。

### 1. 一个 turn 只能有一条有序事件通道

- `AgentTurnEventPublisher` 必须继续作为 `THINKING_BLOCK_DELTA`、`TEXT_BLOCK_DELTA`、工具事件、`AGENT_TRACE` 和其他原生事件的唯一有序发布入口。
- 禁止在 publisher 存活期间另起线程、回调或组件直接并发调用 `AgentTurnEventStore.append(...)`。独立 append 会绕开批次顺序和 emitter 串行化，产生乱序 SSE；前端 `AgentTurnEventSequencer` 会按 `seq` 等待缺口，表现为“看起来卡住”或思考链到结束后才出现。
- 每个 turn 的 `seq` 必须从 1 开始连续递增。事件持久化、SSE 推送和 `afterSeq` 补偿必须使用同一份序号；任何持久化失败都必须显式暴露，不能静默吞掉后继续发送后续序号。
- 高频文本和思考 delta 可以由 publisher 合并批次，但 `AGENT_TRACE`、工具、HITL、完成和失败等结构事件必须先 flush 尚未写入的 delta，再按同一序号顺序发布。
- 终端事件必须与直播事件保持同序。`turn_completed`、`turn_paused`、`turn_failed`、`canvas_*` 不能先于其依赖的文本、思考、工具或产物事件到达前端。

### 2. 思考与实时输出必须持续可见

- `THINKING_BLOCK_DELTA` 到达后必须立刻更新可见状态，不能等 `turn_completed`、完整句子或完整事件批次。
- `reasoning.activity` / `workflowHint` 必须持续展示最新思考尾部；前端 `ReasoningPanel` 在流式阶段必须显示“正在思考 N 秒：...”，即使还没有完整句子也不能空白。
- 首个 `turn_started` 就应让用户看到活动状态和计时，文本 delta 出现后继续追加显示。用户必须始终能判断智能体是在推理、调工具、执行脚本还是等待用户。
- 完成事件只能把直播推理归档为最终 metadata，不能成为首次显示推理的触发条件。

### 3. 时间字段与 JSON 序列化不可退化

- 任何手工创建、持久化或解析 `OffsetDateTime` / `Instant` 的 `ObjectMapper` 必须注册 `JavaTimeModule` 并关闭 `WRITE_DATES_AS_TIMESTAMPS`。
- `AgentTurnEventStore` 与 `MoonSandboxClient` 是已知事故点。缺模块会让 `AGENT_TRACE.startedAt` 序列化失败，事件 payload 退化成 `{}`，沙箱文件列表也会解析失败，最终导致前端轨迹为空和产物无法发布。
- 新增事件 payload 涉及 Java 时间类型时，必须补一个真实 JSON round-trip 测试，不能只测对象构造。

### 4. HITL 与取消语义必须保持终态

- `reject` 是终止取消，不是可回灌给模型的工具结果。用户拒绝后：checkpoint 原子转为 `used`，turn 以取消/完成结束，不再调用模型、不重新发确认卡片。
- HITL pending/claimed 状态必须限定在当前会话和当前 turn。完成、取消、失败或清理后不得从陈旧 checkpoint 恢复出可再次点击的卡片。
- 前端不得因重放、刷新、补偿事件或重复点击再次提交同一个 `formToken`；后端必须保持一次性消费和幂等终止。

### 5. 沙箱与产物发布链路不可混用宿主机路径

- `write_file`、`read_file`、`list_files`、`run_python_script` 都只操作 runner 容器内的会话工作区：`/workspaces/{conversationId}/{work,output,vendor}`。
- 宿主机 `MoonSandbox/target/workspaces/...`、本机上传目录或 MoonAgent 进程目录都不是沙箱真源，也不是聊天附件真源。
- 每轮任务完成后，`SandboxOutputReconciler` 必须扫描沙箱 `output/`，由 `SandboxArtifactPublisher` 下载文件、校验扩展名/MIME/大小、上传 MinIO，并写入 `agent_assets`。
- Markdown、TXT、DOCX、XLSX、CSV、PDF 属于 `agent_assets.type=document`；PNG/JPG/SVG 属于 `type=image`。产物应带 `gen_type=sandbox_generated`、turn/task 来源、内容哈希和幂等键。
- 后端必须把公开 URL、文件名、MIME、大小等写入 assistant message 的 `metadata.sandboxAttachments`；前端消息卡必须展示文件卡、类型、大小、生成时间和下载入口。只上传 MinIO 但不回写消息，属于未完成。
- 同一内容重复扫描必须幂等，不能制造重复资产或重复卡片；文件超过限制或 MIME 不匹配时跳过并记录可诊断日志。

### 6. 关键链路的最低验证门槛

任何修改下列任一路径的提交，至少运行对应聚焦测试，并在完成说明中写出证据：

```powershell
mvn -pl MoonAgent -am "-Dtest=AgentTurnEventStoreTest,MoonSandboxClientTest,SandboxOutputReconcilerTest,CheckpointResumeServiceTest" test
cd AIStoryboardClient
npm test -- --run src/stores/agentStore.test.ts
npm run build
```

新增或修复直播、HITL、沙箱工具或产物展示时，测试必须覆盖：

- delta 与结构事件按 `seq` 连续、乱序到达时前端正确等待和归并。
- `THINKING_BLOCK_DELTA` 到达即出现可见思考与计时，不依赖 `turn_completed`。
- `AGENT_TRACE.startedAt` 和时间字段 JSON round-trip 不丢失。
- `reject` 后 checkpoint 为终态、模型不再执行、卡片不复活。
- 同一会话写/执行互斥，不同会话可并行。
- `output/` 文件发布到 MinIO、写入 `agent_assets`，并在消息 metadata 与 UI 中形成文件卡。

## Tech Stack

- **Backend**: Spring Boot 4.1.1, Java 25, MyBatis-Plus 3.5.16 (spring-boot4-starter), PostgreSQL, Redis, MinIO, JDK HttpClient
- **Frontend**: React 19 + TypeScript 6 + Vite 8, Zustand 5, Tailwind CSS 4, Axios (timeout 120s), React Router 7
- **AI**: 全部模型调用统一走 **LLM 网关**（AILLMGateway，:8083，`LLM_GATEWAY_BASE_URL` / `LLM_GATEWAY_API_KEY`）；chat/文生图/图改图/视频均经网关转发（生图 `gpt-image-2`、视觉 `gemini-3-flash-preview`、视频 `MiniMax-H3`），协议转换与上游密钥下沉网关
- **Agent**: MoonAgent Spring Boot 4.1.1 + Java 25 + AgentScope 2.0.4，运行时端口 `8084`；共享 Docker 沙箱执行面端口 `8090`
- **Auth**: JWT (jjwt), scrypt password hashing (lambdaworks 1.4.0), cross-system token exchange via `/api/auth/unlogin`
- **Ports**: ApiGateway 8080, Backend 8082, AILLMGateway 8083, MoonAgent 8084, MoonSandbox 8090, Frontend 5173

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
├── CommonCore/                   # ApiResponse / BusinessException / GlobalExceptionHandler / MultipartBuilder
├── agent-core/                   # Agent 通用模型、存储与工具契约
├── asset-client/                 # 资产服务客户端契约
├── asset-service/                # 统一资产库服务
├── AIStoryboardBackend/          # 主业务后端（8082）：项目、分镜、认证、文件兼容接口
├── MoonAgent/                    # Moon 智能体（8084）：AgentScope 运行时、SSE turn、HITL、沙箱工具与产物发布
├── MoonSandbox/                  # 常驻共享 Docker runner（8090）：会话目录、文件工具、Python 执行、资源限制
├── AILLMGateway/                 # 模型网关（8083）：chat/image/video 路由、厂商适配、渠道密钥下沉
├── ApiGateway/                   # 统一 API 网关（8080）
├── XiaoYueCanvas/                # 画布后端（3000，前端代理 `/canvas`）
├── XiaoYueCanvasAgent/           # 画布前端/Agent 相关包
├── AIStoryboardClient/           # React 19 前端（5173）：编辑器、ChatPage、文档/图片资产中心
├── deploy/docker/                # nacos / vosk / redis / minio / sandbox 独立 Compose
├── docs/                         # 设计与实施文档
└── claude/DESIGN.md              # Design tokens spec
```

## Docker 部署目录（deploy/docker/）

基础设施 Docker 部署按服务拆为独立 Compose 项目，可单独启停、单独拷到其他机器。根 `scripts/` 提供 `setup/up/down/status`，接受 `[nacos|vosk|redis|minio|all]`；`sandbox/` 使用自己的 `scripts/up|down|status`，因为启动前需要先构建 runner jar。

| 服务 | 目录 | 端口 | 说明 |
|------|------|------|------|
| nacos | `nacos/` | 8848/9848/8850 | `nacos/nacos-server:v3.2.3` standalone 服务发现；数据卷 `nacos-data` |
| vosk | `vosk/` | 2700 | 离线中文语音识别；模型挂载 `vosk/data/models/vosk-model-cn-0.22` |
| redis | `redis/` | 6379 | 会话锁、缓存；`redis-ai` + AOF 命名卷 |
| minio | `minio/` | 9000/9001 | 最终对象存储：S3 API + 控制台；数据在 `minio/data/` |
| sandbox | `sandbox/` | 8090 | `moon-sandbox-runner` + Squid 出站代理；工作区命名卷 `sandbox-workspaces` |

首次部署：`./scripts/setup.sh`（下载约 1.3GB 中文模型）→ `./scripts/up.sh`。沙箱需另执行：

```bash
mvn -pl MoonSandbox -am package -DskipTests
cd deploy/docker/sandbox
cp .env.example .env
./scripts/up.sh
```

沙箱约束：

- runner 只接 internal 网络，公网只能经 `sandbox-egress-proxy`；域名白名单在 `SANDBOX_ALLOWED_DOMAINS`。
- runner 使用非 root、只读根文件系统、`tmpfs /tmp`、CPU/内存/PIDs 限额和 `unless-stopped`。
- 日常停止不要使用 `docker compose down -v`，否则会删除 `sandbox-workspaces` 命名卷。
- 沙箱工作文件最终仍以 MinIO 为准；MoonAgent 的 `SandboxOutputReconciler` 每轮扫描并发布 `output/`。

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
  - 创建：`POST {gateway}/v1/videos`，Bearer `LLM_GATEWAY_API_KEY`，OpenAI 风格 JSON body `{model, prompt, size, resolution, aspectRatio, duration, negativePrompt?, seed?, imageUrl?}` → 响应统一返回 `{vendor, taskId}`（网关已归一，**业务侧只读 taskId，不再猜 task_id/id/taskId**）
  - 轮询：`GET {gateway}/v1/videos/{taskId}`（queued/running→processing；succeeded→下载；failed→error）
  - 下载：`GET {gateway}/v1/videos/{taskId}/content` → 转存 uploads/videos（限时链接须立即转存）
  - **厂商协议适配器（2026-08-27 重构）**：网关 `com.llmgateway.vendor.video`（spi/dto/impl 三层）按 channel.type（minimax / openai_compatible）分发 `VideoVendorAdapter`：每协议一个适配器，自含请求载荷构建（MiniMax JSON content 数组 / Laozhang multipart）+ 创建/轮询响应解析 + 重试策略（次数/可重试判定/退避）。**新增协议 = 新实现类声明新 channelType + DB 配渠道，编排器零改动；同协议复用同一适配器**（openai_compatible → LaozhangVideoAdapter）
  - **同类模式已推广**：聊天/生图路由 = `com.llmgateway.vendor.route`（`ChatImageVendorAdapter` + 按 channel.type 分发，Gemini 转换 / OpenAI 兼容透传；结果复用 RouteResult，无 sealed）；agent-core 视频 poll 去重 = `VideoPollExecutor`（VideoGenerationServiceImpl / MinimaxVideoServiceImpl 委派，只去 agent-core 内、不抽 CommonCore，见 skill `vendor-chat-image-route-adapter.md` / `video-poll-executor.md`）
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

**依赖**：`pom.xml` 引入 `spring-ai-bom:2.0.0`（dependencyManagement import）+ `spring-ai-starter-model-openai`（SB 4.1.1 兼容；泄漏断言正则必须限定 groupId `org\.springframework\.ai:spring-ai[^:]*:jar:(1\.|0\.)`）。

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
**编排层（2026-09-26 起）**：MoonAgent 只有一个 `MoonAgentRuntime`
（`HarnessAgent`、PostgreSQL StateStore、动态 Skill 仓库、PermissionContext、
Subagent、Compaction、异步工具和 Wakeup）。旧 `AgentOrchestrator`、
`UnifiedAgentLoop`、`DynamicTaskEngine`、Specialist/IntentHandler/Capability
路由代码已退役；旧对象只能通过只读兼容查询访问。

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

## AgentScope 运行时 (`/api/agent/**`)

MoonAgent 的唯一执行器是 `MoonAgentRuntime`。REST Controller 只负责会话归属、
请求校验、事务边界和 SSE 传输；消息、资产、checkpoint、工具审计和生成记录仍是
产品数据。Nacos 是六个标准 Skill、全局系统提示词和 MCP 元数据的版本化资产源，
classpath 是同版本资产的离线回退。AgentSpec 和 AgentScope Service 不在当前范围。

### Turn API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/conversations/{id}/turns/stream` | 新建或编辑重发一轮；body 可带 `editMessageId` |
| POST | `/api/agent/conversations/{id}/turns/{turnId}/resume/stream` | 通过 checkpoint `formToken` 恢复同一 turn |
| POST | `/api/agent/conversations/{id}/turns/{turnId}/interrupt` | 中断指定 turn 所属会话 |
| GET | `/api/agent/conversations/{id}/runtime` | 运行时、Skill、Prompt、MCP 元数据状态 |
| GET | `/api/agent/conversations/{id}/turns/{turnId}/events?afterSeq=N` | 按序号补偿重放 turn 事件 |

### 通用创作智能层

- `project_creative_contexts` 保存项目级目标、创意方向、风格、参考、硬/软约束、反馈和模型偏好；
  不保存跨项目记忆，也不引入建筑等垂直领域字段。
- 明确出现“必须/严格/保留/不能改”等词时进入 `STRICT`，命中约束通过
  `constraint_flagged` 事件下发；未识别到可验证硬约束时要求先澄清。
- AgentScope 已启用 Plan Mode、任务列表、工具结果驱逐、五类创作子智能体、PostgreSQL
  `DistributedStore` 和 OpenTelemetry；模型角色通过
  `AGENTSCOPE_MODEL_PLAN/PROMPT/CRITIC/VISION/IMAGE/VIDEO` 覆盖。
- 图片生成后会调用视觉批评器。批评结果为 `REPAIR` 时允许一轮提示词/参考修复；
  第二轮仍失败则必须转 HITL。视频和图片评测记录保存在 `agent_media_critiques`。
- 长视频先保存 `agent_timeline_manifests`，再由合并工具按顺序组装；缺片不得静默跳过。
- 新接口：`/api/agent/projects/{projectId}/creative-context`、
  `/api/agent/runs/{runId}/plan`、`/api/agent/runs/{runId}/critique`、
  `/api/agent/runs/{runId}/items/{itemId}/repair`。

`/messages/stream`、`/form/submit`、`/video/plan/generate`、计划 actions、
`/messages/interrupt-and-send` 和 `/messages/{messageId}/edit-and-resend`
返回 `410 Gone`。会话、消息、资产、生成记录和历史计划读取接口保持可用。

### SSE 协议

每个 SSE frame 的 `data` 都是 `AgentTurnEventEnvelope`：
`{turnId, seq, type, payload}`。生命周期 `type` 为 `turn_started`、
`turn_paused`、`turn_completed`、`turn_failed`，另有 `constraint_flagged`、
`canvas_session_required`、`canvas_run_completed`、`canvas_run_failed`；原生 AgentScope 事件使用
`type=agent_event`，其中 `payload={eventType, data}`。前端按 `seq` 去重并按
turn 归并文本、推理、工具、Subagent、Plan、HITL、Artifact、Usage 和 Error。

- 实时文本使用 `TEXT_BLOCK_DELTA` / `AGENT_RESULT`，思考使用 `THINKING_BLOCK_DELTA`，轨迹使用
  `AGENT_TRACE`。所有这些事件都必须经 `AgentTurnEventPublisher` 发布并保持连续 `seq`。
- 前端 `AgentTurnEventSequencer` 对每个 turn 持有缺口；如果后端绕开有序 publisher，前端不会
  “猜顺序”，而是等待缺失序号，表现为卡住。不要用缩短前端等待时间掩盖后端乱序。
- `turn_completed.payload.metadata` 必须合并并保留实时阶段的 `reasoning`、`agentTrace`、
  `metrics` 和 `sandboxAttachments`，不能因终态 payload 只含正文而把直播数据覆盖为空。
- 沙箱产物文件卡由 `metadata.sandboxAttachments` 驱动；没有该 metadata 就不能算产物已交付。

### 数据与恢复

- `agent_turns` / `agent_turn_events`：流重连、审计和 `afterSeq` 补偿。
- `agentscope_sessions`：AgentScope 运行时上下文。
- `agent_sandbox_sessions`：会话沙箱状态、最后使用时间、过期时间和版本。
- `agent_messages/assets/checkpoints/tool_invocations`：产品数据。
- 未迁移会话在首个 AgentScope turn 前从最近 20 条产品消息重建会话；已有运行时状态优先。
- 旧 checkpoint 标记过期，活动旧计划标记取消；表和历史内容不删除。
- runner 重启不丢 `sandbox-workspaces`；MoonAgent 重启后可重新扫描 `output/` 并按内容哈希幂等发布。

### 权限与资产

- 读工具默认 ALLOW。
- 删除、清空、覆盖、批量生成、Canvas 执行进入 AgentScope `ASK`/HITL。
- 单张媒体生成是否直接执行由 `ai.agent.agentscope.direct-media-generation` 控制。
- `write_file` 和 `run_python_script` 走现有审批、工具审计与 FULL_ACCESS 策略；`reject` 必须终止本轮，不能把拒绝结果继续喂给模型。
- 沙箱最终产物统一经 `SandboxArtifactPublisher` 上传 MinIO 并写 `agent_assets`；聊天卡片只引用资产 URL 和 message metadata，不暴露宿主机路径。
- 六个生产 Skill：`storyboard`、`image`、`video`、`canvas-design`、`canvas-run`、`general`。
- Nacos 发布脚本：`scripts/nacos/publish_assets.py`；只支持 upload/review/release 生命周期，
  Canvas Bridge 不进入共享 Nacos。

## Historical pre-AgentScope notes (`/api/agent/**`) — 非当前协议，仅供迁移追溯

> 本节记录 2026-09 以前的编排器、SSE 和 HITL 实现。它们不是当前调用协议，不能让新代码按这里的 endpoint/event 名实现；当前唯一权威说明见上方的 `AgentScope 运行时` 与 `AgentScope / Sandbox 不可回归红线`。

### 数据库（V2 migration `V2__agent_conversation.sql`，3 张表，与分镜 scenes 无关）

| 表 | 关键字段 | 说明 |
|----|---------|------|
| `conversations` | user_id + project_id（JWT 归属双键）、title、status | 一个项目可多个会话；project 删除级联 |
| `agent_messages` | conversation_id（CASCADE）、role（user/assistant）、content | 消息历史 |
| `agent_assets` | conversation_id（**可空**=未归属）、type（**image/video/reference**）、url、task_id、status | Agent 生成的图/视频 + 用户上传的参考图 |
| `agent_checkpoints` | conversation_id（CASCADE）、action、form_token（一次性）、plan（方案 JSON 文本）、step、status（pending/used/expired）、expiration_time | HITL 确认点（V4 migration，替代原内存 Map 快照） |

> **Migration 手动执行**：项目未引入 Flyway，`db/migration/V*.sql` 为留档，需 psql 手动应用。
> AgentScope turn 协议依赖 **V39**：`agent_turns`、`agent_turn_events`、事件序号唯一约束和旧状态归档。

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
| POST | `/api/agent/stt` | ~~语音识别~~（已下线 2026-08-21：前端改为 **WebSocket 直连 vosk-server** `ws://localhost:2700`，见 `VITE_VOSK_WS_URL`，不经后端；生产内网部署 + 防火墙禁公网） |

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
- **异步**：`CompletableFuture.runAsync(..., agentExecutor)`（虚拟线程），不阻塞编排主流程；任务体全 try-catch，对话零影响。先立即写入首句兜底标题，AI 成功后再条件替换，因此模型超时/空内容/无路由也不会永久停在「新对话」
- **生成**：`ConversationTitleService` 使用 `ai.agent.title-model` 显式模型（默认 `mimo-v2.6-pro`，可按环境覆盖；留空才回退网关默认文本模型），单次超时由 `ai.agent.title-timeout-seconds` 控制，显式传 `thinking_level: minimal` 避免推理内容占满输出并拖慢/掏空标题。默认网关文本模型可能被后台随时切换，不能作为标题稳定性的隐式依赖
- **落库（并发坑）**：编排线程持有同一 `AgentConversation` 实体并整实体 updateById，标题线程**必须**用 `LambdaUpdateWrapper` 只 set title/updatedAt 两列，并带预期旧标题的原子条件（首句兜底从「新对话」更新，AI 标题从兜底值更新）——既防覆盖用户手动重命名，也防整实体更新冲掉新字段
- **推送与补齐**：落库成功后新标题暂存 `renamedTitleByConversation`，`messageEndPayload` 在 `message_end` 附 `title` 一次性下发；若标题晚于流结束，`agentStore.ts` 在首条消息未收到 `e.title` 时按 1.2s/3s/7s/15s/30s 拉取会话详情，覆盖首句兜底与后续 AI 标题后自动停止
- **AgentScope**：其 `AgentResultEvent` 只给最终 `Msg`，`Msg.name` 是代理名；`AgentState` / `SessionInfo` 没有会话标题字段，因此当前标题生成不依赖 AgentScope，也不能直接从首轮回复结果读取现成标题

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
- **分镜自动审查**：分镜方案写库成功后直接创建新的 `review-action` HITL checkpoint，在当前 Moon 对话展开“分镜审查结果”并展示项目全部分镜；不通过 handoff 二次路由，避免审查链自循环
- **分镜素材选择**：审查卡“上传素材”包含项目 AI 资产、当前项目产出素材（按对话分组，当前对话优先）和当前分镜多图；只有 AI 资产进入 `scene_assets`，产出素材/分镜图片进入 `scene_reference_images` 并分别标记 `source_type=output|scene_image`
- **素材覆盖与生命周期**：再次保存按最终勾选完整覆盖 AI 资产和产出/分镜图片引用，用户直接上传图片不受影响；“查看参考物”按用户上传图片、上传资产、上传的产出素材分组；移除产出素材只解除引用，不删除原生成文件
- **LLM 配置**：复用 `LLM_GATEWAY_BASE_URL` / `LLM_GATEWAY_API_KEY`（.env，Spring AI 网关）

## Verification Commands

```powershell
# 仓库根目录；Java 25。JAVA_HOME 必须是 Windows 路径。
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"

# 关键 Agent 直播、HITL、沙箱链路
mvn -pl MoonAgent -am test

# 主后端
mvn -pl AIStoryboardBackend -am test

# Sandbox runner jar（部署镜像前必须成功）
mvn -pl MoonSandbox clean package

# 前端（tsconfig 为 solution-style；仓库脚本已包含 tsc -b）
cd AIStoryboardClient
npm test -- --run
npm run build

# 沙箱 Compose 静态检查与启动
cd ..\deploy\docker\sandbox
docker compose --env-file .env.example config -q
docker compose --env-file .env up -d
docker compose --env-file .env ps
```

**Maven gotcha**: `JAVA_HOME` must use Windows path format (`C:\\...`), not POSIX (`/c/...`). `mvn.cmd` is a Windows batch file；direct invocation is required or Maven can fail with `ClassNotFoundException: plexus-classworlds`.

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
