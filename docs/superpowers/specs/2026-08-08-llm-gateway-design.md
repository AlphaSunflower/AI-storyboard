# 大模型网关（AILLMGateway）设计文档 v2

- 日期：2026-08-08
- 状态：已批准（brainstorming 流程）
- 范围：AI Storyboard 项目内新增独立大模型网关服务，Backend 彻底解耦大模型配置与调用
- 前置：v1 设计（2026-08-07）曾完整实现并通过 E2E，后被 git reset 摘除；本版为**全新开发**，基于今日空骨架，范围扩大（视频链路进网关）

## 1. 背景与动机

AI Storyboard 当前所有模型调用集中在 `AIStoryboardBackend/service/ai/` 包，存在以下问题：

1. **路由逻辑散在业务代码里**：`geminiImageModelSet` 判断走 Gemini 接口、`sora2ModelSet` 判断用哪把密钥、`videoModelAliases` 做模型名映射、`videoProvider` 做 minimax/laozhang 门面分发——每个服务各自持有 HttpClient 直连 api2.laozhang.ai / api.minimaxi.com / Dify
2. **横切关注点纠缠**：密钥选择、模型路由、供应商切换这些与业务无关的逻辑和业务代码耦合，加新供应商要动业务代码
3. **拓展性差**：未来服务多个系统时，每个系统都要重复实现一遍渠道/密钥/路由逻辑

**目标**：新增独立大模型网关服务（AILLMGateway），业务系统只关心"要什么"（业务请求），网关负责"去哪要、用谁的钥匙"（路由/密钥/适配）。业务侧通过 OpenAI 兼容接口调用，网关负责转换为各上游原生协议。**视频链路（MiniMax + Laozhang 双通道）也收进网关**，实现彻底解耦。

## 2. 决策记录

| 项 | 决定 |
|----|------|
| 形态 | 自研网关服务（独立进程，Spring Boot 技术栈） |
| 第一版范围 | chat / 文生图 / 视频（MiniMax + Laozhang 双通道）；edits / Dify 保持直连 |
| API 格式 | OpenAI 兼容（/v1/chat/completions、/v1/images/generations、/v1/videos 系列，model 在 body） |
| 管理方式 | 纯 REST API（/admin/**），渠道/密钥/模型路由 DB 化 + AES 加密 + JWT ADMIN，运行时生效 |
| 调用鉴权 | 静态 API Key（网关签发，SHA-256 哈希存储） |
| 落位 | 本仓库内新目录 `AILLMGateway/`（与 AIStoryboardBackend 平级） |
| 业务改造 | 同步切换：现有生图/生文/视频调用改走网关；Gemini 生图也走网关 |
| 数据库 | 独立 PostgreSQL 库 `llm_gateway` |
| 端口 | 8083 |
| 下载模式 | 网关代理下载（业务只认 /v1/videos/{taskId}/content 单一端点） |

## 3. 架构总览

```
┌─────────────────────┐   OpenAI 兼容    ┌──────────────────────┐   原始协议    ┌──────────────┐
│  AIStoryboardBackend │ ───────────────▶ │  AILLMGateway (8083) │ ────────────▶ │  Laozhang    │
│  (业务，只关心业务)   │  Bearer 网关Key  │  路由/鉴权/密钥管理    │  透传/转换    │  api2...v1   │
└─────────────────────┘                   │  /v1 + /admin 双通道 │                ├──────────────┤
       现有生图/生文/视频服务               └──────────┬───────────┘                │  Gemini      │
       全部改为指向网关                                │                            │  原生转换     │
                                              ┌───────▼───────┐                   ├──────────────┤
                                              │  llm_gateway   │                   │  MiniMax     │
                                              │  独立 PG 库     │                   │  api.minimaxi │
                                              └───────────────┘                   └──────────────┘
```

- 新目录 `AILLMGateway/`（Spring Boot 4 + MyBatis-Plus + PostgreSQL + jjwt + scrypt，与 Backend 同技术栈）
- 端口 8083，独立数据库 `llm_gateway`
- 业务侧只发 OpenAI 兼容请求 + 网关 Key；模型路由、密钥选择、Gemini/MiniMax 原生转换全部下沉到网关
- 视频下载：网关流式代理（业务永不接触上游 URL / Key）

## 4. 数据模型

5 张核心表，`V1__gateway_tables.sql`（Flyway 或手动执行，与 Backend 约定一致用 SQL 手动执行）：

| 表 | 关键字段 | 说明 |
|----|---------|------|
| `channel` | name、type（openai_compatible/gemini/minimax）、base_url、api_key（**AES 密文**）、enabled、priority | 上游渠道；可配多个同类型渠道做路由 |
| `model_route` | model_name（非唯一）、channel_id（FK）、default_params（JSON：size/temperature 等） | 模型名 → 渠道映射；一个模型可指向多个渠道按 priority 轮换 |
| `gateway_api_key` | name、key_hash（**SHA-256 哈希**）、enabled | 业务侧静态调用 Key（明文只签发时显示一次） |
| `admin_user` | username、password_hash（scrypt N=16384）、role、status | 管理后台登录 |
| `call_log` | model、channel_id、status、duration_ms、error、**video_url**（视频下载暂存） | 调用日志 + 视频直链暂存 |

> video_url 字段：MiniMax 轮询 succeeded 时 content.url（限时链接）落此字段，供 /v1/videos/{taskId}/content 下载端点查询。Laozhang 无需（其下载端点为确定性路径）。

## 5. 对外 API

### 5.1 OpenAI 兼容入口（/v1/**，静态 Key 鉴权）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/chat/completions` | 生文/对话（脚本生成、标题重命名、提示词优化等） |
| POST | `/v1/images/generations` | 文生图（含 Gemini 模型自动转原生格式） |
| POST | `/v1/videos` | 视频创建（按 model 分发 Laozhang / MiniMax） |
| GET | `/v1/videos/{taskId}` | 视频轮询（统一响应 `{taskId,status,progress?,error?}`） |
| GET | `/v1/videos/{taskId}/content` | 视频下载（网关流式转发） |
| GET | `/v1/models` | 模型列表（可选，便于调试） |

- 鉴权：`Authorization: Bearer ***` 比对 `gateway_api_key` 表（SHA-256 哈希）
- 请求体与 OpenAI 完全一致（model 在 body）
- **第一版不做**：`/v1/images/edits`（图改图保持直连）

### 5.2 管理 API（/admin/**，JWT ADMIN）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/login` | 登录发 JWT（access+refresh，复用 jjwt 模式） |
| CRUD | `/admin/channels` | 渠道管理（Key 写入时 AES 加密，读取永远不返回明文） |
| CRUD | `/admin/routes` | 模型路由管理 |
| CRUD | `/admin/api-keys` | 业务调用 Key 管理（只显示一次明文） |
| GET | `/admin/call-logs` | 调用日志查询（分页倒序） |

## 6. 核心流程

### 6.1 chat / 文生图

```
1. 请求 → 静态 Key 校验（Bearer → SHA-256 → gateway_api_key 表）
2. 解析 body.model → 查 model_route 表（按 priority 升序取第一个 enabled 的）
3. AES 解密 channel.api_key
4. 转发：
   - openai_compatible 渠道 → base_url + 原路径透传，Bearer 换为渠道 Key
   - gemini 渠道 → 请求体转换为 Gemini 原生格式（generateContent），响应转回 OpenAI 格式
5. 响应透传；落 call_log（异步，不阻塞响应）
```

### 6.2 视频创建 / 轮询 / 下载

```
创建：
1. 静态 Key 校验 + 路由（MiniMax-H3 → minimax 渠道；veo-* → laozhang 渠道）
2. 协议转换：
   - minimax 渠道 → content 数组 JSON（text + image_url first_frame；图生视频的本地图由业务侧转 data URI 内联后随请求体传入——图片在业务本地 uploads 目录，网关无访问权限，业务侧保留此转换逻辑）
   - laozhang 渠道 → multipart 表单（model/prompt/seconds/duration/size/resolution/aspectRatio/metadata/input_reference）
3. 转发 → 返回统一 task_id

轮询：
1. GET /v1/videos/{taskId} → 按 taskId 反查渠道
2. minimax → GET /v2/query/video_generation/{taskId}，succeeded 时 content.url 写入 call_log.video_url
3. laozhang → GET /videos/{taskId}（fallback /video/generations/），成功解析 content 直链
4. 统一响应 {taskId, status, progress?, error?}

下载：
1. GET /v1/videos/{taskId}/content → 按 taskId 反查渠道
2. laozhang → 转发 GET {base_url}/videos/{taskId}/content（Bearer 渠道 Key）
3. minimax → 从 call_log.video_url 取直链转发
4. InputStream 流式返回（180s 超时 + 3 次重试，与业务现状对齐）
```

## 7. 错误处理

- 上游非 200：透传上游 `{error:{message}}`（保持现有 `extractReadableError` 的解析链），业务侧文案逻辑不动
- 网关自身错误：统一 `{error:{message}}` OAI 风格
- 超时：HttpClient connectTimeout 30s / request timeout 120s（与现有约定一致；视频下载 180s）
- 重试：429/5xx 轻量重试 2 次（Laozhang 视频创建保留 10 次换池重试）
- 渠道不可用（无 enabled 渠道）→ 503 `{error:{message:"no available channel for model: xxx"}}`

## 8. AIStoryboardBackend 改造点

1. `AiConfigProperties` 新增：`ai.gateway.base-url`（默认 `http://localhost:8083`）、`ai.gateway.api-key`（网关签发的 Key）
2. `ScriptGenerationService.callVisionApi`：URI 改为 `gatewayBaseUrl + /v1/chat/completions`，Authorization 换为网关 Key——唯一变化，body 结构不变
3. `ImageGenerationService`：
   - 纯文生图分支 → `gatewayBaseUrl + /v1/images/generations`，Authorization 换网关 Key
   - Gemini 分支（`gemini-3-pro-image-preview`）→ 也走网关，网关负责转 Gemini 原生格式；业务侧删掉 `geminiImageModelSet` 判断（路由判断下沉）
   - edits 分支（图改图）→ 第一版保持直连 Laozhang，不动
4. `ImageRefinePromptService`、`ConversationTitleService`、`PromptOptimizeService`、`VideoPlanService`（四个 chat 调用方）→ 同样切到网关 chat 端点
5. `VideoGenerationService` / `MinimaxVideoService`：
   - 创建/轮询/下载改走网关端点（/v1/videos 系列）
   - 本地文件存储逻辑（uploads/videos + FileStorageService）保留在业务侧，下载仍由业务侧从网关拉取后落盘
   - `videoProvider` 分发逻辑迁入网关（按 model 路由），业务侧删掉 minimax/laozhang 判断
6. `AiConfigProperties` 里的 baseUrlOpenai/baseUrlVision/geminiImageModels 等字段保留（edits/Dify 仍用），但 chat/文生图/视频路径不再引用

## 9. 网关实现清单

```
AILLMGateway/
├── src/main/java/com/llmgateway/
│   ├── LLMGatewayApplication.java
│   ├── config/          # Security（双通道：/admin/** JWT + /v1/** 静态Key）、CORS、MyBatis、dotenv
│   ├── controller/
│   │   ├── OpenAiCompatController.java   # /v1/chat/completions + /v1/images/generations + /v1/videos 系列
│   │   └── admin/AdminAuthController.java, AdminChannelController.java,
│   │            AdminRouteController.java, AdminApiKeyController.java, AdminLogController.java
│   ├── service/
│   │   ├── GatewayRoutingService.java    # 路由核心：查route→取channel→解密→转发
│   │   ├── UpstreamClient.java           # HttpClient 封装（透传/转换/重试）
│   │   ├── GeminiFormatConverter.java    # OpenAI ↔ Gemini 格式互转
│   │   ├── VideoGatewayService.java      # 视频创建/轮询/下载（Laozhang + MiniMax 双协议）
│   │   └── KeyService.java               # AES 加解密、SHA-256 比对
│   ├── entity/ mapper/ dto/              # 与 Backend 相同模式
│   └── exception/ GlobalExceptionHandler.java
├── src/main/resources/
│   ├── application.yml   # 端口8083、数据源、JWT密钥、AES密钥（env注入）
│   ├── db/migration/     # V1__gateway_tables.sql
│   └── .env.example
└── pom.xml               # 与 Backend 同版本栈（SB4 + MP + jjwt + postgresql + scrypt）
```

## 10. 测试

- 后端编译：`mvn compile`（沿用 CLAUDE.md 的 JAVA_HOME 方式）
- 网关单测：路由选择（多渠道 priority）、Gemini 格式转换、AES 加解密往返、视频协议转换
- 联调验证：起网关 → curl 调 `/v1/chat/completions`（经网关 → Laozhang）→ 确认业务侧脚本生成走网关成功；文生图同验；视频创建/轮询/下载全链路

## 11. 部署

- 开发期：本地起两个进程（Backend 8082 + Gateway 8083），`ai.gateway.base-url=http://localhost:8083`
- `.env` 新增：`LLM_GATEWAY_DB_URL/USERNAME/PASSWORD`、`LLM_GATEWAY_JWT_ACCESS/REFRESH_SECRET`、`LLM_GATEWAY_AES_SECRET`、`LLM_GATEWAY_ADMIN_INIT_PASSWORD`
- 根 `.env.example` 补 `LLM_GATEWAY_BASE_URL`、`LLM_GATEWAY_API_KEY`（Backend 侧）
- 生产：与 Backend 同样方式容器化（本次只交付代码 + 配置，不涉及部署流水线改动）

## 12. 第一版明确不做（YAGNI）

- 计费/配额
- 多租户
- 限流
- 图改图 edits 走网关（保持直连）
- Dify 接入网关
- 模型列表自动同步
- 管理前端页面（纯 REST API）
