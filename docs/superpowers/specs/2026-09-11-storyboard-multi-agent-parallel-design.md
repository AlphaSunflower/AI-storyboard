# 分镜多智能体并行设计

## 目标

把现有 MoonAgent 分镜链从“单模型串行处理材质、骨架、分镜、绑定、审查”升级为运行时多智能体并行架构。

约束：

- 剧情骨架必须串行，只允许单一骨架智能体产出全局叙事、镜头总数和逐镜摘要，避免编号漂移、重复镜头和人物设定冲突。
- 骨架智能体不使用强模型。骨架只做轻量结构化规划，默认使用低延迟文本模型。
- 场景内容生成、逐镜素材绑定、质量审查必须并行执行。
- 不使用“全部参考图挂到全部分镜”的兜底作为主方案。
- 单点失败只重试对应智能体或对应批次，不能因为一个批次失败而丢弃全部中间结果。

## 非目标

- 不替换现有分镜编辑器、HITL 确认卡和场景持久化接口。
- 不改变用户已确认的“方案确认后写库”交互。
- 不把所有阶段都并行为无依赖的任务。素材 profiling -> 骨架 -> 场景批次 -> 绑定 -> 审查仍保持数据依赖。

## 运行时拓扑

```text
上传素材
  -> 素材 Profiler Agents（并行，每张图一个）
  -> 骨架 Agent（串行，显式配置的快模型）
  -> Scene Writer Agents（并行，按镜头区间拆分）
  -> Reference Binder Agents（并行，每镜一个）
  -> Review Agents（并行：连续性 / 一致性 / 素材覆盖）
  -> 确定性 Merge + 最多一轮 Repair
```

## 组件设计

### MaterialProfilerAgent

每张参考图独立执行，输入为图片 URL、用户填写的用途和上传类型。

输出 `MaterialProfile`：

- `url`
- `kind`: reference / asset
- `entityType`: character / prop / scene / style / unknown
- `summary`
- `identityConstraints`
- `reusableConstraints`
- `confidence`

带用户文字约束的 asset 可跳过视觉调用，直接使用用户约束。参考图必须进入视觉模型做独立分析，不再只靠 URL 字符串猜测。

### StorySkeletonAgent

全局唯一，严格串行。必须显式绑定 `skeleton-model`，不能隐式使用网关的默认文本模型。
本地默认配置可写当前网关已启用的快模型 `mimo-v2.5-pro`，但配置缺失时必须启动校验失败或明确报错，避免误用强模型。
骨架只输出轻量 JSON：

- `globalBible`: 人物、地点、风格、时间线
- `totalScenes`
- `batches`: 每批镜头范围
- `sceneBriefs`: 每镜一句话摘要和承接关系
- `continuityAnchors`: 跨批次必须保持一致的状态

骨架不展开完整 `imagePrompt` 和 `videoPrompt`，避免高成本模型承担结构规划工作。

### SceneWriterAgent

每个批次一个并行智能体。默认使用高质量场景模型，输入包括：

- 固定范围的 `sceneBriefs`
- `globalBible`
- 相邻批次的边界摘要
- 对应素材的 `MaterialProfile`

输出只包含本批镜头，必须保持骨架分配的 `sceneNumber`，禁止新增、删除或重排镜头。

并行度由配置限制，默认按 CPU/虚拟线程能力允许，但项目级默认限制为 4 个并发 agent。

### ReferenceBinderAgent

所有镜头生成后，每镜一个并行智能体，输入为本镜文本和全部 `MaterialProfile`。

输出：

- `sceneNumber`
- `bindings[]`
- 每个 binding 包含 `assetId`、`url`、`role`、`purposeText`
- 没有关联时必须返回空数组，明确表达“该镜不需要素材”

禁止默认把所有素材绑定到所有镜头。

### ReviewAgent

并行执行三类审查：

- 连续性：镜头编号、场景承接、动作因果
- 一致性：人物外观、道具、地点、全局风格
- 素材覆盖：参考图是否绑定到正确镜头，是否存在空绑定或错误重复

审查问题以结构化 `ReviewFinding` 返回。确认问题才进入 Repair Agent，不能以审查意见直接覆盖用户已经确认的镜头。

## 数据流与一致性

1. 每个 agent 的输出都带 `sceneNumber` 或稳定的 `materialId`。
2. Scene Writer 完成后按 `sceneNumber` 排序合并，不按完成时间合并。
3. Merge 前校验镜头编号连续、无重复、字段完整。
4. 缺批或编号冲突只重试对应范围。
5. Binder 结果与最终镜头集合并行前再次校验，防止阶段切换导致绑定丢失。
6. 所有失败通过 checkpoint 保存已完成部分，resume 时只补未完成 agent。

## 模型路由

新增独立配置：

```yaml
ai:
  agent:
    skeleton-model: ${AGENT_SKELETON_MODEL:mimo-v2.5-pro}
    scene-model: ${AGENT_SCENE_MODEL:qwen3.8-27b}
    binder-model: ${AGENT_BINDER_MODEL:mimo-v2.5-pro}
    review-model: ${AGENT_REVIEW_MODEL:mimo-v2.5-pro}
    max-parallel-agents: 4
```

约束：

- 上述变量是显式的业务模型配置，不是 `gateway:default_model:text`。
- `skeleton-model` 不要求强推理能力，只处理结构 JSON；为空时不得静默回退到网关默认模型。
- `scene-model` 保留高质量生成能力。
- `binder-model` 和 `review-model` 可采用低延迟模型。
- 所有模型仍可被环境变量覆盖，不能把模型名硬编码在业务类中。

## Spring AI 2.0 平台选择

### 纯 Spring AI Core

Spring AI 2.0 核心提供 `ChatClient`、结构化输出、Advisors 和 `ToolCallingAdvisor`。
官方 [Building Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
定义了 Chain、Parallelization、Routing、Orchestrator-Workers 和 Evaluator-Optimizer 模式，
但这些模式在核心中是应用层编排，不是原生多智能体运行时。

Spring AI 官方 examples 中的 `ParallelizationlWorkflow` 使用 `ExecutorService +
CompletableFuture` 并行执行并保持输入顺序；`OrchestratorWorkers` 示例定义了 orchestrator /
worker / synthesizer 的职责边界。它们适合作为实现参考，但不是需要引入的框架构件。

### Spring AI Alibaba Agent Framework

当前仓库已经使用 `spring-ai-alibaba-graph-core:2.0.0-M1.1`。同版本的
`spring-ai-alibaba-agent-framework:2.0.0-M1.1` 提供：

- `SequentialAgent`: 固定的串行阶段，可直接承载骨架阶段。
- `ParallelAgent`: Fan-Out/Gather，多子智能体并发执行并按策略合并，支持 `maxConcurrency`。
- `LlmRoutingAgent` / `SupervisorAgent`: 动态路由与主控-专家模式。
- `LoopAgent`: 有界审查/修复循环。
- `ReactAgent`: 带工具调用的执行智能体。

`ParallelAgent` 要求至少 2 个、至多 10 个子智能体，且每个子智能体的 `outputKey` 必须唯一。
因此场景批次建议控制在 2 到 10 个，例如 11 镜拆成 2 到 4 个批次；逐镜绑定如果超过 10 个单元，
应使用 Graph 的并行分支或分组并行，不应硬塞进一个 `ParallelAgent`。

### 推荐组合

```text
Spring AI Alibaba Agent Framework
  -> SequentialAgent(骨架)
  -> ParallelAgent(Scene Writers)
  -> ParallelAgent(Binders，必要时按 10 个以内分组)
  -> ParallelAgent(Reviewers)

Spring AI Alibaba Graph Core
  -> 动态分支、状态合并、checkpoint、人工中断与恢复

Spring AI Core
  -> ChatClient、结构化输出、工具调用、模型与 Advisor 边界
```

这是当前仓库在该版本线下最贴近原生能力的方案。Agent Framework 仍是 milestone 版本，
接入前需要单独验证依赖冲突和运行时兼容性；如果必须先保持稳定，可以先只用现有 Graph Core
加应用层 `CompletableFuture` 实现相同拓扑。

## 失败与重试

- 单个 Profiler 失败：只重试该图片，其他图片继续。
- 骨架失败：整个分镜链暂停，因为没有骨架不能安全并行。
- Scene Writer 某批失败：已成功批次保留，只重试失败范围。
- Binder 某镜失败：只重试该镜。
- Review 某类失败：其他审查继续，最终只标记缺失审查类型。
- 不采用全量素材挂载作为失败兜底。

## 可观测性

每个 agent 输出以下轨迹事件：

- `agent_started`
- `agent_finished`
- `agent_failed`
- `agent_retried`

轨迹必须包含 `agentType`、`unitId`、`batchRange`、`sceneNumber`、`model`、`durationMs` 和 `attempt`，方便区分“素材丢失”“骨架错误”“批次生成失败”“绑定失败”。

## 测试策略

- 合并顺序测试：即使批次完成顺序反转，最终镜头仍按 `sceneNumber` 排序。
- 并行隔离测试：一个慢批次不阻塞其他批次的执行。
- 编号完整性测试：缺号、重号、越界时只重试对应范围。
- 模型路由测试：骨架、场景、绑定和审查分别使用各自配置模型。
- 绑定准确性测试：两张参考图不会默认绑定到全部镜头，空绑定是合法结果。
- 失败恢复测试：某批失败后保留其他批次，resume 只补失败范围。
- 并发上限测试：并行 agent 数不会超过 `max-parallel-agents`。

## 分阶段实施

### Phase 1

先并行化 Scene Writer 批次，保留现有骨架和持久化接口。验证编号和合并顺序，降低当前约 7 分钟的串行分镜生成时间。

### Phase 2

接入 MaterialProfilerAgent 和 ReferenceBinderAgent，移除全量绑定兜底表达，建立逐镜素材绑定。

### Phase 3

接入并行 ReviewAgent 和局部 Repair，完善 checkpoint/resume 与可观测性。

## 验收标准

- 11 镜场景在存在多个批次时，批次生成并行执行，总耗时由最长批次决定，而不是各批次相加。
- 剧情骨架保持单智能体串行输出，且不使用高质量强模型。
- 每个分镜最多挂载与剧情有关的参考素材，不再出现默认全量挂载。
- 任一子任务失败不会导致整链重新生成，已完成结果可恢复。
- 现有 HITL 确认、分镜写库、编辑器和素材查看接口保持兼容。
