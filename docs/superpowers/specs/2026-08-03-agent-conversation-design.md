# AI Agent 对话模块设计（数据库 + 后端逻辑）

日期：2026-08-03
状态：已批准（用户逐节确认）

## 背景与目标

目前 AI 智能体（Dify Agent 工作流，通过 `DifyAgentController` `/api/ai/dify/**` 对接）与 AI 分镜系统的唯一联系是 `scenes` 表：generate-script 批量写分镜、generate-image/generate-video 在无 sceneId 时创建 `scene_number=0` 的**临时 scene（孤儿数据）**。

目标：
1. 建立 AI Agent 对话的数据模型——用户选择项目后与 Agent 对话（对话功能暂不开发，先建库和后端逻辑）
2. Agent 对话中生成的图片/视频存储路径，但**与分镜（scenes）无关**
3. 消灭临时 scene 孤儿数据：无 sceneId 时不再创建临时 scene，改写入独立的 agent_assets 表

## 已确认的需求基线

| 决策点 | 结论 |
|--------|------|
| 对话数据模型 | conversation 表 + message 表（一个项目可多个会话，会话下多条消息） |
| 生成资产存储 | 独立 agent_assets 表，关联 conversation_id，含 URL/类型/提示词 |
| Dify 对接方式 | 后端代理 Dify `/v1/chat-messages`（response_mode=blocking），conversation 表存 Dify conversation_id |
| 会话归属 | conversation 含 user_id + project_id，JWT 鉴权，用户只能看自己的会话 |
| DifyAgentController 改造 | 无 sceneId 时不再创建临时 scene，改写入 agent_assets |
| 无 sceneId 且无 conversationId | 写 agent_assets（conversation_id = NULL，未归属资产），不拒绝调用 |
| 交付范围 | 设计文档 + 建表 SQL + 实体/Mapper/Controller 全部实现（前端暂不开发） |
| 图生图/图生视频 | 用户上传图片，Dify 工作流变量名 `PicUrl`（String，存图片访问 URL），后端按路径读文件做源图 |
| 参考图落库 | 用户上传的参考图也写入 agent_assets（type=reference），关联会话 |
| 上传端点 | 新增 POST /api/agent/upload（JWT 鉴权，可选 conversationId） |

## 方案选型

**方案 A（采用）：独立 Agent 对话模块**
- 新增 3 张表：conversations / agent_messages / agent_assets
- 新增 `AgentConversationController`（`/api/agent/**`，JWT 鉴权）
- 新增 `AgentChatService`（代理 Dify chat-messages）
- 改造 `DifyAgentController`：DTO 加 conversationId，删临时 scene 逻辑
- 优点：边界清晰、JWT 与 X-Dify-Key 认证互不冲突、后续开发对话 UI 直接对接

**方案 B（否决）：对话端点并入 DifyAgentController**
- DifyAgentController 是 X-Dify-Key 认证（给 Dify 工作流调用），前端用户对话必须走 JWT——认证冲突

**方案 C（否决）：只建 conversations + agent_messages，资产塞 message JSON 字段**
- 与"独立 agent_assets 表"需求矛盾，资产查询统计困难

## 数据库设计（V2 migration：V2__agent_conversation.sql）

```sql
-- 1. conversations — AI Agent 对话会话（关联项目 + 用户）
CREATE TABLE IF NOT EXISTS conversations (
    id                   TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    user_id              TEXT NOT NULL,                          -- 会话归属用户（JWT 鉴权）
    project_id           TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    title                TEXT NOT NULL DEFAULT '新对话',
    dify_conversation_id TEXT,                                   -- Dify 侧会话 ID（/v1/chat-messages 返回）
    status               TEXT NOT NULL DEFAULT 'active',         -- active | archived
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_project ON conversations(user_id, project_id, updated_at DESC);

-- 2. agent_messages — 对话消息
CREATE TABLE IF NOT EXISTS agent_messages (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id  TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role             TEXT NOT NULL,                              -- user | assistant
    content          TEXT NOT NULL,
    dify_message_id  TEXT,                                       -- Dify 消息 ID（追踪用）
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_messages_conv ON agent_messages(conversation_id, created_at);

-- 3. agent_assets — Agent 相关的图片/视频资产（与分镜无关）
CREATE TABLE IF NOT EXISTS agent_assets (
    id               TEXT PRIMARY KEY DEFAULT gen_random_uuid()::TEXT,
    conversation_id  TEXT REFERENCES conversations(id) ON DELETE CASCADE,  -- 可空=未归属资产
    type             TEXT NOT NULL,                              -- image | video | reference（reference=用户上传的参考图）
    url              TEXT NOT NULL,                              -- /api/files/xxx（本地存储路径）
    prompt           TEXT,                                       -- 生成提示词（reference 类型可为空）
    model            TEXT,                                       -- 使用模型（reference 类型可为空）
    status           TEXT NOT NULL DEFAULT 'queued',             -- queued | generating | completed | failed
    task_id          TEXT,                                       -- 视频异步任务 ID（反查更新用）
    error            TEXT,                                       -- 失败原因
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_assets_conv ON agent_assets(conversation_id, type);
```

### 设计要点

- **conversations** 同时挂 user_id + project_id：JWT 鉴权查 user_id，项目级联删除会话
- **agent_messages** 不存媒体字段——资产独立成表，消息与资产的关联通过 conversation_id 间接建立（前端按会话分别拉取）
- **agent_assets.conversation_id 可空**：兼容 Dify 工作流未传 conversationId 的旧调用，资产照存但不归属任何会话（彻底消灭临时 scene 孤儿数据）
- **task_id**：视频异步任务完成时，`pollVideoTask` 靠它反查并更新 asset 状态（与 scene 的 videoTaskId 机制对称）
- 删除策略：项目删 → 会话级联删（CASCADE）→ 消息级联删；资产在会话删除时 CASCADE

## 后端接口设计

### 新增 AgentConversationController（`/api/agent/**`，JWT 鉴权）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/conversations` | 创建会话 `{projectId, title?}` → 返回会话（含 id） |
| GET | `/api/agent/conversations?projectId=xxx` | 当前用户的项目会话列表（按 updated_at 倒序） |
| GET | `/api/agent/conversations/{id}` | 会话详情（含消息列表） |
| DELETE | `/api/agent/conversations/{id}` | 删除会话（级联删消息） |
| GET | `/api/agent/conversations/{id}/messages` | 消息列表（按 created_at 正序） |
| POST | `/api/agent/conversations/{id}/messages` | 发送消息（代理 Dify chat-messages） |
| GET | `/api/agent/conversations/{id}/assets` | 该会话的生成资产列表 |
| POST | `/api/agent/upload` | 上传图片（multipart，可选 conversationId）→ 存 uploads/images/ → 返回 URL → 落库 agent_assets(type=reference) |

**鉴权规则**：所有端点从 JWT 取 user_id，校验 `conversation.userId == currentUser.id`，否则 403。创建会话时校验 `project.userId == currentUser.id`（不能给别人的项目开对话）。`/api/agent/upload` 传了 conversationId 时同样校验归属。

### 图片上传端点（POST /api/agent/upload）

```
请求：multipart/form-data
  file            必填，图片文件
  conversationId  可选，上传到某个会话

处理：
1. 校验文件类型（image/*），大小上限 20MB（spring.servlet.multipart.max-file-size）
2. 存入 uploads/images/（FileStorageService 新增 saveUploadedImage(MultipartFile)）
3. 返回 { url: "/api/files/images/xxx.png" }
4. 落库 agent_assets：type=reference，status=completed，conversation_id 可空
```

返回的 URL 即 Dify 工作流的 `PicUrl` 变量值（用户在前端把上传结果填进工作流输入）。

### PicUrl 的消费方式（图生图/图生视频）

Dify 工作流把 `PicUrl`（图片 URL）传给生成端点：

- `DifyGenerateImageRequest` / `DifyGenerateVideoRequest` 各增加 `String picUrl` 字段
- Controller 层映射：`picUrl` 非空 → 作为 `generatedImageUrl` 传入 Service（`ImageGenerationService.callImageEdit` / `VideoGenerationService.createVideoTask` 已支持从本地 uploads/ 按路径读源图，零额外改造）
- 优先级：`picUrl`（用户上传的图）> `generatedImageUrl`（完善已有生成图）——前端/Dify 工作流按场景二选一传，后端在 Controller 层合并处理

```
sceneId 非空 + picUrl → 图生图写 scene（完善/重绘场景图）
sceneId 空 + picUrl + conversationId → 图生图写 agent_assets
sceneId 空 + picUrl + 无 conversationId → 图生图写 agent_assets（未归属）
```

### AgentChatService（新增）—— Dify 对话代理

```
POST /v1/chat-messages
{
  "inputs": { "project_id": "...", "project_name": "..." },  // 工作流变量
  "query": "用户消息",
  "response_mode": "blocking",        // 非流式，简化后端处理
  "conversation_id": "..."            // 有则续接，无则新建
}
```

流程（`POST /api/agent/conversations/{id}/messages` 内部）：
1. 保存 user 消息（role=user）到 agent_messages
2. 调 Dify chat-messages（带 conversation.difyConversationId）
3. 成功 → 保存 assistant 消息（content 取 Dify 响应的 answer），回填 conversation.difyConversationId
4. 失败 → user 消息保留，返回错误

Dify baseUrl 和 App API key 走 `AiConfigProperties` 新增的 `difyBaseUrl` + `difyApiKey` 字段（从 `.env`/yml 读取，不硬编码）。

### DifyAgentController 改造（消灭孤儿 scene）

**DTO 变更**：`DifyGenerateImageRequest` / `DifyGenerateVideoRequest` 增加 `String conversationId` 和 `String picUrl` 两个字段。

**路由逻辑改为**：
```
sceneId 非空                       → 写 scene（现有逻辑不变）
sceneId 空 + conversationId 非空   → 写 agent_assets（关联该会话）
sceneId 空 + conversationId 空     → 写 agent_assets（conversation_id = NULL，未归属）
[删除] 创建临时 scene（scene_number=0）的逻辑
```

**picUrl 合并**：`String effectiveGeneratedImageUrl = picUrl 非空 ? picUrl : generatedImageUrl`，传入 Service 的 generatedImageUrl 参数（本地读文件逻辑已存在）。

### AI Service 解耦（关键改造）

- `ImageGenerationService.generateImage`：sceneId 允许为空。非空 → 维持现有 scene 状态更新；为空 → 跳过 scene 读写，只返回本地文件 URL
- `VideoGenerationService.createVideoTask`：同样支持 sceneId 为空（不写 scene 状态）
- `pollVideoTask` 改为**双通道反查**：先按 `videoTaskId` 查 scene，查不到再查 agent_assets（asset.taskId 匹配），更新 asset.status/url/error

### 新增文件清单

| 文件 | 类型 |
|------|------|
| `entity/AgentConversation.java` | @Data + @TableName("conversations") |
| `entity/AgentMessage.java` | @Data + @TableName("agent_messages") |
| `entity/AgentAsset.java` | @Data + @TableName("agent_assets") |
| `mapper/AgentConversationMapper.java` | extends BaseMapper |
| `mapper/AgentMessageMapper.java` | extends BaseMapper |
| `mapper/AgentAssetMapper.java` | extends BaseMapper |
| `dto/request/AgentCreateConversationRequest.java` | record(projectId, title) — userId 由 JWT 取 |
| `dto/request/AgentSendMessageRequest.java` | record(content) |
| `service/agent/AgentChatService.java` | Dify 代理 + 消息落库 |
| `controller/AgentConversationController.java` | `/api/agent/**` 8 端点（7 个会话/消息/资产 + upload） |
| `FileStorageService` 扩展 | 新增 `saveUploadedImage(MultipartFile)` |

实体字段命名遵循现状：`@TableId(type = IdType.ASSIGN_UUID)`、时间字段用 `OffsetDateTime` + `@TableField(fill = ...)`。

## 实现顺序

1. **V2 migration SQL** — `db/migration/V2__agent_conversation.sql`（3 张新表）
2. **实体 + Mapper** — 6 个新文件（3 entity + 3 mapper）
3. **AI Service 解耦** — `ImageGenerationService` / `VideoGenerationService` 支持 sceneId 为空；`pollVideoTask` 双通道反查
4. **DTO + 配置** — Dify DTO 加 conversationId；`AiConfigProperties` 加 `difyBaseUrl` / `difyApiKey`
5. **DifyAgentController 改造** — 删临时 scene 逻辑，改写 agent_assets（含 multipart 变体）；DTO 加 conversationId + picUrl
6. **AgentConversationController + AgentChatService** — 8 个端点（含 upload）+ Dify chat-messages 代理 + FileStorageService.saveUploadedImage
7. **验证** — `mvn compile`

## 安全与兼容

- **JWT 鉴权**：`/api/agent/**` 由 JwtAuthenticationFilter 保护（默认所有路由已保护，确认未在 SecurityConfig 白名单），Controller 内二次校验 conversation.userId 归属
- **`/api/ai/dify/**` 保持 X-Dify-Key 认证**（DifyApiKeyFilter），新增 conversationId 参数不影响现有 Dify 工作流（不传则资产未归属）
- **不破坏现有分镜流程**：sceneId 非空时行为与现在完全一致
- **`.env.example` / application.yml**：补 `DIFY_BASE_URL`、`DIFY_API_KEY` 占位（无真实值），不提交密钥

## 验证方案

```bash
# 后端编译（MUST use Windows paths）
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

# 手工验证（可选）：建会话 → 发消息 → 确认 agent_messages 落库；
# Dify 工作流调 generate-image 无 sceneId → agent_assets 有记录、scenes 无新行
```

## 明确不做（YAGNI）

- ❌ 前端对话 UI（已确认暂不开发）
- ❌ 消息流式输出（response_mode=blocking）
- ❌ 资产删除/重命名端点、会话归档接口（status 字段预留，后续需要再加）
- ❌ Dify 工作流 DSL 改动（对话对接时再配）
