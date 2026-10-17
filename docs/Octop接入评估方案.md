# Octop / octop-harness 接入评估方案

> 评估对象：`TencentCloud/Octop`（自托管助手应用）+ `TencentCloud/octop-harness`（Agent 运行时库）
> 评估方式：**源码级审计**（浅克隆 `--depth 1`，只读，未运行）
> 日期：2026-10-17 ｜ 结论：**不采用 Octop 作宿主；octop-harness 可作可选内核，但 HITL 与事件发布必须留在 Java 侧。**

---

## 0. 先澄清三件容易混淆的事

| 说法 | 事实 |
|---|---|
| "腾讯开源了 WorkBuddy" | **WorkBuddy 本身未开源**，仍是闭源商业产品。腾讯开源的是 **Octop**，同一支腾讯云团队出品，媒体称之为"可自部署版 WorkBuddy"，但腾讯从未如此定义 |
| 网上那些 `workbuddy-*` 仓库 | 都是社区二创（账号池、签到脚本、便携启动器、OpenAI 兼容反代），与内核无关 |
| Octop 是一个应用 | Octop 是**五个可拆仓库**：`Octop`（应用）、`octop-harness`（运行时库）、`octop-gateway`（IM 桥）、`octop-memory`（分层记忆）、`octop-browser`（CDP）。真正可当内核的只有 `octop-harness` |

**规模（实测）**：Octop 后端 702 个 py / 137,412 行 + 测试 545 个 / 84,801 行；dashboard 1015 个 ts/tsx / 183,067 行；octop-harness 210 个 py / 36,456 行。与本仓库同量级。

**许可**：两者均 MIT（商用友好）。`octop-harness` 0.1.x、约 96 star、封装 LangChain `deepagents`，迁移责任链为 `你 → octop-harness → deepagents → LangChain → LangGraph`。

---

## 1. 六维裁决

| 维度 | 评级 | 决定性证据 |
|---|---|---|
| **多租户隔离** | **部分** | API 对象层归属校验一致（`require_agent_owner_row` / `_require_thread` / `get_writable_base`）；但宿主层四类突破：任意宿主目录（`routers/filesystem.py:143` `restrict_to_home=False`）、绝对路径读取无拒绝名单（`routers/workspace.py:107` + `octop-harness/backends/workspace.py:696`）、默认 backend `root_dir="/"`（`octop-harness/backends/__init__.py:84-88` 原文 "gives the agent full host access by default"）、`session_key` 线程劫持（`routers/chat/turn.py:77-79` 无 owner 比对，键可预测 `gateway/threads.py:44`）。**README 自己把产品限定为 "one admin, shared household"（`README.md:50`）**，与安全表里的 "JWT multi-user isolation"（`:54,541`）矛盾 |
| **HITL 终态语义** | **缺失** | 见 §2，直接违反 CLAUDE.md 红线 §4 |
| **事件流有序性** | **缺失** | `seq` 只存在于 `gateway/steer/coordinator.py`（38 处）与 `ws/ws_channel.py`（12 处）的**控制帧**；`token` / `reasoning` / `tool_*` / `done` 不带序号；重订阅返回 `seq:0` 打断单调性；`subscribe` 无 cursor / `afterSeq`；一个逻辑 turn 跨 WS + SSE 两套传输 |
| **媒体与产物链路** | **部分** | 三家适配器是真 HTTP（Volcengine Ark / DashScope Wan / MiniMax），有真 task 轮询（5s / 600s）与超时取消；产物能到 UI（`tool_media.py` → `MessageBubble.tsx:970-1008`）。但**无资产表**（21 个 migration 零命中）、**无 TTL/GC**、无 retry/backoff、`InMemoryMediaJobStore` 从未被持久化替换、云后端存的是 base64 JSON envelope 而非真对象 |
| **接入面（网关 + MCP）** | **部分** | 任意 OpenAI 兼容 `base_url` + 自定义 `headers`（`llm/factory.py:292-322`），`delta.reasoning_content` 显式回填；MCP `streamable_http` + 自定义 header 透传并自动补 `Accept`（`custom_mcp.py:368-383`）。**但 chat 只有 WS 没有 turn-SSE**、per-server MCP 超时完全不支持、媒体仅三家且强制 HTTPS（`media_generation.py:215`）、ACP 仅 stdio |
| **可嵌入性（库 vs 应用）** | **部分** | `octop-harness` 是真库：wheel 只打 `src/octop_harness`，零 FastAPI import，`HarnessAgent(config).stream()` 即可用，自带 `langgraph | openai | mcp` 协议切换。`Octop` 应用焊死：FastAPI + DB + gateway + 4187 行 `AgentManager` + dashboard，配置只能走 DB rows |

**读法**：六个维度**没有一个"满足"**。两个"缺失"恰好是本仓库踩过真实事故、写进红线的两项。

---

## 2. 最重的发现：Octop 的 HITL reject 恰好是红线禁止的语义

CLAUDE.md 红线 §4 原文：

> `reject` 是终止取消，不是可回灌给模型的工具结果。用户拒绝后：checkpoint 原子转为 `used`，turn 以取消/完成结束，不再调用模型、不重新发确认卡片。

Octop 的实现（已自查源码确认）：

```python
# octop-harness/src/octop_harness/middleware/tool_guard.py:107-119
if decision.get("type") == "approve":
    return await handler(request)

reject_message = decision.get("message")
...
return ToolMessage(
    content=reject_message,
    tool_call_id=str(tool_call.get("id", "")),
    status="error",
)
```

**拒绝被包成一条 `status="error"` 的 ToolMessage 回灌给模型，模型拿着它继续往下走。** 这正是红线里那句"不是可回灌给模型的工具结果"所禁止的形态。

配套证据：

- 决策词汇表只有四个值，**没有 `cancel`**：`security/models.py:38` — `DecisionType = Literal["approve", "edit", "reject", "respond"]`
- 仓库内**没有任何终止逻辑**（`goto=` grep 零命中）
- docstring 明说 reject payload "injected into the model context verbatim"（`security/models.py:230-231`）
- 自家测试把"reject 之后仍有模型输出"写成期望（`tests/test_global_processor_team.py:1299-1300,1338-1339`）

**含义**：采用 Octop 的 HITL，等于把"取消卡片反复弹出 / 界面卡住"那一类事故重新引进产品。这不是体验取舍，是回归。

---

## 3. 可直接复用（不必自建）

| 能力 | 证据 | 接入成本 |
|---|---|---|
| 任意 OpenAI 兼容网关 + 自定义 key/模型名 | `ProviderConfig(id, base_url, api_key, protocol="openai")`（`config/__init__.py:471-491`）；未知 provider kind 一律回落 `openai` | ~15 min（`base_url=http://gw:8083/v1`） |
| 对严格 relay 的 `stream_options` 兼容 | custom provider 默认 `stream_usage=False`，注释理由是"strict OpenAI-compatible stacks … 422 on that field" | 0，正合内部网关形态 |
| MCP `streamable_http` + `X-Internal-Token` | `headers` 原样透传 + `Accept` 自动补齐；`http→streamable_http` 归一；LAN/loopback 允许明文 | ~15 min |
| 26 个工具常驻 + 防长工具表静默截断 | `default_open:true` 或按轮 `mcp_servers:[...]`；`prioritize_active_mcp_tools` 专治模型侧截断 | ~10 min |
| 思考与正文分离、不批处理 | `REASONING` 独立事件类型，同时覆盖 `reasoning_content`（DeepSeek 系）与 `thinking` block（Anthropic 系）；channel 契约明写 "no delta batching" | 0 |
| 三家媒体适配器 | Volcengine / DashScope / MiniMax，真 HTTP + 真轮询 + `safe_to_resubmit` 错误分类 | 这三家为 0；另接别家 2–4 天/家 |

**可抄的机制（不是产品，零集成成本）**：

1. `after_seq` + `Last-Event-ID` 的完整参考实现（`trajectory.py:188-234` + `useTrajectorySession.ts:143-186`，cap 2000 + 15s 心跳）
2. JWT 中间件：`/api/*` 全局覆盖 + 角色声明不信任 + 每请求重载用户行
3. HITL 的 fail-closed 校验习惯（无 decisions 直接 `raise`，未知策略回落 ask）
4. quota 骨架 + 固定上限（20 KB/owner、100 docs/KB、200 MB zip）

**明确不采纳**：`octop acp` 入站。它要拉起整个 `OctopServer()`，且入站**没有 `request_permission`**，`hitl_required` 被静默丢弃（`acp/server.py:88-135,226`）——用它即等于丢掉审批语义。

---

## 4. 必须自建或改造

| # | 缺口 | 性质 | 估时 |
|---|---|---|---|
| 1 | HITL reject 改为终止（加 `cancel` 词汇、不发 `Command(resume)`、拒绝不包 ToolMessage），覆盖 dashboard/IM/MCP 三个 surface | 局部改造 | 3–5 天 |
| 2 | HITL 授权无条件校验（`pending_id` 为空时也校验 thread 归属） | 小补丁 | 0.5–1 天 |
| 3 | HITL token 单次消费 + 幂等（`consumed` 状态、CAS、resume 前先 mark、pending id 从 16bit 提到 128bit） | 小补丁 | 1–2 天 |
| 4 | chat 事件流重整：连续 `seq` + `turn_events` 落库 + `afterSeq` 重放 + 服务端心跳 | **重写级** | 2–4 周 |
| 5 | 媒体资产管线：assets 表 + 生成即落行 + TTL/GC + 云后端存真对象 | 中型改造 | 1–2 周 |
| 6 | 宿主层隔离（per-user 目录、拒绝绝对路径、默认 root 非 `/`、JWT 不进 query） | **架构级** | 2–3 周 + 重验 48+ router |
| 7 | Rate limit + 每用户并发上限 | 局部改造 | ~1 周 |
| 8 | 程序化 SSE 入口（不想写 WS 客户端时） | 局部改造 | 2–3 天 |

---

## 5. 三条路径

### 路径 A —— Octop 作产品宿主（**不建议**）

- 变更：新增 Python 服务 + 自带 dashboard；Java 退化为调用方（流式只能写 WS 客户端或走 MCP 拿整段回答）
- 失去：连续 `seq` + `afterSeq` 补偿、`reject` 终止、产物落库与文件卡、per-conversation 串行锁——**红线四项全部退化**
- 额外事故面：`GET /api/providers` 把 `api_key` 明文回显（`routers/providers.py:185-187`），若指向生产网关 key，等于对每个登录用户公开
- 时间线：单 agent 打通 1–2 周；"可上线多用户"不可估
- **最大风险：负向迁移。**

### 路径 B —— octop-harness 作内核库，挂在 Java 后端之后（可选）

- 变更：Java 侧起 Python sidecar，用 `HarnessAgentConfig(providers=[ProviderConfig(base_url="http://gw:8083/v1")], mcp_servers={javaagent: {...}})` 组装；**关键约束：sidecar 不拥有 `seq`**，用 `protocols/__init__.py:43-55` 把输出切成 `openai`/`mcp` chunk，由现有 `AgentTurnEventPublisher` 重新编号发布
- 保留：Java 全部业务、现有 SSE 协议与前端 sequencer、HITL checkpoint、MoonSandbox、MinIO + `agent_assets`
- **硬约束**：HITL 只走 Java 侧；sidecar 侧必须禁用 `interrupt_on` 与 `tool_guard.require_approval`，否则两套 pending 会互相复活
- 时间线：sidecar + 协议桥 0.5–1 天；接到生产可用 2–3 周
- **最大风险：双状态机漂移**（LangGraph checkpoint vs `agentscope_sessions` 双写）

### 路径 C —— 保留现有栈，只借机制（**推荐起步**）

- 变更：三件事 —— ① 把本次审计当检查清单逐条验证自家实现（`pending_id` 缺失时的归属校验、reject 后是否真的不调模型、token 是否 CAS 单次消费）；② 抄 `trajectory` 的 `after_seq` / `Last-Event-ID` / replay cap 边界处理做对照；③ 抄 MCP 侧两个工程细节（`Accept` 头注入、`prioritize_active_mcp_tools` 的防截断思路）
- 时间线：3–5 天
- **最大风险不是技术风险，是判断风险**：把"引入 Octop"当架构升级，实际是替换进两个已被审计证明更弱的维度

---

## 6. 立项前必须先跑的 4 个 gate（读代码已到尽头）

1. **LangChain `HumanInTheLoopMiddleware` 的 reject 语义**（最高杠杆）：主 `interrupt_on` 门在未随仓库分发的包里。已证实本仓库无终止逻辑、`tool_guard` 路径实测回灌、测试把"reject 后仍有输出"写成期望；**未证**：LangChain 是否在该门内做了图级中止。→ 做一次带 `interrupt_on` 的真实 reject，观察是否还有后续 token
2. **`deepagents.backends.utils.validate_path`** 是否拒绝 `..` / 绝对路径 / 符号链接（包不在 checkout）→ 决定 IDOR 可达面
3. **部署 worker 数**：`HitlPendingStore` 是 process-local（`hitl/store.py:58-63`），N>1 时刷新落到别的 worker 会静默丢卡片 → 起两 worker 复现
4. **`bwrap` 是否可用**：唯一 OS 级 jail，仅 Linux + 非宿主 root 生效 → 决定"任意用户拿到 shell"是理论还是实测

另需实测：`?token=` 是否出现在访问日志；IM 主动推送与在途 turn 的锁是否互斥；`api.minimax.cn` 是否真的提供 `/v1/image_generation` 与 `/v2/video_generation`（适配器只有 `httpx.MockTransport` 测试）。

---

## 7. 结论

1. **A 不走**。它不是"少写代码"，是"把已经正确的终态语义换成缺失实现"。
2. **想要 harness 的 LLM/MCP 抽象** → B，且 HITL 与事件发布严格留在 Java 侧。
3. **只是不想写 provider 适配** → C。`ChatModelFactory` 的能力（自定义 `base_url` / header / reasoning 往返 / `stream_usage` 关闭）在 Java 侧用 Spring AI + 现有网关已基本覆盖。
4. Octop 的真实价值在**接入层零件**（provider factory、MCP client、三家媒体适配器、`after_seq` 参考实现），不在运行时。
