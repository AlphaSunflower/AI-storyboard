# AgentScope 任务中心收敛评估

> 结论：**不收敛域任务的后台执行与 stale recovery，只保留已完成的 wakeup / 分布式存储收敛。**
> 依据：Harness 原生机制面向「单次工具调用超时转后台」，产品任务中心是带领域语义的持久化任务模型；
> 替换会丢失产品能力，适配层只是加一层而不减少代码。

## 1. 评估范围

对应总计划「后续必做清单」第 2 项：保留 `agent_async_tasks` 产品任务中心、列表、取消、重试与 UI 状态，
评估把通用后台执行、wakeup、stale recovery 收敛到 Harness 的 `AsyncToolMiddleware`、`TaskRepository`、
`MessageBus`，前提是不牺牲现有产品能力。

## 2. 现状盘点（MoonAgent）

### 2.1 产品任务中心

`AgentScopeAsyncTaskService`（`MoonAgent/.../ai/agent/agentscope/AgentScopeAsyncTaskService.java`，2645 行）
是域内唯一后台任务入口，落库表 `agent_async_tasks`，字段包含：

`id / conversation_id / user_id / session_id / tool_call_id / tool_name / kind / status / title / stage /
progress / request_json / result_json / result_summary / asset_ids_json / result_urls_json / message_id /
anchor_message_id / parent_task_id / version / cancel_requested / context_consumed_at /
wakeup_status / wakeup_enqueued_at / wakeup_consumed_at / wakeup_message_id / wakeup_error /
wakeup_attempts / error / idempotency_key / created_at / updated_at / completed_at`

任务种类（`kind`）：`image-batch`、`long-tool`（剧情稿 / 分镜 / 视频合并 / 沙箱脚本等）。

对外能力：

- 创建：`startBatchImageTask`、`startStoryboardImageTask`、`startStoryboardTask`、
  `startPlotReviewStoryboardTask`、`startStoryboardApprovalTask`、`startStoryboardRevisionTask`、
  `startStoryboardVideoTask`、`startSandboxScriptTask`。
- 查询：`getTask`、`getConversationTask`、`listTasks`、`listTaskViews`、`getStatus`。
- 操作：`cancelTask`、`retryTask`、`followUpTask`。
- 产品联动：`bindAnchorMessages`（任务卡锚定消息）、`consumeUnreadContext`（未读上下文注入）。
- 实时性：通过 `AgentTaskEventHub` 推送 `snapshot` / `task_stream`（stage/text/thinking）到
  `/api/agent/tasks/events`。

### 2.2 stale recovery

`AgentScopeAsyncTaskService` 实现 `AgentRecoverySource`：`findCandidates(now, limit)` +
`recover(candidate, attempt, leaseOwner)`，配合 `AgentRecoveryLeaseService` 做租约 + 重试 + 退避，
按 `kind` 恢复不同任务。这是域级恢复，覆盖「任务行处于 running 但执行者已死」的场景。

### 2.3 wakeup（已收敛）

`AgentWakeupService` 实现 Harness `WakeupDispatcher.WakeupTarget`，`start()` 时构造
`new WakeupDispatcher(messageBus.messageBus(), this)`；重试也走
`messageBus.messageBus().enqueueWakeup(userId, sessionId, "moon-aigc")`。

即：wakeup 的队列与投递已经是 Harness `MessageBus`，产品侧只保留 `wakeup_status` 等可观测状态字段。

### 2.4 分布式存储（已收敛）

`MoonAgentRuntime.init()` 只创建一个 `PostgresDistributedStore`，同时喂给 HarnessAgent、
`AgentScopeMessageBus` 与 async tool registry（`distributedStore.asyncToolRegistry()`），
避免多实例各自维护连接与状态。

## 3. Harness 原生能力

| 机制 | 定位 | 关键接口 |
| --- | --- | --- |
| `AsyncToolMiddleware` | 单个工具调用超过 `asyncToolTimeout` 时转后台执行，用占位消息继续本轮 | `onActing`；`MessageBus` + `AsyncToolRegistry` |
| `AsyncToolRegistry` | 后台工具调用的生命周期记录 + 陈旧检测 | `register/complete/fail/findStale(sessionId, ttl)/markTimeout` |
| `TaskRepository` | 子智能体后台任务（按 `sessionId + taskId`） | `getTask/putTask/listTasks/cancelTask` + 可选 push delivery |
| `MessageBus` | 会话 inbox / wakeup 队列 | `WorkspaceMessageBus`、`WakeupDispatcher` |

`AsyncToolMiddleware` 由 Builder 在 `messageBus != null && asyncToolTimeout != null` 时注册
（`HarnessAgent.java:2666`）；MoonAgent 已开启（`AGENTSCOPE_ASYNC_TOOL_TIMEOUT=90s`、wakeup 开启）。

## 4. 对照与结论

| 维度 | 产品任务中心 | Harness 原生 | 结论 |
| --- | --- | --- | --- |
| 任务语义 | kind/stage/progress/assetIds/slots/锚定消息 | 仅状态 + 单段 result 文本 | **保留产品侧**；原生表达不了 UI 契约 |
| 存储 | `agent_async_tasks`（产品事实源） | `TaskRecord`（子智能体域） | **不替换** |
| 后台执行 | 域任务主动入队（立即返回 taskId） | 工具调用**超时后**才转后台 | 机制正交，不是重复实现 |
| wakeup | 状态字段 + Harness `MessageBus` | `MessageBus` + `WakeupDispatcher` | **已收敛** |
| stale recovery | 租约 + 重试 + 按 kind 恢复 | `findStale` 仅覆盖后台工具调用 | **保留产品侧** |
| 取消/重试/跟进 | 产品能力 | `cancelTask` 仅子智能体任务 | **保留产品侧** |

关键判断：Harness `AsyncToolMiddleware` 是**安全网**（工具跑超时后兜底），不是任务中心的替代品；
产品任务中心是**主动调度**（工具立刻返回 taskId，后台自己跑）。两者职责不同，不构成重复实现。

## 5. 必须维持的不变量

> 任何可能超过 `asyncToolTimeout`（默认 90s）的 agent 工具，必须**立即返回 taskId**，
> 否则会被 `AsyncToolMiddleware` 抢走，产生占位消息 + 独立 wakeup，绕过产品任务卡。

当前设计满足该不变量：`merge_storyboard_videos` 等长任务工具直接返回
`{status: queued, taskId}` 并写入 `turnState.addTaskCard(...)`，不阻塞。

### 可观测性

`AsyncToolMiddleware` 触发时打印 INFO：

```
Tool execution timed out after {N}s, offloading to background: session={id}
```

巡检命令：

```powershell
# MoonAgent 日志中是否出现误转交
Select-String -Path MoonAgent\target\moon-agent*.out.log -Pattern "offloading to background"

# 产品任务中心实际分布
$env:PGPASSWORD='123456'
psql -h localhost -U postgres -d ai_storyboard -c `
  "select kind, status, count(*) from agent_async_tasks group by kind, status order by kind, status;"
```

实测（2026-10-11）：运行日志中误转交命中 **0 次**；任务中心 39 条
（`image-batch: completed 6`、`long-tool: completed 28`、`long-tool: failed 5`）。
即当前没有任何工具触发 Harness 后台转交，两套机制未发生冲突。

## 6. 若将来确实要收敛（备选设计，本期不做）

触发条件（满足任一再评估）：

1. 开始用 Harness 后台子智能体（`TaskRepository` 出现真实负载）；
2. 出现工具超过 90s 被 Harness 转交、需要与产品任务卡统一展示；
3. 产品任务中心与子智能体任务需要共享同一条「未读结果投递」链路。

届时推荐做法：**让 `agent_async_tasks` 成为唯一存储，向上暴露为 `TaskRepository` 适配器**，
即 Harness 走我们的表，而不是把域任务搬进 `TaskRecord`。这样：

- 产品 UI/API 契约不变；
- 子智能体后台任务与域任务共用一份可观测状态；
- `wakeup` 继续复用 `MessageBus`，不新造队列。

不推荐的方向：用 `TaskRecord` 取代 `agent_async_tasks`（会丢 kind/stage/progress/assetIds/slot UI），
或让域任务依赖 `AsyncToolMiddleware`（会变成「跑满 90s 才转后台」，丢即时反馈）。

## 7. 附：本次评估的复现依据

- Harness 源码：`agentscope-harness-2.0.4-sources.jar` 中
  `io/agentscope/harness/agent/middleware/AsyncToolMiddleware.java`、
  `bus/AsyncToolRegistry.java`、`subagent/task/TaskRepository.java`、`bus/MessageBus.java`。
- MoonAgent：`AgentScopeAsyncTaskService`、`AgentWakeupService`、`AgentScopeMessageBus`、`MoonAgentRuntime`。
- 配置：`AGENTSCOPE_ASYNC_TOOL_TIMEOUT`（默认 `90s`）、`AGENTSCOPE_WAKEUP_ENABLED`、
  `AGENTSCOPE_DISTRIBUTED_STORE_ENABLED`。
