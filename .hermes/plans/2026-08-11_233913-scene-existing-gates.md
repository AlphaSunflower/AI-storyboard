# AI 分镜「现有分镜检测 + 人工介入策略」实现计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 生成分镜前检测当前项目是否已有分镜；有则先问「基于现有优化 / 全新创建」，写库前再问「删除再导入 / 追加 / 不生成」，全程人工介入卡片驱动。

**Architecture:** 复用现有 HITL 基建（human_input 卡片 + checkpoint + resume 分发，前端零改动）。两个决策点各对应一个 checkpoint：链入口「处理方式」卡片（checkpoint action=`scene-mode`）+ 方案确认卡片动态选项（有现有分镜时选项从「满意/不满意」变为写库策略）。写库/删除复用 AisplitIntentHandler 已注入的 `SceneMapper`（BaseMapper.delete/selectList 现成能力），**不新增任何工具方法**。

**Tech Stack:** Spring Boot 4 / Spring AI 2.0 编排（策略注册 + HITL 模板）/ MyBatis-Plus。

---

## 现状（关键事实）

| 事实 | 位置 |
|------|------|
| aisplit 链：剧本优化 gate → 分镜方案 gate → generateScenes → 方案确认卡片(agree/disagree) → resume 写库 | `AisplitIntentHandler.java` |
| `writeScenes` 是**追加语义**（批量 insert） | `AgentTools.writeScenes` → `AgentGenerationService.writeScript` |
| `AisplitIntentHandler` **已注入 SceneMapper**（resume 里 selectCount 用） | `AisplitIntentHandler.java:29` |
| 删除/查询现成：`sceneMapper.delete(wrapper)` / `selectList(orderByAsc(sceneNumber))` | BaseMapper 能力 |
| resume 分发：`byAction.get(提交的 action)`；澄清类 checkpoint 走 Orchestrator 特判 | `AgentOrchestratorImpl.java` |
| 前端 HumanInputCard 通用渲染任意 actions | 零改动 |

**关键设计约束**：现有分镜检测**只能在 `handle()` 入口做一次**，不能放进 `handleFromScriptGate`——该方法会被 scene-mode resume 重入，重入时再检测会二次弹卡片死循环。

---

## 流程设计（两个决策点）

```
用户消息 → 意图 aisplit → handle() 检测现有分镜 count
│
├── count == 0 → 现状流程（剧本优化 → 方案 → 确认卡片[满意/不满意] → agree 追加写库）
│
└── count > 0 → 卡片1「处理方式」(checkpoint action=scene-mode)
     ├─ [基于现有分镜进一步优化]  scene-mode-optimize
     │     → resume: 查现有分镜文本 → 拼入需求 → handleFromScriptGate（LLM 基于现有优化）
     ├─ [额外创建全新分镜内容]     scene-mode-fresh
     │     → resume: handleFromScriptGate(原始需求)（正常生成，写库策略由卡片2决定）
     └─ [本次不生成分镜]           scene-mode-cancel
           → resume: 发 message 结束，不生成

生成完成后（卡片2 = 方案确认卡片，动态选项，checkpoint action 仍为 agree）：
│
├── count == 0 → 现状 [满意 agree][不满意 disagree]
└── count > 0  → [先删除现有分镜再导入 replace][追加到现有分镜后面 append]
                 [不满意，重新生成 disagree][不生成分镜 cancel]
                 （planText 前缀警告：覆盖导入将删除现有分镜及其产出素材）
    → resume(agree/replace/append/cancel/disagree)：
       replace → sceneMapper.delete(projectId) + writeScenes（覆盖）
       append  → writeScenes（追加，现状）
       cancel  → 不写库，发 message「已取消」
       agree   → writeScenes（无现有分镜场景，语义=新建）
       disagree → 发「调整意见」卡片 (checkpoint action=scene-regenerate，选项=✍自定义输入)
                  → resume(custom)：上一轮方案文本 + 用户调整意见 → 重走 handleFromScriptGate
                    （LLM 基于上一轮方案与意见重新优化剧本 → 重新生成 → 再次确认卡片，可循环）
```

**选项 id 命名**：卡片1 三个选项 id 以 `scene-mode-` 前缀（动态选项，不进 byAction 注册表，走 Orchestrator 特判——与 `clarify-option` 同模式）；卡片2 选项 id 固定 `replace`/`append`/`cancel`/`disagree`（注册进 resumeActions，与 `agree` 并列）；`scene-regenerate` 调整意见卡片的选项只有 `custom`（复用上一轮自定义输入，Orchestrator 特判）。

---

## 实施步骤

### Task 1: AisplitIntentHandler — handle() 入口现有分镜检测 + 卡片1

**Objective:** 意图 aisplit 且项目已有分镜时，先发「处理方式」卡片结束本轮，不直接进生成链。

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/AisplitIntentHandler.java`

**Step 1: handle() 开头加检测**

```java
@Override
public String handle(OrchestrationRequest request) {
    String projectId = request.getConversation().getProjectId();
    long existing = existingSceneCount(projectId);
    // 已有分镜：先问「处理方式」（基于现有优化 / 全新创建 / 不生成）——只在 handle 入口检测，
    // handleFromScriptGate 是 resume 重入点，放那里会二次弹卡片死循环
    if (existing > 0) {
        return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
                "检测到您当前项目已有 " + existing + " 个分镜，如何处理？",
                "scene-mode",
                List.of(Map.of("content", request.getContent(), "existingCount", existing)),
                "human_input",
                List.of(
                        Map.of("id", "scene-mode-optimize", "title", "基于现有分镜进一步优化"),
                        Map.of("id", "scene-mode-fresh", "title", "额外创建全新的分镜内容"),
                        Map.of("id", "scene-mode-cancel", "title", "本次不生成分镜"))));
    }
    return handleFromScriptGate(request, request.getContent());
}
```

**Step 2: 抽 4 个小方法**（放类底部，供 handle/resume 复用）

```java
/** 当前项目分镜数 */
private long existingSceneCount(String projectId) {
    return sceneMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.storyboard.entity.Scene>()
            .eq(com.storyboard.entity.Scene::getProjectId, projectId));
}

/** 清空当前项目全部分镜（覆盖导入前置），返回删除条数 */
private int clearScenes(String projectId) {
    return sceneMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.storyboard.entity.Scene>()
            .eq(com.storyboard.entity.Scene::getProjectId, projectId));
}

/** 现有分镜文本（scriptContent 列表，供「基于现有优化」拼入 LLM 上下文） */
private String buildExistingSceneText(String projectId) {
    var list = sceneMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.storyboard.entity.Scene>()
            .eq(com.storyboard.entity.Scene::getProjectId, projectId)
            .orderByAsc(com.storyboard.entity.Scene::getSceneNumber));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < list.size(); i++) {
        String c = list.get(i).getScriptContent();
        if (c != null && !c.isBlank()) sb.append(i + 1).append(". ").append(c).append("\n");
    }
    return sb.toString();
}
```

**Step 3: 编译验证 + Commit**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
Expected: BUILD SUCCESS

```bash
git commit -am "feat(agent): aisplit 入口检测现有分镜，有则发「处理方式」卡片（优化/全新/不生成）"
```

---

### Task 2: AgentOrchestratorImpl — scene-mode / scene-regenerate 特判 + byAction 路径 setAction

**Objective:** resume 时按 checkpoint action 把卡片1/调整意见卡片的提交转给 aisplit handler；byAction 分发路径补 setAction（disagree 需知道提交的 action）。

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/AgentOrchestratorImpl.java`

**Step 1: resume() 加两个特判**（插在 clarify-option 特判之后、byAction 分发之前）

```java
// 3) scene-mode：分镜处理方式卡片（基于现有优化/全新创建/不生成），选项 id 动态 → 转 aisplit handler
if ("scene-mode".equals(cp.getAction())) {
    request.setAction(action);
    byIntent.get("intent-aisplit").resume(request, cp);
    return request.getLastMessage();
}
// 4) scene-regenerate：调整意见卡片（不满意后），选项=custom → 转 aisplit handler（customText 带意见）
if ("scene-regenerate".equals(cp.getAction())) {
    request.setAction(action);
    request.setCustomText(customText);
    byIntent.get("intent-aisplit").resume(request, cp);
    return request.getLastMessage();
}
```

**Step 2: byAction 分发路径补 setAction**（现有代码 `handler.resume(request, cp);` 前加一行）

```java
// 提交的 action 传给 handler（disagree/replace/append/cancel 等写库策略与不满意分支需要区分）
request.setAction(action);
handler.resume(request, cp);
```

**Step 3: 编译验证 + Commit**

```bash
git commit -am "feat(agent): resume 特判 scene-mode/scene-regenerate 卡片转 aisplit，byAction 分发补 setAction"
```

---

### Task 3: AisplitIntentHandler — resume 三个新分支 + 方案确认卡片动态选项

**Objective:** 卡片1 resume（optimize/fresh/cancel）与卡片2 写库策略（replace/append/cancel）落地。

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/AisplitIntentHandler.java`

**Step 1: resumeActions 扩展**

```java
@Override
public Set<String> resumeActions() {
    return Set.of("agree", "replace", "append", "cancel", "disagree");
}
```

> `disagree` 从「byAction 兜底」升级为显式分支（注册进 resumeActions）；`scene-mode-*` / `scene-regenerate` 的 custom 选项走 Orchestrator 特判（Task 2），不注册。

**Step 2: resume() 开头加 scene-mode 分支**（clarify-option 分支之前）

```java
// 卡片1：分镜处理方式（scene-mode）——基于现有优化 / 全新创建 / 不生成
if ("scene-mode".equals(checkpoint.getAction())) {
    String action = request.getAction();
    String original = support.planField(checkpoint.getPlan(), "content");
    if ("scene-mode-cancel".equals(action)) {
        String msg = "好的，本次不生成分镜。需要时可以随时再让我生成。";
        support.sendMessage(request, msg);
        support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", msg));
        return msg;
    }
    if ("scene-mode-optimize".equals(action)) {
        // 基于现有分镜优化：现有分镜文本 + 原始需求拼入剧本优化 gate 输入
        String existing = buildExistingSceneText(request.getConversation().getProjectId());
        String supplemented = "【现有分镜】\n" + existing
                + "\n【用户需求】\n" + original
                + "\n请在保留现有分镜合理结构的基础上，按用户需求优化并生成新的分镜方案。";
        return handleFromScriptGate(request, supplemented);
    }
    // scene-mode-fresh：全新创建，正常流程
    return handleFromScriptGate(request, original);
}
```

**Step 3: 方案确认卡片动态选项**（`handleFromPlanGate` 的 runHITLStage 处）

```java
// 4. HITL 通用模板：方案消息 → checkpoint(agree) → human_input 事件。
// 已有分镜 → 选项变为写库策略（replace/append/disagree/cancel）+ 覆盖警告；无 → 现状（满意/不满意）
long existing = existingSceneCount(request.getConversation().getProjectId());
List<Map<String, Object>> actions = existing > 0
        ? List.of(
            Map.of("id", "replace", "title", "先删除现有分镜再导入"),
            Map.of("id", "append", "title", "追加到现有分镜后面"),
            Map.of("id", "disagree", "title", "不满意，重新生成"),
            Map.of("id", "cancel", "title", "不生成分镜"))
        : List.of(Map.of("id", "agree", "title", "满意"), Map.of("id", "disagree", "title", "不满意"));
String planText = "📋 分镜方案（共 " + scenes.size() + " 个镜头）：\n" + support.summarizeScenes(scenes);
if (existing > 0) {
    // 覆盖导入会删除现有分镜及其产出素材——卡片正文前置警告
    planText = "⚠ 检测到您当前已有 " + existing + " 个分镜（含已生成的图片/视频素材），覆盖导入将全部删除。\n" + planText;
}
return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
        planText, "agree", scenes, "human_input", actions));
```

> 说明：checkpoint.action 保持 `agree`（StagePlan action 参数不动），新选项 id 走 byAction 注册表（Task 3 Step 1 已注册 replace/append/cancel/disagree）。

**Step 4: resume() 的 agree 分支改为写库策略分发 + 不满意走调整意见卡片**

```java
// 执行写分镜（EXECUTE step）；写库策略按提交 action：
// replace=先清空现有再写（覆盖） / append=追加（现状） / cancel=不写 / disagree=不满意→调整意见卡片重走
String chosen = request.getAction();
if ("cancel".equals(chosen)) {
    String msg = "好的，已取消本次分镜导入，现有分镜保持不变。";
    support.sendMessage(request, msg);
    support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", msg));
    return msg;
}
if ("disagree".equals(chosen)) {
    // 不满意 → 发「调整意见」卡片：上一轮方案文本存 plan，用户自定义输入意见后重走生成链
    String prevPlan = "上一轮分镜方案：\n" + support.summarizeScenes(support.parsePlanScenes(checkpoint.getPlan()));
    return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
            "这个分镜方案不满意？请告诉我需要调整什么（节奏、镜头数量、画面风格等），我会重新生成。",
            "scene-regenerate",
            List.of(Map.of("content", prevPlan)),
            "human_input",
            List.of(Map.of("id", "custom", "title", "✍ 自定义输入"))));
}
List<AgentSceneItem> items = support.parsePlanScenes(checkpoint.getPlan());
if ("replace".equals(chosen)) {
    int removed = clearScenes(request.getConversation().getProjectId());
    log.info("分镜覆盖导入：已清空 {} 条现有分镜，projectId={}", removed, request.getConversation().getProjectId());
}
int count = agentTools.writeScenes(request.getConversation().getProjectId(), items)
        .getOrDefault("count", 0) instanceof Number n ? n.intValue() : 0;
// ……sceneCount 计算与 resumeStage 收尾保持现状（selectCount 总数，replace 后即新数量）……
```

**Step 5: resume() 增加 scene-mode / scene-regenerate 分支**（clarify-option 分支之后）

```java
// 卡片1：分镜处理方式（scene-mode）——基于现有优化 / 全新创建 / 不生成
if ("scene-mode".equals(checkpoint.getAction())) {
    String action = request.getAction();
    String original = support.planField(checkpoint.getPlan(), "content");
    if ("scene-mode-cancel".equals(action)) {
        String msg = "好的，本次不生成分镜。需要时可以随时再让我生成。";
        support.sendMessage(request, msg);
        support.sendEvent(request, "message_end", Map.of("messageId", "", "sceneCount", -1L, "content", msg));
        return msg;
    }
    if ("scene-mode-optimize".equals(action)) {
        // 基于现有分镜优化：现有分镜文本 + 原始需求拼入剧本优化 gate 输入
        String existing = buildExistingSceneText(request.getConversation().getProjectId());
        String supplemented = "【现有分镜】\n" + existing
                + "\n【用户需求】\n" + original
                + "\n请在保留现有分镜合理结构的基础上，按用户需求优化并生成新的分镜方案。";
        return handleFromScriptGate(request, supplemented);
    }
    // scene-mode-fresh：全新创建，正常流程
    return handleFromScriptGate(request, original);
}

// 调整意见（scene-regenerate）：上一轮方案文本 + 用户意见 → 重走生成链（LLM 基于意见重新优化）
if ("scene-regenerate".equals(checkpoint.getAction())) {
    String prevPlan = support.planField(checkpoint.getPlan(), "content");
    String opinion = request.getCustomText();
    String supplemented = prevPlan + "\n【用户调整意见】\n"
            + (opinion == null || opinion.isBlank() ? "请重新生成一版更合适的方案" : opinion);
    return handleFromScriptGate(request, supplemented);
}
```

**Step 6: 编译验证 + Commit**

```bash
git commit -am "feat(agent): 方案确认卡片按现有分镜动态选项（覆盖警告/追加/不生成），不满意走调整意见卡片重走生成链"
```

---

### Task 4: 全量验证

**Files:**
- 无改动

**Step 1: 后端编译 + 前端基线**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit
```
Expected: 均无错误（前端零改动仅基线）

**Step 2: ad-hoc 验证脚本**（tempfile + `hermes-verify-` 前缀，跑完即删）

断言：
- `resumeActions` 含 `replace`/`append`/`cancel`
- `"scene-mode"` 在 Orchestrator 特判 + AisplitIntentHandler resume 分支存在
- 卡片1 三选项 id 齐备（scene-mode-optimize/fresh/cancel）
- 方案确认卡片动态选项（`existing > 0` 分支含 replace/append/cancel；无分镜分支含 agree）
- `clearScenes`（sceneMapper.delete）在 replace 分支被调用
- 关键防死循环：`"scene-mode"` 检测只在 `handle(` 入口，`handleFromScriptGate` 内不出现 `existingSceneCount(` 调用（重入安全）

**Step 3: 手动 e2e（可选，需起 8085 测试实例）**

| 场景 | 输入 | 期望 |
|------|------|------|
| 已有分镜 + AI 分镜需求 | 项目有 5 分镜，发「帮我生成分镜」 | 卡片1「检测到您当前项目已有 5 个分镜」3 选项 |
| 卡片1 → 全新创建 | 提交 scene-mode-fresh | 正常生成链 → 方案确认卡片含 replace/append/disagree/cancel |
| 方案确认 → 覆盖 | 提交 replace | 先清空 5 条再写入 N 条新分镜 |
| 方案确认 → 追加 | 提交 append | 5 + N 条并存 |
| 方案确认 → 取消 | 提交 cancel | 不写库，现有分镜不变，message_end |
| 卡片1 → 基于现有优化 | 提交 scene-mode-optimize | LLM 上下文含现有分镜文本，产出优化方案 |
| 卡片1 → 不生成 | 提交 scene-mode-cancel | 直接结束，无生成 |
| 无现有分镜回归 | 空项目发 AI 分镜 | 现状两张卡片流程不变 |

---

## 文件改动汇总

| 文件 | 改动 |
|------|------|
| `handler/AisplitIntentHandler.java` | handle() 入口检测 + 卡片1；resumeActions 扩展；resume 3 新分支；方案确认卡片动态选项；4 个小方法 |
| `impl/AgentOrchestratorImpl.java` | resume 特判 `scene-mode`（1 个 if） |
| 前端 | **零改动** |

## 风险与权衡

- **两次卡片确认**（处理方式 + 写库策略）多一步点击——但这是用户描述的两个独立决策点，符合「不断完善方案，用户满意再执行」哲学；如嫌繁琐可后续合并为一个 4~5 选项卡片（本期不做）。
- **覆盖导入不可撤销**：replace/clearScenes 无软删——HITL 确认卡片本身即防误删屏障；DB 无备份恢复，属可接受权衡（ponytail 注释标注）。
- **optimize 路径依赖 LLM 理解「现有分镜 + 需求」结构文本**——prompt 层面不改（supplemented 文本自带指令），若实测优化质量差可后续在 `callScriptOptimize` prompt 加「基于现有分镜」指令（本期不做）。
- **scene-mode-cancel / cancel 的 sceneCount=-1**：前端互斥判断 `sceneCount > initialSceneCount` 不成立，不触发分镜列表刷新 ✓。
- **并发边界**：清空+写入非原子（先 delete 再批量 insert）——若中途失败会出现「分镜被清空但未写入」，可后续加事务（本期：单用户单会话场景风险低，写分镜失败会发 error 事件，用户可重试）。

## 开放问题

1. 卡片2 的「不满意，重新生成」（disagree）目前走兜底文案「好的，请继续完善设计方案」——是否需要改成显式重新走生成链？（本期保持兜底，避免扩大改动面）
2. 「基于现有分镜优化」生成的新分镜若数量与现有不一致，覆盖导入后旧分镜的图片/视频关联（sceneId 外键）会一并删除——是否需要先确认无生成资产才允许覆盖？（本期不做，用户确认卡片已明示「删除现有」）
