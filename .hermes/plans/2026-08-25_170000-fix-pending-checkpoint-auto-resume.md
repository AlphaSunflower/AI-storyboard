# 根因修复：用户文字输入自动关联 pending checkpoint

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 用户输入文字时，如果有未消费的 HITL checkpoint，自动走 resume 而非 run。

**Architecture:** 在 `streamMessage` 入口处检测 pending checkpoint，有则转 `submitFormAndResume`（action=custom, customText=用户输入）。一处改动，所有意图链受益。

**Tech Stack:** Spring Boot 4.0, MyBatis-Plus

---

### Task 1: streamMessage 检测 pending checkpoint → 自动 resume

**Objective:** 用户发文字消息时，如果有未消费的 checkpoint，自动走 resume 路径

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentChatServiceImpl.java:419-475`

**当前行为：**
```
用户输入文字 → streamMessage → orchestrator.run() → PlanGraph.run() → 意图识别 → handler.handle()
```
旧 checkpoint 被忽略，plan 里没有 prompt → 崩溃。

**修复后行为：**
```
用户输入文字 → streamMessage → 检测 pending checkpoint?
  ├─ 有 → orchestrator.resume(conversation, formToken, "custom", content, ...) → PlanGraph.resume() → handler.resume()
  └─ 无 → orchestrator.run()（原逻辑不变）
```

**Step 1:** 在 `streamMessage` 的 `completableFuture.runAsync` 内部、`orchestrator.run()` 调用前，加 pending checkpoint 检测：

```java
CompletableFuture.runAsync(() -> {
    try {
        // ★ 根因修复：有未消费的 checkpoint 时，用户文字视为对 HITL 卡的自定义回复
        var pendingCp = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentCheckpoint>()
                        .eq(AgentCheckpoint::getConversationId, conversationId)
                        .eq(AgentCheckpoint::getStatus, "pending")
                        .gt(AgentCheckpoint::getExpirationTime, OffsetDateTime.now())
                        .orderByDesc(AgentCheckpoint::getCreatedAt)
                        .last("LIMIT 1"));
        String answer;
        if (pendingCp != null) {
            log.info("streamMessage: 检测到 pending checkpoint action={}, 自动 resume", pendingCp.getAction());
            answer = orchestrator.resume(conversation, pendingCp.getFormToken(),
                    "custom", content, Map.of(), List.of(), null, emitter);
        } else {
            answer = orchestrator.run(conversation, content, picUrl, emitter);
        }
        // ... 后续 persistAssistant 逻辑不变
```

**Step 2:** 需要的 import：
- `com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper`
- `com.moon.moonagent.entity.AgentCheckpoint`
- `java.time.OffsetDateTime`

**Step 3:** 冒烟测试：
1. 发送 "我要生成一个帅气的摩托" → 弹出 pic-clarify 澄清卡
2. 直接输入文字 "动漫热血·速度激情：动态俯视，主角骑着摩托疾驰"（不点按钮）
3. 预期：系统自动 resume，走 pic-clarify 分支 → 生成 prompt → 展示确认卡
4. 不应出现 "prompt 为空" 错误

**Step 4:** Commit: `fix(agent): streamMessage 检测 pending checkpoint 自动 resume`

**风险：**
- checkpoint 过期（30 分钟 TTL）后用户再发文字 → pendingCp=null → 走原 run() 路径，无影响
- 用户故意发无关文字（如"今天天气好"）→ resume 走 intent-other 的 custom 分支 → 正常闲聊
- 并发：conversationLock 保证同一会话只有一个活跃编排，无竞态

**验证：**
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q
```
Expected: BUILD SUCCESS
