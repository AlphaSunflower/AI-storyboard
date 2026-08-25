# Skill-Based Agent 设计方案

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 用声明式 YAML Skill 替代硬编码 IntentHandler，让新增 Agent 能力 = 新增一个 YAML 文件，核心编排器零改动。

**Architecture:** Agent 能力定义为 YAML Skill 文件（`classpath:skills/*.yaml`），每个 Skill 声明：触发条件、系统提示词、可用工具、HITL 检查点模板、SSE 事件模板。编排器（SkillOrchestrator）加载所有 Skill → 意图识别 → 路由到匹配 Skill → 用 LLM 在 Skill 上下文中执行。这与 Hermes 的 skill 模式本质相同：声明式能力定义 + 运行时解释执行。

**Tech Stack:** Spring Boot 4, Spring AI 2.0 ChatClient, SnakeYAML (已有), commons-text (已有), PostgreSQL

---

## 现状分析

当前系统（CLAUDE.md 描述，代码尚未全部落地）：
- `PromptConfig` 已实现 YAML 提示词加载（`classpath:prompts/*.yaml`），但只有模板+变量替换，无触发/工具/HITL 声明
- `AgentOrchestrator` + `IntentHandler` 策略模式：每个意图 = 一个 Java 类，新增意图要写代码
- `agent_checkpoints` 表已建（V4 migration）

**核心痛点：** 新增一个 Agent 能力（比如「音乐生成」）需要写 Java 类 + 注册 + 测试，门槛高。

**Skill 方案收益：** 新增能力 = 新增一个 YAML 文件，非开发者（产品/运营）也能定义 Agent 行为。

---

## Skill YAML 格式设计

```yaml
# skills/storyboard-split.yaml
name: storyboard-split
description: 根据用户需求生成分镜脚本
version: "1.0"

# 触发条件（规则前置 + LLM 兜底）
triggers:
  keywords: ["分镜", "剧本", "脚本", "故事板"]    # 规则前置匹配
  intent_type: "aisplit"                          # LLM 意图分类标签
  confidence_threshold: 0.6                       # 低于此值走澄清

# 系统提示词（支持 {{variable}} 占位符）
system_prompt: |
  你是一个专业的分镜师。用户需求：{{user_message}}
  当前项目：{{project_title}}
  已有分镜数量：{{scene_count}}

# 可用工具列表（对应 AgentTools 的 @Tool 方法）
tools:
  - name: writeScenes
    description: 写入分镜到数据库
  - name: replaceScenes
    description: 覆盖替换现有分镜

# HITL（Human-in-the-Loop）检查点定义
hitl:
  - id: scene_confirm
    trigger: after_generation                   # 生成后暂停
    actions:
      - id: agree
        label: "满意，导入项目"
        icon: "✓"
      - id: disagree
        label: "不满意，重新生成"
        icon: "✗"
      - id: custom
        label: "自定义输入"
        icon: "✎"
        type: textarea
    message_template: "已生成 {{scene_count}} 个分镜，请确认："

# SSE 事件模板
events:
  on_start:
    type: workflow
    title: "分镜生成"
  on_progress:
    type: message
    template: "{{incremental_content}}"
  on_complete:
    type: message_end
    template: "已生成 {{scene_count}} 个分镜"

# 多轮对话策略
conversation:
  max_clarify_rounds: 2         # 连续澄清追问上限
  max_regenerate_rounds: 3      # 不满意重生成上限
  on_max_clarify: "proceed"     # 达上限后直接出默认方案
  on_max_regenerate: "free_input" # 达上限后提示直接输入新需求

# 条件分支（简化版状态机）
branches:
  - condition: "{{scene_count}} > 0"    # 项目已有分镜
    hitl_override:
      id: scene_mode
      actions:
        - id: optimize
          label: "基于现有优化"
        - id: fresh
          label: "全新创建"
        - id: skip
          label: "不生成"
```

---

## 与现有系统的关系

```
现有（硬编码）                      Skill 方案（声明式）
─────────────                      ──────────────────
IntentHandler 接口           →     Skill YAML 文件
handle() 方法                →     system_prompt + tools
resume() 方法                →     hitl.actions + branches
AgentOrchestrator 注册表     →     SkillLoader 扫描
PromptConfig                 →     复用（Skill 内嵌 prompt）
intent-threshold 配置        →     triggers.confidence_threshold
```

**渐进迁移路径：** 不需要一次全改。现有 IntentHandler 可以继续运行，新能力用 Skill 定义，逐步迁移旧 handler 为 Skill。

---

## Step-by-Step Plan

### Task 1: SkillLoader — YAML 加载器

**Objective:** 创建 SkillLoader，扫描 `classpath:skills/*.yaml` 并解析为 Java record

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/ai/agent/skill/AgentSkill.java` (record)
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/ai/agent/skill/SkillLoader.java` (@Component)
- Create: `AIStoryboardBackend/src/main/resources/skills/storyboard-split.yaml` (示例 Skill)

**实现要点：**
```java
// AgentSkill.java — 一个 record 对应 YAML 全部字段
public record AgentSkill(
    String name,
    String description,
    TriggerConfig triggers,
    String systemPrompt,
    List<ToolRef> tools,
    List<HITLCheckpoint> hitl,
    Map<String, EventTemplate> events,
    ConversationPolicy conversation,
    List<Branch> branches
) {
    public record TriggerConfig(List<String> keywords, String intentType, double confidenceThreshold) {}
    public record ToolRef(String name, String description) {}
    public record HITLCheckpoint(String id, String trigger, List<HITLAction> actions, String messageTemplate) {
        public record HITLAction(String id, String label, String icon, String type) {}
    }
    public record EventTemplate(String type, String title, String template) {}
    public record ConversationPolicy(int maxClarifyRounds, int maxRegenerateRounds, String onMaxClarify, String onMaxRegenerate) {}
    public record Branch(String condition, HITLCheckpoint hitlOverride) {}
}
```

```java
// SkillLoader.java — 复用 PromptConfig 的扫描模式
@Component
public class SkillLoader {
    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();

    @PostConstruct
    void loadAll() {
        // 扫描 classpath:skills/*.yaml
        // 用 SnakeYAML 解析为 Map，手动映射到 AgentSkill record
        // 关键：嵌套对象（TriggerConfig/HITLCheckpoint）逐层映射
    }

    public AgentSkill get(String name) { return skills.get(name); }
    public Collection<AgentSkill> all() { return skills.values(); }
    public List<AgentSkill> findByKeyword(String text) { ... }
}
```

**验证：** `mvn compile` 通过 + 加载日志打印已加载 Skill 数量

---

### Task 2: SkillRouter — 意图识别 + Skill 路由

**Objective:** 替代硬编码 IntentHandler 注册表，用 Skill 的 triggers 做路由

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/ai/agent/skill/SkillRouter.java`

**实现要点：**
```java
@Component
public class SkillRouter {
    private final SkillLoader skillLoader;
    private final ChatClient chatClient; // Spring AI

    /**
     * 路由逻辑（两层）：
     * 1. 规则前置：遍历所有 Skill 的 triggers.keywords，文本包含即命中
     * 2. LLM 兜底：无关键词命中 → 调 LLM 做意图分类，返回 {skillName, confidence}
     * 3. 低于 confidence_threshold → 返回 null（走澄清）
     */
    public AgentSkill route(String userMessage, Map<String, Object> context) {
        // 第一层：关键词匹配
        for (AgentSkill skill : skillLoader.all()) {
            for (String kw : skill.triggers().keywords()) {
                if (userMessage.contains(kw)) return skill;
            }
        }
        // 第二层：LLM 分类
        return llmClassify(userMessage, context);
    }
}
```

**验证：** 单元测试 — mock SkillLoader，验证关键词命中 / LLM 兜底 / 低信心返回 null

---

### Task 3: SkillExecutor — Skill 执行引擎

**Objective:** 根据 Skill 定义执行 LLM 对话 + 工具调用 + HITL 暂停

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/ai/agent/skill/SkillExecutor.java`

**实现要点：**
```java
@Component
public class SkillExecutor {
    private final ChatClient chatClient;
    private final AgentTools agentTools; // 已有的工具组件
    private final PromptConfig promptConfig;

    /**
     * 执行流程：
     * 1. 渲染 system_prompt（替换 {{variables}}）
     * 2. 组装 tools 列表（从 Skill.tools 映射到 AgentTools 的 @Tool 方法）
     * 3. 调 ChatClient 流式生成
     * 4. 遇到 HITL trigger → 构建 checkpoint → 发 human_input SSE 事件 → 返回
     * 5. 工具调用 → 执行对应 AgentTools 方法
     */
    public Flux<SseEvent> execute(AgentSkill skill, OrchestrationRequest request) {
        String systemPrompt = renderPrompt(skill.systemPrompt(), request.context());
        // ... ChatClient.builder().system(systemPrompt).tools(resolveTools(skill.tools()))
    }
}
```

**关键设计：** Skill.tools 的 name 映射到 AgentTools 的实际方法。维护一个 `Map<String, ToolMethod>` 注册表，Skill 只声明工具名，Executor 反射查找。

**验证：** 集成测试 — 传入简单 Skill + mock LLM，验证 SSE 事件流

---

### Task 4: SkillOrchestrator — 入口编排器

**Objective:** 替代 AgentOrchestratorImpl，统一入口

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/ai/agent/skill/SkillOrchestrator.java`

**实现要点：**
```java
@Component
public class SkillOrchestrator {
    private final SkillRouter router;
    private final SkillExecutor executor;
    private final ConversationLock lock;

    /**
     * run() 流程：
     * 1. 获取会话锁
     * 2. 路由到匹配 Skill
     * 3. 无匹配 → 走默认 Skill（intent-other，闲聊）
     * 4. 执行 Skill → SSE 流
     * 5. 完成后释放锁
     */
    public Flux<SseEvent> run(String conversationId, String userMessage, Map<String, Object> context) {
        AgentSkill skill = router.route(userMessage, context);
        if (skill == null) skill = router.defaultSkill(); // 闲聊兜底
        return executor.execute(skill, buildRequest(conversationId, userMessage, context));
    }
}
```

**验证：** 端到端 — 发消息 → SSE 流 → 检查事件类型

---

### Task 5: 迁移现有意图到 Skill YAML

**Objective:** 把 CLAUDE.md 描述的 4 个 IntentHandler（aisplit/pic/video/other）转为 YAML Skill

**Files:**
- Create: `src/main/resources/skills/storyboard-split.yaml` (aisplit → storyboard-split)
- Create: `src/main/resources/skills/image-generate.yaml` (pic → image-generate)
- Create: `src/main/resources/skills/video-generate.yaml` (video → video-generate)
- Create: `src/main/resources/skills/general-chat.yaml` (other → general-chat)

**关键：** 每个 YAML 包含完整的行为定义（触发词、提示词、工具、HITL、对话策略）。复杂分支（如 aisplit 的 scene-mode gate → optimize gate → confirm）用 `branches` 声明。

**验证：** 4 个 YAML 全部被 SkillLoader 加载，日志无 warn

---

### Task 6: HITL 模板引擎

**Objective:** Skill 的 hitl 声明渲染为实际的 checkpoint + SSE 事件

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/ai/agent/skill/HITLRenderer.java`

**实现要点：**
```java
@Component
public class HITLRenderer {
    /**
     * 渲染流程：
     * 1. 从 Skill.hitl 找到当前 trigger 匹配的 checkpoint
     * 2. 渲染 message_template（替换变量）
     * 3. 生成 form_token → 落库 agent_checkpoints
     * 4. 构建 human_input SSE 事件
     */
    public SseEvent render(AgentSkill.HITLCheckpoint checkpoint, Map<String, Object> vars, String conversationId) {
        String message = renderTemplate(checkpoint.messageTemplate(), vars);
        String token = UUID.randomUUID().toString();
        // 落库 checkpoint ...
        return SseEvent.humanInput(token, checkpoint.actions(), message);
    }
}
```

**验证：** 单元测试 — mock DB，验证 SSE 事件结构和 checkpoint 落库

---

### Task 7: 前端适配（零改动验证）

**Objective:** 验证 Skill 方案产出的 SSE 事件与现有前端协议完全兼容

**验证步骤：**
1. 启动后端，发送触发 storyboard-split Skill 的消息
2. 检查 SSE 事件格式是否与 CLAUDE.md 协议一致
3. 前端 AgentChatPanel 无需改动（事件协议不变）

**预期：** 前端零改动。Skill 只是后端内部架构变更，SSE 事件协议不变。

---

## 风险与取舍

| 风险 | 影响 | 缓解 |
|------|------|------|
| YAML 表达力有限，复杂分支（如 aisplit 的多轮澄清+重生成）难以声明 | 中 | branches 支持条件表达式；超复杂逻辑 fallback 到 Java handler |
| LLM 分类延迟（+1 次调用） | 低 | 关键词前置命中率高时跳过 LLM |
| YAML 维护者不了解约束（如 tools 名称必须与 AgentTools @Tool 匹配） | 中 | SkillLoader 启动校验 + 详细错误信息 |
| 嵌套 record 映射 YAML 容易 NPE | 中 | 防御性解析，缺失字段用默认值 |

## 开放问题

1. **Skill 热更新**：是否需要支持运行时重载 Skill YAML（不重启）？当前方案只在 @PostConstruct 加载。
2. **Skill 版本管理**：是否需要 Skill 多版本共存？当前只取最新。
3. **条件表达式引擎**：`branches.condition` 用什么解析？简单方案 = 字符串 contains 匹配；完整方案 = SpEL/自定义 DSL。建议先用简单方案。
4. **兜底策略**：所有 Skill 都不匹配时，走 `general-chat` Skill 还是硬编码兜底？

---

## 文件清单

```
新增文件：
├── src/main/java/com/storyboard/ai/agent/skill/
│   ├── AgentSkill.java          # Skill 数据结构（record）
│   ├── SkillLoader.java         # YAML 加载器
│   ├── SkillRouter.java         # 意图路由
│   ├── SkillExecutor.java       # Skill 执行引擎
│   ├── SkillOrchestrator.java   # 统一入口
│   └── HITLRenderer.java        # HITL 渲染器
├── src/main/resources/skills/
│   ├── storyboard-split.yaml    # 分镜生成
│   ├── image-generate.yaml      # 图片生成
│   ├── video-generate.yaml      # 视频生成
│   └── general-chat.yaml        # 通用对话

修改文件：
├── src/main/resources/application.yml  # 无改动（Skill 走 classpath 扫描）
└── 前端零改动（SSE 协议不变）

迁移文件（渐进，可选）：
└── 删除旧 IntentHandler 实现类 → 由 Skill YAML 替代
```
