# Agent 能力模块化：Graph + Skill 组合设计定案

> **For Hermes:** 这是设计评审 + 执行计划。先读 [§0 结论] 和 [§1 概念澄清] —— 结论是 **「Skill 声明 Graph」的框架级反模式不要做**。本计划提供正确设计（Graph=流程、Skill=知识包）与两条执行路径。

**Goal:** 判定「把 Graph 流程控制 + Skill 知识封装/按需加载结合」是好是坏，给出正确设计，并落地可执行的模块化路径。

**Architecture:** 双轴分离——**Graph（代码，确定性）** 是 StateGraph 主图，独占流程控制（状态/条件分支/loop/HITL resume）；**Skill 是数据/配置（知识包）**，声明「触发规则 + 提示词 + 工具清单 + 模型分工 + HITL 模板 + 上限」，被节点代码消费。Skill 是 data，不是 graph 定义；Graph 是 code，不是 YAML 声明。

**Tech Stack:** Spring Boot 4.0.0, Spring AI 2.0.0, spring-ai-alibaba-graph-core 2.0.0-M1.1（已用）, SnakeYAML（PromptConfig 已有）, PostgreSQL。

---

## §0 结论（先说好坏）

**按字面理解（Skill 的 YAML frontmatter 声明 nodes/edges/state/HITL，运行时用泛型解读器构建 StateGraph）——是坏设计，不要做。** 两个已存在的草案（`.hermes/plans/2026-08-25_210000-skill-based-agent-design.md`、`...-skill-graph-agent-design.md`）正是这个反模式，建议不执行、归档。

**但内核有一半是好的**，且大部分**已经在本项目落地**了：

| 用户诉求 | 现状 | 结论 |
|---|---|---|
| 能力模块化 | `IntentHandler` 策略注册（6 个）+ `PlanGraph` StateGraph | ✅ 已是模块化 |
| 知识封装（提示词） | `resources/prompts/{orchestrator,script,services}/*.yaml` + `PromptConfig` | ✅ 已封装成文件 |
| Graph 流程控制 | `PlanGraph` 条件边/切链/HITL/计数持久化 | ✅ 已是 Graph |
| Skill 按需加载 | 6 个 intent 全量加载 | ⚠️ 规模不到，YAGNI |

**真正的问题不是「缺 Skill 层」，而是「加一个多步能力要写重复脚手架」**——你自己的《多智能体协作设计》给的泛化解是 **SubAgent 抽象（有界循环 agent）**，不是 YAML 解读器。详见 §5。

---

## §1 概念澄清（关键：两个正交控制轴）

「Skill」在 Hermes/Anthropic/DeepSeek Harness 语境下 = **知识封装 + 按需加载**，其核心性质是 **LLM 按描述相关性在运行时选择并加载**（模糊、语义、模型驱动）。

「Graph」= **确定性流程**，其核心性质是 **代码决定节点顺序/分支/loop/HITL**（无 LLM 自主控制）。

这是**两个不同的控制主体**：
- Graph 的路由由**代码**决定（`edge_async` 返回目标节点、静态映射表）。
- Skill 的选取由**LLM**决定（description relevance）。

**把 Graph 拓扑塞进 SKILL.md frontmatter，等于让一个文件同时干两件互相冲突的活，且引入两个控制主体打架——这就是反模式的根源。**

### 反模式三宗罪（对应两份草案）

1. **框架里再写框架。** `AisplitIntentHandler.resume()` 有 8+ 分支，每个都做 DB 调用、资产匹配、结构化输出+兜底解析、11 字段 `StagePlan`、计数持久化、scene_assets 关联。这些**无法声明进 YAML**。要么：
   - 逻辑全留在 Java 配置类里被 YAML 节点 id 指认 → YAML 变成纯命名层，逻辑仍在 Java，**加了仪式却没简化**，且拓扑(Java)/知识(YAML)分离要手动同步；
   - 要么写一个泛型 LLMNode/ToolNode/HITLNode 解读器覆盖所有情况 → 节点一需要自定义 DB 调用/结构化输出兜底/条件路由读 checkpoint.plan JSON，泛型节点就退化成 switch(nodeId)，**正是你想消灭的硬编码分发器**。

2. **重复造已存在的东西。** IntentHandler + PlanGraph + PromptConfig **已经 = 能力模块化 + Graph 流程 + 知识封装**。用 YAML 驱动的数据解读器替换一个实测打磨过、有真机证据（SSE 空流根因/resume 路由/计数持久化）的 StateGraph + 策略注册，是**高风险换零收益的 churn**。

3. **版本风险已被草案自己证实。** v3 草案写 graph-core `1.0.0.2`（对应 Spring AI 1.x）——项目是 2.0.0，会拉进 SB3 依赖。说明这条「YAML 声明 + 泛型 builder」路径对 API 形状是猜测，而项目已在 2.0.0-M1.1 上跑通。

---

## §2 正确设计：双轴分离（Graph=流程，Skill=知识包）

**不要**让 Skill 定义 Graph。**让 Graph 的节点消费 Skill 包。**

```
┌─ Layer 1: Graph（代码，确定性，唯一流程权威）───────────────┐
│  PlanGraph / StateGraph：节点 + 条件边 + HITL checkpoint +     │
│  resume 路由 + loop（条件边回边）。SSE 在节点内直发。          │
│  ⚠ 流程/分支/HITL/循环永远在代码里 —— 不让 YAML 声明。         │
└──────────────────────────────────────────────────────────┘
                         ▲ 节点读取
┌─ Layer 2: Skill（数据/配置，知识包，能被节点消费）─────────────┐
│  每个能力一份：触发规则 + 提示词集 + 工具清单 + 模型分工        │
│             + HITL 模板 + 上限。                              │
│  <= 知识封装/可编辑/可版本化/（可选）按需加载。                 │
└──────────────────────────────────────────────────────────┘
```

- **Skill 是 data。** 它回答「这个能力知道什么、怎么调、需要什么」：提示词（知识）、工具 manifest、模型选择、HITL 卡片模板、重试/澄清上限。由 `@Component` 注册表或 resources 目录加载成一个 record，节点代码消费它。
- **Graph 是 code。** 它回答「按什么顺序、什么条件、在哪等人工、怎么分支/循环」：节点、条件边、checkpoint、resume。由 `edge_async`/`node_async`/`addConditionalEdges` 决定。
- **按需加载的度**：在确定性 Graph 里，「按需」= 节点即将执行时才加载该能力知识包（惰性物化），**不是** LLM 自由选。前者是部署粒度优化，后者会破坏「用户满意再执行」+ HITL + 确定性保证（这正是项目刻意不做 `ChatClient.tools()` 自动循环的原因）。

**Skill 内可含小固定顺序，但跨能力的任何分支/loop/HITL 属于 Graph，不放进 Skill。**

---

## §3 现状盘点（好消息：好的一半已经在）

- **能力模块化**：`IntentHandler`（intentType/resumeActions/handle/resume）+ `@Component` 自动收集 + `PlanGraph` 双注册表（byIntent/byAction）。新增意图 = 新类 + @Component，核心零改动。`ai/agent/handler/` 现有 6 个：Aisplit / Pic / Video / SceneReview / Delete / Other。
- **知识封装**：`resources/prompts/{orchestrator,script,services}/*.yaml`，`PromptConfig` 加载。已按用途分文件。
- **Graph 流程**：`PlanGraph` 节点 + 条件边 + 外部 emittersByThread（SSE 空流根因修复）+ resume 路由（P3）+ 计数持久化（P4）。
- **工具面**：`AgentTools` @Tool（writeScenes/replaceScenes/refineImage/generateVideo/deleteScenes），handler 直接调（刻意不接 ChatClient.tools() 自动循环）。
- **模型分工**：`GatewayModelService`/`AgentNodeClients`（memoryClient/memoryVisionClient）+ 卡片 models/recommended/reasons 协议。

---

## §4 决策点（执行前必须选）

**A. 最小正确路径（推荐）** —— 不新建 Skill 引擎，做「能力知识包」轻量收敛：把每个 handler 的提示词 + 工具 manifest + 模型选择 + HITL 模板 + 上限**归拢到一个 per-capability 单元**（文件/record），让 handler 锚定它。收益 = 知识内聚、可编辑、可版本化；成本 = 小。**前提：你确认要腾出这个重构窗口，且它确有收益。**

**B. 等真正需要时再上 SubAgent** —— 你已认定规模瓶颈不是 YAML 能解，泛化解是 SubAgent（有界循环 agent + agent_subtasks 表 + SubTaskDispatcher + maxSteps/deadline/幂等/终止/审计），见 `docs/2026-08-23-多智能体协作设计.md`。**新增第 7 个多步能力时走这条，而非 YAML。**

**C. 只产出 ADR（架构决策记录）** —— 把 §0-§2 写成文档归档，明确"Skill 声明 Graph 不做/为什么"，作为将来评审依据。**零代码改动。**

> 推荐组合：**A 若不值得就跳到 C；B 留到真实需求出现时再立项。** 无论如何，**两份 210000 草案不要执行**。

---

## §5 执行计划（先定 A/B/C，再走 Phase 0/1）

### Phase 0：Git 收尾（提交所有文件 + 新分支）

> ⚠ **本（plan）会话不执行**——plan 模式禁止 mutate。以下为执行阶段命令。

当前工作区：4 modified（`AiConfigProperties.java`、`AStoryboardMobile/app/(tabs)/editor.tsx`、`AStoryboardMobile/package.json`、`package-lock.json`）+ 3 untracked（`.hermes/plans/` 两草案 + 本计划）。你明确要「提交所有文件，新建一个 git 分支」。

```bash
cd /e/Desktop/AI-storyboard
# 1. 确认要提交的改动清单
git status
# 2. 全部暂存 + 提交（按你「先提交 master 当前状态再建分支」的习惯）
git add -A
git commit -m "chore: 提交本轮工作区改动（移动端编辑器 + 网关配置 + plans 草案）"
# 3. 从 master 新建特性分支
git checkout -b feature/agent-skill-modularization
git push -u origin feature/agent-skill-modularization
```

**注意**：`AStoryboardMobile/package-lock.json` / `package.json` 若包含你的移动端调研改动，按 CLAUDE.md 惯例一并提交；`AiConfigProperties.java` 的改动确认是「本轮进度」计划内的。分支名可按你偏好改。若你先想让我在分支上做设计，先执行以上三步。

### Phase 1：Spring AI 2.0 / StateGraph 知识确认（证据优先）

本机已有权威证据（spike/字节码级），无需 web 搜索兜底（外部搜索后端当前不可用）：
- `references/spring-ai-alibaba-graph-core.md` — StateGraph 核心 API（addNode/addConditionalEdges/addEdge/并行(多条出边)/loop(条件边回边)/interruptBefore+withResume）+ OverAllState 序列化克隆坑 + M1.1 文档与 jar 不符点。
- `references/plangraph-migration-implementation.md` — P0-P4 实测（SSE 空流根因/emittersByThread/resume 路由化/计数持久化/routingHint）。
- `references/moon-agent-architecture-2026-08.md` — 「确定的确定性工作流 + 意图路由，非工具即智能体、非多智能体」概念分类。
- `references/agent-framework-evaluation.md` — DeepSeek Harness `ctx.skills`/Agent-Skills 模式 vs Spring AI 自建 vs Graph Core 三选一决策。

若需外部佐证（可选）：等 web 搜索恢复后查「spring-ai-alibaba graph-core StateGraph loop parallel」「Anthropic Agent Skills pattern」。**不阻塞本设计。**

### Phase 2A（若选 A）：能力知识包收敛

目标：把知识归拢到 per-capability 单元，Graph 与 handler 不变。这是**小重构**，不新建引擎。

- **新增** `ai/agent/skill/AgentCapability.java`（或沿用现有 record 模式）描述一个能力包：`{name, triggers, promptsKey, tools, modelRole, hitlTemplate, limits}`。不声明 graph。
- **改造** 各 `*IntentHandler`：锚定对应 `AgentCapability`，提示词换取 `promptConfig.get(..)` 从能力包取（现状已是 `promptConfig.get("orchestrator/xxx")`，主要是把散落的模型角色/工具清单/上限显式化到一处）。
- 保持 `IntentHandler` 接口、`PlanGraph`、SSE 协议、前端全零改动。

### Phase 2B（若选 B）：SubAgent 抽象立项（另行评审）

按 `docs/2026-08-23-多智能体协作设计.md` 出独立计划：SubAgent 注册 + agent_subtasks 表 + SubTaskDispatcher + 有界循环收敛（maxSteps/deadline/幂等/终止/审计）。**不在本计划展开。**

### Phase 2C（若选 C）：ADR 文档

把 §0-§2 写到 `docs/`，标题如「Graph + Skill 组合设计评审（不做 Skill 声明 Graph）」，作为将来评审依据。

---

## §6 文件清单（按所选路径）

- **必做（Phase 0）**：git commit（工作区）+ 新建 `feature/agent-skill-modularization` 分支。
- **A**：`MoonAgent/.../ai/agent/skill/AgentCapability.java`（新增）+ 各 `*IntentHandler.java`（小改）+ `resources/prompts/**`（可能微调分组）。
- **B**：另行立项，独立计划。
- **C**：`docs/网络 Graph+Skill 评审.md`（新增，不覆盖原文件，按你「绝不覆盖原文件」偏好）。
- **不执行**：`.hermes/plans/2026-08-25_210000-*.md` 两份草案（归档/删除，删除前列出作用供你确认）。

---

## §7 验证

- **编译**：`export JAVA_HOME="C:\Program Files\Java\jdk-21"` + `"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f MoonAgent/pom.xml compile -q`。
- **回归**：MoonAgent e2e 冒烟（`X-User-Id` 头 + `POST /messages/stream`）——主回答流式 + aisplit 链 human_input 卡片 + resume 续流，按 `references/plangraph-migration-implementation.md` 配方。
- **前端零改动**：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build`（SSE 协议不变）。
- **若 C**：无代码，仅文档校对。

---

## §8 风险与取舍

| 项 | 影响 | 处置 |
|---|---|---|
| SKILL.md-as-graph 反模式 | 中-高（框架内框架/双控制轴/高风险 churn） | **不做**，归档草案 |
| 提示词已文件化，A 的边际收益有限 | 低 | A 只做知识内聚，不新建引擎；若你评估无收益 → 跳 C |
| SubAgent 是真正解法但更重 | 中 | 等真实第 7 能力需求再立项（B） |
| graph-core M1.1 非 GA | 低 | 已在 2.0.0-M1.1 跑通，不新增该依赖 |

## §9 开放问题（需你拍板）

1. **选 A / B / C？** A = 能力知识包收敛；B = SubAgent 立项（另行评审）；C = 只写 ADR。
2. **两份 210000 草案**：归档还是删除？（删除前我会列出各自作用与重叠关系供你确认——按你清理偏好。）
3. **分支名**：`feature/agent-skill-modularization` 可以吗？
4. **Phase 0 的 git 提交**：确认把 `AStoryboardMobile/*` 与 `AiConfigProperties.java` 一并提交（你说了「提交所有文件」，我默认全提）。
