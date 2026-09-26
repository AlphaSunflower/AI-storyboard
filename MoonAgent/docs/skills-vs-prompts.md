# skills 与 prompts 边界约定（历史说明）

> **状态：部分废弃（2026-09-26）。** Skill 选择已由 AgentScope 按标准
> `name/description` 完成；本文提到的 `SkillPromptBindingValidator` 和
> `AgentOrchestratorSupport.prompt()` 路由绑定属于旧 PlanGraph 路径。
> 当前 Skill、Prompt 和 MCP 资产治理见
> `docs/superpowers/specs/2026-09-26-agentscope-architecture.md`。

> 历史状态：定稿（2026-09-02）
> 目标：消除「skill + yaml 双轨」的违和感，明确每种提示词该放哪、为什么。

## 结论

`skills/*/SKILL.md` 与 `prompts/**/*.yaml` **有意分层，且代码已基本 skill-first**：编排 gate 的提示词
经 `AgentOrchestratorSupport.prompt(req, stepId, fallbackKey, vars)` 优先读 SKILL.md body 的 `### stepId`
段；这些 gate 对应的 `orchestrator/*` 兜底 YAML 已从 `prompts/` 删除，即当前提示词**只来自 SKILL.md**。

因此本约定不是推倒重来，而是把边界固化，并补齐变量替换对等与漂移护栏。

## 边界

### `skills/*/SKILL.md` —— 用户面技能（唯一来源）

frontmatter 元数据：
- 路由：`triggers` / `priority` / `menu_order`
- 模型角色：`model_role`（text / vision / image / video）
- 工具：`tools`、上限：`limits`、HITL 按钮：`hitl`、多步流程：`flow`

markdown body：按 `### stepId` 分段承载**编排 gate / 知识提示词**。此处是改提示词的唯一入口，
改配置/提示词都不用碰 Java。

满足「技能 = 带路由 + 用户面能力」，才有 `### stepId` 知识段。

### `prompts/**/*.yaml` —— 仅两类，不承载用户面技能提示词

1. 内部子服务提示词（无 triggers/menu_order/model_role，是 handler 背后的内部 lambda）：
   `services/intent-recognition`、`conversation-title`、`asset-matching`、`scene-asset-matching`、
   `agent-answer`、`prompt-optimize`、`image-refine-prompt`、`video-plan-with-image`，以及 `script/*`。
2. 跨切面 / 兜底模板：`orchestrator/hitl-card`、`orchestrator/friendly-error`、`orchestrator/image-recovery`。

> 这些不入 SKILL 的原因：它们没有 `triggers`/`menu_order`/`model_role`，强行并进 SKILL.md 会把
> 内部 lambda 伪装成「用户面技能」，破坏「技能 = 带路由的用户面能力」这个约定。

## 为什么 `services/*`、`script/*` 保留 YAML（跨模块约束）

- `PromptConfig`（agent-core）扫描 `classpath*:prompts/**/*.yaml`，是全模块共享；`SkillLoader`
  （MoonAgent）扫描 `classpath:skills/*/SKILL.md`，是单模块。
- agent-core 的 `VideoPlanServiceImpl` / `ImageRefinePromptServiceImpl` / `PromptOptimizeServiceImpl`
  与 `AIStoryboardBackend` 都按 name 引用 `services/*`、`script/*`。若并入 MoonAgent 的 SKILL body，
  这些模块无法访问，需跨模块重接。

## 变量替换对等

`{{var}}` 替换统一由 agent-core 的 `PromptResolver.resolve(template, vars)` 处理，skill body 路径
（`AgentOrchestratorSupport.prompt()`）与 YAML 路径（`PromptConfig.get(name, vars)`）共用同一实现，
行为一致。不做 SKILL.md 热刷新（用 `#` 后改 SKILL.md 需重启）。

## 漂移护栏

`SkillPromptBindingValidator` 启动校验 `AgentOrchestratorSupport.gatePrompts()` 每个绑定：
若 `fallbackKey` 不在 `prompts/**`，则 stepId 必须在对应 SKILL body 有 `### stepId` 段，否则启动 fail-fast，
杜绝「既不在 skill body、又没有兜底」导致的空提示词。
