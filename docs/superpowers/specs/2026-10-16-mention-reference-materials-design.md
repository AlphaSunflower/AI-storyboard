# @ 引用资料（Mention Reference）设计方案

日期：2026-10-16（v2：交互改为「正文内蓝色 `@` 标记 + 系统提示词声明语义」）
范围：Moon 智能体对话输入卡（`/chat` 页、编辑器抽屉、画布子站共用一个 `ChatComposer`）
状态：待确认（见 §9）

---

## 1. 交互定调（本次确认版）

**`@` 是正文里的标记，不是附件卡片。** 用户在输入框里看到的是蓝色（品牌色）的 `@名称`，消息发出去后这段标记连同正文一起进入模型上下文；系统提示词事先声明这个约定的含义：

> 正文里 `@xxx` 是**引用资料标记**：它指向的资料在**你可读的范围内**，你必须去读（用对应工具）之后再回答，不要要求用户重新上传；读不到时明确说读不到，不要猜内容。

即：**「`@` 标记 = 读它」由系统提示词约定**，四类资料各自的读取工具见 §4。

与之配套的第二条（机器可读、防止同名歧义）：**同一个 token 在请求里带一份结构化 companion**（§3）。token 给人看、companion 给后端解析——因为 `@设定.docx` 这类纯名字可能重名（两个同名文档、两个同名会话），只靠名字解析会让模型读到错的东西。

> 为什么不能只靠名字：文件与会话标题都允许重名，模型只能"猜"；分镜 `分镜3` 在本项目内唯一，但跨项目歧义。带 id 后，后端可以把「本轮每个 `@` 分别指向谁」直接注入上下文（§4.3），模型一次命中。

## 2. 交互设计

### 2.1 触发与命中

- 触发字符：`@`。命中规则：取光标前最近的 `@`，其到光标之间不含空格/换行 → 进入 mention 模式，`@` 之后为查询词（空查询＝展开四组列表）。派生基于 `selectionStart`（不是整串 `trim`），与 `slashQuery`（`ChatComposer.tsx:382-387`）同构。
- 与斜杠命令互斥：`slashQuery` 为真时不进 mention；mention 打开时不进斜杠模式。
- 无匹配项时不进 mention 模式（保持 `@xx` 为普通文本，不劫持 Enter），与 `ChatSlashMenu` 现有策略一致（`ChatComposer.tsx:541-542`）。
- 收纳态联动：mention 打开时必须并入 `collapsed` 判定（与 `slashMenuVisible` 并列，`ChatComposer.tsx:861-862`），否则输入卡一收缩菜单就被裁掉。
- `onKeyDown`（`ChatComposer.tsx:1192-1201`）新增 mention 分支时**必须保留 `e.nativeEvent.isComposing` 中文输入法保护**（`@` 常与拼音同时出现在输入法里）。
- 只读会话（归档 / LEGACY 编排，`ChatComposer.tsx:160-172`）不触发。

### 2.2 菜单结构

一个向上弹出的浮层（`createPortal` + `useAbovePopoverPlacement`，`abovePopover.ts:66-115`；与 `ChatSlashMenu.tsx:47-84, 88-138` 同款定位与 window 键盘捕获）：

```
┌ 引用资料 ───────────────────────────┐
│ 📁 项目库文件        打开项目库选择器  │
│ 💻 本地文件          从本机选择文件    │
│ ── 分镜（当前项目 12 个）────────────│
│    📋 全部分镜…      打开分镜选择器    │
│    分镜 1  雨夜街头，霓虹倒影…         │
│    分镜 2  ……                         │
│ ── 对话（当前项目 4 个）─────────────│
│    💬 分镜重做 · 23 条 · 摘要已就绪    │
│    新对话 · 8 条 · 摘要生成中          │
└─────────────────────────────────────┘
```

- 查询词对四组同时过滤（与 `slashItemMatches` 同形）；分机组内联 8 条 + 「打开分镜选择器」，会话组内联 8 条 + 滚动。
- **菜单高度有硬上限**：`MENTION_MENU_MAX_HEIGHT`（360px，取「输入卡到视口顶」与它的较小值），超出内部滚动；每组超出 8 条的部分只留一行说明（`note` 行：不参与键盘导航、不计入分组数量），**不得**按数据量逐条铺开——会话上百条时整块菜单会顶到页面顶部、遮住消息区。键盘 ↑↓ 移动时选中项自动滚进可视区。
- 键盘：↑↓ 跨组移动、Enter/Tab 选中、Esc 关闭。
- 上限：单轮引用 ≤ 10 条（对话 ≤ 3、分镜 ≤ 5）；到上限时菜单项置灰 + `disabledHint`。

### 2.3 选中后：把 `@名称` 插进正文并变蓝

- 选中项目 → 用 `@名称 ` 替换用户刚输入的 `@查询词`，光标落在名称之后。
- **标记是原子的**：退格一次删掉整个 `@名称`（不是逐字删），否则会出现 `@分镜` 这种半截标记。
- 同一轮里同一个资料只允许出现一次（重复选中＝把光标移到已有标记处）。
- 输入框里 `@名称` 显示为蓝色（`agentChatTokens.brand`）。

**⚠️ 着色是本次最大的实现约束：现有输入框是受控 `<textarea>`（`ChatComposer.tsx:1186-1211`），textarea 无法局部着色。** 三种做法：

| 方案 | 做法 | 代价 | 结论 |
|------|------|------|------|
| **(a) 镜像高亮层** | textarea 后面叠一层同字体/同 padding/同宽高的 div 渲染同一段文本，把 `@名称` 包成蓝字；textarea 文字设为 `color: transparent` + `caret-color: 正色`，滚动同步 | 需严格对齐字体度量（`fontSize:16/lineHeight:1.6/同一 font-family`）；`useAutoGrowTextarea` 已按内容量高度，两层要一起长 | **已定（2026-10-16 确认）：P0 采用** |
| (b) contenteditable 富文本 | 真富文本 token，可天然原子化 + 直接着色 | 要重写受控输入、IME 组合、auto-grow、`composerInjection`、语音回填（`stt` 直接 `setText`）、拖拽落字等全部现成逻辑，回归面最大 | 后置（P3 再评估） |
| (c) 输入框内不着色 | 只在发送后的气泡里把 `@名称` 渲染成蓝 chip | 改动最小，但不满足「输入时变蓝」 | 不采纳 |

方案 (a) 的可行性关键：中文换行必须逐字对齐——两层在同一浏览器、同一字体下渲染同一字符串，度量天然一致；只需保证 `box-sizing`、`padding`、`border: none`、`white-space: pre-wrap`、`word-break` 完全一致，并把 `textarea` 的滚动位置同步给镜像层。

### 2.4 发送后的呈现

气泡里的 `@名称` 渲染为蓝色标记（`MessageBubble.tsx` 正文渲染路径），点击可打开对应资料（文档预览 / 分镜预览 / 该会话）。

## 3. 数据契约

### 3.1 token + companion

```ts
// AIStoryboardClient/src/api/agent.ts（新增）
export type AgentReferenceKind = 'library_file' | 'local_file' | 'scene' | 'conversation';

export interface AgentReference {
  kind: AgentReferenceKind;
  id: string;              // agent_assets.id / library assetId / sceneId / conversationId
  token: string;           // 正文里的标记原文，如 '@分镜3'、'@设定.docx'（与正文逐字一致）
  name: string;            // 展示名（token 去掉 @）
  assetId?: string;        // 本地文件上传后的 agent_assets.id
  mediaId?: string;        // 项目库 mediaId
  url?: string;            // /api/files/files/** 或 /api/library/media/**
  mimeType?: string;
  sizeBytes?: number;
  sceneNumber?: number;
  conversationTitle?: string;
  messageCount?: number;
  summaryStatus?: 'ready' | 'generating' | 'stale' | 'unavailable';
}
```

- `content` 里保留 `@名称` 原文（这是"系统提示词约定"的落点，也是用户看到的东西）。
- `references` 是同一批引用的机器可读版本，随 `streamChat` body 下发。
- 项目库文件 / 本地文件**同时**继续作为 `attachments` 下发：这条链路的文档读取（Tika → 摘要 → `read_document`）已完整可用，不必重造（§4.2）。
- 用户消息 `metadata.messageInput.references` 一并落库（`agentStore.ts:3890-3906`），刷新后气泡仍能渲染蓝标记。

### 3.2 后端

```java
// MoonAgent/src/main/java/com/moon/moonagent/dto/request/AgentReferenceRequest.java（新增）
public record AgentReferenceRequest(
    @NotBlank @Size(max = 32) String kind,   // library_file | local_file | scene | conversation
    @NotBlank @Size(max = 128) String id,
    @NotBlank @Size(max = 200) String token, // 正文里的标记原文
    @Size(max = 200) String name,
    String assetId, String mediaId, String url, String mimeType, Long sizeBytes,
    Integer sceneNumber) {}
```

`AgentSendMessageRequest`（现有 `attachments` 上限 20，`AgentSendMessageRequest.java:10-29`）新增：

```java
@Size(max = 10, message = "引用资料最多 10 项") List<@Valid AgentReferenceRequest> references,
```

透传链路与 `attachments` 完全同形，逐个补齐（**漏一处就会出现「编辑重发丢引用」**）：
`AgentConversationController.java:280-306` → `AgentChatService.java:182-221` 六个重载 → `AgentChatServiceImpl.java:890-960, 1211-1343`（建轮 / HITL 续流 / 编辑重发）→ `OrchestrationRequest.java:29-106` → `TurnMediaCanonicalizer.java:44-107`。

## 4. 后端实现

### 4.0 系统提示词声明 `@` 约定（两处，都要有）

1. **全局约定**（面向所有会话）：写进全局系统提示词资产 `MoonAgent/src/main/resources/agent-assets/global-system-prompt.txt`（§二.12）。**这份 classpath 文件就是本项目实际生效的那份**——`GlobalPromptRepository.getPrompt` 先尝试 Nacos，取不到就回退 classpath（回退位置见 `NacosAssetProperties.globalPromptFallbackLocation`）；当前不使用 Nacos，因此**不需要发布步骤**。内容要点：
   - `@` 开头的是引用资料标记，代表该资料在可读范围内，**必须先去读再回答**，不要要求用户重新上传；
   - 每类资料对应哪个工具（文档 → `read_document`；会话 → `read_conversation`；分镜 → `read_project_scene` 或注入块；项目库文件 → 同文档）；
   - 读不到（越权 / 已删除 / 摘要不足）必须明说，不许编。
2. **每轮兜底**（不依赖全局提示词的版本）：在 `TurnContextMiddleware` 的 `<turn-context>` 里加一小段 `@` 约定声明 + 本轮 `@` 的**解析结果**（§4.3）。这样即使全局提示词被整体替换/落后，模型也能正确理解"这些 @ 分别指向谁"。

`TurnContextMiddleware.onSystemPrompt`（`TurnContextMiddleware.java:50-164`）是全仓既有的「每轮往系统提示词尾部追加只读上下文」唯一挂点：已在注入画布模式、参考输入模式、附件元数据、文档摘要+读取策略、生成偏好、活动工作流、`<project-creative-context>`、`<constraint-ledger>`、`<capabilities>`，最终包成 `<turn-context>`。本方案与它同构，新增一个 `<turn-references>` 子块；中间件实例在 `MoonAgentRuntime.java:209-217` 一次性构造，**新增依赖要改这里**。

### 4.1 四类引用的读取通路现状（实测，含必修缺口）

| 类别 | 现状 | 必修 |
|------|------|------|
| 本地文件（docx/xlsx/pdf/txt） | **完整可用**：`/api/agent/upload` → MinIO `files/` + `agent_assets(type=reference, kind=file)` → `attachments` → `TurnContextMiddleware.appendDocumentContext`（`:247-297`）注入「元数据 + 摘要 + 读取策略」→ 模型调 `read_document(assetId, offset, maxChars)` 经 Tika 转 Markdown 分片返回 | 无阻塞项。可选打磨：上传即预热解析/摘要（现在**首轮永远是 `summary=(not generated yet)`**，因为索引行只在首次 `read_document` 时才创建，`AgentDocumentService.java:84-100`） |
| 项目库文件 | **传输已通、后端读取断链**（详见 §4.2） | **必须修** |
| 分镜 | **后端完全没有按 id/编号注入的能力**：DTO 无字段、`OrchestrationRequest` 无字段、运行时无通道；读路径只有 `list_project_storyboard`（整项目读，`AgentScopeStoryboardTools.java:151-169`）。现状是前端拼一句纯文本「请查看分镜3」（`ChatComposer.tsx:709-714`、`embeddedAppMessages.ts:228-231`），模型必须自己再调一次工具 | **必须新写**（§4.3） |
| 当前项目对话 | **没有「引用另一会话」的能力**。唯一跨会话先例是侧边对话 = `cloneSessionState` **整份深拷贝**源会话 AgentState（`MoonAgentRuntime.java:412-460`），随源会话增长线性膨胀，不是引用 | **必须新写**（§4.4） |

顺带确认两条死字段（不要在方案里依赖）：`routingHint` 在 AgentScope 路径**没有任何消费方**（`grep getRoutingHint` 在 `ai/agent/agentscope/**` 零命中），只落消息 metadata；`cancelActiveTasks` 同理无消费方。所以"靠 routingHint 触发分镜注入"不可行。

### 4.2 项目库文件读取断链（必修）

**问题**：统一选材弹窗已把项目库文档包成 `{ kind: 'file', source: 'library', assetId, mediaId, url: '/api/library/media/...' }`（`ChatComposer.tsx:1348-1371`），但：

- `AgentDocumentService.requireDocument`（`AgentDocumentService.java:177-192`）要求 `agent_assets.id` + 同 `conversationId` + `url` 前缀 `/api/files/files/`（`FILE_PREFIX`）——`/api/library/media/**` 会被直接判「文档不存在或不属于当前会话」；
- `recentSummaries`（`:126-170`）同样按 `url LIKE '/api/files/files/%'` 过滤，项目库文档**永远不会**出现在文档摘要里。

结果：`@某个项目库文档` 之后，模型拿不到正文，只有（且仅在已绑定资产组时）`list_asset_groups` 给的 `fileName/mimeType/objectKey/contentUrl` 元数据。

**实现落地（2026-10-16 更新）**：转存放在**后端**而不是前端——MoonAgent 收到 `kind=library_file` 的引用后，按 `mediaId` 经 asset-client 做**归属校验**、取字节、写入既有文档链路（MinIO `files/` + `agent_assets(reference, kind=file)` 绑到本会话），此后 `read_document` 与文档摘要照常可用。偏离原方案（原写「前端选中时转存」）的原因：① 浏览器取项目库媒体字节要过跨域与鉴权，且媒体 URL 可能是限时链接（前端另有 `refreshLibraryMediaAttachments` 专门刷新它），服务端取更稳；② 归属校验必须在服务端做，前端传来的 `mediaId` 不可信；③ 转存只有一处实现，能按内容哈希做幂等，避免同一文件重复落库。新增 `LibraryReferenceAssetService`（接口 + impl），`AgentDocumentService` 的读取逻辑**不动**。

**⚠️ 向 asset-service 读素材必须带真实 `userId`（实现期实测踩坑）**：

- `AssetClient.getAssetInternal(assetId, actingUserId)` 只在 `actingUserId` 非空时才加 `X-User-Id`（`AssetClient.java:337`）；而 asset-service 的 `AssetAuthenticationFilter`（`:52-61`）在内部令牌有效、`X-User-Id` 缺失时**把身份兜成字符串 `"internal"`**，并不是"按资产自身 owner 返回"。
- 而读取链路 `LibraryRepository.findAsset`（`:55-66`）的 SQL 是 `WHERE a.id = :id AND a.owner_user_id = :userId`——于是 `owner_user_id='internal'` 永远匹配不到，请求 404，转存被降级成「正文不可用」，**功能静默失效、只留一条 warn**。
- 所以：调用点必须传**会话所属用户**（`AgentScopeRunContext.conversation().getUserId()`）。这一步本身就是**用户级**防 IDOR；在此之上再判项目归属（`ownerProjectId == projectId` 或 `scope == GLOBAL` 或链接列表命中）——注意**前端从不调用 `libraryApi.linkProject`**，全局素材永远不在项目链接里，因此必须显式放行 GLOBAL，否则 `@全局项目库文档` 一律读不到。
- 共享对象存储：asset-service 与 MoonAgent 用的是同一个 bucket（`storyboard`，`asset-service/application.yml:51-54` vs `MinioStorage` 的 `storyboard.storage.bucket`），所以按 `media.objectKey()` 直接 `MinioStorage.get` 即可取字节，不必经浏览器 URL 或 loopback HTTP。

**顺带必修的小 bug**：拖拽项目库素材的分支只处理 `IMAGE/VIDEO/...`，文档会掉进 audio 分支（`ChatComposer.tsx:777-828`）；本地文件是 IMAGE/VIDEO/AUDIO/DOCUMENT 四型，拖拽路径要一并覆盖。

### 4.3 分镜引用与 `<turn-references>` 注入

- 新增 `SceneReferenceService`（`service` 接口 + `service/impl` 实现）：按 `sceneId` 取单镜内容，复用 `StoryboardClient.getScene(sceneId)`（`StoryboardClient.java:323-355`）与带缓存的项目级读（`:85-121`）。
- 在 `TurnContextMiddleware` 新增 `appendReferenceContext(lines, request, runContext)`（紧跟 `appendDocumentContext` 之后），把本轮的每个 `@` 解析成一行：

```
<turn-references>
@ 标记约定：正文里以 @ 开头的名称是引用资料标记，指向你可读范围内的资料，必须先读取再回答；
读不到时明确说明，不要编造内容，也不要要求用户重新上传。
- token=@分镜3 kind=scene sceneId=... 分镜号=3 有图=yes 有视频=no
  剧情：...
  图片提示词：... / 视频提示词：... / 负面词：... / 运镜：... / 景别：... / 声音：...
- token=@设定.docx kind=local_file assetId=... mimeType=... 文档摘要：(not generated yet)
  → 用 read_document(assetId=...) 读取
- token=@对话《分镜重做》 kind=conversation conversationId=... messages=23 stale=false
  summary=...
  → 摘要不足时用 read_conversation(conversationId=...) 分片读取
- token=@没有的东西 kind=unresolved 说明：没有找到同名资料，请向用户确认
</turn-references>
```

- **解析失败的 token 显式列出**（`kind=unresolved`）：这是"系统提示词约定 + 用户可能手打 `@xxx`"的必然情况，必须让模型知道"这个引用不存在"，而不是自己编内容。
- 字符预算：整块 ≤ 6000 字符，超出按引用顺序截断并标 `truncated=true`（模型可改用工具读全量），避免「引用一大片」把当前任务挤出上下文。
- 归属：`sceneId` 必须属于该会话 `projectId`，否则归入 `unresolved` 并记日志（不中断本轮，避免"引用错了就发不出消息"）。
- 另提供工具 `read_project_scene(sceneId | sceneNumber)` 供模型按需读全量字段（`StoryboardClient.getScene` 已有能力），并加入只读白名单（`MoonAgentRuntime.java:1101-1137`）。

### 4.4 对话引用：三层「不全读」（核心）

**先排除两条看着省事、实测不能走的路：**

- **不复用 `/compact`（`compactSession`，`MoonAgentRuntime.java:1392-1454`）**：它是**写操作**（`context.clear()/addAll()` 后 `saveAgentState`，`:1439-1441`），会真改掉目标会话的运行时上下文且不可回滚；粒度也不对（只总结老前缀、保留最近 12 条；`messagesBefore <= 12` 直接 `SKIPPED`，短会话拿不到摘要，`:1407-1413`）；`CompactOutcome` 只有计数、**取不到摘要文本**；`COMPACTION_PROMPT`（`:116-132`）是 AIGC 续作字段表，口径不符；`conversationLock` 保护的是 URL 里的会话 id、不是目标会话，并发写同一 state slot 会撞 CAS。
- **不照抄侧边对话**：`cloneSessionState` 是整份深拷贝（`MoonAgentRuntime.java:449-460`），不是低成本引用。
- 数据源只用产品表 `agent_messages`（已有索引 `idx_agent_messages_conversation_created_id`），**不读 `agentscope_sessions`**——这样连从未初始化过 AgentScope 的旧会话也能引用，也不会与 JVM 内 state 缓存互相干扰。

**第一层：会话摘要索引（被动生成，选中即预热）**

- 新表 `agent_conversation_index`（迁移 `V47__agent_conversation_index.sql`，与 `V44__agent_document_index.sql` 同形）：

```sql
CREATE TABLE IF NOT EXISTS public.agent_conversation_index (
    id                     text PRIMARY KEY DEFAULT gen_random_uuid()::text,
    source_conversation_id text NOT NULL UNIQUE REFERENCES public.conversations(id) ON DELETE CASCADE,
    project_id             text NOT NULL,
    user_id                text NOT NULL,
    message_count          integer NOT NULL DEFAULT 0,
    source_hash            character varying(64) NOT NULL,   -- 消息 id 序列 + 末条消息 hash
    summary                text,
    status                 character varying(32) NOT NULL DEFAULT 'generating', -- generating|ready|stale|failed
    model                  character varying(128),
    created_at             timestamp with time zone NOT NULL DEFAULT now(),
    updated_at             timestamp with time zone NOT NULL DEFAULT now()
);
```

- **生成时机（被动，按本次确认）**：**用户从 `@` 菜单选中某个会话的那一刻**，前端立刻 `POST /api/agent/conversations/{id}/reference-summary` 预热（此时用户还在打字，通常等得到）；发送时若仍 `generating`，`<turn-references>` 里给 `summary=(generating)` 并指示模型先用 `read_conversation` 按需读。**不做**「每轮结束自动刷新」——省掉每个会话的一次小模型调用。
- 摘要复用现成能力：**直接调 `DocumentSummaryService.summarize(String markdown)`**（`DocumentSummaryService.java:29-64`：分块并发 + merge + 失败降级，入参就是任意 markdown），不新写分块逻辑。既有参数（`DocumentProperties.java:14-26`：`summaryChunkChars=12000`、`summaryMaxChunks=24`、`summaryConcurrency=3`、`summaryMaxChars=1500`）就是「长文不全读」的现成配置。
  - 前置：把 `agent_messages`（role + content + createdAt）拼成 `用户：… / 智能体：…` 的 Markdown 转写；
  - 摘要口径：新增**引用口径** prompt（区别于文档分析口径），产出：**目标 / 已确认设定与风格 / 关键结论与决策 / 已产出素材（assetId+名称）/ 未决问题**；
  - 模型：照 `SpringAiDocumentSummaryModel` 形状（`ai.agent.documents.summary-model` 默认 `deepseek-flash`、`thinking_level=minimal`、`maxTokens(800)`、超时 60s）新增 `ai.agent.conversations.summary-model`；形状也可照 `ConversationTitleServiceImpl`（显式小模型 + minimal thinking + 失败只记日志不抛出），但它只吃首条消息前 200 字，粒度不对，不能直接复用。
- 失效：`source_hash` 与当前不符即 `stale`；stale 时注入旧摘要并标 `stale=true`，界面显示「摘要已过期 · 点击刷新」（走同一个预热端点）。
- `conversations` / `AgentConversationVO` 既无 `message_count` 也无摘要列（消息数只能 `selectCount`，`AgentChatServiceImpl.java:1893-1898`），这些展示字段全部由新表提供，**不往 `conversations` 加列**。

**第二层：分片精读工具 `read_conversation`**

- 新增 `AgentScopeConversationTools`（与 `AgentScopeDocumentTools` 同形）：
  - `list_referenced_conversations()`：本轮被引用的会话 + 摘要 + 状态；
  - `read_conversation(conversationId, offset, maxChars)`：把目标会话消息转成 Markdown 后按 `offset/maxChars` 分片返回（默认 6000 / 上限 12000，与 `DocumentProperties` 同款配置）。
- 提示词措辞与文档策略对齐（`TurnContextMiddleware.java:260-264` 已有中英双写先例）：
  `对话引用规则：常规引用只用摘要；需要原文、精确表述或具体决策时调用 read_conversation。`
- 工具内归属校验：用 `AgentScopeRunContext.conversation()` 的 `userId + projectId` 与目标会话比对，越权返回 `ok=false`（不抛异常），与 `AgentScopeDocumentTools` 的兜底风格一致。

**第三层（可选，后置）：`conversation-digest` 子智能体**

- 机制已就绪：`AgentScopeSubagents.readOnlyExecutors`（`AgentScopeSubagents.java:24-103`，现有 `creative-planner / prompt-director / reference-curator / continuity-editor`）加一条 declaration 即可，`MoonAgentRuntime.java:327-333` 注册，模型经 `agent_spawn/agent_send/agent_list` 调用。
- 定位：第二层的省 token 包装（子智能体在自己上下文里读分片，主智能体只拿结论）。**默认不上**——主智能体直接调 `read_conversation` 少一次推理调用、成本更低，等出现「摘要不够、要在引用会话里挖细节」的真实反馈再开。

### 4.5 工具注册与权限（漏任一项就"工具不存在"或"弹确认卡"）

| 事项 | 位置 |
|------|------|
| toolkit 注册 | `AgentScopeToolkitFactory.java:108-123`（照 `documentTools.tools().forEach(...)` 加一行） |
| 只读白名单（否则走 `ASK`/HITL） | `MoonAgentRuntime.java:1101-1137` |
| 能力声明（避免提示词承诺不存在的能力） | `TurnCapabilityReporter.java:118-122, 137-140` |
| 每轮注入 | `TurnContextMiddleware.java:130` 附近新增 `appendReferenceContext`；中间件依赖在 `MoonAgentRuntime.java:209-217` 补 |

### 4.6 会话列表接口（供 `@` 菜单第 4 组）

- 复用 `GET /api/agent/conversations?projectId=`（前端 `agentApi.listConversations`，`api/agent.ts:878`），VO（`AgentConversationVO.java:11-24`）**附加**只读字段：`messageCount / summaryStatus / summaryPreview`（前 120 字）；数据来自 §4.4 索引表。
- 新增 `POST /api/agent/conversations/{id}/reference-summary`：选中即预热 / 摘要过期刷新（Controller 只做归属校验 + 调 Service）。
- 归属一律 `userId + projectId` 双条件；不存在与无权统一 40401 同文案。

### 4.7 后端改动文件清单

| 文件 | 改动 |
|------|------|
| `dto/request/AgentReferenceRequest.java`（新增） | 引用入参 record |
| `dto/request/AgentSendMessageRequest.java:10-29` | 新增 `references`（`@Size(max=10)`） |
| `handler/OrchestrationRequest.java:29-106` | 新增 `references` 字段 |
| `ai/agent/AgentChatService.java:182-221` + `impl/AgentChatServiceImpl.java:890-960, 1211-1343` | 六个重载 + 建/续/改三处透传 |
| `service/TurnMediaCanonicalizer.java:44-107` | 透传 references（与 attachments 同形） |
| `ai/agent/scene/SceneReferenceService.java`（新增，接口+impl） | 单镜读取 + 归属过滤（复用 `StoryboardClient.getScene`） |
| `ai/agent/conversation/ConversationReferenceService.java`（新增，接口+impl） | 转写 `agent_messages` → 摘要（复用 `DocumentSummaryService.summarize`）→ 索引；`stale` 判定；分片读取 |
| `entity/AgentConversationIndex.java`（agent-core）+ `mapper/AgentConversationIndexMapper.java`（新增） | 索引实体与 Mapper |
| `agentscope/AgentScopeConversationTools.java`（新增） | `list_referenced_conversations` / `read_conversation` |
| `agentscope/AgentScopeStoryboardTools.java:151-169` 附近 | 新增 `read_project_scene` |
| `agentscope/AgentScopeToolkitFactory.java:108-123` | 注册新工具 |
| `agentscope/MoonAgentRuntime.java:1101-1137`；`:209-217` | 只读白名单；中间件依赖 |
| `agentscope/TurnContextMiddleware.java:130` 附近 | `appendReferenceContext`（`<turn-references>` + `@` 约定兜底声明） |
| `agentscope/TurnCapabilityReporter.java:118-140` | 能力声明 |
| `ai/agent/document/AgentDocumentService.java:60-192`（仅方案 B） | 让读取接受 `mediaId` |
| `dto/response/AgentConversationVO.java:11-24` + 列表查询 | 附加展示字段 |
| `AIStoryboardBackend/src/main/resources/db/migration/V47__agent_conversation_index.sql`（新增） | 建表 + 索引（迁移仍需手工 psql 应用，项目未引入 Flyway） |
| `MoonAgent/src/main/resources/agent-assets/global-system-prompt.txt` | 加 `@` 引用资料约定段落（§二.12）；**classpath 即生效源，无需发布到 Nacos** |

## 5. 前端改动清单

| 文件 | 改动 |
|------|------|
| `components/agent/ChatMentionMenu.tsx`（新增） | 四组浮层：分组、查询过滤、键盘导航、展开/分页。照抄 `ChatSlashMenu.tsx`（`items` 注册表 + `useAbovePopoverPlacement` + portal + window 键盘捕获） |
| `components/agent/ComposerMentionHighlight.tsx`（新增，方案 a） | 与 textarea 同度量的镜像层，把 `@名称` 渲染成蓝色；滚动同步 |
| `components/agent/ChatComposer.tsx` | `mentionQuery/mentionMenuVisible`（基于 `selectionStart`）；菜单渲染并列于 `:905-912`；`onKeyDown` 加 mention 分支（保留 `isComposing`）；并入 `collapsed`（`:861-862`）；插入/删除原子 token（退格删整段）；镜像层挂载与滚动同步；发送时产出 `references`（`:544-584`）；**`+ → 上传分镜` 统一改为插入 `@分镜N` 不自动发送**（`:709-714` 改回传；顺手统一 `请查看 ` 与 `buildSceneInspectPrompt` 的空格差异） |
| `stores/agentStore.ts` | `pendingReferences`（key = `kind:id`，**不能复用 `addPendingAttachments` 的 url 去重**，`agentStore.ts:3686-3692`）；按会话持久化（照 `pendingAttachmentsKey`，`:116-161`，切会话恢复 `:3287-3303`）；token 与正文的一致性（删 token 时同步移除引用）；`sendMessage` 快照与清空（`:3849-3862, 3931`）；**`interruptAndSend` 必须加 opts 并透传**（现在签名是 `(content: string)` 无 opts，`:875, :3416-3473`，`ChatComposer.tsx:564-571` 调用时不传任何参数——不改就是"停止并发送丢引用"）；乐观消息写 `metadata.messageInput.references`（`:3890-3906`） |
| `api/agent.ts` | `AgentReference` 类型；`streamChat` body 增 `references`（`:1245-1260`）；`referenceSummary(conversationId)` 预热接口；会话 VO 新字段类型 |
| `components/agent/MessageBubble.tsx` | 气泡正文里的 `@名称` 渲染为蓝标记并可点击（正文渲染路径；附件 chip 读取路径 `:134-164, :1186-1191` 不动） |
| `components/agent/SceneSelectorModal.tsx` | 确认回调由「立即发送」改为「回传选中分镜」（`onConfirm(scenes)` 签名不变，改调用方） |
| `api/library.ts`（只读复用） | `@` 菜单「项目库文件」内联列表用 `libraryApi.listAssets({ projectId, q, type, page, size })`（`library.ts:193-209`），明细 `getAsset(id)`（`:69-73`） |

## 6. 分阶段交付

- **P0（纯前端 + 无后端新表）**：`@` 菜单 + 蓝色 token（镜像高亮层）+ 四类入口；本地文件/项目库文件走既有 `attachments`；`+ → 上传分镜` 同步统一为「插入标记」；分镜/对话在发送时附带 `references`（后端暂不消费时，正文里的 `@分镜3` 仍能像现在一样被模型理解）。先验证交互与着色。
- **P1**：分镜结构化引用（`references` 全链路 + `<turn-references>` 注入 + `read_project_scene`）+ **项目库文件读取断链修复**（§4.2，否则 `@项目库文档` 只是个装饰）。
- **P2**：对话引用（`agent_conversation_index` + 摘要服务 + 预热端点 + `read_conversation`）+ 列表 VO 字段。
- **P3**：打磨（原子 token 升级 contenteditable、`conversation-digest` 子智能体、上传即预热文档摘要、上限与预算提示）。

## 7. 风险与红线检查

- 不动 `AgentTurnEventPublisher` / `seq` 通道、不动 HITL 终态语义：本方案只扩展**入参**与**system prompt 注入**，不新增事件类型、不新起并发 append（红线 §1/§4）。
- 注入块如涉时间字段，序列化走既有中间件路径；若把 `summaryUpdatedAt` 放进事件 payload，按红线 §3 补 JavaTimeModule + JSON round-trip 测试。
- 归属校验：`sceneId` 属于会话 `projectId`，`conversationId` 同 `userId + projectId`；越权归入 `unresolved` 并记日志，绝不入上下文。
- 上下文预算：`<turn-references>` ≤ 6000 字符、对话摘要 ≤ 1500 字符（`summaryMaxChars`）、单轮引用 ≤ 10 条；超限截断 + 显式 `truncated`。
- **注入内容一律不可信**：`@名称` 由用户输入或资料标题派生，注入时不得当作指令执行（避免把标题里的"忽略之前指令"当真），渲染层只呈现原文。
- 回归面：`+` 菜单、斜杠菜单、拖拽引用（分镜/项目库/素材）、注释 chip、参考图 chip、`pendingAttachments` 清理时机（`ChatComposer.tsx:1348-1371`、`agentStore.ts:3900-3931`）必须保持原行为。
- 着色方案 (a) 的风险集中在字体度量对齐；必须有 `ChatComposer` 层测试 + 手工核对中文换行/滚动/folding（收缩态）三条。

## 8. 测试与验收

后端（`mvn -pl MoonAgent -am test`，聚焦新增）：
- `TurnContextMiddlewareReferenceTest`：`<turn-references>` 渲染、`@` 约定声明存在、字符预算截断、越权与不存在 token 归入 `unresolved`、**无引用时零新增行（零回归）**。
- `ConversationReferenceServiceTest`：摘要缓存命中 / `stale` 判定 / 转写与分块（复用 `DocumentSummaryService` 参数）/ `read_conversation` 分片边界。
- `AgentScopeConversationToolsTest`：按 conversationId 读消息正常返回；**跨用户、跨项目一律 `ok=false`**（防 IDOR）。
- `AgentDocumentServiceTest` 扩展（若走方案 B）：项目库 `mediaId` 文档可读；若无方案 B，则在 A 路径补「项目库文件转存后 `read_document` 成功」的集成测试。
- `AgentSendMessageRequestTest`：`references` 上限 10、`kind` 非法值被拒、`token` 必填。

前端（`cd AIStoryboardClient && npm test -- --run` + `npm run build`）：
- `ChatComposer.test.tsx`：`@` 命中/退出、与斜杠菜单互斥、选中后 token 插入且原子删除、发送后清空、`interruptAndSend` 带引用不丢。
- `ComposerMentionHighlight.test.ts`（方案 a）：token 切分与着色区间、无 token 时输出与原文逐字一致、换行/空行对齐。
- `agentStore.test.ts`：`pendingReferences` 生命周期、按会话持久化、`metadata.messageInput.references` 落库。
- `MessageBubble.test.tsx`：气泡里 `@名称` 渲染为蓝标记且不可注入 HTML。

手工验收（`/chat` 页）：
0. **前置**：`deploy/stack` 或本地进程里的 **MoonAgent 必须用新代码重启**（8084 是常驻 java 进程，改完不重启仍是旧行为）；前端 dev server `cd AIStoryboardClient && npm run dev`（5173）后打开 `/chat`。引用注入、`read_project_scene`、项目库转存都在 MoonAgent 侧，不重启看不到效果。
1. `@` → 本地文件 → 传 docx → 正文出现蓝色 `@xxx.docx` → 发送 → 模型先读文档（`read_document`）再回答；
2. `@` → 项目库文件 → 选一个文档 → 同上（**P1 前此项应失败，修复后必须通过**——这是本方案最容易"看起来好了其实没读到"的一项）；
3. `@` → 分镜 → 选分镜 3 → 发「把分镜 3 的镜头拉近一点」→ 回复基于该镜真实内容，且**没有多余的 `list_project_storyboard` 往返**；
4. `@` → 对话 → 选另一会话（chip/菜单里显示摘要状态）→ 选中即预热 → 发送「沿用那个会话的风格」→ 首轮用摘要；追问细节时模型调 `read_conversation` 而不是全量读取；
5. 边界：手打 `@不存在的东西` 发送 → 模型明确说"没有找到这个引用"，不编内容；引用 11 条被拦；越权 sceneId/conversationId 不进上下文；
6. 着色回归：含 token 的长段落、空行、中文换行、收缩态打字、粘贴带 `@` 的文本，镜像层与 textarea 逐字对齐。

## 9. 决策记录

**已确认（2026-10-16）**

1. **交互形态**：`@` 写进正文并显示为蓝色标记（不是附件卡片）；系统提示词声明「`@` = 引用资料、在可读范围内、必须先去读」。
2. **着色实现**：方案 (a) 镜像高亮层。
3. **`+ → 上传分镜` 统一**：勾完只往正文插入 `@分镜N`，**不再自动发送**；`@` 菜单与 `+` 菜单行为一致。
4. **对话摘要生成时机**：被动——用户从 `@` 菜单选中会话的那一刻预热，不在每轮结束后自动刷新。

**本方案据此自定的实现选择**（如无异议即照此实施）

5. **项目库文件读取修复走 A**：选中时把项目库文件转存为会话内 `agent_assets(reference, kind=file)`，复用既有文档链路（摘要 + `read_document`）；不做 B（改 `AgentDocumentService` 核心读取服务）。
6. **`@` 约定两处都写**：每轮 `<turn-context>` 兜底声明 + 全局系统提示词资产（`agent-assets/global-system-prompt.txt`，classpath 即生效源，不用 Nacos），后者让手打 `@xxx` 也能被正确理解。
7. **`conversation-digest` 子智能体不进 P2**，留到出现「摘要不够、要在引用会话里挖细节」的真实反馈时再开。

**仍需确认的一件事**：本次先做到哪一步（P0 纯前端交互 / P0+P1 含分镜与项目库修复 / 一次做到 P2）。

## 10. 实施进展（2026-10-16）

| 范围 | 状态 | 证据 |
|------|------|------|
| 前端 P0（`@` 菜单四组 + 蓝色标记 + 原子 token + 发送携带 references + `＋分镜`统一） | **已完成** | `ChatComposer.test.tsx` 新增 8 例、`mentionReferences.test.ts` 19 例、`ComposerMentionHighlight.test.tsx` 4 例、`ChatMentionMenu.test.tsx` 9 例、`MessageBubbleMention.test.tsx` 6 例、`agentStore.test.ts` 新增 8 例；前端全量 109 文件 / 881 例通过，`npm run build` 成功 |
| 后端 P1-a（references 契约 + 全链路透传 + metadata 落库 + 续流/编辑重发恢复） | **已完成** | `AgentReferencePassThroughTest` 8 例 + 回归 33 例，`BUILD SUCCESS` |
| 后端 P1-b/c（`<turn-references>` 注入 + `SceneReferenceService` + `read_project_scene`） | **已完成** | `TurnContextMiddlewareReferenceTest` 7 例、`SceneReferenceServiceImplTest` 4 例、`AgentScopeStoryboardToolsTest` 18 例、`TurnCapabilityReporterTest` 9 例 |
| 项目库文档转存（§4.2） | **已完成并接线** | `LibraryReferenceAssetService` 12 例 + 接线回归 `TurnContextMiddlewareLibraryReferenceTest` 4 例（断言以真实 `userId` 调用、成功交出 `read_document` 用的 assetId、失败/抛异常降级） |
| 后端聚焦合计 | **66 例全绿** | `mvn -pl MoonAgent -am "-Dtest=…" "-Dsurefire.failIfNoSpecifiedTests=false" test` → `Tests run: 66, Failures: 0, Errors: 0`，`cmd /c` 复核 `MVN_EXIT=0` |
| 后端全量回归 | **721 例全绿** | 所有并行任务停止后跑 `mvn -pl MoonAgent -am test` → `Tests run: 721, Failures: 0, Errors: 0` / `BUILD SUCCESS` / `MVN_EXIT=0` |
| 全局 `@` 约定 | **已完成，不依赖 Nacos** | 写在 `MoonAgent/src/main/resources/agent-assets/global-system-prompt.txt` §二.12。`GlobalPromptRepository.getPrompt` 是「先读 Nacos，取不到走 classpath 回退」——**Nacos 不用时，classpath 这份就是实际生效的那份**（回退位置由 `NacosAssetProperties.globalPromptFallbackLocation` 指定，默认正是该文件）。此外每轮 `<turn-references>` 里还会再声明一次，所以即使全局提示词被整体替换，带引用的那一轮也不会丢约定 |

**实现期确认的两处细节（与 §2/§5 的差异，均为有意为之）**
1. 输入框外面多了一层 `.chat-composer-input`：镜像高亮层要绝对定位在 textarea 底下，收缩态的可伸缩性（`flex: 1 1 auto`）挂在这一层上；`ChatComposer.test.tsx` 里那条断言 DOM 结构的用例已同步更新。
2. 「引用目录」与正文是**派生关系**：store 里的 `pendingReferences` 只是目录，发送时按「token 是否仍在正文里」过滤（`activeReferences`），插入新引用时也会用同一口径清理目录——因此用户整段删除、全选重写、退格删除都不会留下幽灵引用，也不会让额度被它们占满。**停止并发送**（`interruptAndSend → sendMessage`）不传 opts，靠 store 快照 + 正文过滤兜底，因此不需要给 `interruptAndSend` 加参数（原 §3.1 里「必须加 opts」的判断已被这条更简的实现取代）。
3. 会话引用在本轮**只落 UI**：`@` 菜单第 4 组会列出当前项目其它会话，但项目处于 `disabled` 并提示「引用对话将在下一步（会话摘要）开放」——P1 没有 `read_conversation`，插了标记模型也读不到，宁可不给假承诺。P2 落 §4.4。
4. HITL 续流不带 `references` 字段：引用属于「发起本轮」的那条用户消息、已落 `metadata.messageInput.references`，续流时后端从落库消息恢复（`resumeReferences`），少一个易分歧的真相源。
5. **`references` 的「字段缺失」与「显式空数组」是两种语义**（实现期修掉的第二个幽灵引用路径）：
   - 缺失（`null`）：老客户端/工具调用没带该字段 → 后端从 `previousInput` 恢复，保证「只改正文不改 `@`」的重发不丢引用；
   - 显式 `[]`：调用方明确表示「改后的正文不再引用任何东西」→ **必须尊重，不得恢复**。原实现用 `requestedReferences == null || isEmpty()` 判定（`AgentChatServiceImpl.prepareEditedTurn`），会把用户的「删掉标记再重发」复活成旧引用。
   - **坑中坑**：`streamAgentScopeTurnInternal` 在进编辑分支之前还有一处 `references == null ? List.of() : references` 归一（与 `attachments` 同形），它会把「字段缺失」提前变成「显式空数组」，于是 `prepareEditedTurn` 永远看不到 `null`。修语义时必须**同时**去掉这处归一（`references` 原样下传，非编辑路径单独把 `null` 当空表用），否则测试会以「恢复没生效」的形式失败。
   - 前端配套：`editAndResend` 用**这条消息自己的** `metadata.messageInput.references` 与编辑后的正文求交集后显式下发（不取 composer 快照），所以「留下了 `@分镜3`」→ 引用仍在，「删掉了」→ 下发 `[]` 且不再复活。
   - 回归：前端 `agentStore.test.ts`（编辑重发两条）、后端 `AgentReferencePassThroughTest`（`editedTurnTreatsExplicitEmptyReferencesAsAuthoritative` + 原有「字段缺失则恢复」用例）。

**遗留与风险（明确不做的部分）**

| 项 | 说明 |
|----|------|
| 会话引用（P2） | `@` 菜单第 4 组只列 UI 且置灰；`read_conversation` + `agent_conversation_index` 未实现。要开启必须按 §4.4 做，**不要**复用 `/compact`（写操作，理由见 §4.4） |
| Nacos（不使用） | 本功能**不依赖 Nacos**：`nacos-assets.json` 保持原样（`globalPrompt.version=1.0.0`，无 diff），不需要跑 `scripts/nacos/publish_assets.py`。将来若真要把这份提示词发到 Nacos，需先把版本提一版——发布脚本按「版本 + 内容 hash」校验，同版本不同内容会直接报 `Nacos prompt hash mismatch` |
| 转存副本的生命周期 | 转存出的 `agent_assets` 与 MinIO `files/` 对象走既有会话资产生命周期，「清空上下文」保留产出素材 → 转存副本不会被自动回收。如需回收要另开一条清理（本次刻意不动，避免与清理任务冲突） |
| `TurnCapabilityReporter.storyboardTools` | 注入后未被使用（判定走常量名/注册表），是无害的留痕字段；清理它需要同时改 `TurnCapabilityReporterTest` 的构造点，本次未动 |
| `read_project_scene` 的归属粒度 | 只校验「属于当前会话的项目」，不校验 userId——与同文件既有 `list/update/delete_project_storyboard` 完全同形（会话本身已按 userId 归属） |
| 运行时唯一未验证假设 | 转存依赖「MoonAgent 能按 `objectKey` 直读 asset-service 写入的对象」。已核对两侧配置同为 `storyboard` bucket / 同 endpoint / 同凭据（`MoonAgent/application-local.yml:12-16` vs `asset-service/application-local.yml:30-34`），但**真实点击验收仍需重启 MoonAgent 后确认**；若凭据不一致会退化成「正文不可用」并留 warn 日志（可据此定位） |
| 行为变更 | `＋ → 上传分镜` 从「勾完即发」改为「只插入 `@分镜N`」（已确认）；`SceneSelectorModal.onConfirm` 语义改为回传选中分镜 |


