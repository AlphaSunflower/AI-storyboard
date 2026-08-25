# MoonAgent 架构债务修复计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 修复 MoonAgent 的 5 项架构隐患，优先级从 P0（分布式锁）到 P3（PlanGraph 拆分）。

**Architecture:** 保持现有 PlanGraph + IntentHandler 架构不变，逐点修补。不引入新框架，只用已有的 Redis/PostgreSQL 依赖。

**Tech Stack:** Spring Boot 4.0, Spring Data Redis, PostgreSQL, Spring AI 2.0

---

## 优先级排序

| 优先级 | 问题 | 风险 | 改动量 |
|--------|------|------|--------|
| P0 | ConversationLock 单机锁 | 多实例数据错乱 | 小（Redis 已有） |
| P1 | StoryboardClient 无熔断 | 线程堆积 | 中 |
| P1 | AgentTools 错误 Map | LLM 误判 | 小 |
| P2 | planField 魔法字符串 | 重构风险 | 中 |
| P3 | PlanGraph 过度中心化 | 可维护性 | 大 |

---

### Task 1: ConversationLock → Redis 分布式锁（P0）

**Objective:** 用 Redis SETNX 替换 JVM Semaphore，支持多实例部署

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/ConversationLock.java`

**Step 1:** 确认 Redis 依赖已存在（pom.xml 有 `spring-boot-starter-data-redis`）

**Step 2:** 改造 ConversationLock：

```java
@Component
@RequiredArgsConstructor
public class ConversationLock {

    private final StringRedisTemplate redisTemplate;

    private static final String PREFIX = "agent:lock:conversation:";
    private static final long TTL_SECONDS = 300; // 5 分钟自动过期（防死锁）

    public boolean tryAcquire(String conversationId) {
        String key = PREFIX + conversationId;
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

    public void release(String conversationId) {
        redisTemplate.delete(PREFIX + conversationId);
    }

    public void releaseAndRemove(String conversationId) {
        release(conversationId);
    }
}
```

**Step 3:** 本地冒烟：启动 MoonAgent + Redis，发两条并发消息到同一会话，第二条应返回 40901

**Step 4:** Commit: `fix(agent): ConversationLock 改用 Redis 分布式锁`

**风险：** tryAcquire 的 3s 超时改为 Redis 原子 setIfAbsent（无阻塞等待）。语义从"等 3s"变为"立即返回"——更符合 fail-fast 原则，但需确认前端 40901 重试逻辑。

---

### Task 2: AgentTools 错误反馈 — 异常替代 Map（P1）

**Objective:** 工具错误抛 IllegalArgumentException，让 Spring AI ToolCalling 将错误注入对话上下文

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentTools.java`

**Step 1:** 删除 `error()` 辅助方法，改为直接抛异常：

```java
// Before
private static Map<String, Object> error(String code, String message) {
    return Map.of("ok", false, "code", code, "message", message);
}

// After — 删除 error()，工具方法中：
throw new IllegalArgumentException("分镜写入失败：" + e.getMessage());
```

**Step 2:** 每个 @Tool 方法的 catch 块改为：

```java
} catch (IllegalArgumentException e) {
    throw e; // 已经是明确错误，直接上抛
} catch (Exception e) {
    log.error("AgentTools.xxx 失败: {}", e.getMessage(), e);
    throw new IllegalArgumentException("操作失败：" + e.getMessage());
}
```

**Step 3:** 冒烟：调用 writeScenes 传无效 projectId，验证 LLM 收到错误后能正确回复用户

**Step 4:** Commit: `refactor(agent): AgentTools 工具错误改抛异常替代 Map 返回`

**注意：** Spring AI 2.0 的 @Tool 异常处理行为需要验证——spike 说"异常不向上抛"，但这是 1.x 的行为。2.0 可能已改变。如果 2.0 仍然吞异常，保持 Map 返回但改用 `throw new ToolExecutionException(...)` 。

---

### Task 3: StoryboardClient 快速失败 + 缓存兜底（P1）

**Objective:** 主后端不可用时快速失败，不阻塞编排线程

**Files:**
- Modify: `MoonAgent/src/main/java/com/moon/moonagent/client/StoryboardClient.java`

**Step 1:** 给 RestTemplate 加超时（已有配置，确认生效）：

```java
@Bean
public RestTemplate restTemplate() {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(10000);
    return new RestTemplate(factory);
}
```

**Step 2:** 给读操作加 Caffeine 缓存（pom 加依赖）：

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

```java
private final Cache<String, List<Scene>> sceneCache = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .maximumSize(100)
        .build();

public List<Scene> getProjectScenes(String projectId) {
    return sceneCache.get(projectId, id -> fetchProjectScenes(id));
}

private List<Scene> fetchProjectScenes(String projectId) {
    // 原有 RestTemplate 调用
}
```

**Step 3:** 写操作不缓存，但加 try-catch 快速失败：

```java
public void batchInsertScenes(List<Scene> scenes) {
    try {
        // 原有调用
    } catch (ResourceAccessException e) {
        throw new BusinessException(50301, "主后端不可用，请稍后重试");
    }
}
```

**Step 4:** 冒烟：停主后端，发消息到 Agent，应快速返回友好错误（不卡 120s）

**Step 5:** Commit: `feat(agent): StoryboardClient 加 Caffeine 缓存 + 快速失败`

---

### Task 4: planField 魔法字符串 → 枚举（P2）

**Objective:** 消除 plan JSON 字段名的字符串契约，编译器可检查

**Files:**
- Create: `MoonAgent/src/main/java/com/moon/moonagent/ai/agent/PlanField.java`
- Modify: `AgentOrchestratorSupport.java` (planField 调用)
- Modify: 各 `*IntentHandler.java` (planField 调用)

**Step 1:** 定义枚举：

```java
public enum PlanField {
    CONTENT("content"),
    SOURCE("source"),
    PIC_URL("picUrl"),
    ASSET_IDS("assetIds"),
    KIND("kind"),
    ORIGINAL_CONTENT("originalContent");

    private final String key;
    PlanField(String key) { this.key = key; }
    public String key() { return key; }
}
```

**Step 2:** 给 `planField` 加枚举重载：

```java
public String planField(String planJson, PlanField field) {
    return planField(planJson, field.key());
}
```

**Step 3:** 逐步替换调用点（每个 handler 一个 commit）

**Step 4:** Commit: `refactor(agent): planField 魔法字符串改用 PlanField 枚举`

---

### Task 5: PlanGraph 子图拆分（P3，长期）

**Objective:** 将各 handler 内部流程拆为独立子图，PlanGraph 只做路由

**Files:**
- Modify: `PlanGraph.java`
- Modify: 各 `*IntentHandler.java`

**这个任务暂缓**——当前 PlanGraph 虽然臃肿但功能正确。等多 agent 协作真正跑起来（某个 handler 返回 handoff 信号）再拆分更有意义。现在拆是过度设计。

---

## 验证

```bash
# 编译
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile

# 启动冒烟
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" spring-boot:run
# 预期：Started MoonAgentApplication in ~10s

# MCP 端点验证
curl -s -X POST http://localhost:8084/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}},"id":1}'
# 预期：serverInfo: moon-agent 1.0.0
```

## 风险

1. **Task 1 (Redis 锁):** `setIfAbsent` 无阻塞等待，语义从"等 3s"变"立即失败"。确认前端 40901 处理逻辑。
2. **Task 2 (异常上抛):** Spring AI 2.0 @Tool 异常处理需 spike 验证。如果吞异常，换 `ToolExecutionException`。
3. **Task 3 (Caffeine):** 缓存一致性——写操作后需清缓存。`sceneCache.invalidate(projectId)` 加在 batchInsertScenes/replaceScenes/deleteScenes 后。
