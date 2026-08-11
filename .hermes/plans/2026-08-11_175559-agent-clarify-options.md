# Agent 澄清追问升级为「人工介入选项卡片」实现计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 把大模型的澄清追问（意图不明确 / 剧本信息不足 / 方案不明确）从「纯文本让用户打字回答」升级为「选项卡片，列出人工介入选项供用户点选」。

**Architecture:** 完全复用现有 HITL 基建——`human_input` SSE 事件 + `HumanInputCard`（通用 actions 按钮渲染）+ `agent_checkpoints` 表 + `resume()` 分发。后端把两处「文本追问」改为发 `human_input` 选项卡片；用户点选后走 `/form/submit`，后端按所选选项继续对应链。**前端零改动**。

**Tech Stack:** Spring Boot 4 / Spring AI 2.0（BeanOutputConverter 纯解析）、Zustand（已就绪）、SSE。

---

## 现状（关键代码路径）

| 环节 | 现状 | 文件 |
|------|------|------|
| 意图澄清（低置信度） | 只发 message 文本「没太确定你想做什么——分镜/图片/视频？」后结束本轮 | `AgentOrchestratorImpl.java:90-94` |
| 链内 gate 澄清（aisplit） | `callScriptOptimize` / `callStoryboardPlan` 返回 `{type:0,message}` 时只发 message 追问文本 | `AisplitIntentHandler.java:53-61,71-78` + `AgentOrchestratorSupport.java:111-137` |
| 选项卡片渲染 | `human_input` 事件 → `HumanInputCard` 渲染 actions 按钮 → `submitHumanInput(a.id)` → `POST /form/submit` | 前端已就绪，零改动 |
| resume 分发 | `byAction.get(action)` 按 checkpoint.action 查 handler（agree / generate_image / generate_video）；查不到兜底「好的，请继续完善」 | `AgentOrchestratorImpl.java:149-155` |

**关键技术约束**：前端提交的 action = 用户点击的**选项 id**；后端 `byAction.get(action)` 拿 handler，但 `handler.resume(request, cp)` **没有把 action 传进去**——澄清选项是 LLM 动态生成的（opt1/opt2…），必须把用户选中的选项 id 传给 handler 才能知道选了哪个。

**方案要点**：给 `OrchestrationRequest` 加 `action` 字段携带所选选项 id；新增两个 checkpoint action（`intent-clarify` / `clarify-option`），在 `AgentOrchestratorImpl.resume()` 里加两个特判分支（byAction 注册表与现有 handler 全部不动）；LLM 结构化输出 record 增加 `options` 字段（type=0 追问时给出 2~4 个选项）。

---

## 实施步骤

### Task 1: OrchestrationRequest 增加 action 字段

**Objective:** resume 时把用户点选的选项 id 传入 handler（现有接口签名不改）。

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/OrchestrationRequest.java`

**Step 1: 加字段**

在 `private String lastMessage = "";` 后加：

```java
/** resume 阶段用户点选的选项 id（run 阶段为空串；由 Orchestrator.resume 设置） */
private String action = "";
```

（类已有 `@Setter`/`@Getter`，非 final 字段不进 `@RequiredArgsConstructor`，构造器不变。）

**Step 2: 编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/OrchestrationRequest.java
git commit -m "feat(agent): OrchestrationRequest 增加 action 字段（resume 携带用户点选的选项 id）"
```

---

### Task 2: AgentOrchestratorSupport 增加 options 支持

**Objective:** LLM 追问时输出结构化选项；support 提供 plan 中取嵌套 List 的解析方法。

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/AgentOrchestratorSupport.java`

**Step 1: record 增加 options 字段**

```java
/** 剧本优化结构化输出（options：type=0 追问时的 2~4 个选项；type=1 时为空/缺失） */
public record ScriptOptimizeResult(int type, String message, String script,
                                   List<Map<String, Object>> options) {}
/** 分镜方案结构化输出（options 同上） */
public record StoryboardPlanResult(int type, String message,
                                   List<Map<String, Object>> options) {}
```

> Jackson 反序列化 record：缺失字段 → 引用类型为 null（不是报错），调用处判空即可。现有 `VideoPlanResult` 不动。

**Step 2: 更新两个 gate 的 system prompt**（`callScriptOptimize` / `callStoryboardPlan`）

剧本优化 prompt 改为：

```java
"输出 JSON：{\"type\":1或0,\"message\":\"给用户的回复\",\"script\":\"优化后的完整剧本\",\"options\":[{\"id\":\"opt1\",\"title\":\"选项文案\"}]}"
    + "。type=1 表示已理解可继续（script 必填，options 为空数组）；"
    + "type=0 表示需求不足需追问（此时 message 为追问内容，script 为空，options 必须给出 2~4 个选项供用户选择，title 用中文简短动词短语）。"
```

分镜方案 prompt 类似（去掉 script 字段要求）：

```java
"输出 JSON：{\"type\":1或0,\"message\":\"方案说明\",\"options\":[{\"id\":\"opt1\",\"title\":\"选项文案\"}]}。"
    + "type=1 方案已明确（options 为空数组）；type=0 需用户补充（message 为追问，options 必须给出 2~4 个选项）。"
```

**Step 3: support 增加 planListField 解析方法**

放在 `planField` 旁边（复用 `objectMapper`）：

```java
/** checkpoint plan JSON 取 items[0] 的 List 字段（宽松解析，缺失/失败返回空 list） */
public List<Map<String, Object>> planListField(String planJson, String field) {
    if (planJson == null || planJson.isBlank()) return List.of();
    try {
        var items = objectMapper.readTree(planJson).path("items");
        if (items.isArray() && !items.isEmpty() && items.get(0).has(field)) {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (var e : items.get(0).path(field)) out.add(objectMapper.convertValue(e, Map.class));
            return out;
        }
    } catch (Exception ignored) {}
    return List.of();
}
```

**Step 4: 编译验证 + Commit**

同上 mvn 命令，Expected: BUILD SUCCESS。

```bash
git commit -am "feat(agent): 剧本优化/分镜方案 LLM 追问支持 options 结构化选项 + planListField 解析"
```

---

### Task 3: AisplitIntentHandler 重构——gate 追问发选项卡片

**Objective:** type=0 追问从「纯文本」升级为「human_input 选项卡片」；resume(clarify-option) 按所选选项带补充文本重走 gate。

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/AisplitIntentHandler.java`

**Step 1: 抽两个可重入方法（handle 主体从步骤 1 开始整体迁入）**

```java
/** 从「剧本优化 gate」重走：优化 → 分镜方案 → 分镜 JSON → HITL（澄清选项补充后由 resume 调用） */
public String handleFromScriptGate(OrchestrationRequest request, String content) {
    // 现有 handle() 的步骤 1~4 整体搬入，步骤 1 的入参用 content 替换 request.getContent()
}

/** 从「分镜方案 gate」重走：方案 → 分镜 JSON → HITL（剧本已定，澄清选项补充后由 resume 调用） */
public String handleFromPlanGate(OrchestrationRequest request, String script) {
    // 步骤 2~4（分镜方案 gate 起），用脚本调用步骤 3 的 generateScenes(script, ...)
}
```

`handle()` 瘦身为：

```java
@Override
public String handle(OrchestrationRequest request) {
    return handleFromScriptGate(request, request.getContent());
}
```

**Step 2: type=0 分支改为发选项卡片**（两处，步骤 1 与步骤 2）

现有：

```java
if (opt == null || opt.type() == 0) {
    if (!support.clarifyLimitReached(conversationId, request)) {
        if (request.getLastMessage().isBlank()) {
            support.sendMessage(request, opt != null ? opt.message() : "已理解你的需求，请继续补充。");
        }
        return request.getLastMessage();
    }
    script = content;
}
```

改为（未达上限且有 options → 卡片；无 options → 退回纯文本追问，保证 LLM 不听话时不阻塞）：

```java
if (opt == null || opt.type() == 0) {
    if (!support.clarifyLimitReached(conversationId, request)) {
        if (opt != null && opt.options() != null && !opt.options().isEmpty()) {
            return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                opt.message(), "clarify-option",
                List.of(Map.of("kind", "script", "originalContent", content, "options", opt.options())),
                "human_input", opt.options()));
        }
        if (request.getLastMessage().isBlank()) {
            support.sendMessage(request, opt != null ? opt.message() : "已理解你的需求，请继续补充。");
        }
        return request.getLastMessage();
    }
    script = content;
}
```

分镜方案 gate（`plan.type()==0`）同样处理，`kind` 用 `"plan"`、`originalContent` 存当前 `script`。

**Step 3: resume 增加 clarify-option 分支**

```java
@Override
public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
    if ("clarify-option".equals(checkpoint.getAction())) {
        // plan: {kind:"script"|"plan", originalContent, options:[{id,title}]}
        String kind = support.planField(checkpoint.getPlan(), "kind");
        String original = support.planField(checkpoint.getPlan(), "originalContent");
        String chosenId = request.getAction();
        String title = support.planListField(checkpoint.getPlan(), "options").stream()
            .filter(o -> chosenId.equals(o.get("id")))
            .map(o -> String.valueOf(o.getOrDefault("title", "")))
            .findFirst().orElse("");
        String supplemented = title.isBlank() ? original : original + "\n（补充：" + title + "）";
        return "script".equals(kind)
            ? handleFromScriptGate(request, supplemented)
            : handleFromPlanGate(request, supplemented);
    }
    // ……现有 agree 写分镜逻辑保持不变……
}
```

> 澄清选项也计入 `clarifyCount`（resume 重走 gate 若再 type=0 会继续 +1，达 `max-clarify-rounds` 上限后自动退化为「按原始需求出默认方案」，语义与现状一致）。

**Step 4: 编译验证 + Commit**

```bash
git commit -am "feat(agent): aisplit gate 追问升级为选项卡片，resume 按所选选项补充后重走 gate"
```

---

### Task 4: 意图澄清（低置信度）改发意图选择卡片

**Objective:** 「分镜/图片/视频」澄清从文本改为 human_input 选项卡片。

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/AgentOrchestratorImpl.java`

**Step 1: run() 低置信度分支（90-94 行）改为 runHITLStage**

```java
if (intentResult.confidence() < agentConfig.getIntentThreshold()) {
    // 选项 id = intentType（intent-* 前缀与现有 resumeActions：agree/generate_image/generate_video 无冲突）
    return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
        "没太确定你想做什么，请选择：",
        "intent-clarify",
        List.of(Map.of("content", content)),
        "human_input",
        List.of(
            Map.of("id", "intent-aisplit", "title", "生成分镜"),
            Map.of("id", "intent-pic", "title", "生成图片"),
            Map.of("id", "intent-video", "title", "生成视频"),
            Map.of("id", "intent-other", "title", "其他 / 继续输入"))));
}
```

**Step 2: resume() 加两个特判（插在 byAction 分发之前，149 行附近）**

```java
// 意图澄清：用户点选目标意图 → 按所选意图重新分发 handle（originalContent 存原始用户消息）
if ("intent-clarify".equals(cp.getAction())) {
    IntentHandler h = byIntent.getOrDefault(action, byIntent.get(IntentRecognitionService.FALLBACK_TYPE));
    OrchestrationRequest r2 = new OrchestrationRequest(conversation,
            support.planField(cp.getPlan(), "content"), null, emitter);
    return h.handle(r2);
}
// 链内 gate 澄清：转 aisplit handler 按所选选项继续（action 由 request 携带）
if ("clarify-option".equals(cp.getAction())) {
    request.setAction(action);
    return byIntent.get("intent-aisplit").resume(request, cp);
}
```

> 意图澄清的 `action` 天然是 intentType（intent-aisplit/pic/video/other），不注册进 byAction 注册表（特判优先），与现有 `agree` 等 resumeAction 无冲突。`handler.handle(r2)` 返回的 lastMessage 会由调用方正常落库。

**Step 3: 编译验证 + Commit**

```bash
git commit -am "feat(agent): 低置信度意图澄清升级为选项卡片，resume 按所选意图重新分发"
```

---

### Task 5: 全量验证

**Files:**
- 无改动

**Step 1: 后端编译**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
Expected: BUILD SUCCESS

**Step 2: 前端零改动确认 + 类型检查（防回归）**

```bash
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit
```
Expected: 无错误（前端本任务未改动，仅确认基线）

**Step 3: 手动 e2e（启动 8082 后端 + 5173 前端，SSE 模拟）**

参考 `scripts/agent_e2e_test.py` 的 SSE 调用方式，验证三条路径：

| 场景 | 输入 | 期望 |
|------|------|------|
| 意图澄清 | 模糊消息（如「帮我做点东西」） | `human_input` 事件带 4 个 actions（生成分镜/生成图片/生成视频/其他） |
| 意图选择续流 | 提交 `formToken` + `action=intent-pic` | 进入图片链，流式 message + 后续事件 |
| gate 澄清 | 剧本需求信息不足（如「帮我写个剧本」） | `human_input` 事件带 LLM 生成的 2~4 个 options 按钮 |
| 选项续流 | 提交某 `optN` | 选项标题拼入需求，继续走分镜方案 → 分镜 JSON → 确认卡片（agree/disagree） |
| 兜底 | LLM 未输出 options（可临时断网/改 prompt 模拟） | 退回纯文本追问，不阻塞 |

**Step 4: Commit（如有遗留）**

---

## 文件改动汇总

| 文件 | 改动 |
|------|------|
| `service/agent/handler/OrchestrationRequest.java` | +1 字段（action） |
| `service/agent/handler/AgentOrchestratorSupport.java` | +2 record 字段（options）、+1 方法（planListField）、2 处 prompt |
| `service/agent/handler/AisplitIntentHandler.java` | 重构抽 2 个 gate 方法；2 处 type=0 发卡片；resume +clarify-option 分支 |
| `service/agent/impl/AgentOrchestratorImpl.java` | 低置信度分支改卡片；resume +2 特判 +setAction |
| 前端（AgentChatPanel / HumanInputCard / agentStore / agent.ts） | **零改动** |

## 风险与权衡

- **LLM 选项质量不可控**：options 由 deepseek-v4-flash 生成，可能不理想 → 兜底：options 缺失/为空时退回纯文本追问（Task 3 Step 2 已内置），不阻塞对话。
- **选项 id 冲突**：意图澄清选项 id 用 `intent-*` 前缀，gate 澄清选项 id 由 LLM 生成（optN）——均不进 byAction 注册表（特判优先），与现有 agree/generate_image/generate_video 无冲突。
- **record 反序列化**：Jackson 对缺失字段给 null（引用类型），代码已判空；`options` 字段对旧网关响应天然兼容（type=1 无 options）。
- **checkpoint 兼容**：新增两个 action 值（`intent-clarify` / `clarify-option`），旧 checkpoint 不受影响；plan 结构新增 `kind`/`originalContent`/`options` 键，`planField` 宽松解析缺失返回空串。
- **落库文案**：用户点选选项后落库 user 消息为「确认：生成分镜」——「确认」前缀对选项选择语义略怪但无功能影响；如介意可在 `submitFormAndResume` 落库文案处调整（可选，本期不动）。

## 开放问题

1. 意图澄清卡片是否需要「其他 / 继续输入」选项？（计划默认保留，走 intent-other 主回答）
2. 选项卡片标题文案风格（动词短语 vs 完整句）依赖 LLM，是否需要在前端兜底展示顺序？（本期不做）
