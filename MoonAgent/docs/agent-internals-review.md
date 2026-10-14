# Moon 智能体内部设计审查：PTC、输出契约、渲染意图、fork、waterfall

> 对象：MoonAgent 的**引擎内部设计**（工具选型见 `agentscope-capability-upgrade.md`）。
> 方法：AgentScope 2.0.4 的类与签名由 `javap` 从 jar 直接读出（`agentscope-core` +
> `agentscope-harness`，共 922 个类）；Moon 侧为源码只读审计，证据为 `path:line`。
> **未运行任何测试，未做运行时验证。** 标注"未核实"的项为审计无法从源码确认者。

---

## 0. 结论表

| 你的设想 | 判断 | 一句话理由 |
|---|---|---|
| PTC / `run_code` 通用编排 | **不要做通用版** | 框架无执行面；净收益只有 **1–2 次往返**（并行扇出你们已实现）；代价是权限/审计/事件/取消 7 条机制同时退化 |
| 改做「复合任务工具」 | **推荐** | 一次请求 → 一次**逐项**审批 → 一个持久扇出任务，复用现有 task rail |
| 工具输出契约 | **最该先做，且现状比"没有契约"更糟** | 失败被 `guard()` 包装成 `ok:true`，模型与审计**同时**被误导 |
| 工具自描述呈现（render intent） | **该做，框架有原生载体** | 模型通道与 UI 通道现在是两套手写字符串，必须收敛到一个结构 |
| Subagent fork | **可行但纯 DIY，且对现有 5 个子智能体不划算** | `SubagentFactory` 由你实现 + `AgentState.context` 可截断复制；框架无此特性、无边界检查 |
| 前端按工具硬编码 + 重放不一致 | **前提有误；真问题更简单也更严重** | 卡片早已按 `kind` 数据驱动（仅 10 处工具名比较，且其消费者从未挂载）；真问题是**工具结果根本没渲染**（`toolDrafts` 无消费者）+ replay 窗口丢历史/缺口永久卡住 + live-only 状态 |
| waterfall / 单调 guard / deferContext | **全部有框架落点** | `MiddlewareBase` 是 CPS waterfall；`PostActingEvent` 可改写并 `stopAgent()`；`ContextStore` 是 deferContext 载体 |

---

## 1. PTC / `run_code`：结论是"别做通用版"

### 1.1 框架层面：没有执行面

对 922 个类全量筛查，AgentScope 2.0.4 **没有** DSH 那种 `ctx.codeRuntime`：
无 code-runtime / interpreter / REPL / python 工具类，只有
`io.agentscope.core.tool.coding.{ShellCommandTool, CommandValidator, UnixCommandValidator,
WindowsCommandValidator}` 与 harness 的 `ShellExecuteTool`。
PTC 在这里是**自研项**，不是配置项。

### 1.2 并行收益你们已经拿到了，PTC 只剩 1–2 次往返

AgentScope 已有现成扇出-收集机制，且你们已开启（`AGENTSCOPE_ASYNC_ENABLED:true`）：
`AsyncToolRegistry`（`register/complete/fail/findStale/markTimeout`）、
`AsyncToolRecord{RUNNING,COMPLETED,FAILED,TIMEOUT}`、
`WaitAsyncResultsTool.waitForResults(count, ...)`、
`TaskRepository`/`BackgroundTask`（可 `cancel`、完成回调、delivery 标记）。

审计实测：`generate_image_batch`、`start_storyboard`、`generate_storyboard_images/videos`、
`merge_storyboard_videos`、`run_python_script` **全部已经是"立即返回 queued ack + 持久任务"**，
每 slot 状态落在 DB 的 `result_json.slots`（`AgentScopeAsyncTaskService.java:1055-1082`），
可取消、可唤醒。**缺的只是"模型等待并串接"，不是并行执行。**

> 因此对"5 张图 + 分镜 + 合成"这类请求，代码编排**最多省 1–2 次往返**
> （省在工具结果的重复下发与再推理，不在于并行）。收益远小于直觉。

### 1.3 代价：七条机制同时退化（逐条证据）

审计给出的决定性事实：**工具边界就是进程边界**（MoonSandbox 是 :8090 独立 HTTP 服务，
`MoonSandboxClient.java:40-158`），而所有安全属性都挂在唯一受审计的调用漏斗
`AgentScopeToolPipeline.executeMap`（`AgentScopeToolPipeline.java:132-163`）
与**按外层工具名匹配**的权限规则（`MoonAgentRuntime.java:1052-1191`）上。

1. **权限/HITL**：规则按 toolkit 注册的**外层工具名**匹配（`MoonAgentRuntime.java:1064-1070`）。
   代码内嵌套调用根本到不了 `PermissionContextState` → 要么用外层批准**静默放大用户授权范围**
   （违反"批准只覆盖本次列出的操作"，`AgentScopeCheckpointService.java:139`），
   要么嵌套的高危工具根本跑不了、`run_code` 变成后门。
   同时 HITL 卡片塌缩成一张不透明卡（`MoonAgentRuntime.java:805-810` → `AgentScopeCheckpointService.java:90-131`），
   **"拒绝第 3/5 步"无法表达**（终态拒绝的语义单元只有外层工具，`AgentChatServiceImpl.java:1393-1398`）。
2. **审计**：嵌套若直接调 service bean（现有工具就是这么干的），
   `agent_tool_invocations` **零行**写入；要补救必须给每个嵌套步骤合成带独立 id 的
   `ToolCallParam`/`ToolUseBlock` 重新进入 pipeline，否则 `invocation_key` 冲突/为空
   （`AgentScopeToolPipeline.java:259-269`）。
3. **事件**：嵌套不产生任何 `ToolResultStart/EndEvent` → 恰好一个外层 spinner + 一个不透明结果；
   `AGENT_TRACE` span、`TOOL_*` 时间线、以及挂在 `ToolResultEndEvent` 上的文档标记事件
   （`plan_updated`、`media_critique`、`assembly_progress`、`shot_progress`，
   `AgentChatServiceImpl.java:1782-1797`）**全部不再触发**。
   且从工具线程直接 `store.append` 正是红线 #1 禁止的乱序回归。
4. **产物**：`SandboxOutputReconciler` 只扫 `output/`（`SandboxOutputReconciler.java:33`）；
   写在别处的文件永不发布。**而且它的"每轮末尾扫一次"今天就已经在赛跑异步生产者**
   —— 异步路径是自己从执行结果发布的（`AgentScopeAsyncTaskService.java:1795-1806`），
   唤醒回合是否会补扫，**未核实**。
5. **取消**：可靠粒度只有 turn 和 task；`AgentSandbox` 客户端**没有 cancel API**（`MoonSandboxClient.java:40-158`）。
   于是"取消第 3/5 步"没有载体，`run_code` 会把取消从"下一个副作用前停"降级为"当前脚本跑完才停"。
6. **可观测性**：异常被 `executeMap` 捕获后写审计再**重抛**（`AgentScopeToolPipeline.java:157-162`），
   一路到 `MoonAgentRuntime.java:812-815` → 整个 turn 失败；
   AgentScope 是否把它转成模型可读的 ERROR 结果，**未核实**。
7. **成本**：批量任务的**逐项失败在返回 payload 里不可见**（只回 ack），
   代码编排会把这种"看不见的失败"放大 N 倍。

### 1.4 更正：嵌套调用"绕过预算"是误报，但视频确实没有预算

审计原文称"分镜媒体嵌套调用绕过了预检与媒体预算"。**实施前逐行核对后，这个结论大部分不成立**，
记录更正如下（证据均为 `path:line`）：

| 说法 | 核对结果 |
|---|---|
| 嵌套分镜**图片**生成绕过媒体预算 | **误报**。`generateImageForStoryboard` 委托给同一个私有 `generateImage`（`AgentScopeImageTools.java:112-123` → `:138`），`beginMedia("image", 2)` 就在其中（`:156-160`）。嵌套路径**同样消耗预算**。 |
| 嵌套分镜**视频**生成绕过媒体预算 | **部分成立但归因错误**。`beginMedia` 在整个 `agentscope` 包内**只有** `AgentScopeImageTools:157, 245` 两处调用；`AgentScopeVideoTools` 从不调用它。所以不是"嵌套绕过"，而是**视频在顶层与嵌套路径上都没有 per-turn 媒体预算**。 |
| 嵌套调用绕过 `MediaToolPreflightService` | **成立**。`MEDIA_TOOLS` 只含 `generate_image` / `generate_image_batch` / `generate_video`（`MediaToolPreflightService.java:35-38, 48-50`），分镜媒体工具不在其中。 |
| 因此这是"已经发生的洞，必须马上修" | **不成立（判断部分）**。分镜媒体提示词由分镜草稿/shot 规格程序化构造，而 preflight 的 `REFERENTIAL_PHRASES`（"这张/刚才/上一张"）是针对自由文本指代的；分镜流程另有审批卡与跨流程守卫（`AgentScopeImageTools:151-155, 240`、`AgentScopeVideoTools:160` 的 `isStoryboardRequested()`）。此外前端队列走的是 `StoryboardQueueRequestGuard`（`service/StoryboardQueueRequestGuard.java`），与 agent 工具路径不是同一条。 |

**结论**：不做投机性"守卫"改动。如果产品希望"每轮每类媒体只允许一次"的不变量扩展到视频，
最小正确改动是在 `startVideo` 里加 `beginMedia("video", N)` —— 这是一个需要产品决策 + 独立测试的变更，
不应作为静默修复塞进去。**这条从"立刻修"降级为"待产品确认"。**

### 1.5 建议：复合任务工具，而不是通用 `run_code`

审计推荐并被本审查采纳的方案：

1. **一个用户旅程一个复合入口工具**（如 `storyboard_produce_and_render`），
   在**一个 `AgentAsyncTask`** 内串起 `startStoryboardTask` → 图 → 视频 → `merge`，
   用 parent/child 任务行 + `waiting_parent`（`AgentScopeAsyncTaskService.java:166-177, 205-213`）。
2. **审批仍然一次，但逐项列出**：把计划中的嵌套步骤枚举进 `pauseForConfirmation` 的
   `toolCalls` payload（`AgentScopeCheckpointService.java:90-131`），
   让"批准一次"仍然等于"批准了这些具体操作"。
3. **复用守卫**：复合任务内部继续走 `AgentScopeTurnState.beginMedia` 预算与 `MediaToolPreflightService`。
4. **进度只经 `AgentTurnEventPublisher.publishRaw`**（`AgentTurnEventPublisher.java:87-91`）
   按 slot 发布，绝不从任务线程 `store.append`。
5. **产物一律写沙箱 `output/`**，必要时扩展 reconciler 支持"晚写补扫"。

---

## 2. 输出契约：现状比"没有契约"更糟

### 2.1 四个结果构造点，没有共享工厂

| 位置 | 行为 |
|---|---|
| `AgentScopeTool.java:41-49` | 所有自研工具：`ToolResultBlock.of(callId, name, TextBlock(toJson(output)))`，**单 TextBlock，无 DataBlock** |
| `CanvasRunToolAdapter.java:92-97` | 自己拼块；`write` 失败 → `"{}"`（`:172-178`） |
| `SpringAiToolAdapter.java:40-43` | 从 ToolCallback 原始字符串拼块；null → 空串 |
| `MoonAgentRuntime.java:631-633` | HITL resume 的结果 |

`toJson`（`AgentScopeTool.java:70-78`）：null → 字面量 `"{}"`；String → 原样；
Jackson 失败 → **`String.valueOf(value)`**（可能把 `java.util.LinkedHashMap@1a2b3c` 喂给模型）。
输入侧有 JSON schema（`:62-68`），**输出侧零 schema、零 `ToolResultConverter`、零校验**（grep 0 命中）。

### 2.2 最该先修的一条：失败被伪装成成功

- 所有 storyboard 工具的逻辑失败是 `Map.of("ok", false, ...)`（如 `AgentScopeStoryboardTools.java:359-363, 455-461`），
  但被 `guard()` 包成 **`{ok:true, result:{ok:false, ...}}`**（`:1151-1158`）。
- 后果有两层：**模型读到 `"ok":true`**；连审计摘要器也记成 `COMPLETED`
  （`ToolAuditSummarizer.java:75`）。
- 同类：`update_creative_context` 把畸形 JSON 静默置 null 后仍返回 `ok=true`
  （`AgentScopeCreativeTools.java:256-258`）；`CanvasRunToolAdapter` 把非 JSON 上游输出
  标成 `{ok:true, value:<raw>}`（`:109-121`）。

**这不是"缺少契约"，而是"失败被主动掩盖"。** 任何 PTC/批量化都会把它放大。

### 2.3 其他已核实的畸形通道

- `get_agent_task` → 未知/越权 taskId 返回 null → 模型看到 `{}`，无 ok/status/error
  （`AgentScopeSessionTools.java:131-135` + `AgentScopeAsyncTaskService.java:606-610`）。
- 无界输出：`list_project_storyboard`（`AgentScopeStoryboardTools.java:158-168`）、
  `list_conversation_documents`（`AgentScopeDocumentTools.java:45-47`）、文档正文（`:81`）无上限；
  唯一的兜底是 harness 的 tool-result eviction（`maxResultChars=80_000`，
  `AgentScopeProperties.java:40-42`），那是**事后截断，不是校验**。
- 审计是唯一读输出的人（`audit/ToolAuditSummarizer.java:67-86` + `ToolExecutionTemplate.java:55-82`），
  它**从不改模型可见文本、也从不拒绝**。

### 2.4 框架给的契约缝（可直接用）

| API | 作用 |
|---|---|
| `ToolResultConverter.convert(Object, Type) → ToolResultBlock` | 单一转换缝 |
| `DefaultToolResultConverter`（`handleNull`/`handleVoid`/`serialize`） | 已定义 null/void 语义 |
| `@Tool(converter = ...)` | 每工具可声明转换器 |
| `ToolResultBlock{id,name,List<ContentBlock> output,metadata,state}` + `text/error/of/suspended/withState` | 结果是一等类型 |
| `ToolResultState{SUCCESS,ERROR,INTERRUPTED,DENIED,RUNNING}` | 状态词汇（单调 guard 基础） |
| `ToolBase.checkPermissions/matchRule/generateSuggestions` | 工具**自述**权限判定与建议规则 |
| `@Tool(readOnly, concurrencySafe, dangerousFiles, dangerousDirectories, stateInjected, returnDirect, strict)` | 元数据，替代硬编码清单 |

### 2.5 设计要求

1. **一个信封**：`MoonToolResult<T>` = `status`（`ok|error|needs_confirmation|queued|partial`）
   + `summary`（模型读）+ `data` + `render`（UI 读）+ `refs`（资产/文件定位符）+
   `error{code,message,hint}`。
2. **一个转换器**：实现 `ToolResultConverter`；禁止各工具自己拼 `ToolResultBlock`。
3. **扁平化 guard**：`guard()` 不得把内层 `ok:false` 包成外层 `ok:true`；
   状态必须**向外传播**而不是被吞。
4. **进模型前校验**：schema/字段/大小；失败返回 `ToolResultBlock.error(可读诊断 + hint)`，
   绝不把原始垃圾或 `Object@hash` 喂给模型。
5. **失败显式**：`ToolResultState.ERROR/DENIED/INTERRUPTED` + 可读中文诊断；
   异常栈永不进上下文。
6. **统一状态消费者**：`ToolAuditSummarizer` 与 UI 通道消费**同一**状态枚举。

---

## 3. 前端渲染：前提需要修正，真问题更严重

### 3.1 修正：不是"每个工具一个硬编码组件"

审计实测（`AIStoryboardClient`）：

- `src/components/agent/` 有 **61 个非测试 `.tsx` / 63 个导出组件**，但**卡片选择早已是数据驱动的**：
  按 `metadata.kind` + payload 字段，见 `MessageBubble.tsx:1107`（`kind==='canvas-run'`）、
  `:1464`（`kind==='video-plan'`）、`:1447`（`metadata.hitl && !pending`）、`:1810`（`taskCards`）。
- 真正的工具名硬编码只有 **10 处比较 / 5 个名字**，集中在一个 mapper
  （`agentStore.ts:1601-1618, 2126`：`plan_enter|plan_write|plan_exit|todo_write|deliver_artifact`）。
- 而**这个 mapper 的消费者 `AgentScopeTimeline.tsx` 从未被挂载**（仅测试引用）
  → 那 10 处比较实际上是**死代码**。
- 审计估计：卡片主体已 ~85% payload 驱动；若服务端下发 `renderIntent`，
  可把 `MessageBubble` 的 3 个 early-return + 2 个内联分支与 store 的 mapper
  收敛为一次注册表查表，约等于**消掉 90% 的分发逻辑**。

**所以要做的不是"把 60 个组件改成数据驱动"（已完成 85%），而是下面三件事。**

### 3.2 真问题一：工具结果根本没渲染（最严重，且不是重放问题）

`TOOL_*` 事件写进 `generating.toolDrafts`（`agentStore.ts:2105-2120`），
而 **`toolDrafts` 没有任何组件消费**（唯一读取点 `:2123` 是它自己）。
后果：工具返回内容在**直播与重放里都不可见**——不是"重放不一致"，而是两条路径都没有。
这与 §2 的"模型通道 / UI 通道互不派生"叠加，就是"模型看到 X、用户看到 Y"的完整解释。

### 3.3 真问题二：replay 窗口会丢历史，且缺口会永久卡住

- `accept`（`:195-214`）只在 `pendingByTurn` 缓存连续段，**无定时器、不主动补拉缺口**；
  SSE 路径遇缺口就**永久等待**（终端事件被扣住）——这正是 `CLAUDE.md` 红线里
  "看起来卡住"的机制来源。
- `afterSeq` 由已持久化事件推导、`seek` 跳过它们（`:1291-1305`、`:221-226`），
  `accept` 丢弃 `seq <= last`（`:198`），而 `replayTurnEvents` 默认 `afterSeq = lastSeq`（`:2215`）
  → **窗口之前的事件永远无法被 reduce**：刷新后文本/推理/工具历史消失。
- 缺前置事件的自愈能力不完整：无 `turn_started` ⇒ `effects.started=false`（`:1782`）；
  无 `TOOL_CALL_START` ⇒ draft 由后续首个事件建空壳（`:2106`）；
  无 `turn_paused` ⇒ 卡片不出现（除非 `recoverPendingCheckpoint`，`:2318-2320, 2843-2891`）。

### 3.4 真问题三：live-only 状态清单

| 只在直播存在的状态 | 位置 |
|---|---|
| "思考 N 秒"计时 | `ReasoningPanel.tsx:33-37`、`LatticeLoader.tsx:135-145`（`performance.now()` 起算） |
| `reasoningDraft`/`traceDraft`/`usageDraft` | `agentStore.ts:1931-1942`（除非终端 metadata 回带） |
| 打字机 `useStreamingReveal` | `MessageBubble.tsx:790-839` |
| 视频"已等待约 X 秒" | `agentStore.ts:992-998, 4233-4234` |
| `confirmResult` 瞬态 | `:2125, 2239-2241, 3487` |
| `timelineByConversation`（内存态，消费者未挂载） | `:587, 1475-1492` |
| 任务卡本地写入 vs 整体替换 | `attachTaskCard` `:951-973` vs `refreshConversationMessages` `:873-877` |

### 3.5 幂等性：一个洞 + 一处位置依赖

- **HITL 有幂等键**：`formToken` + sessionStorage 回执（`:1149-1207, 1222-1237`），
  重放与刷新各有一道闸（`:1909-1910, 2849-2855`）。
  **但 `formToken` 为空时回执检查失效**（`:1205`）而 `waitingHumanInput` 被重新置位（`:1913`）
  → **重放可复活一张可点击的卡片**（正对红线 #4）。
- **产物卡片无幂等**：服务端按 `assetId` 去重（`AgentChatServiceImpl.java:1898-1946`、
  `SandboxArtifactPublisher.java:80-107`），但前端 React key 用 `url-index`（`MessageBubble.tsx:263`）
  而忽略 `assetId`（`api/agent.ts:45`）→ **位置依赖**。

### 3.6 框架载体（不变，可直接用）

- `DataBlock{Source, id, name}` + `DataBlockStartEvent/DeltaEvent/EndEvent`（可流式结构化数据）。
- `HintBlock{id, hint, source}` + `HintBlockEvent`：给模型的提示，独立于结果内容。
- `ToolEmitter.emit(ToolResultBlock)` + `Toolkit.setChunkCallback(...)`：执行中就吐结构化结果。
- `@Tool(returnDirect=true)` + `MessageMetadataKeys.TOOL_RETURN_DIRECT`：结果直接面客。

### 3.7 设计要求

1. `render` 进**持久化 payload**：`kind` + `items[]` + `status` + 稳定 id + 版本号。
2. **先给 `toolDrafts` 接上一个消费者**——否则任何 render intent 都是空转。
3. 卡片幂等改用 `toolCallId` / `assetId`，去掉 `url-index` 位置依赖。
4. `accept` 的缺口策略增加**超时主动补拉一次 `afterSeq`**，替代"永久等待"。
5. 终端 metadata 回带 live-only 字段（`reasoning.durationMs` 等），补齐重放。
6. 空 `formToken` 一律视为不可复活。

---

## 4. Subagent fork：能做，但先问值不值

### 4.1 框架事实（已核实）

- `SubagentDeclaration.Builder` 的全部字段**没有**"继承父上下文"：
  `name/description/workspaceMode/workspace/inlineAgentsBody/model/maxIters/steps/temperature/topP/variant/mode/hidden/persistSession/inheritParentPermissions/exposeToUser/enablePendingToolRecovery/tools/skills/url/headers/remoteStreaming`。
- `SubagentFactory.create(RuntimeContext) → Agent` **由你实现** → 这是 fork 的缝。
- `AgentState` 有 `List<Msg> context`、`summary`、`curIter`、`permissionContext`、`toolContext`、
  `tasksContext`、`planModeContext`，可 `toJson/fromJson`；你们用 `PostgresAgentStateStore`。
- `DefaultAgentManager.invokeAgent(agent, name, prompt, sessionId)` 已按 sessionId 调用。
- `WorkspaceMode{ISOLATED, SHARED}` 控制子工作区。

**fork = 读父 `AgentState` → 截断到边界 → 以子会话 id 写入。可行，纯 DIY。**
框架**不提供** DSH 那条"边界必须落在未闭合 turn 之外"的约束检查——必须自己加断言，否则会 fork 出半截 turn。

### 4.2 对现有 5 个子智能体不划算

`AgentScopeSubagents.readOnlyExecutors()` 的 5 个是：只读、窄工具白名单、
`WorkspaceMode.SHARED`、`inheritParentPermissions(true)`、要求返回紧凑 JSON。
灌父上下文会：抬高 token（它们靠"窄"才便宜）、引入无关信息**降低 critic 判断质量**、让输出契约更难稳定。

**fork 值得的两个场景**：用户显式"继续/基于上次结果"的**续跑型**任务（对应 async task + wakeup）；
以及子智能体必须看**原始素材**（参考图、分镜 JSON）而非摘要时。
其余继续用"窄上下文 + 显式入参"。

---

## 5. waterfall / 单调 guard / deferContext：全部有框架落点

### 5.1 waterfall：`MiddlewareBase` 本身就是 CPS waterfall

```java
Flux<AgentEvent> onAgent|onReasoning|onActing|onModelCall(
    Agent, RuntimeContext, <Input>, Function<Input, Flux<AgentEvent>> next);
Mono<String> onSystemPrompt(Agent, RuntimeContext, String);
```

可改写输入 → 调 `next` → 改写事件流；不调 `next` 即短路；`MiddlewareChain.build(...)` 负责组合。
`ActingInput` 是 `record List<ToolUseBlock>` → **可在 acting 前改写/注入/抑制整批工具调用**。

`Hook.onEvent(T) → Mono<T>`（可改事件对象）+ `priority()` + `tools()`；
`PreActingEvent` / `PostActingEvent`，后者含
`getToolResult()/setToolResult()/getToolResultMsg()/setToolResultMsg()/stopAgent()`。

**现状**：`AgentScopeToolPipeline` 是**扁平装饰器**（`wrap`/`wrapGeneric` → `withHandler`/`AuditedToolBase`），
内部顺序硬编码（`executeMap`：画布拒绝 → 输入默认值 → ledger.start → beforeTool →
preflight.guard → 幂等 → 调用 → afterTool → finish，`AgentScopeToolPipeline.java:132-163`），
**层与层之间没有"下一层可改写/短路"的语义**。

> **实施时的重新定scope（2026-10-14）**：读完代码后我**没有**把 pipeline 改写成
> `MiddlewareBase.onActing` + `PostActingEvent`，原因有两条，请以此为准：
> 1. **会丢上下文**。preflight 需要单个工具的具体入参、审计需要 per-call 的
>    `ToolInvocationScope`，而 `onActing` 拿到的是**整批** `List<ToolUseBlock>`；
>    把 per-tool 拦截上提到批级会让这两件事变难，是负收益。
> 2. **收益不在当下**。waterfall 真正独有的能力是"after 阶段改写结果 / `stopAgent()`"，
>    而自研工具的结果改写已经在 per-tool 包装里做了；框架工具（read_file 等）**不能**套用
>    契约信封（那会把每个 read_file 的纯文本包成 JSON，膨胀上下文并干扰模型），
>    所以框架工具只做审计、不改写。
>
> 因此 L2 实际执行的是**有价值且安全的那一半**：① 单调 guard（生命周期状态只能前进、
> 终态后拒绝 late write、违反即显式失败）；② 消掉三重归一（`asMap` → `executeMap` → `callAsync`）。
> `PostActingEvent` 这条缝**留到 L3 的 deferContext**，届时确实需要"结果改写"能力再接，
> 而不是先摆一个没人用的 pass-through 中间件。

### 5.2 单调 guard：框架给词汇，guard 自己写在发布口

- 状态词汇 `ToolResultState{RUNNING→SUCCESS|ERROR|DENIED|INTERRUPTED}`。
- 需自建的不变量：① 工具状态只能前进，终态后拒绝 late write；
  ② `AgentTurnEventPublisher` 保持唯一发布口（红线 #1）；
  ③ 终端事件不得先于其依赖事件；④ 违反即**显式失败**（沿用现有 failure latch 模式）。
- 额外一条（本次审计新增）：**内外状态必须单调传播**，禁止 §2.2 那种 `ok:false → ok:true` 的反转。

### 5.3 deferContext：`ContextStore` + `PostActingEvent`

- 载体：`ToolExecutionContext{get/contains/merge/getStores}`、`ContextStore`、
  `@Tool(stateInjected=true)`、`ToolCallParam.getContext()/getEmitter()`。
- 用法：复合工具把嵌套调用的结构化上下文挂在自己的结果 metadata/ContextStore 上，
  由 `PostActingEvent` 在 **tool/result 之后**一次性按序追加进 transcript。
- 效果：嵌套**永不与父级 transcript 交错** —— 这是保住 `seq` 契约、也是未来任何编排的前提。
- 现状：这些原语**零使用**（审计确认 `ToolExecutionContext`/`ContextStore`/`ToolEmitter`/`ExtendedModel`
  均 0 命中）；状态目前走自定义 `AgentScopeRunContext` record。

---

## 6. 通往 DSH 能力的分级路线（忽略代码交付）

| 级别 | 目标 | 主要改动 | 验收 | 不碰的红线 |
|---|---|---|---|---|
| **L0** | 杀掉"失败伪装成功" + 输出契约 | 统一 `MoonToolResult` + `ToolResultConverter`；`guard()` 扁平化传播 `ok:false`；`toJson` 的 null/序列化失败改为显式 error；统一状态消费者 | 每个工具有 schema 测试；`ok:false` 用例在模型与审计两侧都显示失败 | — |
| **L1** | 让工具结果可见 + 修重放 | ① 给 `toolDrafts` 接消费者；② `accept` 缺口加超时主动补拉；③ 终端 metadata 回带 live-only 字段；④ 卡片幂等改用 `assetId`；⑤ `render`（kind/items/status/id/版本）进 payload | 断线重连、刷新、`afterSeq` 重放三种路径渲染一致（快照测试）；工具结果可见 | #1、#4 |
| **L2** | 单调 guard + 去掉冗余归一 | `ToolCallStateGuard`（`RUNNING→{SUCCESS,ERROR,DENIED,INTERRUPTED,NEEDS_CONFIRMATION}`，终态后 late write 显式失败）接入 `executeMap`；`asMap`/`callAsync` 不再重复归一 | 状态回退被拒（新测试）；模型可见 JSON 逐例不变；`seq` 连续不变 | #1、#4 |
| **L3** | 事件作用域（**只剩这一半**） | 并入 L4：事件 payload 加 `parentToolCallId`（供 per-slot 进度挂回外层工具卡） | 复合任务的 slot 进度在 UI 可折叠归组；`seq` 仍连续 | #1 |
| **L4** | 复合入口工具 + 逐项审批（**替代通用 PTC**） | ① 复合入口工具（串 start→图→视频→merge 进一个任务，复用**已存在**的 parent/child 底座）；② 逐项审批卡枚举嵌套步骤 | 一次请求的往返数下降；嵌套步骤仍逐项可审、可取消、进度经 publishRaw | #1、#3、#4、#5 |

> **L3 再次收窄（2026-10-14 第二次定 scope）**：只剩 `parentToolCallId` 这一半，且并入 L4。
> `ContextStore`/`stateInjected`/`deferContext` **无限期搁置**，理由：
> 它们是给"**工具调用工具**"准备的（把嵌套调用的上下文寄存到父结果上、循环在 tool/result 之后追加），
> 而 §1.4/§1.5 的结论恰恰是**不要做真嵌套工具调用**（会绕过权限、审计与事件粒度）。
> L4 采用的形态是"一个复合工具 + 一个持久扇出任务"，其子步骤是**任务 slot，不是工具调用**，
> 因此不需要 ContextStore。为一条我们明确不走的路线预置机制，就是投机性管线——
> 与我在 L2 拒绝摆一个没人用的 pass-through 中间件、在 Bug A 拒绝加一个无意义的澄清门是同一个判断。
> `parentToolCallId` 则有真实生产者：复合任务的 per-slot 进度事件需要挂回外层工具卡，UI 才能折叠分组。

### L4 的前置事实（2026-10-14 核查，**改变了 L4 的工作量**）

- **parent/child 任务底座已经存在**，不需要新建：`AgentAsyncTask.getParentTaskId()`、
  状态 `waiting_parent`、以及父任务收口时的 `releaseChildren(parentTaskId)`
  （`AgentScopeAsyncTaskService.java:2198-2212`；子任务状态由父状态决定，`:812`）。
  也就是说 §1.5 建议的"parent/child 任务行 + `waiting_parent`"**已经在生产代码里跑着**（部分重试流程在用）。
- **per-slot 进度也已经存在**：批量图片/长任务把 slot 状态写在任务的 `result_json.slots`
  并推给 `AgentTaskEventHub`（不是走 `store.append`），所以"进度经 publishRaw"这条要做的只是**核对**，不是重写。
- **因此 L4 真正的新增只剩两件**：① 一个**复合入口工具**（把 start → 图 → 视频 → merge 串进一个任务）；
  ② **逐项审批卡**（在计划卡里枚举将要执行的嵌套步骤，让"批准一次"仍然等于"批准了这些具体操作"）。
- ⚠️ **①和②都是产品面变更，不是纯技术实现**：它们会把现在"分镜审批 → 逐镜生成 → 审查卡"的**多卡流程**
  收敛成"一张逐项计划卡"。这需要产品决策（哪个用户旅程值得合并、是否接受卡片形态变化），
  因此本审查**不擅自发明**新的顶层工具。

  **✅ 产品决策已给出（2026-10-14，仓库所有者裁定）**，原文：
  > 可以做，如果用户提到这个流程就会执行这个复合旅程。一般都是生图然后视频，
  > 视频如果超过了15s以上，就走分镜走图片再走视频。

  落成规格：**一个意图驱动的复合旅程工具**，内部分两条分支 ——

  | 分支 | 触发 | 步骤 |
  |---|---|---|
  | 短 | 目标视频时长 **≤ 15s** | 生图 → 视频 |
  | 长 | 时长 **> 15s**（或用户明确要分镜流程） | 分镜 → 逐镜图片 → 逐镜视频 → 合成 |

  `15` 必须是**配置项**（`AgentScopeProperties` + `application.yml` + env 覆盖），不得写死。
  复合 runner 必须**复用既有步骤实现**（同一批 service 方法），且**不得经 Toolkit 调工具**
  （那会绕过权限与审计，见 §1.4/§1.5）。逐项审批卡必须枚举具体步骤，
  且"批准一次"仍等于"批准这些具体操作"。

> **红线问题的裁定（2026-10-14，仓库所有者）：属于违规，一律改走 publisher。**
> 事实：`AgentChatServiceImpl` 里有 **19 处直接 `turnEventStore.append(...)`**
> （`turn_started`/`turn_failed`/`turn_paused`/`turn_completed`/`canvas_*`/文档标记，
> 例如 `:1252, :1312, :1779, :1859, :1884`），与 `AgentTurnEventPublisher` 并存。
> 改造要求（先证据后改）：逐站点查清线程、publisher 生命周期、能否与 64 项/250ms 批次写入并发、
> 以及改走 publisher 是否会造成**双重发送**；改造后必须保住 `seq` 连续、结构事件先 flush delta、
> 持久化失败保持显式失败、`afterSeq` 与所有 `type`/`payload` **字节不变**。
> 若某站点确实无法改走 publisher，保留并单独上报理由。

**顺带必须先修的两个既有洞**（审计发现）：
1. ~~分镜媒体嵌套调用绕过 `MediaToolPreflightService` 与媒体预算（§1.4）~~ —— **经核对大部分为误报，已降级为"待产品确认"（见 §1.4 更正表）**；
2. `SandboxOutputReconciler` 的"每轮末尾扫一次"与异步生产者赛跑（§1.3 第 4 条）—— **已修并验证**（`publishPathIfNew`/`reconcileNew` + 异步任务完成时补扫；`BUILD SUCCESS, 28 tests`）。

---

## 7. 明确照搬不了的三件事

1. **插件 / HMR 组合系统**（Cordis 的 profile/bundle/patch + 热重载）：无对应物。用
   `ToolsConfig` + MCP + `@Tool` + Skill 仓库近似，别自研框架。
2. **加固隔离运行时**：框架只给抽象，不给内核级隔离（详见另一份文档 §4）。
3. **框架级 fork / 对外 SDK / ACP**：fork 只能 DIY（§4）；对外只有
   `RemoteSubagentTransport`/`AgentProtocolTaskClient` 这类远程 agent 协议。

---

## 8. 证据与不确定性

### 8.0 验证记录（每阶段必须留痕）

命令统一形式（`-Dsurefire.failIfNoSpecifiedTests=false` 是必需的，见下）：

| 阶段 | 命令 | 结果 |
|---|---|---|
| Bug B（reconciler 竞态） | `mvn -pl MoonAgent -am "-Dtest=SandboxOutputReconcilerTest,SandboxArtifactPublisherTest,AgentChatServiceImplTurnTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | **BUILD SUCCESS, Tests run: 28, Failures: 0, Errors: 0**（17+9+2） |
| L0（输出契约） | `mvn -pl MoonAgent -am "-Dtest=ToolResultContractTest,AgentScopeToolPipelineTest,ToolAuditSummarizerTest,AgentScopeStoryboardToolsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | **BUILD SUCCESS, Tests run: 38, Failures: 0, Errors: 0**（11+7+5+15） |
| L0 全模块回归 | 同上，不限 `-Dtest`（127 个测试类） | **Tests run: 493, Failures: 0, Errors: 4**；4 个 error 全部是环境问题——Testcontainers 找不到 Docker 环境（`AgentRecoveryLeaseConcurrencyIntegrationTest`、`HitlCardPersistenceTest`、`MoonAgentApplicationTests`），与本次改动无关 |
| L2（单调 guard + 去冗余归一） | `mvn -pl MoonAgent -am "-Dtest=ToolCallStateGuardTest,AgentScopeToolPipelineTest,ToolResultContractTest,AgentScopeStoryboardToolsTest,ToolAuditSummarizerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | **BUILD SUCCESS, Tests run: 57, Failures: 0, Errors: 0**（15+12+11+14+5）。首轮我的独立跑是 **1 failure + 2 errors**，回退修复后转绿 |
| L1（前端） | `npx tsc -b` + `npx vitest run --config vitest.sandbox.config.cjs <files>` | `tsc` **exit 0 零类型错误**；`agentStore 73 + ToolDraftList 8 + renderIntentRegistry 5 = 86 passed`，exit 0 |
| **改造前基线**（L0+L2 之后、红线重构之前） | `mvn -pl MoonAgent -am test`（全模块） | **Tests run: 520, Failures: 0, Errors: 4**；4 个 error 仍是 Testcontainers/Docker 不可用。**这是红线重构的对照基线**，任何新失败都可据此归因 |
| **L4 + 红线收尾**（2026-10-14 22:30，见 §12） | `mvn -o -pl MoonAgent -am test`（全模块，干净重编译） | **Tests run: 552, Failures: 0, Errors: 4**；4 个 error 全部是 Testcontainers/"Could not find a valid Docker environment" |
| **L4 + 红线收尾**（前端 L1/L4b） | `npx vitest run --config vitest.sandbox.config.cjs`（5 个聚焦文件）+ `npx tsc -b` + `npm run build` | **95 passed / 5 files**，exit 0；`tsc -b` exit 0；`npm run build` **✓ built in 1.98s**，exit 0（构建需一次性放宽沙箱，见 §12.4） |

> 数字口径说明：同一份全模块结果，**控制台**汇总为 520，而逐文件解析 `target/surefire-reports/*.txt` 得到 493；
> 差额 27 无法对上（可能是多类报告文件/重跑覆盖）。**以控制台 520 为基线**，且两种口径都一致表明 `Failures: 0`。

**一次失败并回退的尝试（记录以免重犯）**：为补上"异步任务补扫"这条缺口，我写了
`AgentScopeAsyncTaskServiceTest.sandboxScriptTaskSweepsOutputForArtifactsWrittenAfterTheTurnEndScan`，
结果稳定报 `NullPointerException: Cannot invoke "Object.hashCode()" because "key" is null`
（`AgentTaskEventHub.publish:69` ← `AgentScopeAsyncTaskService.publish:2195` ← `fail:1925` ← `runSandboxScriptTask:1838`），
**试修一次未过，按承诺回退**（回退后该类 15/15 绿），缺口仍在文档里保留。

该失败顺带暴露两个值得知道的事实：
1. **手工构造的 task 走不通这条路径**：`AgentTaskEventHub.publish` 需要一个由真实入队路径设置的字段，
   手工 new 的 task 会让 key 为 null。要补这条测试，应改用服务自己的入队方法构造任务，
   而不是 `ReflectionTestUtils.invokeMethod` 直接调私有方法。
2. **catch 分支会二次抛错并掩盖原始异常**：`runSandboxScriptTask` 的 `catch` 调 `fail(...)`，
   而 `fail(...)` 自己又走 `publish(...)`，于是抛出**第二个** NPE 顶掉了最初的异常。
   生产路径上 task 字段齐全，所以不是线上缺陷，但排障时会丢失根因——建议后续把 `fail` 里的 publish 包一层防御。

**已知环境限制**：本机当前无法访问 Docker，因此依赖 Testcontainers 的集成测试（Postgres/Redis/MinIO）**跑不了**。要跑 CLAUDE.md 红线里的集成门槛，需先启动 Docker Desktop。

**踩坑记录**：`-DfailIfNoTests=false` **无效**；surefire 3.5.6 要的是 `-Dsurefire.failIfNoSpecifiedTests=false`。用 `-am` 时必须加，否则 Reactor 会在 `common-core` 因"No tests matching pattern"直接失败（CLAUDE.md 现有示例命令有同样的坑）。

- **框架侧**：全部来自对 `agentscope-core` / `agentscope-harness` **2.0.4** jar 的
  `javap` 与全量类清单（922 类），本地仓库 `E:\Development\apache-maven-3.9.15\mvn_repo`。
- **项目侧**：来自三项只读代码审计（工具输出契约、PTC 破坏面、前端渲染——第三项见下方补录），
  证据均在正文以 `path:line` 标注。
- **未核实项**：AgentScope 内部"异常 → `ToolResultState`"的映射与 eviction 的确切语义；
  framework 注册的 `write_file`/`execute` 的权限优先级（只读 ALLOW 是否会遮蔽 ASK 名单）；
  wakeup 回合是否会为上一轮的晚写补跑 reconciler；`wait_async_results`/`task_cancel` 是框架真实工具还是元工具；
  服务端由哪个类写入 `TOOL_*` 的 payload 字段（`toolName`/`toolCallId`/`delta`）与 `metadata.kind`；
  `metadata.taskCards`/`metadata.confirmResult` 是否被服务端持久化；
  `plan_updated`/`media_critique`/`shot_progress`/`assembly_progress` 是否真的渲染。
- **方法限制**：未运行任何测试、未做运行时验证。前端审计把"按工具硬编码"这一前提
  修正为"已 85% 数据驱动"——这类前提性结论尤其应以运行时验证为准。

---

## 9. 交接状态（2026-10-14 18:04）

> **本节已于 2026-10-14 22:30 收口，逐项结论与验收证据见 §12。**
> 以下保留当时原貌以便追溯；"进行中/待交付"那些条目现在都已完成，
> **不要再把本节当作当前状态读**。

### 已完成并验证

| 阶段 | 验证证据 |
|---|---|
| L0 输出契约（含 `needsChoice` 修复） | 聚焦 BUILD SUCCESS（44）；全模块 **520 / 0 failures / 4 Docker error** |
| L1 前端 | 我独立跑：`tsc -b` **exit 0**；`agentStore 73 + ToolDraftList 8 + registry 5 = 86 passed` |
| L2 单调 guard + 去三重归一 | 我独立复跑：**BUILD SUCCESS，57/57**（15+12+11+14+5） |
| Bug B reconciler 竞态 | **BUILD SUCCESS，28 tests**（含新增 3 个） |
| Bug A | 核实为**误报**（§1.4 已更正）；视频 per-turn 预算待产品确认 |

### 进行中（两个 Java 子任务，文件互不重叠）

1. **红线修复**：19 处直接 `turnEventStore.append(...)` 收口到 `AgentTurnEventPublisher`。
   已改终态事件方法签名（新增 publisher 参数），**4 个调用点的签名错误已消失**；
   新增 `AgentTurnEventPublisherTest` 覆盖 `seq` 连续 / delta 不被终态越过 / 持久化失败显式 /
   `afterSeq` 逐字段字节相等。
   **待交付**：Phase-1 每站点证据表 + 遗留直连站点理由 + 最终全模块结果（须对比 520/0/4 基线）。
2. **L4 复合旅程**：`AgentScopeJourneyPlanner` 已落地并审阅通过
   （`requested > threshold` → 恰好 15s 走短分支；阈值来自配置；
   `confirmationSummary` 明写"批准后仅执行以上列出的步骤"）。
   **待交付**：`AgentScopeJourneyTools`（工具本体）、`AgentScopeJourneyRunner`
   （**当前编译错误来源**：`AgentScopeAsyncTaskService:112,128` 已引用但类尚未创建）、
   阈值配置项、单父多子任务、以及 5 项测试要求。

### 尚未收口的自有项

- **Bug B 接缝测试**（我的）：`AgentScopeAsyncTaskServiceTest.sandboxScriptTaskSweepsOutputForArtifactsWrittenAfterTheTurnEndScan`
  已按正确方式重写（走服务自身 `startSandboxScriptTask` 入队路径，而不是反射直调私有方法），
  但**连续三轮未能验证**——被上述两个 agent 的中间态编译失败挡住。树恢复后重跑；不过则自修或回退。
- **L4b**：事件 payload 加 `parentToolCallId`（供 per-slot 进度折叠归组），须等红线修复释放 publisher 文件。

### 经验（写给后续同类改造）

**不要在同一个 Maven 模块上并行派发两个 Java 任务。** 实测：双方中间态编译红会让**验证互相阻塞**
（我连续三轮的验证尝试都被外部编译错误挡回），并行的时间收益被抵消甚至为负。
后续应"一个 Java 任务在飞、其余排队"，或把验证集中到所有写入停止后一次执行。


---

## 10. 工作区事故与恢复（2026-10-14 18:16-18:21）

**事件**：18:16:14 一个**外部进程**（非本会话 agent、非我）在 `E:\Desktop\AI-storyboard` 执行
`git stash push -u -m "rail-verify"`，紧随 18:16:15 与 18:02:15 两次 `git reset --hard HEAD`。
后果：`git status` 从 173 个改动文件变为 0，工作区等于 HEAD。

**没有切分支**：reflog 显示今天最后一次 `checkout` 是 00:08:17（master → codex/generation-preferences），
之后无 checkout；两次 HEAD 变动的类型均为 `reset: moving to HEAD`。

**恢复**：该 stash 有 **3 个 parent**（`git cat-file -p stash@{0}`），第三个即未跟踪文件提交
（`194ea286`，508 个文件），因此**带 `-u` 的 stash 把本会话所有新建文件都保存了**。
随后该 stash 被 pop，工作区恢复（`git status` 189 个改动文件），
`ToolResultContract.java`／`ToolCallStateGuard.java`／`AgentScopeJourneyPlanner.java`／
`ToolTurnEventPublisherTest.java`／`ToolDraftList.tsx`／本文件 均已回到磁盘。

**教训（写给后来者）**：
1. `git stash show --name-only` **不列出未跟踪部分**。判断"stash 里有没有新建文件"必须用
   `git cat-file -p stash@{0}` 看 parent 数量（3 个 = 含未跟踪），或用 `git stash show -u`。
   我据此一度误判为"不可恢复"，是错的。
2. 与他人/其他工具**共享同一个工作区**时，`git stash` + `reset --hard` 会静默清空协作者的未提交工作。
3. 恢复用 `git stash apply`（保留 stash 作退路）而非 `pop`。

## 11. 可信基线（恢复后的干净全模块跑）

```
mvn -pl MoonAgent -am test        # 恢复后的工作区，无并发构建
Tests run: 547, Failures: 1, Errors: 4, Skipped: 0
  Failures: AgentScopeAsyncTaskServiceTest.cancelQueuedTaskWritesCompletedAtAsOffsetDateTime:548
  Errors  : HitlCardPersistenceTest / MoonAgentApplicationTests×2 /
            AgentRecoveryLeaseConcurrencyIntegrationTest   ← 全部为"宿主机无 Docker"的环境错误
```

**数字口径更正**：此前出现过的 493 / 501 / **520** 都**不是可信分母**——累加未清理的
`target/surefire-reports/*.txt` 会把多次运行的陈旧报告重复计入（这一点由 turn 事件重构的执行者独立发现）。
**以干净跑的 547 为基线**，任何新增失败应据此归因。

> **2026-10-14 22:30 更新（见 §12）**：本节数字已被取代。当前可信基线是
> **`Tests run: 552, Failures: 0, Errors: 4`**（干净重编译；552 = 547 + 红线/L4 两侧新增）。
> 上面列的那条唯一真实失败 `cancelQueuedTaskWritesCompletedAtAsOffsetDateTime` **已绿**，
> 4 个 error 仍全部是"宿主机无 Docker"的环境错误。**不要再以 547/1 作为对照基线。**


**结论**：L0、L1、L2、Bug B 以及两侧新增的全部测试在此次干净跑中均为绿；
模块内**只剩 1 个真实失败**（复合旅程改动引入的取消路径二次 `updateTask`，已定修法 (b)：
把 `releaseWaitingChildren` 从 `clearLiveStream` 拆出，使取消路径恰好一次终态更新，
且**不得**把断言放宽为 `atLeastOnce()`——那条断言仍在证明 `completed_at` 是 `OffsetDateTime`）。

---

## 12. 收尾与验收记录（2026-10-14 22:30）

本节是当前权威状态。§9/§11 中与之冲突的描述以本节为准。

### 12.1 阻塞启动的 Bean 环（本次修复）

启动即失败：
`agentScopeAsyncTaskService` ↔ `agentScopeJourneyRunner` 构造器互注 →
`BeanCurrentlyInCreationException`。

**关键事实：`@Autowired(required = false)` 打不破构造环。** `required=false` 只吞
`NoSuchBeanDefinitionException`；这里 runner 的 Bean 定义是存在的、只是"当前正在创建"，
Spring 仍会去实例化它。原代码把它写成"可选注入"并注释为已处理循环，实际完全没有生效。

**修法**（`AgentScopeAsyncTaskService`）：把 runner 的直接依赖换成
`ObjectProvider<AgentScopeJourneyRunner>` —— 容器注入的是按需解析器本身，不会在创建本 Bean 时
触发 runner 实例化，编译期依赖保留、运行期仍是单向调用；`journeyEnabled()` / `submitJourney()`
改走 `journeyRunner()` 访问器（provider 可为 null），9 参测试构造器不变，既有单测零改动。

**验证**：真实启动 **`Started MoonAgentApplication in 6.493 seconds`**
（Tomcat 8084、27 个索引工具、MCP 已连），并成功跑完一轮真实对话。

> 这类环本会被 `MoonAgentApplicationTests.contextLoads`（`@SpringBootTest` 全量上下文）拦住，
> 但本机 Docker 名管道不可用，所以只能靠"真的启一次"发现。

### 12.2 `publish(...)` 推送失败不得掩盖根因（本次修复）

`AgentScopeAsyncTaskService.publish(String)` 是任务视图推送的唯一漏斗，
`updateTask(...)` 的**每次进度回调**都会经过它。原实现一旦抛出（历史上真实出现过
`AgentTaskEventHub.publish` 因 key 为空抛 NPE）：

- `fail(...)` 里的第二个异常会顶掉调用方**最初的失败原因**，日志里只剩 NPE；
- `liveListener.update` 的进度回调足以**打断整条生成链**。

改为显式降级为 `log.warn`，绝不向上传播；`runSandboxScriptTask` 的 catch 同时改为带 throwable
记录，保留最初根因的栈。

新增测试 `AgentScopeAsyncTaskServiceTest.taskPushFailureDoesNotEscapeTheTerminalTransition`：
推送侧抛 NPE 时，取消路径不逃逸异常，且终态写入仍为 `cancelled`。

### 12.3 既有竞态测试收口（本次修复）

`AgentScopeJourneyParentChildTest` 的两处断言在**采样一个已被并发任务接管的字段**：
`releaseChildTask` 写完 `waiting_parent → queued/accepted` 后会立刻 `submit` 该子行，
后台线程随即 `claim` 成 `running`，于是"此刻恰好是 queued"取决于调度时机。

**先证据后改**：把 §12.2 的守卫**回退**、删除陈旧 surefire 报告、全新编译后重跑，
仍稳定 `expected: "queued" but was: "running"` —— 与本轮任何改动无关，
是**先于本次改动就存在的竞态断言**（早期之所以为绿，是它在多类同跑时恰好赢了竞态）。

**修法**：harness 在放行写入发生的那一瞬间记录 `Release(status, stage)` 快照，
断言改为断言**这次写入本身**（`queued` / `accepted`）+ 子行已离开 `waiting_parent`。
强度不降、与调度无关。独立复跑 **5/5 全绿**。

### 12.4 验收证据

| 项 | 命令 | 结果 |
|---|---|---|
| 全模块（干净重编译） | `mvn -o -pl MoonAgent -am test` | **Tests run: 552, Failures: 0, Errors: 4**；4 个 error 全部是 Testcontainers/"Could not find a valid Docker environment" |
| 复合旅程聚焦 | `mvn -o -pl MoonAgent -am "-Dtest=AgentScopeAsyncTaskServiceTest,AgentScopeJourneyParentChildTest,AgentScopeJourneyTaskViewTest,AgentScopeJourneyToolsTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | BUILD SUCCESS（17 + 5 + 4 + 9 = 35） |
| 前端聚焦（L1 + L4b） | `npx vitest run --config vitest.sandbox.config.cjs`（agentStore / taskFolding / JourneyStepFolding / ToolDraftList / renderIntentRegistry） | **Test Files 5 passed，Tests 95 passed**，exit 0（73 + 4 + 5 + 8 + 5） |
| 前端类型 | `npx tsc -b` | exit 0 |
| 前端构建 | `npm run build` | **✓ built in 1.98s，exit 0**（仅 chunk >500 kB 的既有告警）。注意：受限沙箱下 vite 加载配置会 `exec('net use')` → `spawn EPERM`，这条命令**必须一次性放宽沙箱**才能跑 |

### 12.5 核对后确认「已完成、§9 此前记为未收口」的项

- **红线重构**：`src/main` 内直接 `turnEventStore.append(...)` **已清零**（全仓只剩
  `AgentTurnEventPublisher` 注释里的一处提及）。Phase-1 每站点证据表见
  `.tmp-turn-refactor-phase1.md`：19/19 收口，**无遗留直连站点**，19 个站点线程、emitter 与
  封套形状均已逐条核对。`AgentTurnEventPublisherTest` 覆盖 §9 声称的 4 条不变量
  （seq 连续 / delta 不被终态越过 / 持久化失败显式闩锁且不再产生后续序号 /
  `afterSeq` 逐字段与 `payloadJson` 字节相等）。
- **L4 复合旅程**：`AgentScopeJourneyPlanner` / `AgentScopeJourneyTools` / `AgentScopeJourneyRunner`
  全部在位，工具已在 `AgentScopeToolkitFactory` 注册、有 `AgentScopeToolLabels` 文案、
  逐项审批摘要走 `AgentScopeCheckpointService`，阈值 `journey-direct-video-max-seconds`
  有 `application.yml` + `AGENTSCOPE_JOURNEY_DIRECT_VIDEO_MAX_SECONDS` env 覆盖。
- **L4b `parentToolCallId`**：已端到端落地——服务端 `viewMap` 下发
  （`AgentScopeAsyncTaskService`，含 `noteJourneyStep` 的步骤进度），前端
  `src/lib/taskFolding.ts` 消费并在 `MessageBubble.tsx:1816` / `ToolDraftList.tsx` 折叠渲染，
  已有 `taskFolding.test.ts`(4) + `JourneyStepFolding.test.tsx`(5)。
- **Bug B 接缝测试**：`AgentScopeAsyncTaskServiceTest.sandboxScriptTaskSweepsOutputForArtifactsWrittenAfterTheTurnEndScan`
  已存在，且在全模块跑中为绿（§9 记的"连续三轮未能验证"已解除）。
- **§11 记的 1 个真实失败**（`cancelQueuedTaskWritesCompletedAtAsOffsetDateTime`）现已绿，
  取消路径恰好一次终态更新。

### 12.6 仍未做 / 已知局限

- **`MoonAgentApplicationTests` / `HitlCardPersistenceTest` /
  `AgentRecoveryLeaseConcurrencyIntegrationTest`** 三个集成门槛因本机 Docker 不可用而无法执行；
  这也是"§12.1 的 Bean 环只能靠真实启动发现"的原因。
- 前端 `npm run build` 本身需要**一次性放宽沙箱**（见 §12.4）；日常受限模式下它必然 `spawn EPERM`，
  这不是代码问题，但会让"默认沙箱里跑完 CLAUDE.md 全部门槛"做不到。
- §12.5 的"红线重构未改变 `type`/`payload` 字节"目前只有 Phase-1 静态分析与全模块绿作为证据，
  **没有"重构前后同一 turn 事件逐字节对比"的运行时证据**。要对齐这条，需要一个能在重构前后
  各跑一次同一 turn 的环境。

