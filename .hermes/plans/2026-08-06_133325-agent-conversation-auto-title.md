# Moon 智能体新会话首条消息异步 AI 重命名标题 — 实现计划

> **For Hermes:** 使用 subagent-driven-development 技能按任务逐个执行本计划。

**Goal:** 新会话的第一次对话发出后，后端异步调用大模型（不思考模式），将会话标题从默认的「新对话」自动重命名为概括首条用户消息的简短标题。更新落库后，随该轮对话的 `message_end` SSE 事件**一次性**推送新标题到前端，前端就地更新会话列表——不轮询、不持续推送。全程不阻塞 Dify 对话主流程，失败静默降级。

**Architecture:** 触发点位于 `AgentChatService.streamMessage`（及 blocking `sendMessage`）中 user 消息落库之后——判定「首条消息 + 标题仍为默认值」满足则向虚拟线程池 `agentExecutor` 提交异步任务。任务内调用 Laozhang chat completions（`baseUrlVision` + `defaultVisionModel`，与 `ScriptGenerationService.callVisionApi` 同模式）生成标题，请求体附带 `thinking_level: minimal`（Flash 系最低思考级别，最接近"不思考"）。标题生成后仅用 `LambdaUpdateWrapper` 条件更新 `title` 字段落库（绝不复用被 Dify 线程共享的实体实例，避免并发写互相覆盖）。落库成功的标题暂存内存 map，`message_end` / `workflow_finished` 事件负载附加 `title` 字段（**取走即删，仅该轮推一次**），前端 store 就地更新会话列表。

**Tech Stack:** Spring Boot 4 / JDK 21（虚拟线程 ExecutorService）、MyBatis-Plus `LambdaUpdateWrapper`、JDK HttpClient、Laozhang API（OpenAI 兼容 chat completions，`thinking_level` 不思考参数）、React 19 + Zustand 5。

---

## 现状 / 关键代码位置

| 环节 | 位置 | 说明 |
|------|------|------|
| 会话创建 | `AgentChatService.createConversation` (L108-120) | 默认 title = 「新对话」；前端 `createConversation` 固定传 `'新对话'` |
| 流式发消息 | `AgentChatService.streamMessage` (L273-338) | user 消息在 L303 独立事务落库；L306 起异步代理 Dify |
| blocking 发消息 | `AgentChatService.sendMessage` (L169-215) | 备用链路，user 消息 L180 落库 |
| 消息结束事件 | `forwardDifySse` `message_end` (L448-471)、`workflow_finished` 成功分支 (L509-552) | 前端收到后做 sceneCount 互斥判断 |
| 异步线程池 | `agentExecutor` (L77) | `Executors.newVirtualThreadPerTaskExecutor()`，全类复用 |
| LLM 调用范式 | `ScriptGenerationService.callVisionApi` (L58-86) | POST `baseUrlVision` + `Bearer apiKey`，body `{model, messages:[{role,content}]}`，取 `choices[0].message.content` |
| 重命名端点 | `AgentConversationController.updateConversation` (L197-215) | PATCH 手动 `setUpdatedAt` 后 updateById，列表置顶 |
| 前端会话列表 | `agentStore.ts` L89-117 | `message_end` 处理在 L279-293（sendMessage）与 L395-409（submitHumanInput） |
| SSE 事件类型 | `agent.ts` `SseEvent` (L44-62) | **已含 `title?: string`**（workflow 节点标题在用），前端类型零改动 |

## 设计决策（含取舍）

1. **触发判定（三重闸，防重复/防覆盖）**
   - 首条消息：user 消息 insert **前** `messageMapper.selectCount(conversationId) == 0`；
   - 标题仍可覆盖：`title == null || title.isBlank() || "新对话".equals(title)`（用户手动改过、或此前已生成成功的不再动）；
   - 并发去重：`Set<String> titleScheduled = ConcurrentHashMap.newKeySet()`，`add(conversationId)` 返回 true 才调度；任务结束（无论成败）移除，允许清空消息后重聊再次尝试。

2. **异步执行**：`CompletableFuture.runAsync(task, agentExecutor)`，不阻塞 SSE 主流程；任务体 `try/catch (Exception)` 全包，任何失败只 `log.warn`，绝不抛出、绝不影响对话。

3. **标题生成（固定模型 + 不思考模式，零新配置）**
   - 模型固定用 `defaultVisionModel`（gemini-3-flash-preview）——**不做模型切换配置**（用户明确暂缓）；
   - **不思考模式**：Gemini 3 系列通过请求参数控制思考。Google 官方级别 `minimal` 是 Flash 系列独有（比 LOW 更少思考，最接近"不思考"；Pro 最低只能 LOW）。请求体附带 `thinking_level: "minimal"`，作为 `ConversationTitleService` 内常量 `TITLE_THINKING_LEVEL`（不配置化，但常量集中一处便于联调改值）。
     - 老张 OpenAI 兼容网关对 Gemini 思考参数的透传写法以控制台为准——**联调时必须验证**：若 `thinking_level` 被拒（400），降级为 `"thinking": false`（OpenAI 兼容通用写法）重试；若仍不被接受则移除该参数（标题任务本身简单，思考差异影响小，宁可不带也不阻塞）。验证结果固化为常量注释。
   - **prompt**（常量）：根据用户首条消息生成 6-15 字中文标题（或 3-8 英文词），禁止标点/引号/「对话」「聊天」后缀，只输出标题本身；
   - 超时 30s（比脚本生成 120s 收紧——标题是锦上添花，不值得长等）；
   - 后处理：trim → 去首尾引号（`"` `'` `「」` `“”`）→ 截断 30 字 → 结果空白则放弃更新。

4. **DB 写入（关键并发坑）**：Dify 线程持有同一 `conversation` 实体实例并会 `updateById`（`persistAssistant` L618 写回 difyConversationId）。标题线程若复用该实例 `setTitle + updateById` 会整实体覆盖，把对方刚写的新字段冲掉。**必须**：
   ```java
   conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
       .eq(AgentConversation::getId, conversationId)
       .eq(AgentConversation::getTitle, "新对话")   // 原子化「仍为默认值才更新」
       .set(AgentConversation::getTitle, title)
       .set(AgentConversation::getUpdatedAt, OffsetDateTime.now()));
   ```
   只动 title/updatedAt 两列；`.eq(title, "新对话")` 条件在 SQL 层解决「标题已被用户改过 / 已被其他线程更新」的竞态（比先查后改更稳）。更新行数 0 = 已被改过，log.debug 即可。updatedAt 刷新使重命名后的会话置顶（与 PATCH 端点语义一致）。

5. **一次性实时推送（用户要求：更新库后及时到前端，但不持续推送）**
   - 重命名任务落库成功后，把结果写入 `Map<String,String> renamedTitleByConversation`；
   - `forwardDifySse` 的 `message_end`（两处：chat-messages 流 L468、恢复流 workflow_finished L548）发送时从 map **`remove` 取走**（取走即删 → 天然只推一次，后续轮次不会再带），负载附加 `title` 字段；
   - 前端 `agentStore` 两个 `message_end` 分支（sendMessage / submitHumanInput）收到 `e.title` 就地更新 `conversations` 列表对应项；
   - **不做**轮询、**不做**定时器、**不做**每次消息都推送——仅该轮消息结束时顺带一次；
   - 极端情况（LLM 比 Dify 首答还慢，message_end 先到）：该轮不推送，标题下次打开抽屉/刷新时自然可见（map 已 remove，无泄漏）。

6. **blocking 路径**：`sendMessage` 同样触发重命名（一致性、备用链路可用）；无 SSE 通道故无法推送，标题靠前端下次拉取可见——可接受，不做额外通知机制。

7. **不做的事（YAGNI）**：不加新配置项、不加新端点、不做 DB 迁移、不做前端轮询/定时器、不抽公共 LLM client（避免 drive-by 重构）。

## 任务清单

### Task 1: 新建 ConversationTitleService（生成 + 清洗 + 条件落库）

**Objective:** 独立的标题生成服务：LLM 调用（固定模型 + 不思考参数）、结果清洗、条件更新三件事封装在一个类里，`AgentChatService` 只负责调度。

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/ConversationTitleService.java`

**Step 1: 编写服务**

```java
package com.storyboard.service.agent;

// imports: LambdaUpdateWrapper, OffsetDateTime, AgentConversation, AgentConversationMapper,
//          AiConfigProperties, HttpClient, ObjectMapper, JsonNode, SLF4J, @Service, Duration,
//          URI, HttpRequest, HttpResponse, HashMap/ArrayList/List/Map

@Service
public class ConversationTitleService {
    private static final Logger log = LoggerFactory.getLogger(ConversationTitleService.class);
    private static final String DEFAULT_TITLE = "新对话";
    /**
     * 不思考模式思考级别：minimal 为 Gemini 3 Flash 系独有最低级别（Pro 最低只能 low）。
     * 老张网关透传写法联调确认后固化于此；若 400 改 "thinking": false，仍不接受则移除。
     */
    private static final String TITLE_THINKING_LEVEL = "minimal";
    private static final String TITLE_PROMPT =
        "你是一名对话标题命名助手。根据用户的第一条消息，为这段 AI 对话生成一个简洁标题。"
        + "要求：6-15 个汉字（或 3-8 个英文单词）；概括对话主题；不要标点、引号、书名号；"
        + "不要“对话”“聊天”“标题”等字眼；只输出标题本身，不要任何解释或前后缀。\n\n用户消息：";

    private final AgentConversationMapper conversationMapper;
    private final AiConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    public ConversationTitleService(AgentConversationMapper conversationMapper,
                                    AiConfigProperties config) {
        this.conversationMapper = conversationMapper;
        this.config = config;
    }

    /** 首条消息异步重命名：生成标题 → 条件更新。任何失败仅记日志，绝不抛出。 */
    public void renameOnFirstMessage(String conversationId, String firstUserContent) {
        try {
            String title = generateTitle(firstUserContent);
            if (title == null || title.isBlank()) {
                log.warn("标题生成结果为空，放弃重命名: conversationId={}", conversationId);
                return;
            }
            applyTitle(conversationId, title);
        } catch (Exception e) {
            log.warn("异步重命名会话标题失败(不影响对话): conversationId={}, error={}",
                    conversationId, e.getMessage());
        }
    }

    /** 调 Laozhang chat completions 生成标题（固定 defaultVisionModel + 不思考模式） */
    private String generateTitle(String userContent) { ... }

    /** 清洗：trim / 去首尾引号 / 截断 30 字 */
    String cleanTitle(String raw) { ... }

    /** 条件更新：仅当 title 仍为默认值时更新 title + updatedAt（不触碰其他字段） */
    private void applyTitle(String conversationId, String title) { ... }
}
```

**Step 2: 补全三个私有方法**

- `generateTitle`：
  ```java
  Map<String, Object> body = new HashMap<>();
  body.put("model", config.getDefaultVisionModel());
  List<Map<String, String>> messages = new ArrayList<>();
  messages.add(Map.of("role", "system", "content", TITLE_PROMPT));
  messages.add(Map.of("role", "user", "content",
          userContent.length() > 200 ? userContent.substring(0, 200) : userContent));
  body.put("messages", messages);
  body.put("thinking_level", TITLE_THINKING_LEVEL); // 不思考模式（联调验证，见常量注释）
  ```
  POST `config.getBaseUrlVision()`，`Authorization: Bearer " + config.getApiKey()`，`.timeout(Duration.ofSeconds(30))`；非 200 或解析异常抛 RuntimeException（调用方吞掉）。成功取 `root.path("choices").get(0).path("message").path("content").asText("")` 过 `cleanTitle`。参照 `ScriptGenerationService.callVisionApi` L58-86 写法，项目要求 Java 代码带中文注释。
- `cleanTitle`：`raw.trim()` → 若以 `"`/`'`/`「`/`“` 开头且成对结尾则剥掉 → 去除换行 → 超 30 字截断。
- `applyTitle`：**只**用 `LambdaUpdateWrapper` 条件更新（见设计决策 4），条件 `.eq(id)` + `.eq(title, DEFAULT_TITLE)`，set title + set updatedAt=now；更新行数 0 说明已被改过，log.debug。

**Step 3: 编译验证**

Run: `export JAVA_HOME="C:\\Program Files\\Java\\jdk-21" && "/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/ConversationTitleService.java
git commit -m "feat: 会话标题异步生成服务（首条消息 → LLM 不思考模式生成 → 条件更新，失败静默）"
```

### Task 2: AgentChatService 接入双路径 + message_end 一次性推送

**Objective:** user 消息落库后判定三重条件提交异步重命名任务（流式 + blocking）；落库成功的标题随 `message_end` 一次性推给前端。

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java`
  - 构造函数 L86-105：注入 `ConversationTitleService`（新增字段）
  - 类内新增：`titleScheduled` Set、`renamedTitleByConversation` Map、`maybeScheduleTitleRename(...)` 私有方法
  - `streamMessage` L303 之后（user 消息落库完成、L306 runAsync 之前）插入触发调用
  - `sendMessage` L180 之后（user 消息落库、L182 callDifyChat 之前）插入触发调用
  - `forwardDifySse` 两处 `sendEvent(emitter, "message_end", ...)`（L468、L548）：负载附加 `title`（取走即删）

**Step 1: 新增字段与注入**

```java
private final ConversationTitleService titleService;
// 并发去重：仅调度一次；任务结束移除，允许清空后重聊再次触发
private final Set<String> titleScheduled = ConcurrentHashMap.newKeySet();
// 重命名落库成功的新标题暂存：随 message_end 一次性下发前端（remove 取走即删，绝不重复推送）
private final Map<String, String> renamedTitleByConversation = new ConcurrentHashMap<>();
```

构造参数追加 `ConversationTitleService titleService` 并赋值。

**Step 2: 触发判定私有方法**

```java
/** 首条消息 + 标题仍为默认值 → 提交异步重命名（并发去重，失败不影响对话） */
private void maybeScheduleTitleRename(AgentConversation conversation, String content) {
    try {
        long msgCount = messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversation.getId()));
        boolean defaultTitle = conversation.getTitle() == null
                || conversation.getTitle().isBlank()
                || "新对话".equals(conversation.getTitle());
        if (msgCount == 0 && defaultTitle && titleScheduled.add(conversation.getId())) {
            CompletableFuture.runAsync(() -> {
                try {
                    titleService.renameOnFirstMessage(conversation.getId(), content);
                    // 落库成功 → 暂存新标题供 message_end 一次性推送；失败则 map 无值，静默降级
                    AgentConversation fresh = conversationMapper.selectById(conversation.getId());
                    String t = fresh != null ? fresh.getTitle() : null;
                    if (t != null && !t.isBlank() && !"新对话".equals(t)) {
                        renamedTitleByConversation.put(conversation.getId(), t);
                    }
                } finally {
                    titleScheduled.remove(conversation.getId());
                }
            }, agentExecutor);
        }
    } catch (Exception e) {
        log.debug("标题重命名调度失败(忽略): conversationId={}, error={}", conversation.getId(), e.getMessage());
    }
}
```

**Step 3: 两处插入触发调用**

- `streamMessage` L303 事务落库后、L306 runAsync 前：
  ```java
  // 首条消息异步 AI 重命名标题（不阻塞 Dify 主流程；结果随本轮 message_end 一次性推送）
  maybeScheduleTitleRename(conversation, content);
  ```
- `sendMessage` L180 事务落库后、L182 callDifyChat 前：同一行调用（blocking 无 SSE 通道，仅落库，靠前端下次拉取可见）。

**Step 4: 两处 message_end 附带 title（一次性）**

`forwardDifySse` 两处发送前改为构造 map 再 send（chat-messages 流 L468 与恢复流 workflow_finished L548 同构）：

```java
Map<String, Object> endPayload = new HashMap<>(Map.of(
    "messageId", messageId, "sceneCount", sceneCount, "content", localized));
// 一次性推送：remove 取走即删，仅本轮携带；后续轮次不再推送
String renamed = renamedTitleByConversation.remove(conversation.getId());
if (renamed != null) endPayload.put("title", renamed);
sendEvent(emitter, "message_end", endPayload);
```

**Step 5: 编译验证**

Run: 同 Task 1 的 mvn compile
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java
git commit -m "feat: 首条消息触发异步标题重命名，message_end 一次性推送新标题"
```

### Task 3: 前端 store 接收标题并就地更新会话列表

**Objective:** `message_end` 事件携带新标题时，就地更新 `conversations` 列表对应项（会话栏即时显示新标题，仅一次）。

**Files:**
- Modify: `AIStoryboardClient/src/stores/agentStore.ts`
  - `sendMessage` 的 `message_end` 分支（L279-293）
  - `submitHumanInput` 的 `message_end` 分支（L395-409，同构处理，HITL 续流结束时标题可能刚生成完）

**Step 1: 两个 message_end 分支追加处理**

```ts
case 'message_end':
  receivedMessageEnd = true;
  if (get().activeConversationId !== snapshotId) break;
  if (typeof e.content === 'string' && e.content) updateAssistantFull(e.content);
  // 首条消息异步 AI 重命名标题：后端在 message_end 一次性携带新 title，就地更新会话列表
  if (typeof e.title === 'string' && e.title) {
    const tid = snapshotId;
    set((s) => ({
      conversations: s.conversations.map((c) =>
        c.id === tid && c.title !== e.title ? { ...c, title: e.title as string } : c),
    }));
  }
  if (typeof e.sceneCount === 'number' && e.sceneCount > initialSceneCount) { ...原逻辑不变... }
  break;
```

（两处分支完全同构；TS 类型无需改动——`SseEvent.title?: string` 已存在。守卫 `c.title !== e.title` 防止重复 set 触发重渲染。）

**Step 2: 前端验证**

Run: `cd E:\Desktop\AI-storyboard\AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: 无类型错误，build 成功（注意 tsconfig 为 solution-style，裸 `npx tsc --noEmit` 假绿，必须 `-p tsconfig.app.json`）

**Step 3: Commit**

```bash
git add AIStoryboardClient/src/stores/agentStore.ts
git commit -m "feat: 前端接收 message_end 一次性携带的新标题并就地更新会话列表"
```

### Task 4: 联调验证（手工，需本地后端 + Dify + Laozhang key）

**Objective:** 全链路验证触发、生成、不思考参数、一次性推送、落库、不覆盖、降级七条路径。

**Step 1: 启动前后端**（本地 8082 + 5173），打开 Moon 智能体抽屉。

**验证矩阵：**

| # | 场景 | 步骤 | 预期 |
|---|------|------|------|
| 1 | 正常重命名 + 实时推送 | 新会话 → 发首条消息（如「帮我想一个关于太空冒险的短片」） | 对话正常流式；message_end 后左侧会话栏标题自动变为类似「太空冒险短片构思」（仅本轮一次） |
| 2 | 不思考参数生效 | 观察后端日志/请求耗时 | 标题生成请求 1-3s 内返回（minimal 级别）；请求体含 `thinking_level`；若 400 → 按设计决策 3 降级 `"thinking": false` 重试；记录实际可用写法固化到常量注释 |
| 3 | 只推一次 | 同会话连发第二条、第三条消息 | 标题不变；`message_end` 不再携带 title（前端列表无变化） |
| 4 | 不覆盖手动重命名 | 手动重命名会话为「我的标题」→ 再发消息 | 标题保持「我的标题」 |
| 5 | 清空后重聊 | clearMessages → 发首条消息 | 标题若为默认值则再次生成并推送；若已被改过则不变 |
| 6 | LLM 降级 | 临时把 `ai.laozhang.api-key` 改错（或断网）→ 新会话发首条消息 | 对话完全正常，标题保持「新对话」，后端 log.warn 记录失败 |
| 7 | 极端时序 | 故意让标题生成慢于 Dify 首答（可在 generateTitle 临时 sleep 模拟） | message_end 先到、无 title；标题落库后下次打开抽屉/刷新可见；map 无泄漏（remove 已取走或覆盖） |

**回归检查：** 会话列表 updated_at 倒序正常；重命名后的会话因 updatedAt 刷新置顶（与 PATCH 端点语义一致）。

### Task 5: 收尾

- 全量编译通过（`mvn compile -q`）；
- 检查 git status 只含计划内文件（用户偏好：git add 只加计划内文件，工作区遗留未提交修改原样保留）；
- 更新 `CLAUDE.md`「AI Agent 对话模块」章节，补充「首条消息异步 AI 重命名标题」小节：触发条件（首条消息 + 默认标题 + 并发去重）、agentExecutor 异步、`LambdaUpdateWrapper` 并发坑、不思考参数（`thinking_level: minimal`，联调确认后的实际写法）、message_end 一次性推送协议（取走即删，不做轮询）。

---

## 文件变更总览

| 文件 | 动作 | 内容 |
|------|------|------|
| `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/ConversationTitleService.java` | 新建 | LLM 标题生成（固定模型 + 不思考模式）+ 清洗 + 条件更新（约 120 行） |
| `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java` | 修改 | 注入服务、两处触发、message_end 一次性附 title（约 +55 行） |
| `AIStoryboardClient/src/stores/agentStore.ts` | 修改 | 两个 message_end 分支处理 e.title（约 +14 行） |
| `CLAUDE.md` | 修改 | 补充模块文档 |

无 DB 迁移、无新端点、无新配置项（模型固定 defaultVisionModel；thinking 级别为代码常量）、无前端类型改动。

## 风险 / 取舍 / 开放问题

1. **不思考参数写法待联调确认**：老张网关对 Gemini `thinking_level` 的透传写法以控制台为准，已设计降级链（`thinking_level: minimal` → `thinking: false` → 移除）。验证结果出来后把实际可用写法固化到代码注释与 CLAUDE.md。若完全无法关思考，flash 默认思考量也不大，标题任务受影响极小。
2. **标题生成依赖 Laozhang**：Laozhang 不可用/慢 → 标题不更新或延迟，对话零影响（设计使然）。模型固定 `defaultVisionModel`（切换配置暂缓，日后要扩展只加一个 `title-model` 字段即可）。
3. **并发写实体**：已用 UpdateWrapper 只更两列规避；`titleScheduled` 与 `renamedTitleByConversation` 均在用后即清，无内存泄漏。
4. **一次性推送时序**：`remove` 取走即删保证只推一次；若 message_end 早于重命名完成（极端），该轮不推送，标题下次打开抽屉/刷新可见——不做轮询、不追推，符合「不持续推送」要求。
5. **标题质量**：不思考模式下 flash 偶发输出怪标题（如直接复述消息）——prompt 已约束；用户可随时手动重命名覆盖，兜底充分。
6. **清空消息语义**：clearMessages 后标题若仍为默认值（此前生成失败）会再次触发——符合「新对话」直觉，不视为缺陷。
7. **测试策略**：项目无单元测试习惯（仅有 starter-test 依赖），本功能为外部 LLM + 异步 + DB 条件更新 + SSE 推送的胶水逻辑，采用「编译 + 手工验证矩阵」；`cleanTitle` 纯函数若有意愿可补 1 个简单单测（可选，不强制）。
