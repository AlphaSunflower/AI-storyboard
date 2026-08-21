# PlanGraph 编排重构设计（StateGraph + Advisor Chain）

日期：2026-08-21
分支：feature/plan-graph-orchestration
状态：设计已批准，P0 spike 已完成

## 背景与目标

当前 `AgentOrchestratorImpl.resume()` 有 12+ if/else 硬编码分支处理不同 checkpoint action，
新增卡片/确认类型必须改编排器本体；HITL 计划流程是"澄清→方案→确认→执行"固定一条龙，
**任务中途无法灵活切换意图链**（用户想切话题时被卡在当前链路）。

目标：以 **StateGraph（spring-ai-alibaba-graph-core）为核心工作流引擎**——定义业务流程、
管理全局状态、条件边路由与切换；**Advisor Chain 为横切能力层**——对话记忆、工具调用、
日志审计按节点自由组合。

## 架构定案

```
前端 SSE 协议（不变）
    ↓
AgentChatService（会话锁 / 消息落库 / emitter.complete 不变）
    ↓
PlanGraph = StateGraph（核心工作流引擎）
    ├─ 节点 = 业务动作（LLM 调用 / AgentTools / 卡片）
    ├─ 边   = 流程定义（条件边替代 12+ if/else 路由）
    └─ Checkpointer = 状态持久化（HITL 暂停点）
    ↓
每个 LLM 节点 → ChatClient（挂 Advisor Chain）
    ├─ MessageChatMemoryAdvisor   → 对话记忆
    ├─ ToolCallingAdvisor         → 工具调用横切（同步工具）
    └─ SimpleLoggerAdvisor        → 审计日志
```

## 图设计（核心：任意节点可切链）

```
START → intent_recognize（唯一入口节点）
          │ 条件边（意图 + confidence 路由）
          ▼
  ┌─ aisplit 组：existing_check → scene_mode卡片 → clarify_gate → 方案生成 → 方案卡片 → write_scenes
  ├─ pic 组：refine / clarify 卡片 → 方案 → 方案卡片 → generate
  ├─ video 组：plan 卡片 → task_accepted → （图外轮询）
  ├─ delete / review / other 组
  └─ 每个节点（含每张卡片节点）都有出边：
        ├─ → 链内下一节点（正常推进）
        ├─ → END（链完成）
        └─ → intent_recognize（★ 切链出口：任意节点可重新路由）
```

### ★ HITL 恢复路由 = 意图开关（解决"卡在链路"的根因）

每张卡片节点 = `interruptBefore` 暂停点。恢复时路由函数先过一道意图开关：

```
resume 路由函数（条件边）:
  用户提交 = action + customText（SSE /form/submit 协议不变）
  ├─ action 属于该卡片的合法选项 → 回当前链下一节点（正常推进）
  ├─ customText 非空 → 意图识别(customText)
  │     ├─ 置信度 ≥ 阈值 且 意图 ≠ 当前链 → ★切链：路由到新意图组节点
  │     └─ 否则 → 仍按当前链的 custom 语义处理（如调整意见）
  └─ 卡片提供显式「换个话题」选项（id=switch-intent）→ 直达 intent_recognize
```

## 全局状态（OverAllState + KeyStrategy）

```
conversationId / projectId / content / picUrl / history（入图）
currentChain（活跃链路）/ lastMessage / sceneCount / plan
action / customText / params / assetIds / source
regenCount / sessionAssets / clarifyCount（内存计数迁入状态 → DB saver 持久化，重启不丢）
```

- 切链不丢上下文：从 aisplit 切到 pic，再切回来，进度/计数/勾选资产还在 state 里
- KeyStrategy：`messages` Append、业务字段 Replace

## Checkpointer（状态恢复）

- **复用现有 agent_checkpoints 表**（Graph saver 接口对接 Mapper，form_token 语义保留——
  前端 /form/submit 协议不变，30min 过期不变）
- 恢复：`getState(threadId=conversationId)` → `updateState()` → `withResume()` →
  `streamFromInitialNode()`，替代手写"查表+一次性消费+分发"
- 内存计数（regenCount/sessionAssets/clarifyCount）落 state → 随 checkpoint 持久化

## Advisor Chain（节点级能力组合）

| 节点类型 | Advisor 组合 |
|---|---|
| 意图识别 / 方案生成 | MessageChatMemoryAdvisor（拼历史）+ SimpleLoggerAdvisor（审计） |
| 工具执行节点（写分镜/查资产/改图） | + ToolCallingAdvisor（同步工具自动循环） |
| 卡片节点 | 无 LLM（纯 HITL 暂停） |
| 视频节点 | task_accepted → 图外轮询（异步形状不变） |

## P0 Spike 结果（2026-08-21 已完成，4/4 PASS）

隔离项目：`E:/Desktop/.spike-graph/`（仓库外，`.spike-spring-ai/` 同类惯例）

验证项：
1. ✅ 条件边路由（意图分发：aisplit/pic/other）
2. ✅ `interruptBefore` 声明式 HITL 暂停（checkpoint 持久化，`snap.next()` = 暂停点）
3. ✅ 恢复执行（`CompiledGraph.updateState(config, map)` + `withResume()`）
4. ✅ ★ 恢复时路由函数重新分发（切链出口）：卡片处 action=switch + 新 intent
   → 路由回 `intent_recognize` → 新链节点 → 停在**新链自己的卡片前**（HITL 语义正确）

### API 修正（文档与实际 jar 不符，已回写技能 reference）

| 文档写法 | M1.1 实际 |
|---|---|
| `state.withResume()` / `state.withHumanFeedback()` | 不存在；用 `CompiledGraph.updateState(config, map)` + `RunnableConfig.withResume()` |
| `SaverConstant.MEMORY` | 不存在；`SaverConfig.builder().register(new MemorySaver())`（`checkpoint.savers` 包） |
| `RunnableConfig.Builder.addStateUpdate(map)` | 实测不生效；恢复前改状态必须 `compiled.updateState(config, map)` 且**重新 getState 取最新快照** |
| 切链后继续走完 | 切链后新链卡片节点会**再次 interrupt 暂停**（HITL 语义正确） |
| 其他 | `GraphStateException` 受检异常；`exec:java` 不自动编译（须先 `mvn compile`） |

版本确认：`spring-ai-alibaba-graph-core:2.0.0-M1.1` + `spring-ai 2.0.0` 无 1.x/0.x 泄漏，
SB 4.0.0 parent 编译/运行通过。

## 迁移路径（每步独立验证）

| 阶段 | 内容 | 验证 | 状态 |
|---|---|---|---|
| P0 | spike：Graph Core M1.1 条件边/HITL 恢复/切链出口 | 4/4 PASS | ✅ 完成 |
| P1 | Advisor Chain 先上（记忆/日志/工具循环，不依赖图） | 存量冒烟 | 待做 |
| P2 | 主图骨架：intent_recognize + 条件边 + 切链出口；存量链=适配器节点（handler 内部零改动） | 全链 e2e + 切链冒烟 | 待做 |
| P3 | 卡片恢复路由函数化（12+ if/else → 条件边） | 每张卡片类型冒烟 | 待做 |
| P4 | 内存计数落 state + DB saver + 重启恢复 | 重启恢复验证 | 待做 |

## 风险与决策点

1. **M1.1 成熟度**：spike 已证条件边/interrupt/恢复/切链可用；DB saver 接口（`BaseCheckpointSaver`）
   形状已取证，P4 对接 Mapper 时验证。若 saver 不可靠 → 降级：图管路由/状态，checkpoint 仍手写
2. **流式打字机**：节点内流式增量（streamPlanWithMessage 模式）需在节点返回 Map 更新前
   逐 token 发 SSE——适配点在 P2 验证
3. **切链语义**：resume 时"合法选项 vs 自定义文本 vs 新意图"的判定边界（现有 custom 在每张
   卡片语义不同：调整意见/自定义方向/新意图）——P3 路由函数保留这些差异
