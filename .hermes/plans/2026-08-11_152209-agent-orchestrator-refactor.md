# AgentOrchestrator 重构实现计划：意图处理器注册 + HITLStage 模板 + 并发/澄清/异步/降级

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 把硬编码 4 分支的编排器重构为「意图处理器注册（策略模式）+ HITLStage 通用模板」，并补齐并发锁、澄清次数上限、意图置信度与规则前置、视频异步化、错误重试 5 项工程能力。

**Architecture:** `AgentOrchestratorImpl` 瘦身为纯分发器：`run()` = 意图识别（规则前置 → LLM + confidence → 阈值判断）→ 按 `intentType` 查 `Map<String, IntentHandler>` 分发；`resume()` = checkpoint 校验消费（共享）→ 按 `checkpoint.action` 查 handler 恢复执行。HITL 三段式（方案生成 → checkpoint 落库 → human_input/video_plan 事件；表单提交 → 一次性消费 → 执行工具 → confirm_result）收敛进 `AbstractIntentHandler` 模板方法，各链只实现 `generatePlan` / `executeTool` 两个钩子。视频生成改异步：checkpoint 消费后立即返回 `task_accepted`，后台轮询写 `agent_assets` 行，前端轮询新状态端点取结果。

**Tech Stack:** Spring Boot 4 / JDK 21、Spring AI 2.0 ChatClient、MyBatis-Plus、PostgreSQL、SSE。**零新增依赖、零数据库迁移**（复用 `agent_assets.task_id/status/url` 现有列）。

**现状（已核实代码）:**
- `AgentOrchestratorImpl.run()` 硬编码 switch 4 分支 → `runAisplit/runPic/runVideo/runAnswer` 私有方法（src/main/java/com/storyboard/service/agent/impl/AgentOrchestratorImpl.java:103-108）
- HITL 重复：aisplit（agree，:158-163）、pic 有图（generate_image，:178-185）、video（generate_video，:233-245）各自写 createCheckpoint + human_input/video_plan；resume 内 3 个 action 分支（:289-334）。**注意：video 的执行不在 resume 里**，走独立端点 `POST /conversations/{id}/video/plan/generate` → `AgentChatServiceImpl.generateVideoFromPlan`（:586-655），与 submitFormAndResume（:698-722）是两套并行的 checkpoint 消费代码
- 意图识别：`IntentRecognitionService.recognize()` 返回裸 String type，白名单兜底 intent-other（IntentRecognitionServiceImpl.java，无置信度、无规则前置、无重试）
- 澄清 gate：aisplit 两个 gate（剧本优化/分镜方案）type=0 追问即结束本轮，无追问次数上限；MAX_STEPS=10 只管编排步数
- 并发：`streamMessage`（:443）/`submitFormAndResume`（:705）/`generateVideoFromPlan`（:593）三个入口各自 `CompletableFuture.runAsync`，无会话级互斥
- 视频：`executeVideoGeneration`（:570-578）→ `pollVideoAndPush`（:659-688）同步轮询最长 7.5min（90×5s）阻塞 SSE；SSE 超时 600s
- 错误：无重试（LLM 调用有兜底默认值，工具返回错误 Map；视频内部已有 429/5xx 轻量重试 3 次）

---

## 任务清单（每任务 2-5 分钟粒度，全部以「编译通过 + 冒烟命令」为完成门槛）

约定：后端编译命令（CLAUDE.md 验证节）：
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
前端：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build`

---

### Phase 1：意图识别增强（置信度 + 规则前置 + 阈值澄清）

#### Task 1: IntentResult 值对象 + recognize() 签名改造

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/IntentResult.java`
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/IntentRecognitionService.java`（接口 javadoc + 返回类型）
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/IntentRecognitionServiceImpl.java`

**Step 1: 新建 record**
```java
/** 意图识别结果：type + 置信度 + 来源（rule=规则前置 / llm=模型 / fallback=兜底） */
public record IntentResult(String type, double confidence, String source) {
    public static final double RULE_CONFIDENCE = 1.0;
    public static final double FALLBACK_CONFIDENCE = 0.0;
    public static IntentResult rule(String type) { return new IntentResult(type, RULE_CONFIDENCE, "rule"); }
    public static IntentResult fallback() { return new IntentResult(FALLBACK_TYPE, FALLBACK_CONFIDENCE, "fallback"); }
}
```
接口方法改为 `IntentResult recognize(String query, List<AgentMessage> recentMessages);`（FALLBACK_TYPE 常量挪到 IntentResult 或保留接口，二选一避免重复——**保留接口现有常量**，IntentResult 引用 `IntentRecognitionService.FALLBACK_TYPE`）。

**Step 2: LLM 输出改 JSON {type, confidence}**
- prompt 输出约束改为：`输出 JSON：{"type":"intent-xxx","confidence":0到1的小数}，禁止其他字符`
- 解析：`BeanOutputConverter<IntentResultRaw>`（record `IntentResultRaw(String type, double confidence)`）纯解析（项目惯例，不发 response_format）；解析失败/白名单外/异常 → `IntentResult.fallback()`
- confidence 默认值处理：JSON 缺 confidence 时按 0.5（走阈值判断，视为不明确）

**Step 3: 编译**
Run: maven compile（见上）
Expected: BUILD SUCCESS

**Step 4: Commit**
```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/IntentResult.java AIStoryboardBackend/src/main/java/com/storyboard/service/agent/IntentRecognitionService.java AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/IntentRecognitionServiceImpl.java
git commit -m "feat(agent): 意图识别返回 {type, confidence, source} 结构化结果"
```

#### Task 2: 规则前置匹配（关键词命中免一次 LLM 调用）

**Files:** Modify: `.../impl/IntentRecognitionServiceImpl.java`

**Step 1: recognize() 开头加规则表**
```java
/** 规则前置：强关键词直接路由（省一次 LLM 调用）；仅放无歧义强信号，歧义交给 LLM */
private static final List<Map.Entry<String, String>> RULE_TABLE = List.of(
    Map.entry("intent-aisplit", "分镜|故事板|剧本"),
    Map.entry("intent-video", "生成视频|做视频|视频方案|动画|短片|视频脚本"),
    Map.entry("intent-pic", "生成图片|画一张|海报|插画|改图|修图|换背景|去掉")
);
// 命中：先按 regex 在 query 上找，再回退 contains（List.of 顺序即优先级：aisplit 优先）
```
- 命中返回 `IntentResult.rule(type)`；未命中才走 LLM
- **顺序注意**：aisplit 关键词优先（"分镜/剧本"强于其他）；"视频"单独命中须带动作词（"做视频/生成视频"），避免 "分镜视频教程" 类误判——规则只做快路径，LLM 仍负责歧义
- 历史消息不参与规则匹配（只匹配当前输入）

**Step 2: 编译 + 冒烟（见 Task 8 汇总冒烟，此处仅编译）**

**Step 3: Commit** `feat(agent): 意图识别规则前置匹配，强关键词免 LLM 调用`

#### Task 3: 低置信度 → 澄清分支（Orchestrator 侧）

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/AgentOrchestratorImpl.java`（run 内意图识别后加阈值判断）
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java`（加 `intentThreshold`）
- Modify: `AIStoryboardBackend/src/main/resources/application.yml`（`ai.agent.intent-threshold: 0.6`）

**Step 1: run() 改分发逻辑（顺带为 Phase 2 铺路，此处先内联）**
```java
IntentResult intent = intentRecognitionService.recognize(content, recent);
if (intent.confidence() < config.getIntentThreshold()) {
    // 低置信度：不硬路由，发澄清消息结束本轮，用户下一条消息带历史重新识别
    sendEvent(emitter, "message", Map.of("content",
        "没太确定你想做什么——是要生成分镜（剧本/故事板）、图片，还是视频？"));
    return "";
}
```
- rule 命中（confidence=1.0）与 fallback（confidence=0 → intent-other）不受影响
- `return` 前注意：`lastMessage` 已由 sendEvent 记录，调用方正常落库 assistant 消息

**Step 2: AiConfigProperties 加字段**（参照现有 `ai.video-provider` 等绑定模式，字段 `intentThreshold` 默认 0.6）

**Step 3: 编译 + Commit** `feat(agent): 意图低置信度触发澄清分支（intent-threshold=0.6）`

---

### Phase 2：意图处理器注册机制（策略模式）

#### Task 4: IntentHandler 接口 + OrchestrationRequest 上下文 + AbstractIntentHandler 基类（含 HITL 助手）

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/OrchestrationRequest.java`
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/IntentHandler.java`
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/AbstractIntentHandler.java`

**Step 1: 上下文 record**
```java
/** 一轮编排的输入：会话 + 消息 + 参考图 + SSE 输出 */
public record OrchestrationRequest(AgentConversation conversation, String content,
                                   String picUrl, SseEmitter emitter) {}
```

**Step 2: 接口（注册机制核心——Orchestrator 只认这个）**
```java
/** 意图处理器：Orchestrator 只做 intent → handler 分发；新增意图 = 新实现类 + @Component，核心零改动 */
public interface IntentHandler {
    /** 本处理器负责的意图标识（intent-aisplit 等） */
    String intentType();
    /** resume 阶段本处理器认领的 checkpoint action 集合（agree/generate_image/generate_video/...） */
    Set<String> resumeActions();
    /** 主链路执行（含 HITL 暂停点） */
    void handle(OrchestrationRequest request);
    /** HITL 表单提交后续流（checkpoint 已由 Orchestrator 校验并一次性消费） */
    void resume(OrchestrationRequest request, AgentCheckpoint checkpoint);
}
```

**Step 3: 抽象基类（从 AgentOrchestratorImpl 迁移共享私有方法）**
`AbstractIntentHandler`（@RequiredArgsConstructor）持有：`ScriptGenerationService / ImageRefinePromptService / VideoPlanService / AgentTools / AgentCheckpointMapper / SceneMapper / AgentAnswerService / ChatClient.Builder / AiConfigProperties`，并把以下从 OrchestratorImpl 原样搬来（protected）：
- `sendEvent(emitter, eventName, data)`（含 lastMessage 语义——**lastMessage 改为 OrchestrationRequest 内可携带的 StringBuilder 或由 handler 返回**，见 Task 6）
- `createCheckpoint(conversation, action, planPayload, step)`、`planField(planJson, field)`、`parsePlanScenes(planJson)`、`summarizeScenes(scenes)`
- `planClient()` + `streamPlanWithMessage` / `extractMessageField` / `callScriptOptimize` / `callStoryboardPlan` / `callImagePrompt` / `callVideoPlan`
- `generateScenes` 调用与 checkpoint TTL 常量（30min）、`ScriptOptimizeResult/StoryboardPlanResult/VideoPlanResult` record 一并迁入

**Step 4: 编译 + Commit** `refactor(agent): 抽取 IntentHandler 接口与编排上下文`

#### Task 5: 迁移 four chains → 四个 handler 实现类（机械搬移，逻辑零改动）

**Files:**
- Create: `.../handler/OtherIntentHandler.java`（runAnswer → handle；resumeActions 返回空集）
- Create: `.../handler/AisplitIntentHandler.java`（runAisplit → handle：两个 gate + generateScenes + HITL；resumeActions = {agree}）
- Create: `.../handler/PicIntentHandler.java`（runPic → handle：有图分支 HITL / 无图分支自动文生图；resumeActions = {generate_image}）
- Create: `.../handler/VideoIntentHandler.java`（runVideo → handle：方案 + video_plan 卡片；resumeActions = {generate_video}）

**Step 1-4:** 逐个把 `AgentOrchestratorImpl` 对应私有方法体搬入 handler 的 `handle()`，`emitter`/`conversation` 改从 `request` 取；每搬一个编译一次。**行为保持完全一致**（含 workflow 事件、message 文案、checkpoint 参数、video_plan 卡片 actions）

**Step 5:** 删掉 OrchestratorImpl 中已迁移的私有方法（runAisplit/runPic/runVideo/runAnswer + 它们专属的 LLM 助手，planClient 若被多个 handler 用则各自持有）

**Step 6: 编译 + Commit** `refactor(agent): 四条意图链迁移为独立 handler 实现类`

#### Task 6: Orchestrator 瘦身为纯分发器

**Files:** Modify: `.../impl/AgentOrchestratorImpl.java`

**Step 1: run() 分发**
```java
// 注入 List<IntentHandler> handlers（Spring 自动收集 @Component 实现）→ Map<String,IntentHandler> byIntent
IntentResult intent = intentRecognitionService.recognize(content, recent);
if (intent.confidence() < config.getIntentThreshold()) { ...澄清... }
IntentHandler handler = byIntent.get(intent.type());
if (handler == null) handler = byIntent.get(FALLBACK_TYPE); // 未知意图兜底 other
lastMessage = handler.handle(new OrchestrationRequest(conversation, content, picUrl, emitter));
```
- **lastMessage 返回值化**：接口 `handle()` 返回 String（本轮最后一条 message 内容），`sendEvent` 的 lastMessage 副作用改由各 handler 内部记录并返回——实现：基类 `protected String lastMessage` 字段，`handle()` 结束时 return；调用方 `persistAssistant` 逻辑不变

**Step 2: resume() 分发**
```java
// checkpoint 校验 + 一次性消费（原子 pending→used）保持现状（共享逻辑留 Orchestrator 或下沉基类）
IntentHandler handler = byAction.get(cp.getAction()); // Map<String,IntentHandler>（resumeActions 展开注册）
if (handler == null) { /* 继续完善等默认分支 */ }
handler.resume(request, cp);
```

**Step 3: 删除 switch 与全部迁移残留 → 编译 → Commit** `refactor(agent): Orchestrator 改为 intent→handler 分发器，删除硬编码 switch`

---

### Phase 3：HITLStage 通用模板

#### Task 7: 模板方法 runHITLStage / resumeStage

**Files:** Modify: `.../handler/AbstractIntentHandler.java`

**Step 1: StagePlan record + 模板方法**
```java
/** HITL 阶段产出：方案文本 + checkpoint action + plan 载荷 + 事件名（human_input/video_plan）+ 确认按钮 */
public record StagePlan(String planText, String action, List<Map<String,Object>> planPayload,
                        String eventName, List<Map<String,Object>> actions) {}

/** HITL 模板：workflow → 方案消息 → checkpoint 落库 → human_input/video_plan 事件（发完即结束本轮等表单） */
protected void runHITLStage(OrchestrationRequest req, String workflowTitle, StagePlan plan) {
    sendEvent(req.emitter(), "workflow", Map.of("title", workflowTitle, "status", "node_started"));
    sendEvent(req.emitter(), "message", Map.of("content", plan.planText()));
    String formToken = createCheckpoint(req.conversation(), plan.action(), plan.planPayload(), Step.EXECUTE.name());
    sendEvent(req.emitter(), plan.eventName(), Map.of(
        "formToken", formToken, "taskId", "",
        "formContent", plan.planText(),
        "actions", plan.actions(),
        "expirationTime", OffsetDateTime.now().plus(CHECKPOINT_TTL).toString()));
}
```
（`Step` 枚举迁入基类或改字符串常量 `"EXECUTE"`——改字符串常量即可，避免枚举跨包暴露）

**Step 2: resume 模板**
```java
/** resume 模板：执行工具 → confirm_result → message_end（executeTool 由各链填充） */
protected void resumeStage(OrchestrationRequest req, AgentCheckpoint cp,
                           String confirmTitle, Map<String,Object> extra) {
    Map<String, Object> result = executeTool(req, cp);   // 钩子：各链调用 AgentTools 对应方法
    sendEvent(req.emitter(), "message", Map.of("content", (String) result.get("content")));
    sendEvent(req.emitter(), "confirm_result", result);   // kind/url/sceneCount/actions
    sendEvent(req.emitter(), "message_end", Map.of("messageId", "", "sceneCount",
        result.getOrDefault("sceneCount", -1L), "content", result.get("content")));
}
```
- 注意：**aisplit 的 message_end 与 confirm_result 带 `sceneCount`=写库后分镜总数**（sceneMapper.selectCount），pic 的带 `url/assetId/actions(refine/done)`——executeTool 返回值 map 承担差异，模板只管转发
- 抽象钩子：`protected abstract Map<String,Object> executeTool(OrchestrationRequest req, AgentCheckpoint cp);`

**Step 3: 三个 handler 接入模板**
- aisplit：`handle()` 尾部 gate 通过后 → `runHITLStage(req, "正在生成分镜…", new StagePlan(planText, "agree", scenes, "human_input", [满意/不满意]))`；`executeTool` = `agentTools.writeScenes` + sceneMapper 计数
- pic 有图分支：`runHITLStage(req, "正在理解图片与需求…", StagePlan(planText, "generate_image", [{prompt,source}], "human_input", [生成图片/继续完善]))`；`executeTool` = `agentTools.refineImage`
- video：`runHITLStage(req, "正在设计视频方案…", StagePlan(planText, "generate_video", [{message,duration,source}], "video_plan", [开始生成视频/继续完善]))`；`executeTool` = 异步视频（Phase 4）
- resume 内 3 个分支的重复事件代码全部删掉，改调 `resumeStage`

**Step 4: 编译 + Commit** `refactor(agent): HITL 三段式收敛进 runHITLStage/resumeStage 模板，三链只填钩子`

---

### Phase 4：澄清上限 + 并发锁 + 视频异步 + 重试

#### Task 8: 澄清次数上限（单轮最多 2 次追问，第 3 次给默认方案）

**Files:**
- Modify: `.../handler/AbstractIntentHandler.java`（或 AisplitIntentHandler：澄清计数）
- Modify: `AiConfigProperties.java` + `application.yml`（`ai.agent.max-clarify-rounds: 2`）

**Step 1: 内存计数（跨用户消息持续，不随消息重置）**
```java
// 澄清计数：conversationId → 连续追问次数。type=1（有进展）或非 aisplit 轮或完成轮 → remove
private final Map<String, Integer> clarifyCount = new ConcurrentHashMap<>();

// 每个 gate 返回 type=0 时：
int n = clarifyCount.merge(convId, 1, Integer::sum);
if (n >= config.getMaxClarifyRounds() /* 2 */) {
    // 第 3 次：不再追问——直接给默认方案（script = 用户原文），HITL 确认卡片兜底让用户改
    clarifyCount.remove(convId);
    script = content; // 跳过 gate 直接走分镜方案
    sendEvent(emitter, "message", Map.of("content", "已按你的原始需求直接生成方案，可在确认卡片上调整。"));
} else {
    sendEvent(emitter, "message", Map.of("content", gate.message())); // 正常追问
    return;
}
// type=1 或方案通过时：clarifyCount.remove(convId)
```
- **重置语义**：type=1（有进展）remove；非 aisplit 意图轮 remove（Orchestrator 分发 other/pic/video 时清理）；任何完成轮（confirm_result/message_end 发出）清理
- `ponytail:` 内存态重启即失（计数清零，无害）；多实例场景需落 DB 列，add when 多实例部署

**Step 2: 编译 + 冒烟（第 3 次追问走默认方案）→ Commit** `feat(agent): 澄清追问上限 2 次，第 3 次自动给默认方案`

#### Task 9: 会话级并发锁（同一 conversation 同时只允许一个活跃编排实例）

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/ConversationLock.java`
- Modify: `.../impl/AgentChatServiceImpl.java`（streamMessage / submitFormAndResume / generateVideoFromPlan / sendMessage 四个入口）

**Step 1: 锁组件**
```java
/** 会话级互斥：同一 conversation 同时只允许一个活跃编排实例。 */
@Component
public class ConversationLock {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** tryLock（3s 超时）：获取失败返回 false（已有编排在跑） */
    public boolean tryAcquire(String conversationId) {
        return locks.computeIfAbsent(conversationId, k -> new ReentrantLock())
                .tryLock(3, TimeUnit.SECONDS);
    }
    public void release(String conversationId) {
        ReentrantLock l = locks.get(conversationId);
        if (l != null) l.unlock();
    }
}
```
- **获取必须在 Controller 线程同步完成**（runAsync 之前）：`if (!lock.tryAcquire(id)) { sendEvent(emitter, "error", {code: 40901, message: "当前对话正在处理中，请稍候"}); emitter.complete(); return; }`
- **释放必须在异步任务 finally**（含 cancel 路径）：`try { ... } finally { lock.release(id); }`
- 四入口统一；blocking `sendMessage` 同锁（tryAcquire 失败抛 BusinessException 40901）
- `ponytail:` JVM 内锁，单实例正确；多实例需 PostgreSQL `SELECT ... FOR UPDATE` 或 Redis，add when 多实例部署（当前无 Redis 依赖，pom 已核实）

**Step 2: 编译 + 冒烟（双并发消息 → 第二条 40901）→ Commit** `feat(agent): 会话级互斥锁，并发编排返回 40901`

#### Task 10: 视频生成异步化（resume 立即返回 accepted，后台轮询 + 状态端点）

**Files:**
- Modify: `.../handler/AbstractIntentHandler.java`（executeTool 的 video 钩子）
- Modify: `.../impl/AgentChatServiceImpl.java`（executeVideoGeneration / generateVideoFromPlan / pollVideoAndPush 改造；新增持久化轮询任务）
- Modify: `.../controller/AgentConversationController.java`（新端点）
- Modify: `.../service/agent/AgentChatService.java`（接口加方法）
- Modify: `.../service/ai/AgentGenerationService.java`（或复用现有 createVideoTask/pollVideoTask 不动）

**Step 1: 创建即落库（agent_assets 现有 task_id/status/url 列，零迁移）**
```java
// executeVideoGeneration 拆两段：
// ① 创建：taskId = generationService.createVideoTask(...) → agent_assets 落一行
//    {conversationId, type=video, task_id=taskId, status=queued, url=null}
// ② 立即回事件：sendEvent(emitter, "task_accepted", {taskId, assetId, message:"视频任务已受理，正在排队生成"})
//    → 本轮 SSE 结束（emitter.complete()）
// ③ 后台：CompletableFuture.runAsync(() -> pollLoop(taskId, assetId), agentExecutor)
//    pollLoop = 复用现有 pollVideoAndPush 轮询逻辑（90×5s），但结果写 asset 行：
//    status=completed → set url；status=failed → set error；progress 每轮 set（status=running）
```

**Step 2: 新状态端点（前端轮询取进度/结果，JWT 鉴权）**
```java
// GET /api/agent/tasks/{taskId} → {status, progress, url, error, assetId}
// 实现：按 taskId 查 agent_assets（归属校验 conversation.userId==auth.name → 40401），返回行内字段
```

**Step 3: resume/生成路径统一走异步**
- `resumeStage` 的 video 钩子（VideoIntentHandler.executeTool）：消费 checkpoint 后 → 上述①（创建+落库+task_accepted）→ 返回
- `generateVideoFromPlan`（/video/plan/generate）改造为同一逻辑（checkpoint 消费照旧，落库 → task_accepted → 后台轮询）；**前端下一 Task 切到统一 `/form/submit`，本端点留壳兼容旧前端**
- `AgentTools.generateVideo`（@Tool）保持同步语义不变（LLM 工具面未启用，暂不影响；若未来启用再改）

**Step 4: 编译 + Commit** `feat(agent): 视频生成异步化——task_accepted 立即返回，后台轮询写 agent_assets`

#### Task 11: 前端适配（task_accepted + 进度轮询 + video 卡片统一走 form/submit）

**Files:**
- Modify: `AIStoryboardClient/src/api/agent.ts`（SseEvent 接口加 `task_accepted`，TaskStatus 类型；已核实 SseEvent 定义在此文件 :44）
- Modify: `AIStoryboardClient/src/stores/agentStore.ts`（task_accepted 处理：记 taskId → 启动 5s 轮询 GET /api/agent/tasks/{taskId}；completed → 渲染 confirm_result 卡片；failed → error；复用现有 videoProgress 模式）
- Modify: `AIStoryboardClient/src/components/.../AgentChatPanel.tsx`（video_plan 卡片「开始生成视频」改调 `/form/submit`（action=generate_video, formToken=planToken）；轮询期间显示进度条/「生成中…」；收到 40901 显示「处理中请稍候」并禁用发送）

**Step 1-3:** 逐文件改，`npx tsc -p tsconfig.app.json --noEmit` 每步过

**Step 4: 全量构建 + Commit** `feat(client): 视频异步进度轮询 + 统一 HITL 提交路径`

#### Task 12: 错误重试 / 降级（窄范围，不做重试框架）

**Files:**
- Modify: `.../handler/AbstractIntentHandler.java`（LLM 调用 1 次瞬态重试）
- Modify: `.../impl/IntentRecognitionServiceImpl.java`（意图识别 1 次重试）

**Step 1: 瞬态重试助手（基类静态方法）**
```java
/** 瞬态失败重试 1 次（LLM 调用幂等可安全重试）；非瞬态异常直接抛 */
protected static <T> T retryTransient(Supplier<T> fn) {
    try { return fn.get(); }
    catch (RuntimeException e) {
        if (!isTransient(e)) throw e;   // IOException/超时/5xx/429 → true；解析类异常 false
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return fn.get();
    }
}
```
- 应用于：callScriptOptimize / callStoryboardPlan / callImagePrompt / callVideoPlan / intent LLM 调用（包在现有 try-catch 兜底外层）
- **工具执行不自动重试**：`writeScenes` 追加语义非幂等，重试可能重复写分镜——保持现有错误 Map 语义；视频 create/poll 内部已有 429/5xx 重试 3 次（现状保留）
- 图生图失败降级文生图：**不做**（行为变化风险大），列 Open Questions

**Step 2: 编译 + Commit** `feat(agent): LLM 瞬态失败重试 1 次（工具幂等性限制不自动重试）`

---

### Phase 5：端到端验证

#### Task 13: 冒烟套件（curl，后端 8082 + 网关 8083 需起）

```bash
# 0. 起服务（网关 + 后端，见 ai-storyboard-dev 技能）
# 1. 规则前置（无 LLM 调用，秒回）：发"帮我生成一个视频，主角是猫" → 期望意图直接 intent-video
curl -N -X POST http://localhost:8082/api/agent/conversations/{id}/messages/stream -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"content":"帮我生成一个视频，主角是猫"}'
# 2. 低置信度澄清：发含糊消息"那个东西弄一下" → 期望收到澄清 message（不走四条链）
# 3. 澄清上限：连续 3 轮对 aisplit 说含糊话 → 第 3 轮收到"按原始需求直接生成方案"+ 确认卡片
# 4. 并发锁：两个 shell 同时发 → 第二条收到 40901
# 5. 视频异步：video_plan 卡片 → 提交 generate_video → 立即 task_accepted → 轮询 GET /api/agent/tasks/{taskId} 直至 completed 拿到 url
# 6. 回归：分镜链（满意→写库 sceneCount）、图改图链（确认卡片）、闲聊链（打字机）各跑一遍
```

#### Task 14: 全量构建 + 文档更新

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```
- 更新 `CLAUDE.md`：编排章节改「策略模式分发 + HITLStage 模板」；SSE 协议加 `task_accepted`；端点表加 `GET /api/agent/tasks/{taskId}`；新配置 `ai.agent.intent-threshold / max-clarify-rounds`
- Commit：`docs: CLAUDE.md 更新编排架构与 SSE 协议`

---

## 涉及文件汇总

**新增（后端 7 + 前端 0）**
- `service/agent/IntentResult.java`
- `service/agent/handler/OrchestrationRequest.java`
- `service/agent/handler/IntentHandler.java`
- `service/agent/handler/AbstractIntentHandler.java`
- `service/agent/handler/AisplitIntentHandler.java` / `PicIntentHandler.java` / `VideoIntentHandler.java` / `OtherIntentHandler.java`（4 个）
- `service/agent/ConversationLock.java`

**修改（后端 6 + 前端 3）**
- `service/agent/impl/AgentOrchestratorImpl.java`（瘦身：分发器 + 阈值澄清）
- `service/agent/IntentRecognitionService.java` + `impl/IntentRecognitionServiceImpl.java`（IntentResult + 规则 + 重试）
- `service/agent/impl/AgentChatServiceImpl.java`（锁 + 视频异步 + task_accepted）
- `service/agent/AgentChatService.java`（getVideoTaskStatus 接口）
- `controller/AgentConversationController.java`（GET /tasks/{taskId}）
- `service/ai/AiConfigProperties.java` + `application.yml`（3 个新配置键）
- 前端：`api/agent.ts`（SseEvent/task 类型）、`stores/agentStore.ts`、`AgentChatPanel.tsx`

**数据库：零迁移**（澄清计数内存态；视频异步复用 agent_assets.task_id/status/url；确认端点复用现有端点）

## 验证策略

- 项目仅 2 个既有单测（src/test/java/com/storyboard/security/JwtTokenProviderTest、ScryptPasswordServiceTest，与本次改动无关）；门槛 = 每任务 maven compile + 可顺带 `mvn test` 确认无回归 + 端到端 curl 冒烟（Task 13）
- 关键行为不变项（回归锚点）：SSE 7 事件协议文案、checkpoint 一次性消费与 30min 过期、无图 pic 自动完成、首条消息异步标题重命名
- 重构风险最高的 Task 5/6（四链搬移）：逐 handler 迁移 + 每步编译，最后靠 Task 13 全链回归兜底

## 风险 / 取舍 / 开放问题

1. **锁范围**：JVM 内锁单实例正确；本项目无 Redis、单实例部署 → 足够。多实例部署时换 `SELECT ... FOR UPDATE`（conversation 行）——已在代码注释 ponytail 标注，不预建
2. **澄清计数内存态**：重启丢失 → 用户重新获得追问额度，无害。如需跨实例 → conversations 表加列（迁移），暂不做
3. **视频异步后 confirm_result 由轮询渲染而非 SSE 推送**：交互变化，前端需适配（Task 11）；SSE 超时 600s 不再被 7.5min 轮询占用，顺带解决长连接风险
4. **/video/plan/generate 端点**：前端切统一 `/form/submit` 后该端点保留为兼容壳（同一 checkpoint 消费逻辑），后续可删
5. **阈值 0.6 / 澄清上限 2 的取值**：先按配置默认值上线，用户可按实际体验调 `ai.agent.intent-threshold / max-clarify-rounds`
6. **规则前置误判**（如"分镜视频教程"）：规则表只放强动作词 + aisplit 词优先；歧义仍由 LLM 兜底。规则表是数据不是代码，调优零成本
7. **图改图失败降级文生图**：明确不做（可能产出与预期不符的图，用户已在确认卡片上有重试入口）；如要需单独需求
8. **工具自动重试**：仅 LLM 幂等调用重试；writeScenes 非幂等不重试（防重复写分镜）——这是刻意的边界，不是遗漏

## 执行顺序建议

Phase 1 → 2 → 3 → 4 → 5 顺序执行（2/3 依赖 1 的 IntentResult 与 handler 基类；4 依赖 3 的模板；5 收尾）。其中 Task 10/11（视频异步）是全链行为变化最大的一对，建议单独一次会话实施，前后各跑一遍 Task 13 回归。
