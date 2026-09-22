# MoonAgent 动态任务编排重构与可回退交付计划

状态：实施中

分支：`codex/dynamic-task-orchestration`

## Summary

- 在独立分支 `codex/dynamic-task-orchestration` 上实施，不直接修改主分支；每个阶段独立提交，测试门禁通过后才合并。
- 将 `UnifiedAgentLoop` 重构为运行时 Planner、Validator、Scheduler、CapabilityExecutor、Evaluator 和 ContinuationService，支持动态 DAG、循环、重试、重规划和人工介入。
- 新会话默认使用 `UNIFIED_LOOP`；旧会话和旧 checkpoint 继续走 `PlanGraph`。
- `PlanGraph` 冻结为兼容层，不删除、不迁移旧计划，也不继续增加新能力。
- 数据库变更严格采用只增不删的向后兼容方案，使主分支代码在新库结构上仍可运行，用户随时可以切回主分支。

## Interfaces And State

- 新增 `TaskPlan`、`TaskNode`、`TaskEdge`、`TaskAttempt`、`TaskArtifact`、`CapabilityDescriptor`。
- `TaskPlanState`：`DRAFT | READY | RUNNING | WAITING_HUMAN | WAITING_ASYNC | REPLANNING | COMPLETED | FAILED | CANCELLED`。
- `TaskNodeState`：`PENDING | READY | RUNNING | WAITING_HUMAN | WAITING_ASYNC | COMPLETED | FAILED | SKIPPED`。
- 新增 `AgentCapability`，首版通过适配器包装现有 Specialist、Handler 和 Skill，不接外部工具或插件。
- `AgentLoopDecision` 改为任务级 `CONTINUE | REPLAN | ASK_USER | WAIT_ASYNC | FINISH | FAIL`。
- `SpecialistResult.handoff()` 只作为 `suggestedNextCapability`，由调度器决定是否插入新节点，不再强制固定专家链。
- 新增 `agent_task_plans`、`agent_task_nodes`、`agent_task_edges`；复用 `agent_loop_runs`、`agent_loop_steps` 记录运行和尝试。
- `agent_assets`、`agent_checkpoints` 增加可空 `plan_id`、`node_id`，旧代码忽略这些列。
- 新增 `GET /api/agent/conversations/{id}/plans/active` 和 `POST /api/agent/conversations/{id}/plans/{planId}/actions`，动作包括 `approve | reject | retry | replan | cancel | resume`。
- 扩展 `GET /api/agent/tasks/{taskId}`，附加 `planId`、`nodeId`、`planStatus` 和下一节点状态。
- SSE 增加 `plan_created`、`plan_updated`、`task_started`、`task_finished`、`plan_waiting_user`、`plan_completed`、`plan_failed`。

## Implementation Changes

- Planner 根据用户目标、历史产物、能力清单、预算和 HITL 策略生成严格 JSON DAG；校验器检查未知能力、循环依赖、缺失依赖、权限和预算。
- Planner 首次输出无效时带错误自动修复一次；再次失败则创建单能力最小计划并要求用户确认，不允许自由发明能力。
- 调度器首版串行执行：按拓扑顺序选择首个 `READY` 节点，节点完成后持久化结果、产物、耗时、错误和评估结果，再推进后继节点。
- 混合评估先做文件、接口状态、schema、数量和产物引用校验，再对有质量标准的能力调用 LLM；默认通过阈值为 `0.75`。
- `RETRY` 重试当前节点，`REPLAN` 保留已完成节点并替换未完成后继，`ASK_USER` 创建人工确认点，`WAIT_ASYNC` 暂停计划等待外部任务。
- HITL 策略使用 `NONE | BEFORE | AFTER | ON_RISK | MANUAL`，支持能力默认值、节点覆盖和用户主动暂停；首版只允许任务级操作，不允许编辑节点参数或拖拽 DAG。
- 视频等异步能力在 `agent_assets` 记录计划节点；终态更新后调用幂等 `PlanContinuationService` 自动推进 DAG。
- 前端计划活跃时轮询 active-plan API；等待人工输入时继续使用现有 HITL 卡片和 `/form/submit`。
- 增加每 30 秒运行一次的恢复扫描器，修复漏回调、重启中断和长期卡住的计划。
- 默认预算为单计划最多 20 个节点、每节点 3 次尝试、全局 2 次重规划；预算耗尽或连续两次相同计划签名时失败并保留诊断记录。
- 新能力只注册到 capability registry；`PlanGraph` 保持现状。新会话默认新版，旧会话保持 `LEGACY`。

## Rollback And Compatibility

- 开发分支从干净主分支创建，不在当前脏分支上实施；每阶段单独提交，不压成一个不可审查的提交。
- 所有数据库变更只新增可空列、索引和新表，不修改或删除旧列，不执行破坏性数据迁移。
- 新引擎使用 `ai.agent.dynamic-plan.enabled` 总开关；线上出现严重问题时关闭开关，让新会话立即回退到 `LEGACY`。
- 如果代码仍需整体回退，直接切回主分支即可；新增表和列不会被主分支读取，属于无害的残留结构，后续确认稳定后再单独清理。
- 禁止依赖新字段的旧接口行为变化；旧会话、旧 checkpoint、旧视频任务和旧 SSE 卡片必须继续可用。
- 回滚验证必须包含“先关闭开关运行旧流程”和“主分支代码连接新数据库结构”两种场景。

## Test Plan

- 后端门禁运行 `mvn -pl agent-core,MoonAgent,AIStoryboardBackend -am test`，失败时不得进入下一阶段。
- 单元测试覆盖 Planner schema、能力校验、环路检测、预算、重试、重规划、评估决策和非法状态转换。
- 集成测试覆盖动态图片到视频再到最终产物的 DAG、人工确认、驳回、重试、重规划、取消和异步完成后自动续跑。
- 故障测试覆盖重复异步回调、重复状态轮询、服务重启、并发恢复扫描、视频失败、超时、无效计划和能力不可用。
- 幂等测试必须证明同一节点不会重复生成资产、重复推进或重复写入 assistant 消息。
- 兼容测试覆盖旧会话仍走 `PlanGraph`、旧 checkpoint 恢复、旧视频任务轮询和现有 SSE 协议。
- 前端运行 `npm --prefix AIStoryboardClient test` 和 `npm --prefix AIStoryboardClient run build`，覆盖计划轮询、任务操作、等待状态和失败状态。
- 画布相关改动运行 `npm --prefix XiaoYueCanvasAgent run verify` 和 `npm --prefix XiaoYueCanvasAgent run test:e2e`。
- 数据库测试覆盖从空库完整迁移、从 V27 增量迁移、新结构下运行主分支代码，以及重复执行迁移的幂等性。
- 最终验收要求：全部门禁通过、新流程完整跑通、回退开关有效、旧流程无回归、重启可恢复、无重复副作用。

## Assumptions And Defaults

- 首版只统一现有业务能力，不接插件、MCP 外部工具或用户自定义节点。
- 首版串行执行；并行分支、分支合并和并发限流留到后续版本。
- 计划和节点全部持久化，不依赖长期 SSE 连接恢复状态。
- 用户只进行任务级确认、驳回、重试、重规划和取消。
- 混合评估阈值为 `0.75`，循环预算和开关均可通过配置覆盖。
- 不删除 `PlanGraph`；彻底清理旧引擎必须另立迁移任务，不能混入本次重构。
