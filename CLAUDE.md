# AI Storyboard

AI-powered storyboard generation platform. Spring Boot 4 backend + React/TypeScript frontend.

## 当前工程状态（2026-10-10）

- MoonAgent 是 Spring Boot 4.1.1 + Java 25 + AgentScope 2.0.4 服务，监听 `8084`；`MoonAgentRuntime` 是唯一智能体执行器。
- MoonSandbox 是独立常驻执行服务，监听宿主 `127.0.0.1:8090`，工作目录只存在于 Docker 命名卷 `sandbox-workspaces`；MoonAgent 通过内部 HTTP 调用，不挂载 Docker socket。
- MoonSandbox 不注册 Nacos。MoonAgent 通过 `AGENT_SANDBOX_ENABLED`、`MOON_SANDBOX_BASE_URL`、`MOON_SANDBOX_INTERNAL_SECRET` 连接。服务发现默认关闭（`NACOS_DISCOVERY_ENABLED=false`），ApiGateway 按 `BACKEND_URI`/`ASSET_SERVICE_URI`/`CANVAS_URI`/`MOON_AGENT_URI` 直连各服务；Nacos（可选，`deploy/docker/nacos` 单起）现在只承载 Skill、全局提示词和 MCP 元数据。
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
- **打断不丢字（2026-10-16）**：用户「停止生成」或「停止并发送」后，智能体已经流出的正文必须留在气泡里，末尾补「…」（`interruptedReplyText` / `interruptedReplyContent` 同一口径），并显式落库为一条 assistant 消息（`metadata.interrupted=true`）。禁止再用 `messages.filter(id !== pendingAssistantId)` 把气泡删掉：那会让刚生成的文字整段消失，刷新后也读不到。`turn_failed` 的 `code=40904` 是用户主动停止，不得弹错误条。
- **打断与重发的时序（2026-10-16）**：`interruptAndSend` 必须是「先打断并等旧 turn 走到终态 → 再提交新 turn」，抢跑会撞会话锁（40901）；后端 `clearInterrupt` 只能在**拿到会话锁之后**调用（此时上一轮已终态），否则旧回合观察不到打断会一直跑到自然结束，用户看到「停止没反应」。
- **生成中发送 = steer（2026-10-17）**：输入框在生成中按发送默认走 `/turns/{turnId}/steer`（插入当前回合，不打断工具）；`interruptAndSend` 只保留给显式停止后的兼容/测试路径。steer 消息落库为用户消息（`metadata.steer=true`），并走 `USER_MESSAGE` 事件；回合已结束时接口返回 `not_running`，前端改走普通发送。同一 `clientMessageId` 幂等；回合结束仍未消费的消息会带入下一轮开头（插在新 user 消息之前），不丢。

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
# 注意：`-am` 会连同依赖模块一起进 reactor，而 `-Dtest` 过滤器在 common-core / asset-client /
# agent-core 上匹配不到任何测试；缺 `-Dsurefire.failIfNoSpecifiedTests=false` 时 surefire 会
# 直接在这些模块上报 "No tests matching pattern ... were executed" 并中止，命令根本走不到
# MoonAgent（2026-10-16 实测）。下面的写法是能真正跑通的版本。
mvn -pl MoonAgent -am "-Dtest=AgentTurnEventStoreTest,MoonSandboxClientTest,SandboxOutputReconcilerTest,CheckpointResumeServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
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
| nacos | `nacos/` | 8848/9848/8850 | `nacos/nacos-server:v3.2.3` standalone 服务发现；数据卷 `nacos-data`。**已不参与统一栈**：栈内 `NACOS_DISCOVERY_ENABLED=false` 走直连，仅在需要恢复注册发现时单起 |
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

### 统一业务栈（deploy/stack/）

`deploy/stack/` 用一个 `docker-compose.yml` 把基础设施与业务一起拉起（6 个 Java 服务 + 主前端/画布前端 +
Postgres/Redis/MinIO/MoonSandbox，共 12 个容器；不含 Nacos 与画布 Bridge——服务发现默认关闭、网关直连，
浏览器画布命令由 canvas 服务的信箱转发）。容器名/网络名/卷名都带 `STACK_PREFIX` 前缀，去掉了固定 IP 与
固定 subnet，同一台机器可并存多套。入口 `http://localhost/`，初始账号 `admin@qq.com` / `123456`。
首启由 `db/` 下的 `docker-entrypoint-initdb.d` 脚本建库、还原 `ai_storyboard` / `llm_gateway` 转储，
并把源账号 `771038325@qq.com` 改名为 `admin@qq.com`、清掉其它账号。数据随目录迁移：
`data/minio`（对象）、`data/sandbox-workspaces`（沙箱工作区），不再依赖 `deploy/docker/minio/data`。
构建/启停/验收/离线打包脚本在 `deploy/stack/scripts/`（`load-and-up.sh`、`verify.sh`、`save-images.ps1`、
`export-usb.ps1`），细节与排障要点见 `deploy/stack/README.md`。

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

### 9. 媒体引用地址必须是相对路径（画布/聊天 turn 入口）

服务端只信任「相对 `/api/files/**`、`/api/library/media/**`，或白名单 origin」的媒体地址（`OutboundUrlGuard.canonicalizeMediaUrl`）：

- 前端**禁止**对上传结果做 `new URL(url, window.location.origin).toString()` 之类的绝对化。浏览器 origin（`http://localhost`、前端 dev 端口、网关端口）与服务端内部 origin（`http://backend:8082` / `http://asset-service:8085` / `http://minio:9000`）天然不同，绝对化会让 turn 直接被 `40001 媒体地址不在允许的服务范围内` 拒绝（画布编辑模式的 `canvasContext.mediaRefs` 尤其容易踩：引用会被 sessionStorage 持久化，每轮重发都失败）。
- 需要额外公网端点（CDN/OSS）时用 `MOON_MEDIA_ALLOWED_ORIGINS` 登记，不要放开前端 origin。
- 浏览器 origin 的绝对 `/api/files/**` 地址由 `TurnMediaCanonicalizer` 归一化成相对路径（顺带修正下游 `fetchRemoteImage` 会拒绝 loopback 的问题）；HITL 续流同样走这一步。

### 10. 画布 MCP 工具契约（服务端 ↔ 画布前端）

- **注入型参数绝不能是必填**：`canvas_graph_*` 的 `userId/projectId/canvasId/runId/operationKey` 由 MoonAgent `CanvasRunToolAdapter` 在模型调用之后注入。`@McpToolParam.required` 默认 `true`，一旦漏写 `required = false`，AgentScope 会先拿模型自己的入参做 schema 校验并失败（`Parameter validation failed ... 未找到所需属性"projectId"`），随后模型会自己编 userId（曾提交 `"default"`）和 operationKey。业务入参（`mediaItems`/`node`/`edge`/`args`）保持必填。回归测试：`CanvasGraphMcpToolsSchemaTest`。
- **节点目录必须镜像画布前端契约**：前端生成节点只有 `imageGeneration` / `videoGeneration`（端口 `prompt`/`reference`(ordered)/`video`，差异在 `data.mode`）。服务端 `CanvasNodeRegistry` 的旧 kind（textToImage/imageToImage/textToVideo/…）只保留校验能力、不再出现在 `listMaps()` 目录里，否则模型会建出前端已合并的旧节点，连线报 `NODE_TYPE_UNKNOWN: videoGeneration`，旧端口（first/last/source）被前端归并成 `reference` 后边挂不上手。回归测试：`CanvasNodeRegistryTest`。
- **画布编辑模式自动接管当前画布**：`AgentCanvasRunService.adoptCurrentCanvas` 复用本轮 run，没有就为当前画布建一条（`createExisting` 不新建画布）；turnId 为空时不接管（run 以 turnId 去重，否则每次调用都会建一条）。
- **结果槽命名画布内递增**：`createResultSlots` 的「结果 N」按整张画布取下一个可用编号，逐生产节点调用不再各产出一个「结果 1」。
- **画布实时刷新**：`GET /canvases/{id}/revision` 只读 revision；画布前端每 4s 轮询，服务端图命令造成的 revision 前进就重载。本地 550ms 存盘防抖窗口或 `saving` 时跳过，避免覆盖用户未落库的编辑。

### 11. 模型目录缓存与「默认模型」切换的生效范围（2026-10-16）

网关 `ModelCatalogCache` 是模型目录三类 Redis key 的**唯一持有者**；任何目录变更都必须经它失效，**禁止在别处裸写 `redisTemplate.delete("gateway:models:*")`**：

| key | 内容 | 失效方式 |
|-----|------|----------|
| `gateway:models:{type\|all}` | `/v1/models` JSON 响应 | 60s TTL，只作兜底 max-age |
| `gateway:default_model:{type}` | 运行时默认模型名（`getDefaultXxxModel()` 直接读它） | 由路由表收敛写入；**不得裸删**——关掉某个非默认模型的开关要用 `reconcileDefault` 重算 |
| `gateway:models:version` | 目录版本号（随机 UUID，每次变更换值） | 消费端（agent-core `GatewayModelServiceImpl`）据此毫秒级失效各自 JVM 缓存，不必等 60s |

- **已接上失效的写入口**：路由 CRUD 与默认切换、`model_params` upsert（参数能力也随列表下发）、渠道 CRUD（`enabled/priority` 直接决定列表成员与顺序）、Ollama 同步。**新增写 `model_route`/`channel`/`model_params` 的路径必须一并接上**，否则最长 60s 内列表与实际可用渠道不一致——禁用渠道的模型仍在列表里，前端选中即 `50301 no available channel`。
- 网关每 5 分钟（`gateway.model-catalog.reconcile-interval-ms`）按路由表收敛默认模型：手工 SQL 改了 `model_route.is_default` 却没清 Redis 时 ≤1 个周期自愈，**不再需要重启网关或手工 `redis-cli DEL`**（V19 那类迁移注释里的手工 DEL 只是「立刻生效」的可选加速）。
- 生效范围：服务端「调用方不传模型 → 用默认」的链路（文本 / 生图 / 生视频 / 视觉）每次现读 Redis，**实时生效**；前端编辑器在挂载 / 回到前台 / 60s 轮询时刷新（版本号未变则整轮跳过 setState），且**用户手选的模型不会被网关默认覆盖**（`localStorage.editorModelChoice`，所选下架才回落默认）。
- **MoonAgent 主智能体模型不需要重启即可跟随默认模型**（`ai.agent.agentscope.dynamic-default-model`，默认开）：AgentScope 的 `ReActAgent.model` 是 `private final`、`HarnessAgent.modelResolver` 只用于子智能体的模型覆盖，所以「不重建 agent 就换模型」的唯一做法是让 agent 持有的 `GatewayDefaultModel` 包装器在每次模型调用时解析真实模型名（`Model` 只有 3 个抽象方法，且请求用的模型名取自 `OpenAIChatModel.stream` 内部的 `effectiveOptions.getModelName()`，`Model.getModelName()` 只服务日志/遥测）。生效时机由 `TurnModelPin` 收敛成业务语义：**在途 turn 内钉住不变，只在智能体闲置（无在途 turn）时于下一轮开始时捡起新默认**；多会话并发时以「全部 turn 结束」为闲置判定，绝不打断在途回合。显式配 `AGENTSCOPE_MODEL`（或 `AGENTSCOPE_MODEL_PLAN|PROMPT`）会钉死为配置值；`AGENTSCOPE_DYNAMIC_DEFAULT_MODEL=false` 回退旧行为（启动时解析一次并固化，**那种配置下切 text 默认模型才需要重启**）。image/video/vision 的默认本就每次现读 Redis，与网关默认无关。
- 已知残留：同一个模型名的 `model_params.context_window` 变更不会热更新（delegate 按模型名缓存，窗口在 delegate 构建时固定），改窗口仍需重启 MoonAgent。
- 回归测试：`TurnModelPinTest`（在途不换 / 闲置才换 / 并发保守 / 关开关不计入）、`GatewayDefaultModelTest`（切默认后 stream 打到新 delegate、同名复用同一实例、窗口跟随）、`ModelCatalogCacheTest`（失效 + 版本号，不依赖 Mockito）、`DefaultModelConsistencyTest`（默认唯一权威 + 写 Redis + 清缓存）、`GatewayModelServiceImplCatalogVersionTest`（版本变即重拉 / 无 Redis 退回 TTL）、`projectStore.models.test.ts`（跟随默认 / 保留手选 / 版本门控）。

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
| POST | `/api/agent/conversations/{id}/turns/{turnId}/steer` | 回合中插入用户消息（steer）：不打断在途工具，在下一个模型步骤注入；回合已结束返回 `not_running`，前端改走普通发送 |
| GET | `/api/agent/conversations/{id}/runtime` | 运行时、Skill、Prompt、MCP 元数据状态 |
| GET | `/api/agent/conversations/{id}/turns/{turnId}/events?afterSeq=N` | 按序号补偿重放 turn 事件 |
| POST | `/api/agent/conversations/{id}/context/clear` | **清空上下文（真删除）**：删该会话全部上下文行（消息、`agent_turns`/`agent_turn_events`、checkpoint、画布会话与运行、文档索引、异步任务、时间线、评审、工作流、资产组绑定）+ 清 AgentScope 运行时上下文与沙箱、标题重置「新对话」、`agentscope_session_initialized_at` 置空；产出素材保留。前端斜杠命令「/清空上下文」= `/clear`（`/compact` = 压缩上下文） |
| DELETE | `/api/agent/conversations/{id}/messages` | 兼容壳：与上一个端点同一实现（同样真删除），旧语义「只删消息」已退役 |

`/清空上下文` 的删除执行器是 `ConversationContextCleanupService`（`@Transactional`，
逐层按 ID 删子表再删父表，返回每张表的删除行数）；编排在 `AgentChatServiceImpl.clearContext`
里先取会话锁（生成中会打断 + 拿不到锁返回 40901），再 `MoonAgentRuntime.resetSession`
（`clearContext` 清会话消息与压缩摘要 + 删 state store + 清本地 `stateCache`——
只删 store 会让缓存里的旧上下文继续生效）。

### 通用创作智能层

- 记忆（2026-10-18 P0；2026-10-20 改为「项目记忆仅智能体写 + 一条全局记忆 + 总开关」）：
  **项目记忆** `agent_memories` 按 `user_id + project_id` 隔离，智能体用 `remember_memory` /
  `recall_memory` 读写，用户只能查看和删除（不提供手动新增/编辑入口）。全局提示词 §二.13 规定何时写
  （跨会话有效的目标/风格/硬约束/已确认决策/成品反馈，一次性指令不写），`TurnContextMiddleware`
  每轮召回 top 5 注入 `<project-memories>`。上限集中在 `AgentMemoryLimits`：**单项目 200 条、
  单账号 500 条、单条 4000 字符**；过期行在写入前清理、不占容量；超限/关闭时智能体收到
  `ok:false`（`MEMORY_UNAVAILABLE`）并须如实告知，不得声称已记住。
  **全局记忆** `agent_global_memories` 每账号一条（`user_id` 主键），由用户维护、跨项目注入
  `<global-memory>`，智能体只读、不会自行修改。
  **总开关** `agent_memory_settings`（缺行视为启用）：关闭后智能体不能写/召回，用户不能修改全局记忆，
  但删除既有记忆仍可用；开关与全局记忆都在 `/account` 记忆管理里。
  接口：`GET /api/agent/memories`（项目记忆）、`GET/PUT /api/agent/memories/settings`（开关 + 全局记忆 +
  上限）、`PUT/DELETE /api/agent/memories/global`、`DELETE /api/agent/memories/{memoryId}`、
  `PUT /api/agent/memories/{memoryId}`（保留的兼容接口，UI 不再使用），
  以及项目级 `GET/DELETE /projects/{projectId}/memories`。
  V42 / V50 migration 已在本地 `newworkflow` / `ai_storyboard` 执行，其他环境仍需手动执行。
  个人信息从弹窗改为独立路由 `/account`，账号信息、密码和记忆管理（开关/全局记忆/只读项目记忆）都在该页。
  2026-10-18 起 `/account` 为左侧菜单：个人信息（名称/邮箱/生成数据/余额占位/改密）、
  余额明细（摘要 + 空变动记录，当前无计费数据）、记忆管理、通用设置、帮助与反馈；
  管理员额外看到「反馈收件箱」。通用设置按账号持久化在
  `public.user_settings`（手动迁移 `db/migration/V49__user_settings_and_feedback.sql`，
  项目未接 Flyway，需 psql 执行）：`defaultApprovalMode`（新会话默认审批模式）、
  `busySendBehavior`（`queue` 排队 / `steer` 插队）、`workStepDisplay`（`thinking` / `standard` / `detailed`）；
  接口 `GET/PUT /api/user/settings`。**新会话默认审批模式只有一个入口**：`agentStore` 的
  `newConversation` / 删除当前会话 / 切换会话回退值都读 `accountSettingsStore` 的
  `defaultApprovalMode`，禁止再写死 `DEFAULT_AGENT_APPROVAL_MODE`（否则设置完全访问后点「新对话」
  又回到方案批准）。设置同时缓存到 `localStorage` 并监听 `storage` 事件，多标签页即时同步。
  排队发送是前端会话级队列（`sendQueueStore` + sessionStorage）：
  输入框上方 `SendQueuePanel` 支持置顶、上下移、删除、清空，以及**插队发送**和**排队中重新编辑**；
  当前回合终态后逐条取出作为普通 turn 发送，不进入当前上下文，也不新增后端排队接口。
  插队发送在生成中会先弹确认、停止当前回合并等它走到终态（复用 `interruptAndSend`，随队列快照带上
  附件/参考图/`@` 引用）；打断期间和队列编辑期间用 `sendQueueStore.holds` 暂缓 `SendQueueRunner`
  自动出队，避免旧正文被抢先发出。等待 HITL/视频方案/画布绑定时只提示、不动队列。
  帮助与反馈写 `public.user_feedback`（不收集联系方式），
  截图先走 `POST /api/files/upload`，提交接口 `POST /api/user/feedback`；管理员用
  `GET /api/user/feedback` 查看收件箱、`PATCH /api/user/feedback/{id}` 标记已处理（仅 `ROLE_ADMIN`）。
  均经 8080 → 8082，**不经过 8083 LLM 网关**（8083 只做模型协议转换）。
  不做自动灌对话、不使用 embedding（字段预留，后续接 pgvector）。
- `project_creative_contexts` 保存项目级目标、创意方向、风格、参考、硬/软约束、反馈和模型偏好；
  不保存跨项目记忆，也不引入建筑等垂直领域字段。
- 明确出现“必须/严格/保留/不能改”等词时进入 `STRICT`，命中约束通过
  `constraint_flagged` 事件下发；未识别到可验证硬约束时要求先澄清。
- AgentScope 已启用 Plan Mode、任务列表、压缩与四类创作子智能体、PostgreSQL
  `DistributedStore` 和 OpenTelemetry；工具结果默认**不驱逐、不截断**（超长结果原样进入上下文），
  需要恢复旧行为时显式设 `AGENTSCOPE_TOOL_RESULT_EVICTION_ENABLED=true`。模型角色通过
  `AGENTSCOPE_MODEL_PLAN/PROMPT/CRITIC/VISION/IMAGE/VIDEO` 覆盖。
- 生成结果不做自动诊断（`visual-critic` 子智能体已下线）：自动视觉评审会对每次生图/生视频
  结果追加一次付费模型调用，且首图往往已经是用户想要的结果。生成后直接把结果交给用户，
  是否重做由用户决定；`agentscope.critique-enabled` 默认 `false`，开启后才重新注册
  `review_generated_media` 工具。历史评测记录仍保存在 `agent_media_critiques`，
  `/api/agent/runs/{runId}/critique` 与 `/items/{itemId}/repair` 保持可读。
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
- `USER_MESSAGE` 是 steer 插入的用户消息（`messageId/content/steer/createdAt`）：与其他事件走
  同一 `seq` 通道，前端按 `messageId` 去重并插到当前助手气泡之前。回合已结束时接口返回
  `not_running`，不得把 steer 事件写到已收尾的 turn 上。
- 前端 `AgentTurnEventSequencer` 对每个 turn 持有缺口；如果后端绕开有序 publisher，前端不会
  “猜顺序”，而是等待缺失序号，表现为卡住。不要用缩短前端等待时间掩盖后端乱序。
- `turn_completed.payload.metadata` 必须合并并保留实时阶段的 `reasoning`、`agentTrace`、
  `metrics`、`toolDrafts` 和 `sandboxAttachments`，不能因终态 payload 只含正文而把直播数据覆盖为空。
- `turn_paused`（HITL 暂停）同样是收尾：流一结束 `generating` 快照就被删除，因此暂停那一刻
  必须把只活在 `generating.toolDrafts` 里的工具明细（含 `renders`）折进卡片 metadata，
  否则工具调用会在暂停瞬间被吞掉，只有刷新才回来。直播草稿只渲染在发起本轮的那条消息上。
- 沙箱产物文件卡由 `metadata.sandboxAttachments` 驱动；没有该 metadata 就不能算产物已交付。

### 数据与恢复

- `agent_turns` / `agent_turn_events`：流重连、审计和 `afterSeq` 补偿。
- `agentscope_sessions`：AgentScope 运行时上下文。
- `agent_sandbox_sessions`：会话沙箱状态、最后使用时间、过期时间和版本。
- `agent_messages/assets/checkpoints/tool_invocations`：产品数据。
- 未迁移会话在首个 AgentScope turn 前从最近 20 条产品消息重建会话；已有运行时状态优先。
- 旧 checkpoint 标记过期，活动旧计划标记取消；表和历史内容不删除。
- **已归档会话定期清理**（2026-10-21）：`ArchivedConversationCleanupJob`（每天 03:40，
  `ai.cleanup.archive.*`）清理归档超过 `grace-days`（默认 3 天）的会话，删除
  `agent_turn_events`、`agent_checkpoints`、画布会话/运行/操作、`agent_document_index`、
  终态 `agent_async_tasks`、工作流运行、AgentScope 运行时状态与沙箱工作区；
  **消息、`agent_turns`、`agent_assets` 行、审计表与 MinIO 图片/文档对象一律保留**。
  每个会话只清一次，台账表 `agent_archive_cleanups`（`V54__agent_archive_cleanups.sql`，需手动执行）；
  缺表时任务只告警跳过，不影响其它链路。回收站保留期同日改为 1 天（`storyboard.storage.trash-retention-days`）。
- **保留期清理**（2026-10-21）：`DataRetentionJob`（每天 04:20，`ai.cleanup.retention.*`，默认
  `dry-run=true` 只统计）分批删 `agent_turn_events`（30 天）、`agent_subagent_runs`（90 天）、
  `asset_outbox` 终态（7 天）、AgentScope 状态表的**孤儿行**（7 天，只删会话已不存在的槽位；
  活跃/归档会话的状态由归档清理负责）。状态表按运行时口径解析：开 DistributedStore（默认）时是
  `agentscope.agentscope_sessions`，关掉才用 `state-schema/state-table`。索引见
  `V55__data_retention_indexes.sql`（需手动执行），开真删前把 `AI_RETENTION_DRY_RUN` 置 false。
- runner 重启不丢 `sandbox-workspaces`；MoonAgent 重启后可重新扫描 `output/` 并按内容哈希幂等发布。

### 权限与资产

- 读工具默认 ALLOW。
- 删除、清空、覆盖、批量生成、Canvas 执行进入 AgentScope `ASK`/HITL。
- 单张媒体生成是否直接执行由 `ai.agent.agentscope.direct-media-generation` 控制。
- `write_file` 和 `run_python_script` 走现有审批、工具审计与 FULL_ACCESS 策略；`reject` 必须终止本轮，不能把拒绝结果继续喂给模型。
- 沙箱最终产物统一经 `SandboxArtifactPublisher` 上传 MinIO 并写 `agent_assets`；聊天卡片只引用资产 URL 和 message metadata，不暴露宿主机路径。
- 六个生产 Skill：`storyboard`、`image`、`video`、`canvas-design`、`canvas-run`、`general`。
- Nacos 发布脚本：`scripts/nacos/publish_assets.py`；只支持 upload/review/release 生命周期，
  画布执行信箱（canvas 服务内）不进入共享 Nacos。

### 工具元数据单一真源（2026-10-17）

- `ToolCatalog` 是工具元数据的**唯一真源**：名称、中文标签、审计/审批策略、所属技能组、组→技能绑定都在这里登记一次。
  `AgentScopeToolkitFactory` 的注册、`AgentScopeToolPipeline` 的 policy、`AgentScopeToolLabels` 的中文名全部从它派生。
- **加/删工具 = 改一个 catalog 条目 + 工具实现**，不要再改 3 张权限清单 / 2 份标签 / 2 个 SKILL / 6 个测试的名单。
  漏登记会在启动时按常驻兜底并打 `[ToolCatalog] 工具未登记` 警告；`ToolCatalogTest` 锁目录完整性。
- 无 `groups` 的条目是常驻工具（技能激活前可见）；有 `groups` 的条目只在该组激活后可见。组与技能的绑定也只写在 catalog。
- **每会话组合**：`ToolCatalog.profileFor(canvasEdit)` 给出该轮允许的技能与工具组；`SkillFilter.only(...)` 管技能，
  `ToolCompositionMiddleware` 管工具组（只移除不允许的组，不清掉同模式内已激活的组）。组合按会话/模式推导，
  **不看套餐、不看用户等级**；后续要加项目级差异，只改 `profileFor` 的入参与推导。
- **超大工具结果兜底**：默认不截断；单次请求估算超过模型窗口 90% 时，`ContextOverflowMiddleware`
  把最大的工具结果完整写入沙箱 `work/overflow/<toolCallId>.txt`，上下文替换为 `read_file`
  定位符（最多 3 条）。无沙箱时直接放行，绝不退化成“截断后丢掉”。回归：`ContextOverflowMiddlewareTest`。
- 回归：`ToolCatalogTest`、`ToolCatalogPermissionTest`、`ToolCompositionMiddlewareTest`、`SkillToolCatalogConsistencyTest`；
  2026-10-17 真实联调（dev）覆盖 HITL resume steer → `STEER-OK`、canvas-edit turn steer → `CANVAS-STEER-OK`，
  `USER_MESSAGE` seq 连续。

### 可选技能 ppt-master（按引用挂载，2026-10-19）

- **不常驻**：`ppt-master` 只在 `ToolCatalog.OPTIONAL_SKILLS` 登记，**不在** `ALL_SKILLS` 里。
  `profileFor(canvasEdit, referencedSkills)` 只在 `PptMasterSkillSupport.skillsForTurn(...)` 判定本轮引用时
  才把它加进该轮技能白名单；未引用时模型 `<available_skills>` 里没有它，会话工作区也没有 `skills/` 目录。
- **引用口径**（`PptMasterSkillSupport`）：正文出现 `ppt-master` / `[[skill:ppt-master]]`，或 `PPT`/`PPTX`/
  `幻灯片`/`演示文稿`/`演示稿`/`课件`。前端聊天 `/` 菜单的「PPT 生成」（`/ppt`、`/pptx`、`/ppt-master`）会写入
  「用 ppt-master 技能生成 PPT：」。一旦会话引用过，后续回合继续可用（HITL 续流/编辑重发不会丢技能）。
- **按需物化**：22MB / 12k 文件的完整技能包常驻 sandbox 镜像 `/opt/moon-skills/ppt-master`（`deploy/skills/
  ppt-master-skill-v6.7.2.zip` 在构建时解压，`.dockerignore` 必须放行该 zip）。引用时 MoonAgent 调
  `POST /internal/sandbox/{conversationId}/skills/ppt-master/materialize`，`SandboxSkillService` 在会话锁内
  把它复制到 `/workspaces/{conversationId}/skills/ppt-master`，用 `.moon-skill-version`（镜像版本 + SKILL.md
  mtime/size）幂等；镜像重建后自动重拷。**不要**把它挂进所有工作区，也不要预展开到 classpath。
- **classpath 只放包装**：`MoonAgent/src/main/resources/skills/ppt-master/SKILL.md` 是 Moon 适配层，
  负责给出沙箱路径、`SKILL_DIR` 解析、attribution guard 与 `output/` 交付约定；上游原始 `SKILL.md`
  在沙箱里，由模型 `read_file` 读取。**不要改上游 SKILL.md 的 frontmatter**——`attribution_guard.py`
  会校验 copyright/license/official_repository/sponsors 与 gate 标记，改了就 fail-closed。
- 沙箱依赖（python-pptx / PyYAML / XlsxWriter / skia-pathops / uharfbuzz 等）在 `deploy/docker/sandbox/
  Dockerfile` 预装；PyMuPDF（AGPL）默认不装。回归：`SandboxSkillServiceTest`、`SandboxControllerTest`、
  `PptMasterSkillSupportTest`、`ToolCatalogTest`、`SkillPackageTest`。

### 通用子智能体编排（2026-10-18）

- `run_task_graph` 是主模型可调用的通用编排工具：提交 `{task, nodes[], maxConcurrency, failFast}`，
  `SubagentGraphValidator` 校验（节点 ≤12、深度 ≤2、单节点下游 ≤6、无环），`SubagentOrchestrator` 分层并行调度。
- 角色清单在 `MoonAgent/src/main/resources/agents/roles/*.json`（researcher / screenwriter /
  visual-director / reviewer），由 `SubagentRoleCatalog` 加载；先不引注册中心，改角色 = 改文件 + 重启。
- 动态角色包 P0：`agent_packages`（V43，已在本地 `newworkflow` / `ai_storyboard` 执行）存
  kind=role 的 manifest JSON；启动时 `AgentRolePackageBootstrap` 把启用包合并进
  `SubagentRoleCatalog`，同名覆盖内置角色，空/非法包跳过并告警，DB 不可用时保留内置。
  校验：manifest name 必须等于包名、maxTurns 1–12、tools 必须已登记（或框架文件工具）。
  `GET /api/agent/packages?kind=role` 只读目录；安装/启停暂不开放公网写接口。工具代码动态包未做。
- 子 Agent 节点恢复：`run_task_graph` / `run_write_task_graph` 支持可选 `graphId`；重试时传同一个
  `graphId`，`SubagentRunAuditService.completedForGraph` 会复用 `COMPLETED` 节点的输出，只重跑失败/
  未完成节点，避免重复 token 和重复写入。V41 增加 `(graph_id, status)` 索引。
- 写图审批：父级 HITL 卡片会逐条列出 `allowWrite` 节点、角色和声明的写工具，并渲染逐工具复选框；
  用户取消勾选的工具通过 resume `params.approvedTools` 回传，`run_write_task_graph` 只保留获批写工具。
  写节点一个获批写工具都没有时整图拒绝执行。子 Agent 中途暂停/恢复仍未实现，当前恢复是节点级
  （同 `graphId` 复用 COMPLETED 输出）。
- 子 Agent 默认**只读**：`SubagentAgentExecutor` 只从父 Toolkit 取「角色允许 + `ToolCatalog` 只读」的交集。
  写任务必须走 `run_write_task_graph`：该工具在父级权限里是 `WRITE`/ASK，先弹 HITL；批准后才执行，
  子节点 `allowWrite=true` 时以 `FULL_ACCESS` 运行，但 toolkit 仍只有角色声明的工具（writer 角色）。
  `run_task_graph` 收到 `allowWrite=true` 节点会直接拒绝，防止绕过审批。reject 时工具不执行，无写入。
- 子 Agent 独立上下文：只拿 `instruction + 直接依赖节点的结果`，不共享父对话；父级只读 `SubagentGraphResult`。
  整图可通过 `run_task_graph.context` 注入一份**只读共享常量**（几何/参数/硬约束），
  `SubagentAgentExecutor` 会把它放在每个子 Agent prompt 顶部；修复轮原样保留，不能改写。
  `contextRef` 可从服务端 `docs/specs`（`subagent-context-root`）加载文件，避免让模型复制长 JSON；
  `context` 非空时优先，`contextRef` 只允许受控目录、拒绝 `../` 与符号链接逃逸。
- 子 Agent 的 `RuntimeContext` 由 `SubagentRunScope` 在 worker 线程内注入父级 `AgentScopeRunContext`，
  工具（创作上下文/分镜读取）才能拿到 conversation/project。
- 前端 `RunTaskGraphCard` 直接从 `run_task_graph` 工具事件的 `input`（任务图）与 `text`（结果）解析，
  渲染节点、依赖和状态；不新增 SSE 协议，刷新/回放同一份数据也能画。
- 逐节点进度：`SubagentGraphListener` 在 worker 线程通过 `AgentScopeRunContext.publisher()` 发
  `SUBAGENT_STARTED` / `SUBAGENT_COMPLETED`（payload: `toolCallId/subagentId/role/status/output`），
  与 turn 事件共用同一条 `seq` 通道；前端存进 `subagentProgress[toolCallId]`，卡片优先显示实时状态。
- 审计：每个节点写 `agent_subagent_runs`（V40 migration，需手动执行），id=`graphId:nodeId`，
  记录 role/status/instruction/output/error/tokens/duration；审计失败只告警，不影响任务图。
- 预算：`ai.agent.agentscope.subagent-max-tokens`（默认 200000）、`subagent-max-duration-ms`
  （默认 300000）；超预算取消剩余节点，`SubagentGraphResult.budgetExceeded=true`。
- 单节点超时：`subagent-node-timeout`（默认 240s）。子 Agent 的 `blockLast` 用它，不用
  AgentScope 默认；修复节点重写整稿时 180s 不够（2026-10-18 `script-fix` 恰好 180018ms 被砍）。
  修复节点的 maxIters 压到 3、复核压到 2，避免修复轮无限分析。
- 真取消：`SubagentAgentExecutor` 轮询取消 token，触发子 `ReActAgent.interrupt(userId, sessionId)`，
  不等 `blockLast` 自然结束。
- 有界修复：`run_task_graph` 默认 `repair=true`；review 输出含“不通过/未通过/FAIL”时，
  `SubagentRepairPlanner` 自动生成 `script-fix` / `visual-fix` / `review-2`（visual-fix 依赖
  script-fix），把 review 意见和上一轮产出回灌；最多自动修 `subagent-max-repair-rounds`
  （默认 2）轮，仍不通过就如实返回，不无限重试。结果合并，`repairRounds` 返回给主模型。
  修复轮使用独立预算；上一轮 wall-clock 超预算不会阻止下一轮，但总时长由工具 20m 硬顶兜住。
  两轮后仍不通过就停：返回 `reviewPassed=false`、`stoppedReason=max_repair_rounds` 和
  `parameterViolations`，由主模型如实汇报剩余冲突，不再自动重试。
- 2026-10-18 连续三轮 review 不通过后的收口：**停止自动加轮**，改用 canonical 几何常量
  `docs/specs/2026-10-18-hirai-canonical.json` + `docs/specs/2026-10-18-hirai-three-shot-canonical.md`。
  脚本、视觉、review 都引用同一份常量；任何新冲突先改常量，不再让模型重写全文。
  canonical 只把帧率/时长/画幅/镜头/门洞/立面/檐口等列为硬数字；Z/Y、占高、前景位置按范围和
  容差推导（`validate_canonical.py`），review 不再追 0.05% 共线这类无效精度。
- 参数表校验：`SubagentParameterValidator`。`outputSchema=parameter-table` 现在是**显式可选**，
  不再对 screenwriter/visual 自动推断；节点给了非空 `parameters` 才校验，缺失不记 violation。
  生成参数以 `contextRef` 的 canonical JSON 为真源。给了表时校验 fps/时长/画幅/色温一致、
  镜1 推进距离闭合、`distanceM` 单调递增、焦段固定（除非 `zoomAllowed`）、升程与速度×时长一致、
  总帧数/渲染帧数、`doorDistanceM` 与 `doorHeightRatio` 投影自洽、光圈范围；结果返回 `parameterViolations`。
- 审计查询：`GET /api/agent/subagent-runs?conversationId=<cid>&graphId=<gid>`（graphId 可选），
  返回节点级 role/status/tokens/duration/output，供前端或排障使用。
- 可观测 UI P0：独立路由 `/observability`（AppHeader「运行观测」）。按当前项目选会话，拉
  `/api/agent/subagent-runs`，按 graphId 分组展示节点 role/status/instruction/output/tokens/duration；
  支持 graphId 筛选、失败节点计数与错误聚类、一键打开对应会话；无新 SSE 协议，只读审计数据。
- 失败节点快照/重跑：V44 给 `agent_subagent_runs` 加 `task_json/graph_json/context_ref`（已在本地
  `ai_storyboard` 执行；`newworkflow` 无该表）。`POST /api/agent/subagent-runs/{id}/retry` 返回可直接
  发送的 `run_task_graph`/`run_write_task_graph` 提示词；观测页「重跑」会选中会话并自动发送，
  发送未启动时回退为填入输入框。写节点重跑仍会重新弹审批。助手/HITL 消息 metadata 现在写
  `turnId`，观测页「打开会话」会带 `turnId`，ChatPage 在消息加载后滚动到对应气泡
  （历史消息无 turnId 时退化为打开会话）。V45 已按 `turn.checkpoint_id -> plan._hitlMessageId`
  回填历史 HITL 卡片的 turnId；普通助手历史消息仍无 turnId。
- 可观测失败聚类：前端 `classifySubagentError` 把错误归并为执行超时 / 审批未通过或拒绝 /
  工具不可用 / 预算超限 / 输出格式错误 / 依赖失败 / 其他错误，避免同类失败按文本散开。
- 同步任务图不参与异步任务声明校验：本轮调用过 `run_task_graph` / `run_write_task_graph` 时，
  `AgentTaskClaimGuard` 不再把“已提交/已完成”判成无 taskId 的伪任务，避免成功后被追加
  “任务未创建，请重试”和 `taskContractViolation`。
- **两层超时都必须覆盖任务图预算**：
  - AgentScope `ToolExecutor` 默认 5 分钟硬超时（`ExecutionConfig.TOOL_DEFAULTS`），
    必须在 Toolkit 级用 `tool-execution-timeout`（默认 20m）覆盖；否则初跑+修复会直接
    `Tool execution timeout after PT5M`（2026-10-18 实测事故）。
  - `async-tool-timeout` 必须大于 tool execution timeout（默认 21m），否则 `AsyncToolMiddleware`
    会把工具后台化，后台结果投递走 sandbox fs 并报 `No active sandbox`，模型看到“任务未创建”。
  - Runtime 启动时会按“初跑 + N 轮修复”预算自动抬高这两个超时并 warn。
- 回归：`SubagentGraphValidatorTest`、`SubagentOrchestratorTest`（含预算）、`SubagentRoleCatalogTest`
  （含 writer）、`AgentScopeOrchestrationToolsTest`（含 context 透传 + 写图拒绝/批准）、
  `SubagentAgentExecutorTest`（真 `ReActAgent` + fake model，含 sharedContext 注入）、
  `SubagentRunAuditServiceTest`、`SubagentRunQueryServiceTest`、`SubagentRepairPlannerTest`、
  `SubagentParameterValidatorTest`、`AgentScopePropertiesTest`、`RunTaskGraphCard.test.tsx`。

### 真实轨迹 eval（2026-10-18）

- `scripts/eval/run_eval.py`：用真实 HTTP/SSE 跑 golden case，断言终态、关键文案、工具序列、耗时、token；
  每个 case 新建会话 → 发一轮 → 删会话，报告写 `scripts/eval/reports/eval-*.json`，失败退出码 1。
- case 在 `scripts/eval/cases.jsonl`；**只锁结构/关键事实，不锁最终文案**（模型输出会变）。
- 真实轨迹来源：`agent_turns` / `agent_turn_events`，挑真实回合把用户输入和工具序列抄成 case；
  不要从零编造 prompt。
- 用途：换模型 / 改提示词 / 加工具 / 改子智能体编排后，先跑一遍再发布；当前 **10/10 通过**
  （identity、无工具问候、只读分镜、HITL reject、任务图只读、任务图 steer、画布只读、
  创作记忆只读、素材只读、HITL approve）。
- 真实轨迹捕获：`scripts/eval/capture_turn.py --conversation <cid> --turn <turnId> --name ...`
  从 `agent_messages` + `agent_turn_events` 生成 case，不抄最终文案。
- 覆盖能力：`hitl` 自动 reject/approve 续流、`steer` 在指定事件插入、`canvasContext` 画布回合；
  画布 case 需要 `MOON_CANVAS_ID`，缺失时 skip。
- 发布前检查：`scripts/eval/preflight.ps1` / `.sh`（单测 + 前端 build + eval）；
  CI 工作流 `.github/workflows/moon-eval.yml`（手动 + 每天 02:00 UTC 定时），
  Secrets 配 `MOON_EMAIL/MOON_PASSWORD/MOON_PROJECT_ID/MOON_CANVAS_ID/MOON_BASE_URL`。
- `expectNoTools` 锁“闲聊不调工具”；`expectTools` 只断言“至少出现”，不禁止其他只读工具。
- 不是单元测试：单元测试锁代码行为，eval 锁模型在真实工具链上的行为。

## 历史协议与存量表（仅迁移追溯）

> 2026-09 以前的 `AgentOrchestrator` / `IntentHandler` / `/messages/stream` / `/form/submit` 已退役（后者返回 410）。旧 SSE 事件名（`message` / `workflow` / `human_input` / `task_accepted` / `message_end` / `error`）不再是当前协议；当前唯一权威是上文「AgentScope 运行时」与「不可回归红线」。
> 下表只记录仍存在的存量产品表，不代表旧编排链路仍可用。

### 存量表（V2/V4 migration，与分镜 scenes 无关）

| 表 | 关键字段 | 说明 |
|----|---------|------|
| `conversations` | user_id + project_id（JWT 归属双键）、title、status | 一个项目可多个会话；project 删除级联 |
| `agent_messages` | conversation_id（CASCADE）、role（user/assistant）、content | 消息历史 |
| `agent_assets` | conversation_id（**可空**=未归属）、type（**image/video/reference**）、url、task_id、status | Agent 生成的图/视频 + 用户上传的参考图 |
| `agent_checkpoints` | conversation_id（CASCADE）、action、form_token（一次性）、plan（方案 JSON 文本）、step、status（pending/used/expired）、expiration_time | HITL 确认点（V4 migration，替代原内存 Map 快照） |

> **Migration 手动执行**：项目未引入 Flyway，`db/migration/V*.sql` 为留档，需 psql 手动应用。
> AgentScope turn 协议依赖 **V39**：`agent_turns`、`agent_turn_events`、事件序号唯一约束和旧状态归档。

## 智能体补充约定（当前有效）

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

### 智能体窗口前端约定

- **入口**：编辑器右下角悬浮球 ☾ → 右侧抽屉（62vw，minWidth 480）：左会话栏（180px，顶部为「☾ Moon 智能体」标题）+ 右对话区（顶部显示当前会话标题，无会话占位「未选择对话」；右侧 📁 产出素材 + 🧹 清除聊天记录按钮）
- **底部输入栏**：可拖拽上下伸缩（min 90 / max 40vh，顶部 4px 把手 hover 变主色）；右侧为「发送」按钮（「✨ 优化」已取消）
- **生成中发送（steer）**：按钮文案「插入并继续」，调用 `/turns/{turnId}/steer`；回合已结束时等本地流收尾后自动改走普通发送。steer 消息以用户气泡插在当前助手气泡之前（`metadata.steer=true`），不打断在途工具。
- **命名**：用户可见文案统一「产出素材」（原「资产」，仅文案，store/字段名 assets/AgentAsset 不变）
- **互斥规则**：`message_end.sceneCount` > 会话开始时 `scenes.length` → `agentGeneratedScenes = true`（仅内存态，刷新恢复）→ `LeftSidebar` 剧本输入禁用
- **inputs 适配 Moon 工作流**：`{ currentProjectId, PicUrl }`（替换旧 `project_id`/`project_name`）
- **卡片模型/参数选择（2026-08-12 起）**：`human_input`/`video_plan` 事件可带 `models`（网关 `/v1/models` 模型列表，含各模型 params 能力枚举+默认值）、`recommended`（LLM 方案生成时预选的参数值）、`reasons`（每参数推荐理由，≤15 字）——前端 `AgentParamSelector` 渲染模型下拉+参数联动，**默认选中 LLM 推荐值并展示理由，用户可改**；提交 `/form/submit` body 带 `params`（`{model,resolution,duration,aspectRatio,size,quality}`），经 `OrchestrationRequest.params` → handler → 生成服务透传，**取值优先级：用户提交 > LLM 推荐 > checkpoint plan 原值 > config 默认**；无 `models` 字段时前端不渲染选择器（零回归）。LLM 选参选项文本由 `AgentOrchestratorSupport.buildModelOptionsText(type)` 组装（网关不可用返回空，LLM 不选参直接出默认方案）
- **参考图**：`POST /api/agent/upload` → 返回 `url` → 作为 `PicUrl` 随消息发送（图生图/图生视频）
- **分镜自动审查**：分镜方案写库成功后直接创建新的 `review-action` HITL checkpoint，在当前 Moon 对话展开“分镜审查结果”并展示项目全部分镜；不通过 handoff 二次路由，避免审查链自循环
- **分镜素材选择**：审查卡“上传素材”包含项目 AI 资产、当前项目产出素材（按对话分组，当前对话优先）和当前分镜多图；只有 AI 资产进入 `scene_assets`，产出素材/分镜图片进入 `scene_reference_images` 并分别标记 `source_type=output|scene_image`
- **素材覆盖与生命周期**：再次保存按最终勾选完整覆盖 AI 资产和产出/分镜图片引用，用户直接上传图片不受影响；“查看参考物”按用户上传图片、上传资产、上传的产出素材分组；移除产出素材只解除引用，不删除原生成文件
- **LLM 配置**：复用 `LLM_GATEWAY_BASE_URL` / `LLM_GATEWAY_API_KEY`（.env，Spring AI 网关）

### @ 引用资料（2026-10-16）

设计见 `docs/superpowers/specs/2026-10-16-mention-reference-materials-design.md`。已落地 P0 + P1（分镜、项目库文件），会话引用（P2）只落 UI（菜单置灰）。

- **契约两份**：正文保留 `@名称`（`global-system-prompt.txt` §二.12 + `TurnContextMiddleware` 每轮兜底声明「`@` = 在你的可读范围内、必须先去读」），请求体另带 `references: [{kind,id,token,name,…}]`（`AgentReferenceRequest`，上限 10）。**必须有 id**：标题可重名，只靠名字会让模型读到错的东西。
- **前端唯一真源是正文**：store 的 `pendingReferences` 只是目录，发送时用 `activeReferences(content,…)` 过滤「token 是否还在正文里」，插入时同口径清理。因此 `interruptAndSend` 不传 opts 也不丢引用；删掉标记=撤销引用。
- **蓝色标记靠镜像层**：`ComposerMentionHighlight` 在 textarea 底下渲染同一段文本，两层共用 `COMPOSER_TEXT_METRICS`（字体/行高/换行/padding 一致），输入框外层因此有 `.chat-composer-input` 包裹层。
- **项目库文档必须转存才能读**：`AgentDocumentService` 只认同会话 + `url` 前缀 `/api/files/files/` 的 reference 资产，`/api/library/media/**` 判「文档不存在」。`library_file` 引用由后端按 `mediaId` 归属校验 + 取字节 + 转存会话资产（`LibraryReferenceAssetService`），之后才走摘要与 `read_document`。**新通路必须保留这道转存。**
- **会话引用（P2，2026-10-18 开放）**：`AgentReferenceRequest.kind=conversation` 由 `ConversationReferenceService` 解析，只允许「同一用户 + 同一项目」；取最近 30 条 user/assistant 消息做确定性摘录（单条 600 字、总 3200 字，不含工具事件/metadata），注入 `<turn-references>` 的 `kind=conversation` 行。越权、跨项目、临时会话、已删除会话一律 `kind=unresolved` 并给原因，不编造内容。前端 `@` 菜单「对话」组已启用；左侧会话列表可拖进输入框，写入 `application/x-moon-conversation` 负载并插入 `@对话《标题》`，不自动发送。摘要不是 LLM 生成。回归：`ConversationReferenceServiceTest`、`TurnContextMiddlewareReferenceTest`、`ChatComposer.test.tsx`、`AgentConversationList.test.tsx`。
- **`<turn-references>` 不可信**：token/name 来自用户输入或标题，注入前折叠换行、去尖括号；`＋ → 上传分镜` 已统一为插入 `@分镜N`（不再勾完即发）。
- 回归点：`ChatComposer.test.tsx`、`mentionReferences.test.ts`、`ComposerMentionHighlight.test.tsx`、`MessageBubbleMention.test.tsx`、`agentStore.test.ts`（`@ 引用资料`）、后端 `AgentReferencePassThroughTest`。

## Verification Commands

```powershell
# 仓库根目录；Java 25。本机 JDK 25 位于 E:\Development\jdk25，环境变量 JAVA_HOME 已指向它。
# 不要再手工赋值：写错路径会让 maven 报 "JAVA_HOME is not defined correctly"，
# 而报错指不到真正原因（旧文档里的 C:\Program Files\Java\jdk-25 已不存在）。

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
- **多标签页登录态**: 前端 Access Token 只存内存，不落 localStorage/SessionStorage；新标签页先通过同源
  `BroadcastChannel`（`moon-auth-session`）向已登录标签页借 token，借不到才走 `/api/auth/refresh`
  Cookie。`refreshAccessToken(force)` 只有 401 重试才强制轮换。改动这段逻辑时必须保留多标签页回退，
  否则第三方 Cookie 策略或刷新轮换时序会让第二个标签页直接掉回 `/login`。
- **CORS**: Configured in `SecurityConfig` via `CorsConfigurationSource` bean
