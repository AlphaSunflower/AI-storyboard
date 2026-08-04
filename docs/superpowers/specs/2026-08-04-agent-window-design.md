# 智能体窗口设计（前端 UI + 后端 streaming 改造）

日期：2026-08-04
状态：已批准（用户逐节确认）

## 背景与目标

2026-08-03 spec（`2026-08-03-agent-conversation-design.md`）已建立 AI Agent 对话的数据模型（conversations / agent_messages / agent_assets 三表）与后端对话模块（`/api/agent/**`，blocking 模式代理 Dify chat-messages），当时明确"前端对话 UI 暂不开发"。

本次目标：
1. **前端智能体窗口**：右下角悬浮球入口 → 右侧滑出抽屉（左会话栏 + 右对话区），对接 Moon 智能体工作流
2. **后端 streaming 改造**：blocking → SSE 流式代理（打字机效果 + 工作流进度 + HITL 人工确认——Moon 工作流含 3 个 human-input 节点，blocking 模式遇 HITL 只返回 `workflow_paused`，无法交互）
3. **inputs 适配 Moon 工作流**：Dify start 节点接收 2 个变量——`currentProjectId`（当前项目 ID）、`PicUrl`（用户上传的参考图 URL），替换旧的 `project_id/project_name`
4. **会话管理完善**：新增/重命名/归档/删除会话；资产删除/分页

## 已确认的需求基线

| 决策点 | 结论 |
|--------|------|
| 入口 | 右下角珊瑚色圆形悬浮球（☾ 图标，呼吸动画） |
| 窗口形态 | 右侧滑出抽屉（~480px 全高），内部左会话栏(~130px) + 右对话区 |
| 会话管理 | 多会话：新建 / 切换 / **重命名** / **归档** / 删除，复用 conversations 表（status: active/archived） |
| 资产管理 | 会话资产网格：**分页加载** + **删除**；消息流中图片/视频卡片渲染 |
| 对话模式 | **streaming（SSE）**，blocking 端点保留兼容 |
| 互斥规则 | 会话级前端状态：智能体生成分镜后，本会话内禁用左侧"剧本输入"，刷新恢复 |
| 参考图 | `/api/agent/upload` 上传 → 发送消息时作为 `PicUrl` 传入工作流 |
| API 对接 | 后端代理，Dify key 只存后端 `.env`；前端零接触 |
| Dify 变量 | `inputs = { currentProjectId: <项目ID>, PicUrl: <图片URL或空串> }` |
| 分镜联动 | **智能体写分镜（generate-script）后，第二栏分镜列表必须自动刷新**：流结束（message_end）比对 sceneCount 与本地 scenes.length，不一致 → 前端 `loadProject(currentProject.id)` 刷新 |
| 生图/生视频归属 | **已核查满足**：sceneId 为空（选了项目未选分镜）→ 只写 agent_assets（image/video 类型），不映射到分镜——`ImageGenerationService.generateImage` / `VideoGenerationService.createVideoTask` 在 sceneId 为 null 时不查不写 scene 表；DifyAgentController 无 sceneId 分支写 agent_assets（generate-video 返回 `{taskId, status, assetId}`，assetId 不塞 sceneId 键，防工作流误当 sceneId 回传） |

## 方案选型

**API 方案（采用 1）**：扩展现有 `/api/agent/**`（AgentChatService 加 streaming 模式，复用三表 + JWT 鉴权 + 事务语义）
- 方案 2（独立 stream 模块）否决：代码重复，两套逻辑维护
- 方案 3（前端直连 Dify）否决：app key 暴露在浏览器，与"走后端 API 对接"偏好冲突

**对话模式（采用 streaming）**：streaming 是 HITL 交互的前提（Dify blocking 遇 human-input 节点返回 `workflow_paused` 后无法继续）；打字机效果 + 节点进度提示提升体验。旧 blocking 端点保留兼容。

**HITL 提交链路（已核实本地 Dify v1.16.1 源码）**：Moon 的 human-input 节点 `delivery_methods=[webapp]` → 表单 recipient 为 `STANDALONE_WEB_APP` → 属于 SERVICE_API surface 允许范围（`ALLOWED_RECIPIENT_TYPES_BY_SURFACE[SERVICE_API] = {STANDALONE_WEB_APP}`），可经 Service API 正常提交。链路：
```
POST /v1/chat-messages (streaming)
  → human_input_required 事件（form_token + task_id + form_content + actions）→ 工作流暂停，SSE 结束
POST /v1/form/human_input/{form_token}  body={action: "agree"}  → 200 {} 恢复执行
GET  /v1/workflow/{task_id}/events (SSE)  → 继续接收后续事件
```

## 后端接口设计（扩展 /api/agent/**）

### 端点总表

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/conversations` | 创建会话 `{projectId, title?}` |
| GET | `/api/agent/conversations?projectId=` | 会话列表（updated_at 倒序，active 优先） |
| GET | `/api/agent/conversations/{id}` | 会话详情（含消息） |
| **PATCH** | `/api/agent/conversations/{id}` | **重命名 / 归档**：body `{title?, status?}`（status: active/archived） |
| DELETE | `/api/agent/conversations/{id}` | 删除会话（级联删消息） |
| GET | `/api/agent/conversations/{id}/messages` | 消息列表（created_at 正序） |
| POST | `/api/agent/conversations/{id}/messages` | 发送消息（blocking，兼容保留） |
| **POST** | `/api/agent/conversations/{id}/messages/stream` | **发送消息（SSE 流式）**：body `{content, picUrl?}` → `text/event-stream` |
| **POST** | `/api/agent/conversations/{id}/form/submit` | **HITL 表单提交**：body `{formToken, taskId, action}` → 代理 Dify form + 续接 workflow events，返回 SSE 流 |
| GET | `/api/agent/conversations/{id}/assets?page=&size=` | 资产列表（**分页**，默认 page=1&size=20，返回 `{records,total}`） |
| **DELETE** | `/api/agent/assets/{id}` | **删除资产**（经 conversation 归属校验） |
| POST | `/api/agent/upload` | 上传图片（multipart，可选 conversationId）→ 存 uploads + 落库 reference 资产 |

**鉴权**：`/api/agent/**` 已在 JWT 保护下（SecurityConfig 白名单不含，自动覆盖新端点），Controller 内二次校验归属（`getOwnedConversation`：不存在与无权统一 40401 同文案防 IDOR）。资产删除经 conversation 归属链校验：`conversation_id` 非空 → 校验归属；**为空（未归属资产）→ 拒绝删除（40401，无归属链可确认）**。资产分页同理只返回本人会话的资产。

### AgentChatService 变更

- `streamMessage(userId, conversationId, content, picUrl)`：
  1. user 消息独立事务（REQUIRES_NEW）立即提交（失败保留，沿用现有语义）
  2. 代理 Dify `chat-messages`（`response_mode=streaming`），逐行解析 SSE 事件，裁剪转发给前端 SseEmitter
  3. **收到 `human_input_required` → 转发精简 `human_input` 事件 → 立即结束本 SSE 流**（Dify 侧 pause 后流自动关闭，不等待）
  4. 流正常结束（message_end）→ 事务内落库 assistant 消息（累积完整 answer）+ 回填 `dify_conversation_id` + 刷新 updatedAt
  5. 流中断/异常 → 转发 error 事件（脱敏文案，全量日志），assistant 不落库
- `submitFormAndResume(userId, conversationId, formToken, taskId, action)`：
  1. POST `{difyBaseUrl}/v1/form/human_input/{formToken}`（`{action}`，Bearer app key）→ 非 200 转发 error
  2. 成功 → GET `{difyBaseUrl}/v1/workflow/{taskId}/events`（**必须带 `user=<conversation.userId>` query 参数**——已核实 Dify 源码该端点 user 必填；同样 Bearer app key）→ 续传 SSE 给前端
  3. 续流结束后同样落库 assistant 消息（若最终输出 message_end）
  4. 续流中再次遇 `human_input_required`（多级确认）→ 重复第 1-2 步流程（前端可再次渲染确认卡片）
- `inputs` 构建：`{ currentProjectId: conversation.projectId, PicUrl: picUrl == null ? "" : picUrl }`
- SseEmitter 超时 10 分钟（生视频最长 2-5 分钟）；前端断开（onCompletion/onTimeout/客户端 abort）清理 Dify 侧连接

### SSE 事件协议（后端 → 前端，裁剪转发）

| event | data | 说明 |
|-------|------|------|
| `message` | `{"content":"增量文本"}` | 打字机增量 |
| `workflow` | `{"title":"生成图片","status":"node_started"\|"node_finished"}` | 节点进度（过滤 Dify 节点 inputs/outputs 巨量 JSON） |
| `human_input` | `{"formToken":"...","taskId":"...","formContent":"满意这个分镜设计方案吗","actions":[{"id":"agree","title":"满意"},...],"expirationTime":1776000000}` | HITL 暂停，前端渲染确认卡片；**收到后后端立即结束当前 SSE 流**（Dify 侧 pause 后流自动关闭） |
| `message_end` | `{"messageId":"...","sceneCount":8}` | 流结束；sceneCount = 项目当前场景数（互斥判定信号） |
| `error` | `{"code":"50202","message":"Dify 服务异常，请稍后重试"}` | 脱敏错误 |

过滤：Dify `ping`、`node_started` 内部 inputs/outputs、`tts_message`、`message_replace`、`workflow_started` 详情一律不透传。

### 图片上传（复用既有 POST /api/agent/upload）

multipart `file` + 可选 `conversationId` → 存 `uploads/images/` → 返回 `{url: "/api/files/images/xxx.png"}` → 落库 `agent_assets(type=reference)`。前端发送消息时将该 URL 作为 `picUrl` 传入。

## 前端组件设计

```
components/agent/
├── AgentFab.tsx              悬浮球（☾，呼吸动画，开关抽屉；抽屉开着时隐藏）
├── AgentDrawer.tsx           抽屉容器（滑入/滑出动画、遮罩点击关闭、Esc 关闭、480px 全高）
├── AgentConversationList.tsx 左会话栏：+新建 / 会话项（标题+时间）/ hover 操作（✏️重命名、🗂归档、🗑删除）/ 归档筛选切换
├── AgentChatPanel.tsx        右对话区：消息流 + 输入区（textarea + 📎参考图预览 + 发送）+ HITL 卡片挂载点
├── MessageBubble.tsx         气泡（user 右珊瑚 / assistant 左奶油卡）+ 轻量 markdown + 图片/视频卡片
├── HumanInputCard.tsx        HITL 确认卡片（formContent + 按钮组，点击→POST /form/submit→续接流，期间禁用发送）
└── AgentAssetsPanel.tsx      资产面板（网格缩略图 + 分页 + 删除；入口在会话栏顶部或对话区标签）
stores/agentStore.ts          Zustand：会话列表/当前会话/消息/流式状态/参考图/HITL 待确认态/互斥标志/资产分页
api/agent.ts                  axios 封装 + fetch 流式读取（POST 无法用 EventSource，用 ReadableStream 逐行解析 SSE）
```

要点：
- **HITL 交互**：收到 `human_input` 事件 → 暂停输入区（发送禁用）→ 渲染确认卡片 → 用户点按钮 → `POST /form/submit` → 后端续接 workflow events，同一响应流继续打字机输出 → 恢复输入区
- **互斥逻辑**：agentStore 内存标志 `agentGeneratedScenes`（默认 false，刷新即恢复）。触发：`message_end.sceneCount` > 会话开始时缓存值 → true。表现：LeftSidebar 剧本 textarea + 「生成分镜脚本」按钮禁用，提示"分镜已由智能体生成，如需手动生成请刷新页面"
- **分镜列表联动刷新**：每个 SSE 流开始前缓存 `scenes.length`；`message_end` 收到 `sceneCount` 后与缓存值比对，不一致（智能体经 generate-script 写入新分镜）→ 调 `projectStore.loadProject(currentProject.id)` → SceneListPanel 展示新分镜。注意 loadProject 会重置 selectedSceneId（可接受，用户注意力在对话区；如需要保留选中在实现时再评估）
- **资产渲染**：回复中 `/api/files/images/*.png` → `<img>`，`/api/files/videos/*.mp4` → `<video controls>`，经 `assetUrl()` 加后端前缀（`http://localhost:8082`）
- **资产面板**：分页接口（page/size），图片网格 + 视频卡片，hover 删除按钮（二次确认）
- **会话操作**：重命名弹小输入框；归档从默认列表移入"已归档"筛选；删除二次确认（级联删消息）
- **零新依赖**：轻量 markdown（加粗/换行/图片/视频链接）手写正则；SSE 用 fetch + ReadableStream

## 数据流时序（含 HITL）

```
点悬浮球 → 抽屉打开 → 建/选会话 → 输入文字(+上传参考图→picUrl)
→ POST /messages/stream → 后端落库 user 消息 → 代理 Dify streaming
→ 前端打字机渲染 → 智能体设计分镜方案
→ human_input 事件("满意这个分镜设计方案吗") → HITL 卡片，输入区禁用
→ 点"满意" → POST /form/submit → 后端提交表单 + 续接 workflow events
→ 智能体调 generate-script 写分镜（scenes++）
→ message_end(sceneCount 增加) → agentGeneratedScenes=true → 左侧剧本输入禁用
→ 同时 sceneCount≠本地 scenes.length → loadProject 刷新第二栏分镜列表
→ 图片方案确认 / 视频方案确认（另 2 个 HITL 节点）同理；生图/生视频无 sceneId 只存 agent_assets 不映射分镜
→ 关闭抽屉 / 刷新页面 → 状态恢复
```

## 实现顺序

1. **后端**：`AgentSendMessageRequest` 加 picUrl → `.env`/`AiConfigProperties` 配置 app key → `AgentChatService.streamMessage()` + SSE 端点 → `submitFormAndResume()` + form 端点 → 会话 PATCH（重命名/归档）→ 资产分页 + 删除端点
2. **后端验证**：`mvn compile` + curl（JWT + SSE 事件序列）+ 模拟 HITL 提交验证续流 + 资产分页/删除
3. **前端**：`api/agent.ts`（含 SSE 流式读取）→ `agentStore` → 组件（Fab → Drawer → ConversationList → ChatPanel → MessageBubble → HumanInputCard → AssetsPanel）
4. **互斥逻辑**：LeftSidebar 联动禁用
5. **验证**：`npx tsc --noEmit` + `npm run build` + 手动全流程

## 安全与兼容

- `/api/agent/**` JWT 保护自动覆盖新端点（SecurityConfig 白名单不含，零改动）
- Dify app key 仅存后端 `.env`（`AI_DIFY_API_KEY=app-gs...`），不提交 git；form_token / task_id 仅存前端内存、只经后端转发
- 旧 blocking 端点与旧调用方不受影响；inputs 变更仅影响 AgentChatService 自身（对话模块）
- 场景写入仍走既有 `/api/ai/dify/generate-script`（X-Dify-Key 认证，不变）；无 sceneId 生成写 agent_assets 逻辑不变
- 资产删除仅删记录，不删 `uploads/` 物理文件（后续如需物理清理再扩展）

## 验证方案

```bash
# 后端（MUST use Windows paths）
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

# 手工：curl 带 JWT POST /messages/stream 观察 SSE 序列；
#   对话推进到 HITL → POST /form/submit → 确认续流输出；
#   资产分页 page/size、删除后列表减少；会话重命名/归档后列表排序变化

# 前端
cd AIStoryboardClient && npx tsc --noEmit && npm run build
```

## 明确不做（YAGNI）

- ❌ 流式中断续传/断点恢复（失败重发整条消息即可）
- ❌ HITL 文本表单字段渲染（Moon 3 个 human-input 节点均为纯按钮，inputs 为空；后续若加文本字段再扩展 HumanInputCard）
- ❌ 资产物理文件清理（只删记录）
- ❌ 多 Dify app 切换（当前单一 Moon app）
- ❌ markdown 完整渲染库、语音/TTS、消息搜索
- ❌ Moon 工作流 DSL 改动（如需调整 delivery/表单结构另行确认）
