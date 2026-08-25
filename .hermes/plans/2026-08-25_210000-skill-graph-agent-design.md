# Skill + Spring AI Alibaba Graph 智能体设计方案 v3

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Agent 能力定义为 SKILL.md 文件（YAML frontmatter + markdown body），Spring AI Alibaba Graph 作为执行引擎——Skill 的 frontmatter 声明 Graph 结构，markdown body 是 LLM 的 prompt。

**Architecture:** SKILL.md 的 YAML frontmatter 声明触发条件、节点类型、边、HITL；markdown body 是自然语言指令（system prompt）。SkillLoader 解析 SKILL.md → AgentSkill record → SkillGraphFactory 构建 StateGraph → CompiledGraph 执行。

**Tech Stack:** Spring Boot 4, Spring AI 2.0, spring-ai-alibaba-graph-core, SnakeYAML, PostgreSQL

---

## Skill 格式：SKILL.md

```markdown
---
name: storyboard-split
description: 根据用户需求生成分镜脚本
version: "1.0"

triggers:
  keywords: [分镜, 剧本, 脚本, 故事板]
  intent_type: aisplit
  confidence_threshold: 0.6

state:
  user_message: replace
  scene_count: replace
  scene_mode: replace
  plan: replace
  feedback: replace

nodes:
  - id: scene_mode_check
    type: llm
    output_key: scene_mode
  - id: script_generate
    type: llm
    tools: [writeScenes]
    output_key: plan
  - id: human_confirm
    type: hitl
    message: "已生成方案，请确认："
    actions:
      - { id: agree, label: "满意，导入项目" }
      - { id: disagree, label: "不满意，重新生成" }
      - { id: custom, label: "自定义修改", type: textarea }
    output_key: feedback
  - id: execute_import
    type: tool
    tool: writeScenes
    input_key: plan
  - id: regenerate
    type: llm
    output_key: plan

edges:
  - from: __START__
    to: scene_mode_check
  - from: scene_mode_check
    type: conditional
    conditions:
      - { when: "scene_mode == 'skip'", to: __END__ }
      - { when: "true", to: script_generate }
  - from: script_generate
    to: human_confirm
  - from: human_confirm
    type: conditional
    conditions:
      - { when: "feedback == 'agree'", to: execute_import }
      - { when: "feedback == 'disagree'", to: regenerate }
      - { when: "true", to: __END__ }
  - from: execute_import
    to: __END__
  - from: regenerate
    to: human_confirm

conversation:
  max_regenerate_rounds: 3
---

# 分镜生成 Skill

你是专业分镜师，负责根据用户需求生成分镜脚本。

## 节点指令

### scene_mode_check

项目已有 {{scene_count}} 个分镜。判断用户意图：
- 用户想基于现有分镜优化 → 输出 "optimize"
- 用户想全新创建 → 输出 "fresh"
- 用户不想生成 → 输出 "skip"

只输出一个词：optimize / fresh / skip。

### script_generate

用户需求：{{user_message}}
画幅：{{aspect_ratio}}，风格：{{style}}

以 JSON 数组格式返回分镜，每个包含：
- sceneNumber（整数）
- scriptContent（镜头描述）
- imagePrompt（图片提示词）
- videoPrompt（视频提示词）
- cameraMovement（运镜）
- shotType（景别）
- soundDesign（音效）

### regenerate

用户不满意，原因：{{feedback}}
上一轮方案：{{plan}}
请根据反馈调整后重新生成，格式同上。
```

**关键：** markdown body 里按 `### 节点id` 分段，每段是该节点的 system prompt。LLM 读的是自然语言，不是 YAML。

---

## 架构

```
SKILL.md 文件
  │
  ├─ YAML frontmatter → AgentSkill record（结构化数据）
  │    ├─ triggers → SkillRouter 路由
  │    ├─ state → KeyStrategyFactory
  │    ├─ nodes → StateGraph.addNode()
  │    └─ edges → StateGraph.addEdge() / addConditionalEdges()
  │
  └─ markdown body → 按 ### 节点id 分段
       └─ 每段 = 该节点的 system prompt（自然语言）
```

---

## Step-by-Step Plan

### Task 1: pom.xml 加 graph-core 依赖

**Files:** Modify `AIStoryboardBackend/pom.xml`

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-graph-core</artifactId>
    <version>1.0.0.2</version>
</dependency>
```

**验证：** `mvn compile -q` 通过

---

### Task 2: AgentSkill record

**Files:**
- Create: `src/main/java/com/storyboard/ai/agent/skill/AgentSkill.java`

```java
package com.storyboard.ai.agent.skill;

import java.util.List;
import java.util.Map;

public record AgentSkill(
    String name,
    String description,
    Triggers triggers,
    Map<String, String> state,          // key → strategy(replace/append)
    List<SkillNode> nodes,
    List<SkillEdge> edges,
    ConversationPolicy conversation,
    Map<String, String> nodePrompts     // nodeId → markdown prompt（从 body 解析）
) {
    public record Triggers(List<String> keywords, String intentType, double confidenceThreshold) {}
    public record SkillNode(String id, String type, String outputKey, String inputKey,
                            String tool, List<String> tools, String message,
                            List<HITLAction> actions) {
        public record HITLAction(String id, String label, String type) {}
    }
    public record SkillEdge(String from, String to, String type, List<Condition> conditions) {
        public record Condition(String when, String to) {}
    }
    public record ConversationPolicy(int maxRegenerateRounds) {}
}
```

**验证：** `mvn compile -q`

---

### Task 3: SkillLoader — 解析 SKILL.md

**Files:**
- Create: `src/main/java/com/storyboard/ai/agent/skill/SkillLoader.java`

核心解析逻辑：

```java
public AgentSkill parseSkillMd(Resource resource) {
    String content = new String(resource.getContentAsByteArray(), UTF_8);

    // 1. 分割 frontmatter 和 body
    //    格式：---\n{yaml}\n---\n{markdown body}
    String[] parts = content.split("^---\\s*$", 3);
    String yamlPart = parts.length > 1 ? parts[1] : "";
    String bodyPart = parts.length > 2 ? parts[2] : "";

    // 2. YAML → 结构化数据
    Map<String, Object> meta = yaml.load(yamlPart);
    // 手动映射到 AgentSkill 的 triggers/state/nodes/edges/conversation

    // 3. Markdown body → 按 ### 节点id 分段
    Map<String, String> nodePrompts = parseNodePrompts(bodyPart);
    // 正则：### (\\w+)\\n → 提取节点id 和后续文本直到下一个 ### 或文件末尾

    return new AgentSkill(..., nodePrompts);
}

private Map<String, String> parseNodePrompts(String body) {
    Map<String, String> prompts = new LinkedHashMap<>();
    // 匹配 ### node_id 后面的内容
    Pattern p = Pattern.compile("###\\s+(\\w[\\w-]*)\\s*\\n(.*?)(?=###\\s+\\w|$)", Pattern.DOTALL);
    Matcher m = p.matcher(body);
    while (m.find()) {
        prompts.put(m.group(1).trim(), m.group(2).trim());
    }
    return prompts;
}
```

**验证：** 写测试 — 解析一个示例 SKILL.md，验证 frontmatter 解析正确 + nodePrompts 按节点分割

---

### Task 4: SkillGraphFactory — AgentSkill → StateGraph

**Files:**
- Create: `src/main/java/com/storyboard/ai/agent/skill/SkillGraphFactory.java`

```java
@Component
public class SkillGraphFactory {
    private final ChatClient.Builder chatClientBuilder;
    private final AgentTools agentTools;

    public StateGraph buildGraph(AgentSkill skill) throws GraphStateException {
        KeyStrategyFactory factory = () -> {
            HashMap<String, KeyStrategy> map = new HashMap<>();
            skill.state().forEach((k, v) -> map.put(k, new ReplaceStrategy()));
            return map;
        };

        StateGraph graph = new StateGraph(skill.name(), factory);

        for (AgentSkill.SkillNode node : skill.nodes()) {
            String prompt = skill.nodePrompts().get(node.id());
            graph.addNode(node.id(), nodeasync(buildNodeAction(node, prompt)));
        }

        for (AgentSkill.SkillEdge edge : skill.edges()) {
            if ("conditional".equals(edge.type())) {
                graph.addConditionalEdges(edge.from(),
                    edgeasync(buildEdgeAction(edge.conditions())),
                    buildRouteMap(edge.conditions()));
            } else {
                graph.addEdge(edge.from(), mapEndpoint(edge.to()));
            }
        }
        return graph;
    }

    private NodeAction buildNodeAction(AgentSkill.SkillNode node, String prompt) {
        return switch (node.type()) {
            case "llm" -> new LLMNode(chatClientBuilder, prompt, node.outputKey());
            case "tool" -> new ToolNode(agentTools, node.tool(), node.inputKey(), node.outputKey());
            case "hitl" -> new HITLNode(node.message(), node.actions(), node.outputKey());
            default -> throw new IllegalArgumentException("未知节点类型: " + node.type());
        };
    }
}
```

**验证：** 单元测试 — 传入 general-chat Skill，buildGraph 不抛异常

---

### Task 5: 3 个通用 Node 实现

**Files:**
- Create: `src/main/java/com/storyboard/ai/agent/skill/node/LLMNode.java`
- Create: `src/main/java/com/storyboard/ai/agent/skill/node/ToolNode.java`
- Create: `src/main/java/com/storyboard/ai/agent/skill/node/HITLNode.java`

LLMNode 核心：
```java
public class LLMNode implements NodeAction {
    private final ChatClient chatClient;
    private final String promptTemplate;  // 从 SKILL.md body 解析的 markdown
    private final String outputKey;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        // 用 StringSubstitutor 替换 {{variable}} 占位符
        String prompt = StringSubstitutor.replace(promptTemplate, state.data());

        Flux<ChatResponse> flux = chatClient.prompt().user(prompt).stream().chatResponse();

        AsyncGenerator<? extends NodeOutput> generator = StreamingChatGenerator.builder()
            .startingNode(outputKey)
            .startingState(state)
            .mapResult(r -> Map.of(outputKey, r.getResult().getOutput().getText()))
            .build(flux);

        return Map.of(outputKey, generator);
    }
}
```

**验证：** 各 Node 单元测试

---

### Task 6: SkillRouter + AgentGraph

**Files:**
- Create: `src/main/java/com/storyboard/ai/agent/skill/SkillRouter.java`
- Create: `src/main/java/com/storyboard/ai/agent/skill/AgentGraph.java`

（与 v2 方案相同，略）

---

### Task 7: AgentController 对接

**Files:**
- Create/Modify: AgentController
- Modify: SecurityConfig

SSE 协议不变，前端零改动。

---

### Task 8: 写 4 个 SKILL.md

**Files:**
- Create: `src/main/resources/skills/storyboard-split/SKILL.md`
- Create: `src/main/resources/skills/image-generate/SKILL.md`
- Create: `src/main/resources/skills/video-generate/SKILL.md`
- Create: `src/main/resources/skills/general-chat/SKILL.md`

每个目录可含 linked files（templates/、references/）——复用 Hermes skill 的目录结构。

---

### Task 9: Graph 可视化

启动时导出 Mermaid 图，方便调试。

---

## 文件清单

```
新增：
├── src/main/java/com/storyboard/ai/agent/skill/
│   ├── AgentSkill.java
│   ├── SkillLoader.java
│   ├── SkillGraphFactory.java
│   ├── SkillRouter.java
│   ├── AgentGraph.java
│   └── node/
│       ├── LLMNode.java
│       ├── ToolNode.java
│       └── HITLNode.java
├── src/main/resources/skills/
│   ├── storyboard-split/SKILL.md
│   ├── image-generate/SKILL.md
│   ├── video-generate/SKILL.md
│   └── general-chat/SKILL.md

修改：
├── pom.xml（加 graph-core 依赖）
├── SecurityConfig.java（路由）
└── AgentController.java（SSE 对接）

零改动：
└── 前端全部（SSE 协议不变）
```

## 阻塞项

**spring-ai-alibaba-graph-core 版本兼容**：1.0.0.2 对应 spring-ai 1.0.x，项目用 2.0.0。需先验证兼容性或找对应版本。
