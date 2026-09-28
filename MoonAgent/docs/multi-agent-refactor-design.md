# MoonAgent 多智能体化改造设计文档（历史归档）

> **状态：已废弃（2026-09-26）。** 本文描述的 PlanGraph、Specialist、
> IntentHandler 和 Supervisor 接力方案不再是运行路径。当前唯一架构是
> AgentScope `MoonAgentRuntime`，权威说明见
> `docs/superpowers/specs/2026-09-26-agentscope-architecture.md`。本文只保留设计演进背景。

> 历史状态：待评审（2026-08-25）
> 范围：MoonAgent `ai/agent` 编排层 —— 把「意图单选路由」升级为「Supervisor 定链 + 专家接力管线」
> 结论先行：**方向成立，但不用新建 6 个 agent。** 6 个角色已全部作为 `IntentHandler` / `SpecialistAgent` 存在，且移交接口（`SpecialistResult.handoff` / `SpecialistContext.previousResult`）已在接口层定义好。真正缺的是「移交通电」与「Supervisor 编链」，改动集中在 PlanGraph 图构建 + 上下文传递，核心 handler 零改动。

---

## 一、目标

把一次请求从「Supervisor 只选一个 handler，跑完即 END」升级为「Supervisor 定一条链，链上专家按序接力，中途可插入人工确认 / 审查」。

关键词：**意图路由（现有）→ 专家接力管线（目标）**。收益在「专注 + 可复核 + 上下文隔离」，**不在并行提速**（链路本质串行，每加一专家多加一次 LLM 调用与上下文搬运，总延迟只会更长）。

## 二、现状对照（基于代码事实）

### 2.1 六个角色 = 已存在的 intentType

| 角色 | intentType | Handler / Specialist | 状态 |
|---|---|---|---|
| 剧本/分镜师 | `intent-aisplit` | `AisplitIntentHandler` | 已存在 |
| 视觉设计师 | `intent-pic` | `PicIntentHandler` | 已存在 |
| 视频导演 | `intent-video` | `VideoIntentHandler` | 已存在 |
| 内容审查/修复员 | AgentScope storyboard tools | `AgentScopeStoryboardTools` | 已迁移 |
| 通用管家 | `intent-other` | `OtherSpecialist`（显式 `SpecialistAgent`） | 已存在 |
| Supervisor | —（编制逻辑，非独立角色） | `PlanGraph` 入口分流 + `IntentRecognitionService` | 已存在（只做单选路由） |

> 另有 `intent-delete`（`DeleteScenesIntentHandler`）为已有的删除分镜能力，非本次 6 角色之一，保留。

### 2.2 已就绪的基础设施

- `SpecialistAgentRegistry`：`intentType → SpecialistAgent` 映射，**新增专家 = 新实现 `SpecialistAgent` + `@Component`，核心零改动**（自动包装 `HandlerDelegatingSpecialist`）。
- `SpecialistResult`：已含 `handoffTarget` / `handoffReason` / `handoffState`，并有 `handoff(target, reason, state)` 构造器 + `needsHandoff()`。
- `SpecialistContext`：已含 `previousResult`（「上一个专家的移交结果（如有）」）。
- `PlanGraph.run()/resume()`：入口在 `PlanGraph.java:417 / :439`，结果为 `K_LAST_MESSAGE`。

### 2.3 断链现状（为什么现在"多智能体"没真正多起来）

1. `HandlerDelegatingSpecialist` 自注释：**「有移交能力但当前 always done」**——永不返回 `handoff`，`execute()` 只返回 `SpecialistResult.done(...)`。
2. `PlanGraph` 构造 `SpecialistContext` 时 `previousResult` **硬编码传 `null`**（「单步，无链式」，`PlanGraph.java:377`），`handoffState` 也无处落。
3. handler 节点返回仅取 `result.message()`（`PlanGraph.java:389/393`），**`handoffTarget` / `handoffState` 被直接丢弃**。
4. `PlanGraph` 各 handler 节点无「移交后继续」的条件边——`resume_route` / `rerun_selected` 只做链的**外层**跳转，节点之间是 DAG 单步，无「专家→专家」接力边。

## 三、目标架构

```
         ┌────────────────────────── Supervisor（现 PlanGraph 入口 + 意图识别，扩充为"定链者"） ──────────────────────────┐
用户消息 ─→ entry → intent_recognize ─(置信度低)→ intent_clarify ────────────────────────────────────────────────────────┤
                        │                                                                                              │
                        └─(置信度达标/规则命中)→ 选定链首专家（node_aisplit / node_pic / node_video / ...）                │
                                                                                                                       │
  ┌────────── 专家接力管线（每条节点完成后检查 handoff） ──────────┐                                                      │
  │   node_X ──(needsHandoff? 是)──→ node_Y（喂 previousResult+handoffState）──(是)──→ node_Z ... ──(否)──→ END        │
  │                  │                                                    │                                          │
  │                  └──(否)──→ 若有 HITL 停顿点在链上，落 checkpoint → task_accepted / human_input → 本轮 END（resume 续）│
  └──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

要点：
- **Supervisor 定链**：决定「这条需求走哪条链、链上哪一步要人工确认 / 上审查」，不新建独立进程（复用现 PlanGraph 路由与 intent-clarify 菜单）。
- **专家接力**：一个 handler 完成后 `needsHandoff()` → 把 `handoffState` 写回 state、`previousResult` 喂给下一专家，走条件边而非 END。
- **HITL 不丢失**：链上的所有人工确认点仍走现在 `runHITLStage` / `resumeStage` + checkpoint 机制，专家接力**不绕过**人在回路。

## 四、改造点（最小增量，复用现有骨架）

### 4.1 核心：PlanGraph 消费 `handoff` 并实现专家接力（改动最大，唯一必做项）

- handler 节点内：`specialist.execute(ctx)` 返回 `SpecialistResult` 后，若 `needsHandoff()`：
  - 把 `handoffTarget` 写入 `K_INTENT`（覆盖当前意图，供下一节点路由）
  - 把 `handoffState` 写入新 state 键 `K_HANDOFF_STATE`（供下一专家读取）
  - 照旧返回 message（供落库）
- 新增条件边：每个 handler 节点之后 `addConditionalEdges(..., edge_async(state -> ...))`，读 `K_HANDOFF_TARGET`：空白 → `END`；否则 → `intentToNode.get(handoffTarget)`，实现「专家→专家」接力。
- `SpecialistContext` 构造时把上一节点留下的 `handoffState` 填入 `previousResult`（替换现在的硬编码 `null`，`PlanGraph.java:377`）。

> 说明：链路节点的上层 handler（6 个 IntentHandler）**不改**。它们保持现有「发卡片 / 写库 / 生成」职责；能否移交由 `SpecialistResult` 声明，由 PlanGraph 驱动。

### 4.2 让专才能"声明移交"

- `HandlerDelegatingSpecialist.execute()`：不再恒 `done`。当需要链式（如 a isplit 写完库后想自动交给场景审查）时，返回 `SpecialistResult.handoff(...)`。
- 但**默认行为必须保持"done"**（现 6 条链都是单专家 + HITL 即可闭环，不可默认开启接力，否则每条消息都多跑专家、多花 LLM）。

### 4.3 Supervisor 职责沉淀

- 现 `PlanGraph` 的 `entry → intent_recognize → intent_clarify → resume_route` 就是 Supervisor 的骨架，**不新建类**。
- 增量：在确定「链首专家」后，显式声明该链的**可移交序列**，让「哪一步上审查」成为配置而非散落在各 handler。

### 4.4 明确不做的事

- 不给 6 个角色各写独立 `SpecialistAgent` 实现（已有 `HandlerDelegatingSpecialist` 自动包装 + 各自 SKILL.md 提示词，重复造 = 6 倍维护 + 上下文传递失真）。
- 不改变资源采集 / 事件协议 / checkpoint 落库 / 会话锁（`ConversationLock`）。

## 五、视频异步边界（关键约束）

- 视频链路现状：`video_plan` 卡片 → 用户确认 → `task_accepted` 立即返回 → **前端 5s 轮询** `GET /api/agent/tasks/{taskId}`，后台虚拟线程轮询 MiniMax（~2min）更新 `agent_assets`。
- **约束**：Supervisor / 其它专家**不得同步等待视频结果**。视频专家受理后应**立即结束本轮**（返回 `done`，不发 handoff），把异步状态机留在 `agent_assets` + `pollVideoTaskAndUpdate` 后台线程里。
- 若未来想"视频完成后自动触发再审"，走**另一条 resume / 异步回调**触发链，而非在 Supervisor 编排内阻塞等。

## 六、审查/修复开关

- 分镜媒体操作统一由 AgentScope 工具按需执行，避免每轮额外跑一次 LLM 和上下文搬运。
- 不再配置自动审查链；查看分镜使用 `list_project_storyboard`，补图使用 `generate_storyboard_images`。
- 开关由 Supervisor（4.3）读取，命中则插入 `node_scene_review`。

## 七、变更清单（文件级）

| 文件 | 改动 | 规模 |
|---|---|---|
| `PlanGraph.java` | handler 节点消费 `SpecialistResult.needsHandoff()`；新增接力条件边；`SpecialistContext.previousResult` 喂 `handoffState` | 主要 |
| `HandlerDelegatingSpecialist.java` | 支持返回 `handoff`（默认仍 `done`） | 小 |
| `SpecialistContext.java` / `SpecialistResult.java` | 字段已在，无需改（或仅加注释） | 0 |
| `*.yml` | 移除已废弃的分镜自动审查开关 | 小 |
| 6 个 `IntentHandler` | **不改** | 0 |

## 八、风险

1. **延迟叠加**：链上每专家多一次 LLM + 移交上下文搬运；只在需要复核的链上开接力，别默认全开。
2. **上下文失真**：`handoffState` 需精简（跨专家只传真正需要的：`assetIds`、`plan`、`sceneNums`、`projectId` 等），不要整段对话塞进 state。
3. **接管失效回退**：若 `handoffTarget` 指向未注册 / 已下线意图，条件边必须兜底到 `END` + 友好 message，避免死循环。
4. **循环保护**：专家接力应有最大跳数（`ai.agent.max-handoff-hops`，默认 4），防 A→B→A 互相移交。
5. **视频边界**：任何专家都不得使 Supervisor 同步等待视频结果（见第五节）。

## 九、实施次序（建议）

1. 先做 **4.1**（PlanGraph 消费 handoff + 接力条件边 + previousResult 喂入）——这是让"链"真正通电的唯一核心改动，且默认 `done` 不影响现有行为。
2. 再验证 AgentScope 分镜查询与批量补图链路。
3. 最后按需加 **4.2**（特定专家声明移交），默认仍单专家闭环。

## 十、验证

- 回归：现有 6 条单专家链必须行为不变（handoff 默认不触发）。
- 聚焦：构造"专家 A 返回 `handoff(B, state)`"的最小场景，断言 B 的 `SpecialistContext.previousResult` 收到 `state`、PlanGraph 走 B 后再 END。
- 异步：视频链确认 `task_accepted` 仍是本轮立即结束，Supervisor 未同步等待。
