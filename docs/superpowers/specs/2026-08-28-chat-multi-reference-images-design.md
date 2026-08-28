# 设计：/chat 参考图多张投喂 + 资产多图（一个资产=多张图）投喂生视频

> 日期：2026-08-28
> 模块：MoonAgent（agent-core / MoonAgent）+ AIStoryboardClient
> 状态：待用户审查

## 一、背景与目标

用户需求（原话）：

> 我希望在 /chat 路径上传参考图可以携带多张图，主要是投喂给生视频模型，以及资产也可以上传多资产，一个资产是有多张图的（一个资产相当于一个人物的样子约束等等），用来投喂给生视频模型。

拆解成两个可交付能力：

1. **/chat 上传参考图支持多张**：聊天输入区可一次选/上传多张临时参考图，作为本条消息的参考；也可从资产库选一个含多图的资产，把该资产的**全部图**作为参考。
2. **资产含多图、多图投喂生视频**：一个资产（人物/道具/场景）= 多张参考图（如人物多角度），选定后这些图作为生视频模型的参考图输入。

### 关键现状（已确认）

| 能力 | 现状 | 是否需改 |
|------|------|---------|
| 生视频模型多参考图 | `VideoGenerationServiceImpl.createVideoTask` 已接受 `referenceImages: List<String>`，合并资产图、截断 ≤9 | **否** |
| 资产库一个资产多图 | `Asset.images[]` 数组，`AssetLibraryPanel` 已支持多图上传 | **否**（仅"选资产取全部图"需改） |
| `agent_assets` 存多张参考图 | 按多条 `type=reference` 记录存储 | **否** |
| 上游参考图贯穿链路 | `picUrl` 单图贯穿 controller→service→orchestrator→handler→视觉模型 | **是（核心）** |

**结论**：本次改动 = 把整条链路的"单图 `picUrl`"升级为"多图 `picUrls: List<String>`"，并让资产选图从"只取首图"改为"取全部图"。DB 零迁移。

## 二、总体设计

采用**数组化贯穿**：凡原来传单个图片 URL 的地方，改为传 `List<String>`。兼容策略：

- 新增 `picUrls: List<String>` 字段，旧 `picUrl` 字段**保留但弃用**（后端读 `picUrls`，写入时兼容读 `picUrl` 兜底）。
- `OrchestrationRequest` 内部用 `List<String> picUrls`，`getPicUrl()` 保留为返回首张（兼容旧 handler 调用，新逻辑一律走 `getPicUrls()`）。

### 数据流（改后）

```
用户选/传多图（临时多图 or 资产全部图）
  ↓ 前端累积到 store.refImageUrls: string[]
  ↓ POST /messages/stream body { content, picUrls: [...] }
  ↓ AgentConversationController → AgentChatService.streamMessage(..., picUrls, emitter)
  ↓ AgentOrchestrator.run(conversation, content, picUrls, emitter)
  ↓ PlanGraph.run → state K_PIC_URLS: List<String>
  ↓ OrchestrationRequest(conversation, content, picUrls, emitter)
  ↓ handler（Pic/Video 等）读 request.getPicUrls()
  ↓ 视觉模型服务（buildRefinedPrompt / buildVideoPlan）多图组装 Media
  ↓ 视频生成 startVideoGenerationAsync → createVideoTask(referenceImages=[...])
```

## 三、后端改动

### 模块：agent-core

**`ai/ImageRefinePromptService` / `ai/impl/ImageRefinePromptServiceImpl`**
- `String buildRefinedPrompt(String imagePath, String userRequest)` → `String buildRefinedPrompt(List<String> imagePaths, String userRequest)`
- 实现：循环 imagePaths，每张读文件→data URI→拼一个 `Media`，全部塞进 `UserMessage.media(...)`（多图视觉）。空列表时退化为纯文本（无图）。

**`ai/VideoPlanService` / `ai/impl/VideoPlanServiceImpl`**
- `VideoPlan buildVideoPlan(String imagePath, ...)` → `VideoPlan buildVideoPlan(List<String> imagePaths, ...)`
- 实现同 buildRefinedPrompt：多图多 Media。

**`ai/AgentGenerationService`（agent-path 接口在 MoonAgent，已有）**
- `createVideoTask(..., referenceImages: List<String>, generatedImageUrl)` 已多图，**不改签名**，仅上游调用时把 `referenceImages` 填上。

### 模块：MoonAgent

**`dto/request/AgentSendMessageRequest`**
```java
public record AgentSendMessageRequest(
    @NotBlank String content,
    String picUrl,                  // 兼容保留（弃用）
    java.util.List<String> picUrls  // 新增；兼容：为空时回退 [picUrl]
) {}
```

**`controller/AgentConversationController`**
- `streamMessage` / `sendMessage`：`request.picUrl()` → 读 `picUrls`（兼容包装：`picUrls==null||empty ? (picUrl!=null?List.of(picUrl):List.of()) : picUrls`）。
- `uploadImage` 不变（仍单文件/次上传，前端循环调即可实现多张）。

**`ai/agent/AgentChatService` / `impl/AgentChatServiceImpl`**
- `streamMessage(String userId, String convId, String content, String picUrl, SseEmitter)` → `(..., java.util.List<String> picUrls, SseEmitter)`
- 内部 `lastPicUrlByConversation` → `lastPicUrlsByConversation`（`Map<String, List<String>>`）；`confirmImageDone` / `deleteConversation` 同步清理。
- 认领未归属资产：循环 `picUrls` 逐条认领（`conversation_id` 从 null → 当前会话）。
- `uploadReferenceImage` 不变（单文件落一条 `agent_assets`）；前端循环调用即可。

**`ai/agent/AgentOrchestrator` / `impl/AgentOrchestratorImpl`**
- `run(conversation, content, picUrl, emitter)` → `(..., java.util.List<String> picUrls, emitter)`
- `resume(...)` 不改（resume 走 HITL 表单，不直接带参考图；参考图已随 run 存进 checkpoint plan 的 `picUrl`/新增 `picUrls`）。

**`ai/agent/PlanGraph`**
- `K_PIC_URL`（String）→ `K_PIC_URLS`（List<String>），`ReplaceStrategy` 同步。
- `run(conversation, content, picUrl, emitter)` → `(..., picUrls, emitter)`；invoke 入参 `K_PIC_URLS`。
- `stateRequest(state)`：`new OrchestrationRequest(conversation, content, picUrls, emitter)`。
- `SpecialistContext` 的 `picUrl` 字段 → `picUrls`（同步改 SpecialistContext record / 其构造）。

**`ai/agent/handler/OrchestrationRequest`**
- `picUrl: String` → `picUrls: List<String>`
- 保留 `public String getPicUrl()`（返回 `picUrls` 首张或 null，供旧命运用——新逻辑一律 `getPicUrls()`）。

**`ai/agent/handler/PicIntentHandler`**
- `handle`：`String source = request.getPicUrl()` → `List<String> sources = request.getPicUrls()`；`buildRefinedPrompt(sources, content)`。
- `resume` / `resumeRefineOption`：`source` 单图→`List`，透传 `buildRefinedPrompt`。

**`ai/agent/handler/VideoIntentHandler`**
- 方案设计输入 `source`（单）→ `sources`（List）。`handleFromVideoClarifyGate` / `handleVideoPlan` 均传 List。
- `handleVideoPlan`：`if ((source==null||blank) && chosen!=null)` 时，从选中资产取**全部图**（`flatMap(images → url)`）作为视觉输入，不再只取首图。
- `runAssetGate` 里的 `picUrl` 传参改 `picUrls`。

**`ai/agent/handler/AgentOrchestratorSupport`**
- `runAssetGate(..., String picUrl)` → `(..., List<String> picUrls)`；checkpoint plan 写 `picUrl` 字段改为写 `picUrls`（JSON数组字符串）。
- `firstAssetImageUrl(projectId, assetIdsCsv)` → `assetImageUrls(projectId, assetIdsCsv): List<String>`（取勾选资产**全部**图片 URL，去重）。
- `startVideoGenerationAsync(req, prompt, duration, aspectRatio, String source, params)` → `(..., java.util.List<String> referenceImages, params)`；内部调 `createVideoTask(conversation, null, prompt, model, resolution, null, effAspectRatio, effDuration, null, referenceImages, null)` —— 参考图走 `referenceImages`，`generatedImageUrl` 传 `null`（参考图路径已统一为 reference）。
- plan 里 `PlanField.PIC_URL` 语义升级为"多图"，plan 序列化用 `picUrls` 数组。

**`ai/agent/AgentTools`**
- `refineImage(..., picUrl, ...)` → `(..., java.util.List<String> picUrls, ...)`；`mode = picUrls非空 ? "edit" : null`；`generateImage(..., referenceImages=null, generatedImageUrl=picUrls.isEmpty()?null:null)`（图改图 edits 仍单源图兜底，取首张）。
- `generateVideo(..., picUrl, ...)` → `(..., java.util.List<String> picUrls, ...)`；`createVideoTask(..., referenceImages=picUrls, generatedImageUrl=null)`。

### 兼容要点

- `PlanField` 枚举新增 `PIC_URLS("picUrls")`；读取时 `picUrls` 缺失回退 `picUrl` 单字段（兼容历史 checkpoint）。
- checkpoint plan 中历史数据只有 `picUrl` 单图：`planField(..., PlanField.PIC_URLS)` 读不到时取 `PIC_URL` 包装成单元素 List。

## 四、前端改动（AIStoryboardClient）

**`api/agent.ts`**
- `AgentSendMessageRequest` 对应 body：`streamChat(conversationId, content, picUrls: string[], onEvent)`，body `{ content, picUrls }`（保留兼容发送 `picUrl` 单字段不需要，前端直接发数组）。
- `uploadImage(file)` 不变（多张时循环调）。

**`stores/agentStore.ts`**
- `refImageUrl: string | null` → `refImageUrls: string[]`
- `setRefImageUrl(v)` → `setRefImageUrls(v: string[])` + `addRefImageUrls(urls: string[])` / `removeRefImageUrl(index)`。
- `uploadRefImage(file)` 保留（单文件追加）；新增 `uploadRefImages(files)` 批量（循环调 uploadImage 再累积）。
- `sendMessage(content, opts?: { picUrls?: string[] })`；`streamChat(..., opts?.picUrls ?? pendingPicUrls ?? refImageUrls ?? [])`。
- `pendingPicUrl: string | null` → `pendingPicUrls: string[]`（继续完善暂存多张）。

**`components/agent/ChatComposer.tsx`**
- 上传图片：`<input multiple>`，`handleFile` 取所有文件循环 `uploadRefImage`。
- 预览区：第一张缩略图 + "还有 N 张"角标（`refImageUrls.length`），点击展开列表（横向滚动）逐张删除。
- 资产选择弹窗：改多选，`handleAssetConfirm` 取所选资产全部 `images[].url` 合并进 `refImageUrls`（不再只取首图）。

**`components/agent/AgentChatPanel.tsx`**
- 同步 store 改动（`refImageUrls` / 批量上传 / 多图预览）。

**`components/asset/AssetLibraryPanel.tsx`**
- 上传/新建已支持多图，不改。仅在 pickup 场合若提供"设为参考图"能力时，可复用其多图数组。

## 五、错误与边界

- 多图上限：**由模型能力决定**（前端不设硬上限），视频侧硬截断 ≤9（现有 `MAX_REFERENCE_IMAGES=9`），超出部分日志告警。
- 单张失败：批量上传时逐张 try/catch，单张失败跳过并提示"部分图片上传失败"，不影响已成功张。
- 全部失败：清空 `refImageUrls` 并提示。
- 视觉模型不支持多图：`buildRefinedPrompt`/`buildVideoPlan` 内部多 Media 组装是 Spring AI `UserMessage.media(List)` 原生支持，无需降级；若上游网关拒绝多 Media，兜底只取首张（日志 warning）。
- 图改图 edits：仍单源图（`generatedImageUrl` 取 `picUrls` 首张），多图语境下 edits 语义为"以第一张为基准改"。

## 六、测试

后端（MoonAgent compile）：
- `AgentSendMessageRequest` 单图/多图/空 `picUrls` 兼容回退。
- `PlanGraph.stateRequest` 多图进 `OrchestrationRequest`，`getPicUrl()` 返回首张。
- `ImageRefinePromptServiceImpl.buildRefinedPrompt` 多 Media 组装（单图 List 与单图 String 行为一致）。
- `VideoGenerationServiceImpl.createVideoTask(referenceImages=[...])` 多图合并与 ≤9 截断（已有，回归确认）。

前端：
- `/chat` 多选上传、删单张、计数角标、发送带 `picUrls`。
- 从资产库选含多图的资产 → `refImageUrls` 含全部图。
- 生视频方案卡片确认 → 参考图多张进入 `referenceImages`。

## 七、不做的事（YAGNI）

- 不做新表、不做 DB 迁移（资产多图与多参考图记录现有结构均承载）。
- 不生语义标注 / 分组（"哪张是角色、哪张是风格"）——后续有需要再加。
- 不做图片排序 UI（按上传顺序）。
- 不重构 `AssetLibraryPanel` 多图上传（已满足）。
