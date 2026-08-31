# Agent 能力扩展指南（Skill + Handler 双文件样板）

> MoonAgent 模块化约定：**加一个能力 = 一个 `SKILL.md`（+ 若需新工具再加一个 `@Tool`/`@McpTool`）**，核心零改动。
> 有复杂流程才写 `IntentHandler`；无 handler 的能力自动走 `skill_manager` 通用执行器（`GenericSkillSpecialist`）。
> 路由（规则前置表/澄清菜单）、注册表、校验都按约定自动跟上。

## 流程（四步）

1. 建 `resources/skills/<name>/SKILL.md`（声明式元数据 + body 知识/提示词）。
2. 建 `ai/agent/handler/<Name>IntentHandler.java`（`@Component implements IntentHandler`）。
3. 编译。启动时 `SkillConsistencyValidator` 自动校验：有 handler 的能力强制 handler↔skill 一致；
   无 handler 的能力走 `skill_manager`（仅校验 `triggers`/`label`/`model_role`，`hitl` 可选）。
4. e2e 冒烟：走 `/messages/stream` 点亮该能力。

> **无 handler 技能（声明即扩展）**：只写 `SKILL.md`（声明 `tools`/`model_role`/`triggers`/`hitl`/body），且对应 `@Tool` 已存在，
> 即可被 `SkillExpander` 索引、`skill_manager` 路由、`GenericSkillSpecialist` 用 Spring AI 2.0 的 `ToolCallingAdvisor` 执行；
> `hitl` 声明了按钮就弹确认卡，未声明则直接执行。
> 需要**多轮多次人工介入**时，用 `flow: [stepId1, stepId2, ...]` 声明多步澄清流程，body 用 `### stepId` 写每步提示词，
> 通用执行器会逐步弹卡、累积信息、同链推进（不切到别的 skill）。

## SKILL.md 样板

```markdown
---
name: <name>                       # 唯一标识（intentType = "intent-"+name）
description: 该能力何时使用（LLM 意图识别/澄清参考）
label: <菜单显示名>                 # 澄清卡片选项文案
version: "1.0"
model_role: text                   # text|vision|image|video（声明式元数据）
tools: [writeScenes]               # 该能力可用的 @Tool
limits: { max_clarify_rounds: 2 }  # 上限参数
triggers:                          # 有序正则/关键词，priority 升序先查
  - '<正则>'
priority: 30                       # 越小越先匹配（delete=10 必须先于 aisplit=30）
menu_order: 10                     # 澄清菜单顺序（越小越前）
hitl:                              # HITL 卡按钮 manifest（id 必须稳定，resume 路由靠它）
  - { id: agree, title: 满意 }
---
# 能力名（说明）
你的能力知识简介。

### <stepId>                      # 该步骤的系统提示词（prompt() 按需加载，未命中回退 promptConfig）
这段是该步骤给 LLM 的提示词/知识。
```

- **frontmatter 只放声明式元数据**：不放 nodes/edges/state/HITL 流程——Graph 永远是代码（防「Skill 声明 Graph」反模式）。
- **body `### id` 段**：该步骤提示词（`support.prompt(req, stepId, fallbackKey, vars)` 按需加载，优先 skill body，回退 promptConfig）。
- 修改提示词只需改 SKILL.md body，**不碰 Java**。

## IntentHandler 样板

```java
@Component
@RequiredArgsConstructor
public class <Name>IntentHandler implements IntentHandler {
    private final AgentOrchestratorSupport support;

    @Override public String intentType() { return "intent-<name>"; }
    @Override public Set<String> resumeActions() { return Set.of("<card-action-id>"); }
    @Override public String handle(OrchestrationRequest request) {
        // 内容处理 + 提示词（按需加载）+ runHITLStage 卡片 / 直接完成
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "<卡片文案>", "<cpAction>", List.of(Map.of()), "human_input",
                support.buildHITLCard(request, "<卡片文案>", List.of(
                        Map.of("id", "<card-action-id>", "title", "<按钮文案>"))),
                List.of(), Map.of(), Map.of()));
    }
    @Override public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
        switch (request.getAction()) {
            case "<card-action-id>": /* 执行 */ return "";
            default: return support.sendFriendlyError(request, "未知操作", "不支持的操作类型。");
        }
    }
}
```

## 自动生效的点（零改动）

- 意图识别规则前置表：`IntentRecognitionServiceImpl` @PostConstruct 从 `skill.all().triggers` 构建。
- 澄清菜单：`PlanGraph.intent_clarify` 从 `skill.allMenuOrdered().label` 构建。
- handler 注册表：`PlanGraph` 自动收集 `List<IntentHandler>`。
- Skill 提示词：`AgentOrchestratorSupport.prompt(req, stepId, fallbackKey, vars)` 按需加载 skill body。
- 一致性校验：`SkillConsistencyValidator`（handler↔skill 1:1 + 字段齐全）。

## 注意

- **priority 纪律**：破坏性意图（delete=10）必须排在宽意图（aisplit=30）之前，否则「删除分镜」会先被分镜词命中。
- **HITL 按钮 id 稳定**：`hitl` 里每个 id 都是 resume 路由的钥匙，改动会断链（id 只能加不能乱改；文案可自由改）。
- **触发词别太宽**：过宽会被 LLM/规则误路由，优先精确动词短语。
