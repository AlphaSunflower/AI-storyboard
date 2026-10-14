# DSH 内核替换评估与实施方案

> 评估对象：`deepseek-ai/deepseek-harness`（下称 DSH，49 个包 / 219 个子包，TypeScript，MIT，developer preview）
> 评估标的：是否用 DSH 内核替换 MoonAgent 的 agent 运行时，并把 AI 分镜、画布、项目库、Moon 智能体改为经 MCP/工具与 DSH 交互，模型调用统一走现有 LLM 网关（:8083）。
> 日期：2026-10-14 ｜ 结论：**不建议做内核替换；建议做双入口并存 + 计费下沉网关。**

---

## 0. 一句话结论

**不做"内核替换"，做"双入口"。**

- **不替换**：MoonAgent 保持唯一在线链路（`MoonAgentRuntime` + SSE turn 协议 + HITL + Sandbox 产物闭环）。
- **并存**：DSH 作为**高级客户端 / 自动化入口**接你的 LLM 网关（:8083）与 MoonAgent 的 MCP 工具面，用于内部提效与 coding/自动化场景，不进多租户产品主链路。
- **计费与审计下沉**：token 审计与图/视频消耗**全部落在 LLM 网关**（唯一所有模型调用的必经点），与 agent 内核选型解耦。这样即使将来真的换内核，计费也不用重做。

理由摘要：可替换部分（内核等价物）约 **2.0 万行 Java + 3.3 万行前端 = 5.3 万行**都绑在当前自有协议上；不可替换部分（领域 + 沙箱）约 **8.2 千行**与协议无关、本来就能给 DSH 用。也就是说：**能换的那部分最贵，该留的那部分最便宜。**

---

## 1. 量化现状（决定成本的事实）

### 1.1 后端

| 分组 | 文件数 | 行数 | 归属 |
|---|---|---|---|
| kernel-equivalent（`ai/agent/{agentscope,turn,impl,sandbox,recovery,task,stream,orchestration,human,skill}`） | ~105 | **20,288** | 换内核就要动/弃 |
| domain（`ai/agent/{storyboard,creative,canvas,document,audit,handler}`） | 63 | **7,158** | 协议无关，可复用 |
| MoonSandbox（独立执行服务，:8090） | 21 | **1,038** | 协议无关，可复用 |
| MoonAgent 主源码合计 | 294 | — | — |

### 1.2 前端（AIStoryboardClient）

| 项 | 文件数 | 行数 |
|---|---|---|
| agent 相关（store/组件/api/测试） | 146 | **32,638** |
| 其中 `stores/agentStore.ts` | 1 | 4,366 |
| 其中 `components/agent/MessageBubble.tsx` | 1 | 1,889 |
| 其中 `pages/ChatPage.tsx` | 1 | 1,784 |
| 前端全部 ts/tsx | 301 | 65,086 |

**解读**：`agentStore.ts`（4,366 行）+ `MessageBubble` + `ChatComposer` + `ChatStatusBar` + `ContextUsageRing` 这一整套，是你自研 SSE 协议（`{turnId, seq, type, payload}`）、推理链展示、HITL 卡片、产物文件卡、token 状态条的具体实现。换 DSH 内核等于让这 3.3 万行前端要么改写进 DSH client plugin，要么报废。

### 1.3 DSH 侧规模（对照）

- 49 个顶层包 = 219 个子包；`packages/*/*/tests` 下 **684 个测试文件**，211/219 包有测试，per-file 100% 覆盖率门禁，多平台 CI。
- 文档 200+ 篇（architecture / capability-seams / subsystems / cookbook / postmortem，含中文全量对照）。
- 自述：developer preview，"THERE WILL BE COMPATIBILITY-BREAKING CHANGES"，会话格式 `SESSION_FORMAT_VERSION = 0` 且无兼容承诺。

---

## 2. 五项审计结论

> 五项审计**全部完成**：2.1 先行核实 / 2.2 token 与多租户 / 2.3 MCP 与鉴权 / 2.4 计费数据模型 / 2.5 客户端 UI / 2.6 执行面接缝。三项结论改写方案：**媒体计费完全没有底子**（2.4）、**HITL 卡片是插件级而非重写**（2.5）、**执行面搬迁 6-14 周且产物对模型不可见**（2.6）。
>
> 2.1 为本轮先行核实、已足以支撑决策的结论；2.2 起为专项审计。

### 2.1 已核实（本轮先行确认，供参考）

- **LLM 网关对接 = 官方支持、零代码**：`packages/bundle/base/cordis.patch.yml:75-96`（`settings.yaml` 热重载，`llm-deepseek:`/`llm-pi-ai:` 段覆盖适配器条目）+ `packages/llm/llm-pi-ai/README.md:98-100`（自声明路由只需 `api` + `baseURL` + `models`；"private proxies such as `https://proxy.example.com:8443` remain supported"）。`apiKeyEnv` 为凭据引用；`compat.thinkingFormat: deepseek` 可纠方言；`input: [text, image]` 必须显式声明（从不自动探测）。
- **MCP 挂载 = 配置级**：`packages/mcp/mcp-client/README.md:11-51`，一行一个 server，工具名 `mcp__<serverName>__<rawName>`；`toolCallTimeoutMs` 默认 **60s**（生图/生视频必调大）。注意该包**不在 base bundle 里**，需在 profile 的 `cordis.patch.yml` 加行。
- **你这边已有现成 MCP 工具面（约 26 个工具，零新代码）**：
  - MoonAgent 5 个 —— `writeScenes` / `replaceScenes` / `replaceSpecificScenes` / `refineImage` / `generateVideo`（[AgentTools.java](../MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentTools.java) L37/56/80/101/146，`@McpTool`）。
    **注意 HITL 追问工具只有 `@Tool` 没有 `@McpTool`**（同文件 ~L190）——即经 MCP 接出去时，分镜/生图/生视频可用，**确认卡片仍归你**，这是对的设计。
  - LLM 网关 2 个 —— `generate_image` / `generate_video`（`AILLMGateway/.../mcp/MediaMcpTools.java` L76/136）。
  - 画布 **19 个** —— `canvas_graph_*`（13）+ `canvas_execute_*` / `canvas_get_execution` / `canvas_cancel_execution`（`XiaoYueCanvas/.../mcp/CanvasGraphMcpTools.java`、`CanvasExecuteMcpTools.java`）。**画布模块本身已经是 MCP server**，接 DSH 无需改造。
- **网关已有调用日志表（计费的天然落点）**：`call_log`（`AILLMGateway/.../db/migration/V1__gateway_tables.sql:49-63`，异步落库）+ 后续迁移补 `task_id`(V2)、`call_id`(V13)、冷热分离 `call_log_archive`(V11)。现有列为 `id / model / channel_id / status / duration_ms / error / video_url / created_at`——**没有 token、没有成本、没有 user/tenant/project 归属**，这正是 token 审计与媒体计费要补的缺口。
- **profile 落点已确认**（本机）：`~/.dsh/profiles/{desktop,web}/cordis.patch.yml` 是"每个 bundle 层之后应用的补丁层"，支持按 id 覆盖、禁用与插入。阶段 0 就是改这个文件 + 建 `settings.yaml`。
- **HITL 有接缝**：`ctx.approval.request` 是 `approval/request` waterfall（"UI channels may provide human answerers"，fail-closed，`allowed-once|rejected|cancelled|unavailable`）；`ctx.userQuestions` 由唯一活动 `UserQuestionProvider` 提供，支持 options + 自由输入 + 多选。客户端侧已有 `approval/requested`、`question/requested` MuxFrame、pending 队列、按 `approvalId` 去重与**重连不重放**（`packages/client/runtime/src/client/sessions/{session,manager,pending}.ts`）——正好对应你红线里"取消卡片复活 / formToken 重复提交"那两个事故。
- **产物文件卡 DSH 已有**：`@deepseek-ai/dsh-client-ui-deliverables` 在回合末尾渲染"产出文件行"（chips + 打开文件），识别方式是**工具的 render intent / `locations`**而非工具名——自定义工具只要声明产出路径就能进这个 UI。这与你自研的 `metadata.sandboxAttachments` 文件卡是同一功能的两种实现。
- **token 计量 DSH 已有半套**：`dsh-token-meter`（surface tokens，按 log revision）、`dsh-session-stats` 投影（`steps/turns/llmMs/ttftMs/decodeMs/decodeTokens/toolMs`，经 `ctx.sessionProjections` 推到客户端，UI 有 `performanceUsage` 条）、`dsh-session-telemetry-otel` 可外送 OTLP。**缺**：跨会话/跨租户聚合、价格、配额、租户身份。
- **执行面接缝齐备**：`ctx.fs` / `ctx.subprocess` / `ctx.shell` / `ctx.codeRuntime` / `ctx.sandbox` 均为可替换接缝，且仓库自带 `e2b` + `fs-e2b` + `subprocess-e2b` 远程沙箱模板（`docs/capability-seams.md:112-148`）。**注意：本 checkout 无 Python 执行工具**，你的 `run_python_script` 需保留为自定义工具或另写 provider。
- **多租户是硬墙**：`packages/identity` 只有 `$DSH_HOME` 级匿名 UUID；Web server 自述 "no TLS, auth, or origin policy"；`session.event` 全 runtime 不过滤。
- **SDK 承载不了产品级 HITL**：仅 `initialize`/`session/prompt`/`shutdown`，"No cancel or session-close methods"，server→client 请求为 dead capability（`packages/sdk/protocol/README.md:37-39`）。

### 2.2 审计⑤：DSH 的 token 计量与多租户隔离接缝（**已完成**）

**token 计量：能测，但不是账本**

- `dsh-token-meter`（`ctx.tokenMeter`）只有 `measure()` / `estimateMessage()` 两个操作，是**拉取式 API、不发布事件**；`TokenMeasurement` 完全在内存 WeakMap 里折叠（`packages/llm/token-meter/src/index.ts:74-157,79`）。
- 真正落盘的是**派生投影**：`tokenUsage` / `contextPressure` / `contextBreakdown` 经 `ctx.sessionProjections` 写入 `session_projcache`（`(sessionId,key,ver,seq,val)`，节流 write-behind + `turn/end` 检查点）。文档自己的措辞："as stale as the last durable checkpoint but never wrong"（`docs/subsystems/session-projection.md:107,111-122`）——**这是缓存，不是台账**。
- usage 的真源在会话日志：`'assistant/message': { turn, step, message, usage?: TokenUsage }`（`packages/core/session/src/types.ts:267-273`）；`TokenUsage = inputTokens / outputTokens / cacheReadTokens? / cacheWriteTokens? / reasoningTokens?`，且**四个桶不相交**（`inputTokens` 只算未命中缓存的输入，"billed input = sum of the three"）。模型身份在**另一个** `request/header` 事件里（`provider`/`model`），所以**按模型聚合要自己做日志顺序 join**。
- 报表能力：单会话 **有**（UI `StatsLine.tsx:165` 按四桶计费）；跨会话 **只有一半**（`session.list` 每行带 `tokenUsage`，但**没有求和/聚合、没有 usage RPC、没有 CLI 命令**）；**按模型完全没有**；**没有 `/usage`、`/cost`** 命令。
- 遥测：`dsh-session-telemetry-otel` **只有 logs，没有 metrics/traces**，默认 DISABLED，可指向自有 OTLP collector（`exporter.url` / `headers`）；但资源属性只有匿名 `user.id` 和 `session.id`——**没有 token、没有模型、没有租户**；token 与模型只在 body 里，且每个 `(turn,step)` 只发第一个 chunk，**at-most-once 会丢**。

**多租户：不是"配置"，是"没有这个模型"**

- 身份：`packages/identity/README.md:5` 原文 "These values do not represent an authenticated account."，只有 `$DSH_HOME/.anonymous-user-id`。
- `docs/subsystems/web-server.md:41`：只接受 `127.0.0.1` 或 `0.0.0.0`，**"there is no TLS, auth, or origin policy"**；`packages/client/connection/README.md:9`：**"The fence is a reachability policy, not authentication"**，且 `dsh web --host 0.0.0.0` 在引入认证层之前 "intentionally unsupported"。
- 会话可见性：SDK 的 `session.event` 明确是 **"every session in the runtime, unfiltered"**（`packages/sdk/protocol/README.md:20`）；`ctx.sessionQuery` 无 owner/tenant 过滤；工具级跨会话授权只比较 `cwd` 相等。
- scope 的能与否（原文）：可按 agent 作用域注册工具/命令/技能/系统提示词/jobs，**审批 answerer 支持 agent 作用域**（"Agent-scoped listeners receive only that agent's requests"）；但 **"they are not sandboxes or authority boundaries"**、**"Only scope-aware APIs isolate state"**（`packages/core/scope/README.md:27,35`）。**反例：user-question provider 不是 agent 作用域的**——"Only one provider may be active in a context… a second registration throws `DUPLICATE_PROVIDER`"（`packages/interaction/user-questions/README.md:11,45`），多租户下"每用户独立追问 UI"需要另设计。

**判定表**

| 能力 | 判定 | 决定性依据 |
|---|---|---|
| (i) 单会话 token 总量 | **已提供** | `tokenUsage` 投影（`token-meter/src/usage-projection.ts:107-140`）+ UI + `session.list` 行 |
| (ii) 跨会话/按租户汇总 | **可落在 `ctx.storage`/`storageDomain` 接缝上建**；**租户键本身需你引入** | `docs/subsystems/storage.md:100-106`（DomainSpec + KvTable，durable-before-memory）；`identity` 无租户概念 |
| (iii) 防篡改审计链 | **可建，但保证很弱** | 无哈希链/签名/WORM；sqlite 实现自述另一 principal 能替换数据库条目即不保证完整性；遥测 at-most-once |
| (iv) 租户数据隔离 | **必须 fork** | web-server 无鉴权 + connection "not authentication" + SDK "unfiltered" + scope "not authority boundaries" |
| (v) 配额强制 | **必须 fork** | `agent-loop` 明说 **"No built-in turn budget"**；`goal-round-driver` 明说 token/currency/quota 策略与 goal 事件不互相归属 |

**结论**：DSH 的 token 能力**够做单会话展示，不够做多租户账本**；多租户隔离在 DSH 里不是"打开一个选项"，而是"没有这个模型"。这进一步支持：**把 token 审计与租户逻辑留在你自己的网关与后端。**

---

### 2.3 审计①：MCP 暴露面与鉴权（**已完成**）

**接口事实**

| 服务 | 端口 | 路径 | MCP 工具 | 鉴权 |
|---|---|---|---|---|
| MoonAgent | 8084 | `/mcp`（Spring AI `streamable-http` 默认端点，未被覆盖） | **5 个**：`writeScenes` / `replaceScenes` / `replaceSpecificScenes` / `refineImage` / `generateVideo` | `X-Internal-Token` 必须等于 `storyboard.internal-secret`；`/mcp` 得 principal `mcp-internal` / `ROLE_INTERNAL` |
| LLM 网关 | 8083 | `/mcp` | **2 个**：`generate_image` / `generate_video` | **无**（`StaticApiKeyFilter` 只过滤 `/v1/`，只有 `/admin/**` 要 ADMIN，其余 `permitAll`） |
| XiaoYueCanvas | 9090 | `/mcp`（显式 `mcp-endpoint: /mcp`） | **19 个**：`canvas_graph_*` 13 个 + `canvas_execute_*` / `canvas_get_execution` / `canvas_cancel_execution` | `X-Internal-Token` |

**两个工具面是分开的（关键发现）**

- **面 A（AgentScope 进程内）**：`SkillExpander` 用 `MethodToolCallbackProvider` 扫 `AgentTools` 得到 **6 个** `@Tool`（含 `moon_request_human_input`），再 `putIfAbsent` 合并 MCP client 回调 → 注册进 AgentScope Toolkit。
- **面 B（Spring AI MCP server）**：注解扫描器只认 `@McpTool` → **恰好 5 个**。`moon_request_human_input`、`canvas_*`、网关的 `generate_image/generate_video` 都**不在** MoonAgent 的 MCP server 上（`spring.ai.mcp.server.expose-mcp-client-tools` 默认 `false` 且未设置）。
- 因此："经 MCP 把 Moon 的能力交给 DSH"**实际只拿到 5 个工具**；HITL 追问工具刻意未暴露（这个设计是对的——外部调用方渲染不了你的确认卡片）。

**必须先修的四个问题（与 DSH 无关，现在就是风险）**

1. **网关 `/mcp` 完全无鉴权** —— 任何能访问 :8083 的调用方都能用你的渠道 Key 生图/生视频（直接资损）。而 `deploy/stack/README.md:78` 与 `SKILL.md:128` 写的「`Authorization: Bearer <网关Key>`」**对 `/mcp` 不生效**（`StaticApiKeyFilter` 只过滤 `/v1/`）。
2. **MoonAgent `/mcp` 无归属校验 + 可冒充** —— 持内网密钥者可对**任意** `projectId` / `conversationId` 操作（工具内只 `selectById`，不校验 `user_id`）；且 `GatewayAuthenticationFilter` 见到 `X-User-Id` 就把该任意值当 principal，审计行可被伪造。即 **IDOR + 冒名**。
3. **MCP 调用绕过你的红线机制** —— 工具路径不经过 `ConversationLock`、`AgentTurnEventPublisher`/事件存储、`ASK`/HITL 审批；所以**没有 SSE 事件、没有确认卡片、产物只落 `agent_assets`**。也就是说"从 DSH 调 Moon 工具"这条链路，天然不满足 CLAUDE.md 那套不可回归验收标准。
4. **暴露面收敛（好消息）** —— 栈内 moon-agent 的 compose 未发布 `ports`，ApiGateway 只路由 `/api/agent/**`，nginx 无 `/mcp` location，故栈内 `/mcp` 仅 compose 网络可达。阶段 0 要在本机/内网打通需显式暴露——**暴露即等于打开上述 1/2/3**。

**已可关闭的一条**：审计①的第 7 项（DSH 能否挂载）已核实——DSH dsh-mcp-client 支持 `transport: streamable-http` + `headers`（可带 `X-Internal-Token`）+ `toolCallTimeoutMs`；`serverName: moon-agent`（≤32 字符约束满足）会得到 `mcp__moon-agent__refineImage` 这样的工具名。

**运行时必须实测（代码判定不了）**：`initialize` 是否 200 且返回 `Mcp-Session-Id`（确认 `/mcp` 路径）；`tools/list` 是否恰好那 5 个；无 token 是否 403；跨用户 `projectId` 能否写入；长任务 POST 是否 >2 分钟不被打断；网关 `/mcp` 是否真的无鉴权。审计已给出可直接粘贴的 `curl` 命令序列。

**对方案的影响**：阶段 0 的"把 Moon 挂进 DSH"**功能可行、但不能直接上生产**。若要做，必须同批修：网关 `/mcp` 加鉴权（复用 `StaticApiKeyFilter` 或独立 MCP Key）；MoonAgent `/mcp` 加归属校验（要求并校验 `X-User-Id` 与 project/conversation 归属，禁止纯内网 principal 调用写工具）；不再无条件信任 `X-User-Id`。**这三条本身就是独立收益——不换内核也建议尽快做。**

---

### 2.4 审计②：现有 token / 用量 / 媒体计费数据模型（**已完成**）

**一句话：token 采得挺全，但"账"完全不存在；媒体侧连单价都没有。**

**① token 采集（唯一在采的模块是 MoonAgent）**

- `AgentTurnMetrics` 带 input/output/cached/total/context/contextWindow/modelCalls/modelDurationMs/toolDurationMs + 提示词与工具定义的估算拆分；`AgentUsageAccumulator` 把**一轮内所有模型调用**累加（**turn 粒度，不是 per-call / per-model**），来源同时覆盖 Spring AI `Usage` 与 AgentScope `ChatUsage`。
- 出口只有两个：per-call 的 SSE `payload.usage{...}`（`AgentScopeEventMapper:101-110`）与终态 `turn_completed.payload.metadata.metrics`（`AgentTraceAccumulator.enrichTerminalEvent:269-275`、`MoonAgentRuntime.turnResult:1274-1277`）。
- **计数是内存 AtomicLong，`snapshot()` 是唯一物化点**——没有独立 usage 表。

**② 数据库：全仓库不存在任何 usage / cost / billing / quota 表**

- `agent_turns`（V39:3-14）**没有 token 列**；`agent_turn_events`（V39:21-29）只有 `turn_id/sequence/event_type/payload TEXT`。
- **今天 token 数字唯一落盘的地方，是 `turn_completed` 的 payload JSON 里**——没有索引、没有列、不能直接聚合。（HITL 暂停时另有一份进 `agent_messages.metadata` JSONB。）
- 仓库级 SQL grep：`input_tokens/output_tokens/total_tokens/prompt_tokens/usage/cost/price/billing/quota/credit` **除了 `form_token`、`max_tokens` 与 information_schema 之外零命中**。

**③ 价格：零。** `model_params` 只有 `max_tokens` 与 `context_window`；`channel` / `model_route` / `gateway_api_key` 无价；Java 侧唯一的 `cost=` 是打印耗时的日志标签。前端配额是**硬编码占位符** `QUOTA_PLACEHOLDER='不可用'` + TODO（`ConversationStatusCard.tsx:6-9,48`）。

**④ 媒体生成记录：事实很全，就是没有钱。** `agent_assets` 已含 `user_id/project_id/conversation_id/scene_id/type/gen_type/prompt/model/params/reference_urls/result_urls/task_id/status/error/duration_ms/idempotency_key/engine_version`；`params` 里有 size/quality/n（图）与 resolution/size/aspectRatio/duration（视频）。**但没有 vendor、没有单价、没有成本列。** 网关侧只记"请求行"（created/polled/succeeded/failed + model/channelId/status/duration_ms），**不含业务侧的 n/size/duration，也不含业务 id**。

**⑤ 归属：用户/项目/会话都有，租户哪儿都没有。**

- `agent_assets`：user ✅ project ✅ conversation ✅ model ✅ / **vendor ❌ tenant ❌**。
- `agent_tool_invocations`：user/project/conversation/model/duration ✅。
- `agent_media_critiques`：project/conversation/turn，**没有 user_id** → 不可直接按用户归属。
- **`llm_gateway.call_log` 完全没有身份**（只有 model/channel_id/status/duration_ms/error/call_id/video_url/task_id）——没有 user、没有 tenant、没有 model_route、没有 api-key id。

**⑥ 网关照实是必经点，但它"认不出人、也不记账"。**

- 所有 chat/图/视频确实都过 :8083（控制器 + 业务侧 `LLM_GATEWAY_BASE_URL`）。
- 但 `StaticApiKeyFilter` 校验完 key 之后**把 principal 硬编码成 "gateway"**（`:46-54`），`AccessLogFilter` 因此永远打印 `[user=gateway]` → **调用方身份是假的**，网关流量无法归属到任何租户/用户。
- **网关从不解析、也不保存 `usage`**（全仓库唯一一次 usage 是给 Ollama 合成响应用的）。重试/失败调用的 usage 只被部分计入（靠 `complete` 标志），上游失败对账不可见。

**⑦ 可复用资产（这是好消息）**

`AgentTurnMetrics` + 两个累加器（per-turn token 真源）、`agent_assets`（per-generation 媒体事实 + user/project/conversation 归属）、`call_log` + `call_id`（per-request 网关骨架）、`agent_tool_invocations`（可照抄的审计表模式）、`model_params`（**单价列的自然归宿**）。

**对方案的影响（修正上一轮的乐观表述）**

我上一轮说"按租户汇总只是一个 SQL 的距离"——**不准确**。准确的说法是：**token 数字在 Postgres 里，但埋在事件 payload JSON 里、没有列没有索引，而且租户维度在整仓库根本不存在。** 所以 token 审计的工程量是三步而非一步：

1. **引入租户键**（你的 `users`/project 归属已有，需要显式下沉到 usage/资产生成记录，或建立 user→tenant 映射）；
2. **把 usage 从 JSON 提出来**：给 `agent_turns` 加 token 列，或按 `agent_tool_invocations` 的模式新建 `agent_usage_records`（推荐后者，避免动 turn 主表）；
3. **让网关真正记账**：`StaticApiKeyFilter` 传递真实 key 身份（key → owner/tenant）→ `call_log` 加 `user_id/tenant_id/model_route_id/api_key_id/tokens/kind/quantity/unit_price/cost`，并让媒体请求带上 `n/size/duration/vendor/business id`；价表落在 `model_params` 或独立 `llm_model_prices`。

好消息是：**这三步与 agent 内核选型完全无关，做完即交付；而且第 2 步已经有现成模式可抄（`agent_tool_invocations`）。** 坏消息是：**网关"认不出人"这一条，会同时卡住 token 审计、媒体计费和多租户配额三件事**——所以它是计费工程的第一块砖。

---

### 2.5 审计③：DSH client plugin 粒度（**已完成**）

**结论：UI 能扩展，但"用自己的 React 应用"= fork。**

- **插件机制**：`package.json` 声明 `dsh.client` + 导出 `exports["./client"]` 构建产物；host 侧扫描 Loader entries → 组装 `window.__DSH_BOOT__` → 通过 `GET /plugins/<id>/client.js?rev=…` 下发。**约束**：扫描是增量的、**没有全量重扫路径**，"不是 client 包"的判定按包名缓存且永不过期 → **插件集变化必须重启**；HMR 仅在 `pnpm run dev:web` 重建时生效；**不能自带 React**（平台种子表是冻结的：react/react-dom/cordis/ui-slots/web-react/ui-primitives/ui-attachment/schema-form），构建有纯度门禁，禁止跨插件值导入。
- **可用的 UI 挂载点（今天的）**：会话节点（keyed slot `conversation.chat.node`）、工具调用渲染器（`tool.call.toolview`）、侧栏/详情（单例 `details`、单例 `sidebar` + 列表 `sidebar.footer.action`）、浮层（列表 `shell.overlay`）、聊天额外页签（`conversation.view`，`ui-trajectory` 是现成先例）、设置页（`settings.section` 列表）、命令与 `/`、`@` 输入触发器、以及 **composer 接管链 `conversation.composer`**。
- **HITL 卡片怎么接（关键）**：**既不用替换 provider，也不用 fork**。现成实现是 `ui-conversation/src/client/skeleton/ApprovalPanel.tsx`（`priority: 1`）与 `ui-user-questions/src/client/QuestionComposer.tsx`（`0`）；候选链是"按 priority 升序、第一个非空选择器胜出"，而 `ui-subagent` 已经用 `priority: -10` 抢占 `conversation.composer` 作为先例。你注册一个更低优先级的同链插件，就能渲染自己的卡片、拿到 pending carrier、并用同一套动词（`PendingApproval.answer` → `wait.respond`）回答。**这是官方支持的接缝。**
- **产物文件卡**：渲染可行（keyed node 或现成的 `conversation.chat.turnTail` 链，DSH 自带的 `ui-deliverables` 就是这么做的，按工具的 mutation `locations` 折叠）。**但下载是问题**：图片**不可 URL 寻址**（会话日志只带不透明 `ImageAttachmentRef`，客户端走 RPC `session.readAttachment` 取 blob），产物以 chips 形式经 `openFile` → host `openPath` 打开；**框架没有文件路由**。你可以用 `ctx.webServer.register({kind:'prefix'})` 自己加一条，但**服务器本身没有 TLS/认证/同源策略** → 实际上只能 loopback，等于下载链接要你自己做鉴权。
- **用你自己的 React 应用替代 shell**：
  - (a) **SDK（stdio JSON-RPC）——HITL 做不了**：`session/prompt` 只返回入队回执、**没有 cancel**、server→client 请求是 "dead capability … the server never sends one"，所以 `ask` 策略会解析成 `unavailable`/拒绝；`session.event` 全 runtime 不过滤。
  - (b) **Host/Client RPC（Typert）** 更强（unary RPC + 两条下行流 + 可取消），但**能力集由构建期显式 import 固定**（客户端装配只有 commands/goals/dynamicCordisRunner/pluginInventory/messageFeedback），加命名空间要改 shipped 包。
  - (c) **写 client plugin 活在 DSH 壳里** —— 推荐，也是唯一能白拿 HITL 与产物卡的路径。
  - **真要"自己的壳"就必须 fork `apps/web` + `packages/client/web`**；而且**整页接管可以、混合不行**（shadow `root` 且 `priority < 0` 后你独自渲染，无法嵌套 DSH 的座位，因为子键是单声明者）。
- **不 fork 能做到的域面板**：`conversation.view` 页签、被 shadow 的 `details`/`sidebar` 列、或追加式 `shell.overlay` 浮层（画布/项目库面板够用）；**要新增一列几何机构**则要 fork `ui-layout` 的 AppFrame（约 1 个文件）；加 typed RPC 约 1 行 + 你自己的 `/remote` 包。

**对你的含义**：如果接受"UI 长在 DSH 壳里"，阶段 2 的 HITL 与产物卡是**插件级**工作量（周级），不是 3.3 万行前端重写；但如果坚持保留现有 `ChatPage`/`agentStore` 那套自有界面，**就是 fork 自己的壳**——那 3.3 万行依然要重写。

### 2.6 审计④：DSH 执行面替换路径（**已完成**）

**结论：四个目标都不需要 fork，但工作量差别巨大；而且有一个隐藏的致命细节。**

| 目标 | 判定 | 要实现什么 | 工作量 | 最大风险 |
|---|---|---|---|---|
| (i) Python 跑在你的服务里 | **需新插件**（接缝支持） | `ctx.codeRuntime`：`run(request)`，`language='python'`，`isolation='container'` | **1-2 周** | 双向绑定必须 lossless-JSON；每次 `run` 都要独立隔离；`tools.*` 子调用要重入受控管线并跨服务往返 |
| (ii) 所有文件 IO 走你的服务 | **支持的接缝** | `FileSystem` 的 11 个抽象方法，照抄 `fs-e2b`（~580 行） | **1-3 周** | `FsVersion` / `FsWriteIntent` 与 before/after diff 基座必须映射到你的版本号，否则每次 `edit`/`write` 都会判为 stale |
| (iii) shell 走你的服务 | **支持的接缝** | `SubprocessRuntime`：`resolveExecutable` / `spawn` / `spawnTerminal`，照抄 `subprocess-e2b`（**~1,735 行**） | **3-6 周** 才能对齐 | 远端启动期间 `pid=-1`，会打断依赖同步 pid 的消费者；`terminal-bash` 需要 PTY 前景进程与 `inputWaiting` 事实，**纯 HTTP API 重建不出来** |
| (iv) 产物上传 MinIO | **需新插件** | 监听 `tools/post-execute`（可替换内容/加 context/阻断）或 `tools/result`（只观察），用 `ctx.fs` 读产物 → 上传 → 写你的资产行 | **1-2 周** | **没有"文件"内容块**——见下 |

**已核实的接缝清单**：`ctx.fs`（`FileSystem`，11 个抽象方法，provider：`fs-local`/`fs-sandbox`/`fs-e2b`）、`ctx.subprocess`（`spawn`→`SubprocessHandle`，`terminate()` 是 SIGTERM→grace→SIGKILL 的树级终止）、`ctx.shell`、`ctx.terminals`、`ctx.codeRuntime`、`ctx.sandbox`（只有 `confine(argv, policy)` 一个方法，失败即 `SANDBOX_UNAVAILABLE` 关闭）、`ctx.sandboxPolicy`。架构文档原话："Filesystem and subprocess providers share one execution world, so pointing them at a remote sandbox moves Bash, PTY, and LSP with them, with no provider forks."——**这句是真的**，`fs-e2b` + `subprocess-e2b` 就是完整样板（注册 = 挂一行插件；同一 ctx key 出现第二个 provider 会**直接让启动失败**，所以必须 `disabled: true` 旧行 + `insert:` 新行）。

**Python 在本 checkout 里不存在**（已核实）：`code-runtime-worker-thread` 只发布 `language='typescript'` / `isolation='worker-thread'`；`run_code` 执行的是 TypeScript（type-strip + `worker_threads` + `resourceLimits`，默认 `computeMs 60s` / `maxWallMs 600s` / 512MB）。Python 只作为**展示口味**存在（`packages/core/tools/src/py-types.ts`）——好消息是 `run_code` 会自动采用该口味，所以补一个 `language='python'` 的 `ctx.codeRuntime` provider 是"接缝级"工作。

**两个隐藏细节（会咬人）**

1. **沙箱语义陷阱**：容器/微VM/远程执行是**整条接缝的兄弟实现，不是 `ctx.sandbox` 的 provider**；`read-only|workspace-write|danger-full-access` **只管文件效果**（原文："Network and process visibility are outside this vocabulary"），**DSH 完全不定义 CPU/内存/时间策略**——你的服务必须自己强制。**而且**：如果保留 `bash-sandbox` 而只换掉 `subprocess` provider，DSH 会用**宿主机 runner（bwrap/Landlock）**包裹 argv 再交给你的服务——**你的容器里得备好那些二进制，否则每次受限调用都会 fail-closed**。
2. **产物在模型侧不可见（对你这个产品最要命的一条）**：`ctx.attachments` 是**图片专有**接缝（png/jpeg/webp/gif），`ContentBlockMap` 只有 `text|reasoning|image|tool-call|tool-result`——**没有"文件"块**。所以 docx/xlsx/pdf/mp4/svg **没有回到模型的路径**：模型看不到自己刚生成的成片/文档，只能靠文本描述。DSH 自带的 `ui-deliverables` 也只认"工具的 mutation locations"，并明确说明**由终端命令间接产生的文件不在其词汇内**。你要的"产物 → MinIO → 资产行 → 消息文件卡"，除了**图片**之外，在 DSH 里**全都要自己造**，且模型可见性仍然缺失。
3. **按会话隔离工作区也不顺**：`SessionHeader` 有 `id`/`cwd`，`SandboxExecutionPolicy` 也带 `sessionId`（文档说"so backends key per-session state off it"），**但它只在 `fs.writeText/editText` 上传递**——`SubprocessSpawnSpec`、`resolveExecutable`、`fs.read*` 都没有；而且 **DSH 自己不创建 per-session 工作目录**。你现在的 `/workspaces/{conversationId}/{work,output,vendor}` 模型要映射过去得自己找替代键（cwd 或"首次 mutation 时捕获 sessionId"），这是个真实的适配坑。

**对你的含义**：阶段 2 的"搬执行面"从"1-2 月"修正为**约 6-14 周（Python 1-2 + fs 1-3 + subprocess 3-6 + 产物 1-2，可部分并行）**，而且**产物链路与 per-session 工作区是净新增，不是平移**。这条审计反而强化了结论：**MoonSandbox（1,038 行、按会话工作区、产物兜底）留在产品主链路上更划算**，DSH 那边最多把 Python 执行当工具接过去。

---

---

## 3. 针对你的三个后续需求

### 3.1 多租户 token 审计

**结论：主体放 LLM 网关，agent 只做对账源。**

| 能力 | 你现在有的 | DSH 有的 | 缺口 |
|---|---|---|---|
| 会话级 token 展示 | `ChatStatusBar`、`ContextUsageRing`、`contextStatus.ts`、`agentStore.mergeUsage`（input/output/cached/total、modelCalls、durationMs） | `token-meter` + `session-stats` 投影 + UI 条 | — |
| 明细持久化 | 见审计 ② | 会话日志内（jsonl/sqlite），无独立计费表 | 需自建明细表 |
| 跨会话/跨租户聚合 | 需自建 | **无**（投影是 per-session） | 需自建汇总 |
| 价格 / 配额 / 余额 | 未确认（审计 ②） | **完全无** | 需自建价表与配额 |
| 租户身份 | JWT + `user_id` + project 归属 | 只有匿名 id | 必须用你的 |

**已确认的存量能力（本轮自查，供审计②复核）**：MoonAgent 侧的 usage 采集**相当完整**——`AgentTurnMetrics`（input/output/cached/total/contextTokens/contextWindowTokens/modelCalls/complete/durationMs/modelDurationMs/systemPromptTokens/toolDefinitionTokens/messageTokens）、`AgentUsageAccumulator`、`AgentTraceAccumulator`（同时吃 Spring AI `Usage` 与 AgentScope `ChatUsage`），并经 `AgentScopeEventMapper.payload` 写入事件、随 `turn_completed.payload.metadata.metrics` 落库到 **Postgres 的 `agent_turn_events`**，且带 `user/project/conversation` 归属。也就是说：**按租户/按会话的 token 汇总，你这边已经比 DSH 更接近可用（DSH 是 jsonl/sqlite + 投影缓存）**；缺的主要是**媒体成本、价表、配额**。

**落点建议**：优先扩展 `AILLMGateway.call_log`（已存在：`id / model / channel_id / status / duration_ms / error / video_url / created_at` + `task_id` + `call_id`，异步落库 + 冷热分离），而不是新起一套；再增加 `llm_usage_records` 明细（每次 chat/image/video 调用一行：`tenant_id, user_id, project_id, conversation_id, agent, provider, channel, model, vendor, kind(chat|image|video), input_tokens, output_tokens, cached_tokens, unit(张|秒|次), quantity, unit_price, cost, status, latency_ms, created_at, idempotency_key`）+ `llm_model_prices` 价表 + 按小时/天汇总表。理由：

1. **网关是唯一必经点**——chat / 文生图 / 图改图 / 视频全部经它转发（CLAUDE.md 已明确）；
2. **图/视频根本不经过任何 agent 内核**，只有网关看得见；
3. **与内核选型解耦**——AgentScope 或 DSH 都只是"谁发起调用"，计费逻辑不随之重写；
4. 网关已有 `StaticApiKeyFilter` 做调用方识别，天然是租户/项目归属的挂载点。

### 3.2 图 / 视频生成消耗

DSH 全仓库 **0 个生图/生视频接缝**（capability-seams 全表无对应条目；附件接缝只管栅格图片输入，且明确"generic files, audio, video … require separate lifecycle and provider contracts"）。所以：

- 这部分能力**永远是你的**（网关 MCP `generate_image` / `generate_video`），不是内核能力——这本身也是"内核替换收益有限"的核心论据。
- 计费需要的最小闭环：**调用明细**（含 `n`、`size`、`resolution`、`duration`、`aspectRatio`、`vendor`、`taskId`、成功/失败、重试次数、耗时）+ **单价表**（按模型/尺寸档/时长档，含阶梯）+ **汇总**（租户 × 项目 × 模型 × 日）+ **幂等键**（防视频轮询把同一次生成记两遍——你现有 `pollVideoTask` 的双通道反查就是同类问题）。
- 视频尤其注意：你的链路是"创建 task → 轮询 → 下载转存"，**计费必须挂在创建成功那一刻**并带 `taskId` 幂等，否则轮询重试会重复计费。

### 3.3 多租户隔离

- 走**双入口**（推荐）：DSH 只在内部/单人使用，产品多租户仍由 `ApiGateway` + JWT + MoonAgent 承担，**多租户问题不存在**。
- 走 **DSH 内核**：必须自己实现"一租户一 runtime + 会话归属校验 + 事件过滤 + 配额"，因为 DSH 侧这三样都没有。这是一笔独立于 agent 逻辑的、纯平台的工程量（数量级：与你现在 API 网关鉴权同级的重写）。

---

## 4. 三种形态与成本对照

| | A. 双入口（推荐） | B. 中间态 | C. 内核替换 |
|---|---|---|---|
| 形态 | MoonAgent 主链路不动；DSH 作为内部高级客户端，经网关（settings.yaml）+ MCP 挂 Moon 工具 | DSH 当内核，但保留你前端与资产闭环：自写 `SessionPersistence`(Postgres)、远程 `fs/subprocess` provider 指向 MoonSandbox、approval/userQuestions 的 UI 通道插件、client plugin 承载画布与项目库 | DSH 全面接管 agent 运行时、会话、UI、沙箱；MoonAgent 退化为无状态工具服务 |
| 触及代码 | 0 行 Java；新增 `cordis.patch.yml` + `settings.yaml` | 新增 TS 插件 5-8 个；MoonAgent 保留（去掉 loop 后的工具面） | 弃 ~20,288 行 Java；前端 32,638 行大部分重写；新 TS 插件 10+ |
| 多租户 | 不存在（DSH 不面向用户） | 需自建（DSH 无鉴权/租户） | 需自建（同上，且更痛） |
| HITL | 不动 | 写 UI 通道插件（approval + userQuestions） | 同 B，且需与 DSH 卡片风格统一 |
| 资产闭环 | 不动 | 自写 provider + attachment/storage 接缝（**附件接缝仅栅格图片**） | 同 B |
| 周期 | 1-2 天见效果 | **6-14 周（审计④修正）** | 1 季度+，且长期跟 upstream breaking |
| 风险 | 低 | 中（前端协议迁移、多租户自建） | 高（沉没成本 + 上游 0.x + 多租户自建） |
| 收益 | 内部提效、验证 DSH 能力、为将来留门 | 内核能力升级（子智能体/工作流/LSP/sandbox 安全/多平台客户端） | 同上，但代价最大 |

---

## 5. 分阶段方案与验收判据

### 阶段 0：验证（1-2 天，0 行业务代码）

1. 建 `~/.dsh/settings.yaml`，写 `llm-pi-ai:` 路由指向 `http://<gateway>:8083/v1`（`api: openai-completions`，`apiKeyEnv` 引用网关 Key，`compat.thinkingFormat: deepseek`，模型列出 `deepseek-flash`、`gemini-3-flash-preview`（`input: [text, image]`））。
2. 在 `~/.dsh/profiles/desktop/cordis.patch.yml` 追加 `@deepseek-ai/dsh-mcp-client` 行，挂 MoonAgent MCP 与网关 `/mcp`，`toolCallTimeoutMs: 1800000`。
3. **前置安全项（必须与阶段 0 同批做）**：① 网关 `/mcp` 加鉴权；② MoonAgent `/mcp` 加 project/conversation 归属校验、不再无条件信任 `X-User-Id`；③ 确认 nginx/ApiGateway 是否需要显式转发 `/mcp`（栈内当前不暴露，本机验证需临时放通）。
4. **可获得的工具清单（确定的）**：MoonAgent 5 个 + 网关 2 个 + 画布 19 个 = **26 个**；**HITL 追问、SSE 事件、确认卡片、产物文件卡都不在 MCP 边界内**，仍归你。
5. 验收判据（缺一即阶段 0 未通过）：
   - DSH 里能调用 `mcp__moon-agent__*` 工具并成功写分镜；
   - DSH 里能触发一次生图，且回合末尾出现产出文件/图片；
   - 网关日志能看到 DSH 的请求，且**思考增量与 tool_calls 是逐块流转的**（不是攒完再吐）；
   - 长任务（生视频）在放大超时后能跑完不中断。

### 阶段 1：并存期（2-4 周）

- 把领域能力从"编排内调用"继续抽象为 **MCP 工具 + SKILL.md**（你已是 `SKILL.md` 形态，接近零成本；注意 DSH 的 skill 是扫 `.dsh/skills`、`.agents/skills`、`$DSH_HOME/skills`）。
- **同期落地计费**（与内核选型无关，收益确定）：网关 `llm_usage_records` + `llm_model_prices` + 汇总任务 + 按租户查询接口；agent 侧 usage 只作对账。
- 验收判据：一张任意租户的账单可复算（网关明细求和 = 账单），图/视频按张、按秒计价可追溯，视频重试不重复计费。

### 阶段 2：按需升级执行面（**修正为 6-14 周**，可选）

只有当阶段 0/1 证明 DSH 的循环/沙箱/子智能体确实优于 AgentScope 时才做：远程 `fs`/`subprocess` provider → MoonSandbox、`SessionPersistence` 的 Postgres 实现（有 `runPersistenceContract` 契约套件可用）、用 `conversation.composer` 低优先级接管渲染自有 HITL 卡（审计③：不必替换 provider）、承载画布/项目库的 client plugin（`conversation.view` 页签或 `shell.overlay`，不必 fork）。**此时 MoonAgent 仍保留领域与资产服务。**

### 阶段 3：内核替换（不建议，除非满足第 7 节判据）

---

## 6. 反方观点（为什么"不做"是更可能正确的答案）

1. **能换的部分最贵**：20,288 行内核等价物 + 32,638 行前端都绑在你的协议与产品语义上，DSH 并不是"少写代码"，而是"换一种写法"。
2. **该留的部分最便宜**：领域 7,158 行 + 沙箱 1,038 行本来就与协议无关，经 MCP/工具交给 DSH 只需要配置。
3. **计费收益与内核选型正交**：你现在就能做，且做完后换不换内核都不返工——**先把这件事做了，再谈内核**。
4. **上游节奏与你的红线文化冲突**：DSH 明确会 breaking、会话格式版本 0；你的 CLAUDE.md 里那套"不可回归红线"是踩过真实事故换来的，跟上 0.x 的收益未必覆盖迁移成本。
5. **DSH 的强项并不落在你的痛点上**：它的优势是通用工具面（LSP/terminal/run_code）、子智能体编排、跨平台沙箱、多客户端与 SDK。对"生成分镜 + 出图出视频 + 交付产物"这条链路，边际收益有限；而它**唯一确定能给**的是 sandbox 安全边界（Landlock/Seatbelt/ACL + fail-closed 审批）与 deliverables UI——前者可作为**定向借鉴**（给 MoonSandbox 补 seccomp/独立容器/强制密钥），后者你已有等价实现。
6. **产物在 DSH 里"回不去"**：`ContentBlockMap` 没有文件块（只有 text/reasoning/image/tool-call/tool-result），`ctx.attachments` 只认四种栅格图片 → 你生成的 docx/xlsx/pdf/mp4 **模型自己看不见**，UI 侧也只有 chips 没有下载路由。而"产物交付"恰恰是你这条业务链的终点。

---

## 7. 决策清单：什么时候该做

**该做（满足任意 2 条）**
- 要把 agent 能力作为产品**卖给第三方开发者**（需要 SDK/插件生态，DSH 的插件面是现成的）；
- 产品要从"分镜创作"扩展到**通用 coding / 自动化**场景，且自研速度成为瓶颈；
- 需要**多进程/多平台客户端**（Web + 桌面 + IDE）且自研客户端成本高于迁移成本；
- 团队扩到能长期养一个**上游跟进 fork**（≥2 人专职平台）。

**不该做（满足任意 2 条即维持现状）**
- 未来 1-2 个季度的目标是业务功能与商业化，而非平台化；
- 团队 < 5 人，无专职平台工程；
- 多租户/SDK 生态尚非当期需求；
- 你对"上游 breaking 导致线上事故"的容忍度低于对"内核迭代速度"的渴望。

**当前判断**：按本仓库现状（自有协议深度耦合、红线文化、多租户尚未落地），**走 A（双入口）+ 立即做计费**，把 B/C 留作阶段 2/3 的可选项。

---

## 8. 不可逆点提醒

- 一旦自建 `SessionPersistence` 与 UI 通道插件，就等于承接了 DSH 会话格式的迁移责任（`SESSION_FORMAT_VERSION` 无兼容承诺）。
- 一旦把 HITL 卡片改由 DSH 的 approval/userQuestions 帧驱动，就放弃了"审批请求自带工具参数"这一便利（DSH 的 `ApprovalRequest` 故意不带参数，answerer 要用 `callId` 关联已流出的 tool/call 事件）。
- 一旦多租户跑在 DSH 进程内，就等于自己实现了一整套会话归属与事件过滤——**这笔工程量与内核能力无关，纯粹是平台税**。

---

## 9. 三问答复：多租户改造路径 / 沙箱内共存 / 自建同等能力

### 9.1 多租户：能加插件，也能改源码——但边界很清楚

**加插件能做的（免 fork）**

- **agent 作用域注册**：`tools` / `commands` / `skill` / `system-prompt` / `jobs` 都走 `ScopedLayers`，可按 agent 隔离；`ctx.scope` 注册视图向下继承、事件准入向上扩展。
- **agent 作用域审批 answerer**：`approval/request` 的 listener 支持按 agent 作用域（"Agent-scoped listeners receive only that agent's requests"）。
- **租户账本**：`ctx.storage` / `ctx.storageDomain`（`DomainSpec` + 类型化 `KvTable`，durable-before-memory）+ `session/event` 监听器，可建"租户 × 会话"的用量表。
- **自有 HTTP 路由**：`ctx.webServer.register({kind:'prefix', path, handler})`——鉴权你自己带；`tapIndex` 可往每个 index 注入资源。
- **凭据与 UI**：`ctx.credentials` 按租户；client plugin 承载你自己的界面。

**必须 fork 的（没有接缝）**

| 缺什么 | 为什么插件补不上 |
|---|---|
| Web 服务端鉴权 | `ctx.webServer` 只是 named-route 注册表 + `tapIndex` + `registerFallback` + `registerUpgrade`，**没有中间件/请求前置链**；文档自述 "there is no TLS, auth, or origin policy"，且 `dsh web --host 0.0.0.0` 在引入认证层前 "intentionally unsupported" |
| 会话所有权与事件过滤 | `session.event` 是**设计上** "every session in the runtime, unfiltered"；`ctx.sessionQuery` 无 owner 过滤、无 principal；apiproxy 的授权只有血缘式（`subagent-unauthorized`）与 `cwd` 相等 |
| 每 agent 追问 UI | `UserQuestionProvider` 是"一个上下文只能有一个 provider"，第二个注册抛 `DUPLICATE_PROVIDER`，**不是 agent 作用域** |
| 配额/预算 | `agent-loop` 明说 **"No built-in turn budget"**；goal 的 token/currency/quota 策略与消息不互相归属 |

fork 规模：`packages/host/webserver` + `packages/client/connection` + `packages/host/apiproxy` + `packages/session/session-query` + `packages/interaction/user-questions`，外加把 principal 贯穿 RPC 层。MIT 允许改，但上游是 developer preview 且自述"内部 API"（新增值导出需签核）→ **换来一条永久维护尾巴**。

**推荐（免 fork）：进程即租户。**

一用户/一会话一个 DSH 子进程 + 独立 `DSH_HOME`，由你的后端经 **SDK stdio** 驱动，**完全不暴露 HTTP**：

- 不暴露 → web-server 无鉴权这条从"致命"变成"不存在"；
- `DSH_HOME` 独立 → **白拿文件系统级隔离**，复用你现有的"会话即目录"心智；
- 取消 = 关掉子进程（文档口径就是"关闭 runtime 进程即放弃 turn"），会话落盘后可重启恢复；
- 租户配额、账本、归属全部留在你的 ApiGateway + 网关，**不进入 DSH**。

你现在的 ApiGateway 已经在做"用户 → 后端实例"的映射，进程级租户只是把被映射对象从 Java 服务换成 dsh 子进程——**同一件事，不是新架构**。

### 9.2 沙箱内存放 DSH 并与 Moon 交互：可行，且是当前最优雅的形态

**已核实的事实（含一个会咬人的坑）**

- **网络是通的**：`sandbox-runner` ∈ `default + sandbox-internal + sandbox-edge`；`moon-agent` ∈ `default（固定 IP）+ sandbox-edge`；`llm-gateway` 与 `canvas` ∈ `default` → **runner 可直连 8084/8083/9090，无需开 egress**。
- **坑**：runner 环境里设了 `HTTP_PROXY/HTTPS_PROXY=http://sandbox-egress-proxy:3128`，而 `NO_PROXY` 只含 `localhost,127.0.0.1,::1,sandbox-runner,sandbox-egress-proxy`——**不含 moon-agent / llm-gateway / canvas** → 必须给 dsh 子进程补 `NO_PROXY`（或清空 proxy 变量），否则内部调用会被 Squid 判 `deny`。
- **镜像没有 Node**：`FROM eclipse-temurin:25-jre-noble` + Python 3.12（python-docx/matplotlib/pandas/openpyxl/Pillow）；root FS `read_only: true` → **Node 24 + dsh profile 必须烤进镜像**，原生模块（sharp / node-pty / koffi / @vscode/ripgrep）在构建期编好。
- **资源与安全**：`tmpfs /tmp` 512m、`mem_limit 2g`、`pids_limit 256`、`cap_drop ALL`、`no-new-privileges`、UID 10001。→ `DSH_HOME` 必须落 `/workspaces/{conversationId}/.dsh`（**唯一可写卷，且不能放 /tmp 那个 512m tmpfs**）；2g 对"JVM + python 子进程 + N 个 node"偏紧，需抬内存或压并发；pids 256 同理。
- **沙箱套沙箱不成立**：`cap_drop ALL` + `no-new-privileges` 下 DSH 自带的 bwrap/Landlock 会 **fail-closed** → DSH 必须以 `danger-full-access`、关掉自沙箱运行。**边界由 MoonSandbox 提供，DSH 只当执行体**——这是分工，不是缺陷。
- **白拿的最大收益**：DSH 的 fs 工具与你的 `run_python_script` **共享同一个 `/workspaces/{conversationId}`** → 不管谁把文件写进 `output/`，现有 `SandboxOutputReconciler` + `SandboxArtifactPublisher` 照旧扫描、上传 MinIO、写 `agent_assets`、生成消息文件卡。**这顺手解决了审计④里"`ContentBlockMap` 没有文件块、产物回不去"的最大坑**：模型看不见内容，但用户看得见、资产闭环成立。

**两种交互形态**

- **A（推荐）：Moon 是宿主，DSH 是沙箱内的执行体/第二大脑。** Moon 经 SDK stdio 驱动 dsh 子进程；HITL、事件序号、产物发布全部留在 Moon。风险最低，且不要求先修 `/mcp`。
- **B：DSH 是宿主，Moon 当工具提供方。** dsh 挂 `mcp__moon-agent__*`，但**必须先修网关 `/mcp` 鉴权与 Moon `/mcp` 归属校验**（见 2.3），否则等于把 IDOR 和免费生图接口暴露给沙箱内进程。

**落地清单**：镜像加 Node + 预置 profile/settings → `DSH_HOME` 落会话目录 → `NO_PROXY` 补内部服务 → dsh 以 `danger-full-access` 运行 → 会话级生命周期（按需启动 / 空闲回收 / 24h 过期 / 纳入现有 4 并发信号量）与取消（kill 子进程）→ 产物仍走现有 reconciler → 版本 pin（preview 会 breaking）→ 日志与审计接入现有 outbox。

### 9.3 能不能做到 DSH 这样的能力

分三层看，结论不同：

| 层 | 差距 | 能否追上 |
|---|---|---|
| **能力集** | AgentScope 已给 Plan Mode / Subagent / Compaction / StateStore / MCP / Skills / DistributedStore / OTel → **不落后**；DSH 独有的是 LSP、terminal-PTY、code-runtime、多客户端与 SDK/ACP | 可补，但多数与你的业务无关（分镜产品要 LSP 吗？） |
| **架构与纪律** | ~25 个能力接缝 + **共享契约测试套件**；插件化到 agent loop 本身；684 测试文件 + **per-file 100% 覆盖率门禁** + 生成式文档与漂移校验 + postmortem 文化 | **能，2-3 个季度 + 2-3 人** |
| **生态** | 30k fork、npm 分发、插件市场、社区、Discord | **追不上，也不必追** |

**最该抄的 5 个机制（按收益排序）**

1. **能力接缝 + provider + 契约测试套件**（DSH 的 `runPersistenceContract` 是范本）：把 `AgentRuntime` / `SandboxExecutor` / `ArtifactPublisher` / `ModelRouter` 定成接口，并写**一份所有实现共用的契约测试** → AgentScope↔DSH 从"重写"变成"换 provider"。**这一条直接决定你阶段 2 是 6 周还是 6 个月。**
2. **append-only 事件日志作为唯一真源 + "model-visible means logged" 不变量**：你已有 `agent_turn_events`，缺的是把不变量写成可执行测试（目前靠 CLAUDE.md 的文字红线）。
3. **`tools/pre-execute | execute | post-execute` 三段式管线**：把审批、审计、产物发布、**计费埋点**收敛到一个拦截点。你现在是 `AuditedToolCallback` / `CanvasToolGuardCallback` / `CanvasToolProgressCallback` / `ToolExecutionTemplate` 多个装饰器叠着——统一成命名管线阶段后，加能力不再需要改多处。
4. **`ProjectionDefinition` 式派生读模型**（纯同步 fold + 持久化缓存 + change feed）：让前端不再从原始事件重算——你的 `agentStore.ts` **4,366 行**就是这个症状的解药。
5. **权限预设 = 模式 × 策略成对**，审批结果用闭合词表 `allowed-once | rejected | cancelled | unavailable` 且 **fail-closed**：能系统性消掉"取消卡片复活""重复提交"那一类事故。

**一句话**：你可以做到 DSH 同等级别的**架构能力**，做不到也不必做同等级别的**平台与生态**。你的护城河是领域链路与产物闭环——把上面 5 条抄进来，你就有 DSH 约 80% 的工程能力，而不用背它的多租户缺口与"产物回不去"。

---

## 10. 一个容器放沙箱、不同人不同 dsh 会话进程：可行性与隔离等级

**结论：技术上能，而且"进程即租户"正是推荐形态；但"一个容器"只给你进程级隔离，不是安全隔离——而且有一个必须先在 MoonSandbox 修掉的漏洞。**

### 10.1 先修：runner 把内网密钥泄漏给了子进程（P0，今天就有）

`MoonSandbox/.../execution/SandboxExecutionService.java:85-92`（shell）与 `:142-149`（python）都是：

```java
ProcessBuilder builder = new ProcessBuilder(argv);      // ← 继承父进程完整环境
builder.directory(session.toFile());
builder.environment().put("PYTHONPATH", ...);           // ← 只 put，从不 clear
builder.environment().put("HOME", session.toString());
builder.environment().put("TMPDIR", tmpDir.toString());
```

Java 的 `ProcessBuilder` 初始环境是父进程环境的**副本**，所以每个被执行的子进程都会继承：

| 继承到的变量 | 后果 |
|---|---|
| `MOON_SANDBOX_INTERNAL_SECRET` | 子进程可调用 `127.0.0.1:8090` 的 `/internal/sandbox/{conversationId}/...`，**以完全可信的内部身份读写/执行任意会话的工作区** |
| `HTTP_PROXY` / `HTTPS_PROXY` | 内部服务调用被塞进 Squid（`NO_PROXY` 不含 moon-agent/llm-gateway），会被 deny |
| `JAVA_TOOL_OPTIONS`、`MOON_SANDBOX_*` | 沙箱配置与后续任何新注入的密钥一并外泄 |

**这不是加 dsh 才有的问题——任何 `run_python_script` 里的脚本今天就能跨会话操作。** 修法很简单也很标准：**`builder.environment().clear()` 后再显式放入白名单**（`PATH`、`LANG`/`LC_ALL`、`TZ`、`HOME`、`TMPDIR`、`PYTHONPATH`、`PYTHONUNBUFFERED`、`PYTHONDONTWRITEBYTECODE`、以及 dsh 需要的 `DSH_HOME`/`NO_PROXY`）。同时把 runner 的 8090 保持在最小可达面（当前子进程能访问容器内 127.0.0.1:8090）。

### 10.2 一个容器里的隔离等级（同一个 UID、同一个内核）

现有约束：`user: "10001:10001"`、`cap_drop: ALL`、`no-new-privileges`、`read_only` 根、`tmpfs /tmp 512m`、`mem_limit 2g`、`pids_limit 256`、`cpus 2.0`。在这个前提下：

- 多个 dsh 进程 = **同 UID、同 PID/网络/挂载命名空间**，只是不同 cwd 与不同 `HOME`。
- **dsh 自己的沙箱救不了你**：`cap_drop ALL` + `no-new-privileges` 下 bwrap/Landlock 会 fail-closed；而且 DSH 的 `read-only|workspace-write` 语义**只管写侧**，"read-side confinement/network policy are out of scope" → **DSH 无法阻止 A 会话的 dsh 读 B 会话的目录**。
- 真正生效的是"约定级"隔离：路径校验（拒绝绝对路径、`..`、软链）由**你的 Java 服务**执行，而不是内核。

| 方案 | 隔离强度 | 需要的改动 | 适用 |
|---|---|---|---|
| **A. 同 UID 多进程**（今天） | 目录约定级 | 无（但要先修 10.1） | 所有租户互信：内部团队、演示、单人 |
| **B. 每租户一个 UID** | **OS 强制读隔离**（`chown` + `chmod 700`；同 UID 才能读） | 镜像预建 UID 池；runner 需保留 `SETUID/SETGID/CHOWN`（`no-new-privileges` 只挡提权、不挡降权，故由 root supervisor 直接 drop 可行）；按租户分配/回收 UID | 少量、半信任的租户 |
| **C. 一租户一容器**（预置 runner 实例池 + 路由按租户选实例） | **内核级隔离** + 每租户独立 mem/cpu/pids 配额 + 无横向移动 | 编排层（你本来就不挂 docker socket，所以走"预置池"而非动态起容器） | **对外多租户的唯一正确答案** |
| D. microVM/沙箱内核（Kata、Firecracker） | 最强 | 基础设施重 | 暂不考虑 |

**要点：一个容器里的多进程永远达不到"多租户隔离"**——它给你的是"多会话进程 + 共享内核"。要么 B（同容器内抬到 UID 级），要么 C（抬到内核级）。

### 10.3 现有执行模型与"长驻 dsh 守护进程"不兼容

`SandboxExecutionService` 的模型是**一次性调用**：`admission` 公平信号量（4）+ 每会话 `ReentrantLock` 在**整次调用期间持有**；超时默认 300s / 上限 600s；超时后 `descendants().destroyForcibly()` + `destroyForcibly()`。

因此：**长驻 dsh 会一直占住一个信号量槽和会话锁，并在最多 600s 被杀。** 正确形态是 **per-turn（短生命周期）dsh 进程**：启动 → 经 SDK stdio 跑完这一轮 → 退出。**会话连续性来自磁盘上的 `DSH_HOME`，不来自进程存活**（这正是 SDK 的设计口径：放弃一个 turn 就关掉 runtime 进程）。

一个额外好处：runner 已经设了 `HOME=<session>`，而 DSH 默认 `DSH_HOME=~/.dsh` → **无需额外配置，dsh 的状态天然落在 `{session}/.dsh`**，一会话一份（sessions/attachments/credentials 全隔离，属应用级隔离）。

### 10.4 两种落地路径

- **路径 A（MVP，零 runner 改动）**：按 per-turn 跑 `sh -c "dsh ... > work/dsh-events.jsonl 2>&1"`，Moon 用现有 `read` 端点轮询该 jsonl 并映射成自己的 SSE。限制：受 600s 上限（可抬 `MOON_SANDBOX_MAX_TIMEOUT_SECONDS`）、**运行中没有 stdin 通道**（HITL 只能留在 Moon 侧、下轮再带上下文）、超时即杀。适合先验证。
- **路径 B（正道）**：给 MoonSandbox 加"受管进程"能力——`start / stream(stdin+stdout) / stop / status`，复用同一套 admission 与 per-conversation 锁，但用**独立的长期进程池**（建议 2-4 槽，空闲回收而非硬超时），并且**先做 10.1 的环境白名单**。这才是"一个容器、N 个 dsh 会话"的真正底座。

### 10.5 资源与运维（必须调）

| 项 | 现值 | 建议（含 dsh） | 原因 |
|---|---|---|---|
| `mem_limit` | 2g | **4-6g** | JVM + python 子进程 + N×(node ≈150-300MB + worker threads) |
| `pids_limit` | 256 | **512-1024** | pids cgroup 把**线程**也算进去；node 多线程、`run_code` 用 worker_threads、每次 grep 起 ripgrep |
| `cpus` | 2.0 | 视并发 2-4 | 与现有 4 路执行槽叠加 |
| 并发 | 执行槽 4 | **长期进程池单列 2-4** | 长任务不应与 `run_python_script` 抢同一批槽 |
| 回收 | 24h 空闲 + 10min 清理 | 增加**进程回收** | 清理 job 删目录前必须确认该会话的 dsh 已退出 |
| 网络 | `NO_PROXY` 缺内部服务 | 补 `moon-agent,llm-gateway,canvas,sandbox-runner` | 否则内部 MCP/网关调用被 Squid deny |

### 10.6 一句话

**"一个容器 + 不同人不同 dsh 进程"是可行的，而且 per-turn 进程比长驻 daemon 更贴合你现有的执行模型；但它等于把租户边界从"容器"降级成"目录约定"。** 对外多租户走 **C（一租户一容器/实例）**；内部或半信任租户可先走 **A/B**，但**必须先把 10.1 的环境泄漏修掉**——否则沙箱内任何一个 dsh（或今天的任何一个 python 脚本）都能拿内网密钥跨会话操作。

---

## 11. DSH 插件级支持盘点（对照你的五个需求）

**结论：比我上一轮说的多——"每个用户一个工作区"DSH 有官方实现，"常驻进程"也有模型可见的实现；但"产物清理"和"多租户"仍然是零。**

| 需求 | DSH 是否有插件支持 | 依据与限制 |
|---|---|---|
| **每个用户一个工作区** | ✅ **有（一等公民）** | `@deepseek-ai/dsh-workspace`：`ctx.workspaceRegistry.create(path,title?)` 经 `fs.realpath` 规范化、**一个规范路径最多一条记录**、持久化排序；`get/list/resolveByPath`；`insertBefore`；**`Workspace.attachSession(id)` 用会话 header 的 `cwd` 与工作区路径比对来归属会话**；`archiveSession` 软隐藏（保留会话日志与位置）；`status()` 返回 `ok\|missing-dir`。需要 `storageDomain` + `sessionPersistence` 作启动依赖。UI 侧有 `client/ui-workspace` |
| **一个 runtime 承载多会话** | ✅ **有** | SDK server：**"gets or creates one agent per `sessionId`"**，`SessionPromptParams` 带 `sessionId`；host apiproxy 提供 session create/list/search。**但协议没有 cancel / session-close**，SDK 创建的 agent 活到进程退出 |
| **常驻 / 后台进程 + 流式** | ⚠️ **部分有（三条路）** | ① `dsh-tool-bash-persistent`：一个 Agent 一个 shell，**"cwd、exported variables、activated environments、functions、background jobs persist across calls"**，`timeoutMs` 默认 300s（超时关 shell）；② `ctx.terminals` + `terminal-bash` + `tool-terminal`：**owner-scoped 持久 PTY**，一个会话同时只允许一个 live send；③ `ctx.jobs` + `jobs-local` + `tool-jobs`：后台任务注册表，**流式任务有单消费游标 `read()`**，owner 隔离按 SessionId |
| **产物文件保留 / 清理** | ❌ **没有** | `dsh-output-retention` 只是**限制"喂给模型的输出"的上下文预算库**（`ItemRetainer`/`TextRetainer`，head/tail/headTail），与磁盘文件无关；workspace 的 `delete(id)` **明确不碰目录与用户文件**，文档原话：**"Session deletion and destructive folder removal are separate, absent capabilities"** |
| **多租户 / 鉴权 / 配额** | ❌ **没有** | 见 2.2（identity 仅匿名 UUID、web-server 无鉴权、SDK `session.event` 不过滤）与 2.4（无配额、无预算） |

**三条必须注意的限制（都是文档原话）**

1. `ctx.jobs`：**"The contract is in-process"** —— `JobStart.run()` 传的是回调与活的 `Agent` 对象；"a durable or cross-process backend must reshape identity, restart, ownership, and observation semantics"。→ **不跨重启恢复**。
2. `ctx.terminals`：**"Sessions are process-local and are not restored after a harness restart."** 且 **"Cross-agent sharing is intentionally absent"**。
3. owner 隔离只是**围栏**而非安全边界：jobs 文档自述 **"Ids such as `bash-1` are predictable, so this fence is the boundary."** → 单进程多用户下这是可猜的围栏。

**对方案的三个修正**

1. **"每个用户一个工作区"不用自己设计目录约定**——用 `workspaceRegistry.create()` + `session.create({workspaceId})`（host 会 `mkdir -p` cwd），会话按 cwd 自动归属，软隐藏用 `archiveSession()`。你只需补两件 registry 不管的事：**user → workspace path 的映射**与**归属校验**（registry 没有 owner 字段）。
   - 形态差异：**一用户一进程**时每个 `DSH_HOME` 各有一份 registry（天然隔离）；**单进程多用户**时 registry 是进程级的全局表 → 归属校验就变成必需品。
2. **"受管进程端点"的范围可以缩小一档**：因为 dsh 自己就有 persistent shell / PTY / 后台任务能力，Moon 只需要维持 **dsh 进程生命周期 + stdio 桥接**，**不必在 runner 里实现 PTY 流式与终端渲染**。第 10.4 路径 B 的工作量随之下调（仍受"不跨重启恢复"与"单消费游标"两个限制约束）。
3. **产物与清理仍然完全是你的**：workspace 的 delete 不删文件、没有 GC、"destructive folder removal" 明确缺席 → 用户注销/额度到期时的目录回收必须复用你现有的 24h 空闲 + 10min 清理 job。这同时说明：**把 MinIO + `agent_assets` 链路留在你这边是唯一选择。**

### 11.1 第二轮盘点补充（含两处对我前文的修正）

**修正 1：cancel 有，但不在 SDK 协议上。** `packages/host/apiproxy/src/api/sessions.ts` 的 `SessionsApi` 提供 `list / search / create / history / fork / prompt / cancel / models / selectModel / rename / attachment`——**host RPC 有 `cancel`**；"没有 cancel"只适用于 **SDK 的 stdio 协议**（"The wire has no per-session close or prompt-cancel method"）。所以若走 host RPC（Typert）路线，取消是可以的；走 SDK 路线则只能杀进程。

**修正 2：一个进程可以跑"配置各不相同"的多个 agent。** `packages/preset/README.md:5`：挂载 preset **"gives that session its own tools and prompt sections while every other live session keeps its own, so one process can run several differently composed agents at once."** → 这意味着**可以按用户/套餐给不同会话不同的工具面与提示词**（例如免费版不给视频工具），而且不需要多进程。这是我没预料到的能力。

**工作区（NEED 1）补充**

- 家族定位原文（`packages/workspace/README.md:5`）：**"This family owns persistent workspaces: user directories with titles and ordered session membership."**；`docs/subsystems/workspace.md:5`：工作区是 **"the persistent record of a directory the user works in: a stable id over a canonical path, a display title, and the ordered account of sessions that belong to it."**
- `session.create` **"At most one of `workspaceId` / `cwd` is accepted"**，归属失败返回 `workspace-attach-failed`；有推送帧 `host/workspace-removed` / `host/workspace-order-changed`（可直接驱动你的 UI）。
- 持久化走 `ctx.storageDomain`（`storage-sqlite` / `storage-json`）→ **durable、单机、文件型**。
- 仍然：**没有任何 tenant/user 字段**；`workspaceId` 只出现在 workspace/session/events 代码里，从未作为租户键。

**多会话（NEED 2）补充**

- 并发控制**只有局部**：每个 Agent 单驱动回合仲裁（`agent-loop`：两个同 id 创建都会准备，但 **"the final `enter()` calls arbitrate publication and every loser rolls its private resources back"**）；`agent-busy` 只针对 session-backed **subagent**；jobs 上限 `maxConcurrentJobsPerOwner` 默认 10；terminal 一会话只允许一个 live send。
- **没有跨进程写者互斥**：`session-persistence/README.md:59` 原文 **"This freshness check does not add cross-process writer exclusion."** → 若你跑多个 harness 进程共用一份存储，必须自己做互斥。

**常驻进程（NEED 3）补充——比我上一轮说的更强**

- **`tool-terminal` 才是最强的那个**：6 个工具含 **`terminal_send`（模型可在运行中写 stdin）**、`terminal_read`（分页）、`terminal_signal`（SIGINT）、`terminal_close`；`enableRunInBackground=true`、`maxResultBytes=262144`；**"incremental task reads do not repeat consumed output"**。后台需要 jobs + tool-jobs 配合。
- `shell` 缝的边界（决定要不要用 terminal）：**"No interactive-input vocabulary — `stdin` is written once at spawn and closed; the seam has no channel to feed a running task and no PTY session concept."**；但 `shell.start()` 的后台进程 **"no timeout applies"**，且 `readOutput()` 是 **"Incremental output read — consecutive reads never re-deliver"**。
- `terminal-bash` 限制：**"Line-oriented output is normalized; full-screen alternate-buffer interaction is unsupported."**、**"Sessions do not survive harness process exit."**
- `jobs-local` 限制：**"Jobs are process-local — records die with the harness process"**、**"The registry does not queue, preempt, or maintain a second mutable counter."**（满了直接 `start()` 失败）、**"A silently ineffective cancel can stall teardown and hold capacity"**。
- `tool-jobs` 参数：`waitTimeoutMs=30000`、`maxWaitTimeoutMs=600000`、`completionDelivery=wakeup`、`maxConsecutiveWakes=3`；限制 **"Stream reads are single-consumer"**、**"Unowned jobs have no session fence"**。
- `tool-bash` 限制：**"Background processes have no executor timeout"**。

**保留与清理（NEED 4）补充——确认是零**

- `spill`：**"NO retention policy … NO retrieval/search API"**、**"The seam has no retrieval or deletion API"**、**"Storage is not access control"**。
- `spill-local`：**"Local spill files persist until external cleanup — the backend has no session-lifecycle deletion or age-based retention policy"**；`spill-policy` 的 `maxInlineBytes` **不配 = 策略关闭**。
- `session-checkpoint-policy` 只做事件日志耐久性：**"The policy durably records execution intent, not generic exactly-once effects."**、**"A persisted call without a result cannot prove whether its external effect completed."**
- 唯一的"导出"能力：`GET /api/session.export`（原始日志 + 引用图片的 ZIP）——**是导出，不是保留策略**。
- 结论不变：docx/pdf/mp4 的存储、TTL、回收**全都要你自己造**；DSH 只给了你一个可实现的缝（`ctx.spillStore`）。

**多租户（NEED 5）补充——文档自己承认了**

- `packages/host/apiproxy/README.md:81`：**"Search failures include provider diagnostics — the gateway is a single-user local service. A carrier that exposes it to multiple users must replace internal search details with a public-safe diagnostic."**
- `packages/session-query/session-query/README.md:55`：**"No caller authorization — this is trusted context-wide infrastructure"**
- `message-feedback`：**"`list`/`put`/`delete` carry no authenticated actor or audit identity."**
- `acp`：**"`authenticate` | No-op because the server advertises no authentication methods."**
- 配额相关全是 per-call / per-round：`llm` 的 **"No retry execution, caching, or rate limiting ships in this service"**、goal 的 **"Round cap, not resource budget"**、workflow 的 **"No token-budget vocabulary"**、subagent-report 的 **"No rate limiting"**。
- 宿主入口的选择：`dsh-web-app` 是**面向浏览器的托管入口**；`dsh-headless` 是**一次性单任务 runner、不监听端口，"unsuitable as a per-user service"**；`dsh-sdk-jsonrpc-server` 是 **"the closest thing to an embeddable multi-session API server"**。

**这轮盘点对落地方案的净影响**：① 工作区与"一进程多会话 + 每会话不同 preset"都是现成的，**你可以少造两块**；② "常驻执行"用 `terminal` 家族就够（含运行中 stdin），**不必自研 PTY 流式**；③ 但"跨重启恢复""跨进程互斥""产物生命周期""租户与配额"四项**全部仍要自建**，且文档已明说该服务是 single-user local service。

---

## 12. 登录与多租户：现成的开源实现（修正"必须自研"的结论）

**先说结论：DSH 内核没有登录（已核实），但生态里有两个开源插件把这件事做了——所以"必须自研"要修正为"可以外挂/借用，但要看清许可证与隔离强度"。**

### 12.1 DSH 自身的"登录"到底是什么

- **内核没有用户登录**：`identity` 只有 `$DSH_HOME` 级匿名 UUID（"do not represent an authenticated account"）；`host/webserver` 无鉴权；`client/connection` 自述 "The fence is a reachability policy, not authentication"；`dsh web --host 0.0.0.0` 在引入认证层前 "intentionally unsupported"；ACP 的 `authenticate` 是 **no-op**。
- **桌面版有"账号登录"，但语义不同**：本机 `~/.dsh/.credentials.yaml` 里有 `deepseek-account-platform/device` 与 `deepseek-account-platform/default` 记录，桌面 profile 的 `ui-settings-account` 配置项是 `completion: api-key` → 这是**"你登录 DeepSeek 账号以使用模型"**（厂商账号 / 设备码流程），**不是"你的用户登录你的产品"**。

### 12.2 方案一：`slywalker2006/dsh-passwords`（最完整的托管形态）

- 定位原文：**"Server-grade gateway that turns DeepSeek Harness into a multi-tenant platform: remote access + auto HTTPS, subuser permissions & quotas, sandbox enforcement, encrypted auth, audit log."**；中文自述"**让多人远程共用 DeepSeek Harness，按账号管理工作区、会话和用量**"。
- **架构恰好是我前面建议的形态**：它是一个**前置网关/反代**（`MCP_GATEWAY_UPSTREAM` 默认 `http://127.0.0.1:3080`），登录后才放行到 dsh；并非改内核。
- 功能（原文摘录）：登录认证（会话 12 小时）、**一主用户 + 任意多子用户**、**目录白名单 `allowedFolders`**、**逐会话授权**（工作区权限不自动包含其中会话）、**每小时 token 上限 + 每日时长上限**、沙盒三档、上传/下载开关、**SSH 与终端默认关闭需显式授权**、封禁、**登录限流与锁定、审计日志**、SQLite 里用户名与审计敏感字段**加密**、**bcrypt 密码哈希**、登出即吊销 token、宿主机**自动 HTTPS（Let's Encrypt，默认 `<IP>.sslip.io`）**、`/gateway/healthz` 与 `/gateway/readyz`、CLI `audit --limit 20`。
- 部署：Docker 一条命令（**镜像内置 DSH**，`-v dsh-home:/data/dsh`）、npm 全局、git 源码、Windows 安装器。
- **两个必须注意的点**：① **License = GPL-3.0 only** → 若你的产品闭源商用，**不能直接集成**，只能借鉴设计（除非你也开源）；② **版本门禁 DSH `>=0.2.1-alpha.1 <0.2.2-0`**，锁 `0.2.1-alpha.2` → 跟上游版本强绑定，升级要验收。

### 12.3 方案二：`king-bcolor/dsh-multi-tenant-projects`（最贴合"每个用户一个工作区"）

- 定位原文：**"per-user symlinked workspaces, login gate, session isolation, workspace-write lock and system-prompt guard"**；目标是"several people share one DSH deployment without seeing each other's sessions or files"。
- 机制：Project 绑定真实目录 → **每个用户一个 symlink 工作区**；用户键 `<project>/<user>`（`alpha/alice` 与 `beta/alice` 可共存）；**全屏登录门**；普通用户侧栏只显示自己 cwd 分桶的会话、设置与工作区切换器被 shadow；**会话被钉死 `workspace-write` 且拒绝 `/permission` 切换**；**两层提示词护栏**（host 注入"受限会话守则" + 每工作区 `AGENTS.md`）。
- **License = MIT**（商用友好），152 个 vitest 测试，带完整中文设计文档。
- **但它自己写明了威胁模型（必须原文引用）**：**"This plugin is a *soft* boundary, not a hard security perimeter. The cwd filter is a query projection, browser tokens can be forged by a technical user, and the agent-layer isolation is prompt-level. For anything exposed to the public internet, put a real boundary (reverse-proxy auth, ngrok basic auth, VPN…) in front of DSH."** 另外 `guardEnabled` 的登录门是 **fail-open**（插件 API 故障时放行）。

### 12.4 这两个方案反过来印证了前面的判断

1. **"DSH 无多租户"成立** → 生态的解法都是**在外面补**：一个做外部网关（12.2），一个在插件里打补丁并自认 soft boundary（12.3）。
2. **"只有进程/容器级才是真边界"成立** → 12.3 的作者自己写了 "put a real boundary in front of DSH"；12.2 用反代 + 逐会话授权 + 配额来补，恰好是"鉴权在网关、隔离靠进程/容器"的组合。
3. **"配额与账本要自己管"成立** → 12.2 做到了"每小时 token 上限 / 每日时长上限"这一档，但**没有媒体成本、没有单价、没有对账**，与第 2.4 节的结论一致。

### 12.5 对你的选择建议

| 你的目标 | 建议 |
|---|---|
| 闭源商用 + 对外多租户 | **不要直接用 12.2（GPL-3.0）**；借它的设计（外部网关 + 逐会话授权 + 配额 + 审计），在你自己的 ApiGateway 里实现；隔离仍走"一用户一进程/一容器" |
| 内部/少量可信团队，快速上线 | 可用 **12.3（MIT）** 做 UX 收窄 + 工作区绑定，**但必须再叠一层真边界**（你的 JWT 网关或 VPN），因为它的隔离是投影级、token 可伪造、登录门 fail-open |
| 只想验证概念 | 用 12.2 的 Docker 一键部署（内置 DSH）最快，能把"登录 → 子账号 → 工作区/会话授权 → 配额"整条链路先跑通，再决定自研哪一段 |

### 12.6 其他值得直接研究的开源先例

- **`nexu-io/open-design`（101k stars，Apache-2.0）** —— **"The 1st Collaborative AI Design Agent Workspace. Local-first and open source… Share work, exchange feedback, and iterate together in one workspace."**，topics 含 `deepseek-harness`/`dsh-plugin`/`figma-alternative`/`local-first`。**与你的产品最同构**（协作式设计 agent 工作区），建议直接研究它的工作区与协作/权限模型。
- **`loopx-project/loopx`（6.3k，Apache-2.0，Python）** —— "A control plane with a durable state kernel for long-horizon agents and teams"（长时程 agent 的持久状态内核，正是"跨重启恢复"那一块）。
- **`PerryLink/dsh-memento`（136，Apache-2.0）** —— "a `ctx.memory` capability seam with a zero-dependency SQLite provider, **approval-gated, auditable**" → **写"能力接缝插件"的最佳范例**（对应第 9.3 节建议抄的第 1 条机制）。
- **`MichengAI/dsh-codex-ui`**（workspace session tree 侧栏）、**`Totoro-qaq/dsh-plugin-bridge`**（session/preset/context 迁移）、**`NanmiCoder/dsh-agent-teams`**（2.1k，多智能体团队）、**`skymecode/deepseek-harness-for-vscode`**（VS Code 扩展，"zero-deployment runtime — no WebUI served"）。
- **精选列表**：`awesome-dsh-plugin/awesome-dsh-plugin`（18.7k stars）、`0xsline/awesome-deepseek-harness`、`Zhiyuan-Fan/Awesome-DeepSeek-Harness-Plugins`、`imsai-sh/awesome-deepseek-harness-plugins`（自称"1024 插件商店"）——查生态先翻这四个。
- **生态规模参考**：GitHub `topic:dsh-plugin` 共 **19,177 个仓库**（2026-10-14 查询）。