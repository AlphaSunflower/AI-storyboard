# 智能体窗口实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 AI 分镜编辑器实现 Moon 智能体窗口——右下角悬浮球入口 → 右侧抽屉（左会话栏 + 右对话区），后端 AgentChatService 增加 SSE 流式代理与 HITL 人工确认表单代理，智能体写分镜后第二栏自动刷新、本会话禁用手动剧本输入。

**架构：** 前端新增 `components/agent/*`（Fab/Drawer/ConversationList/ChatPanel/MessageBubble/HumanInputCard/AssetsPanel）+ `agentStore` + `api/agent.ts`；后端扩展既有 `/api/agent/**`（AgentConversationController + AgentChatService），新增 streaming 端点（SseEmitter 代理 Dify chat-messages SSE，裁剪转发）、HITL 表单提交端点（代理 Dify `/v1/form/human_input/{formToken}` + `/v1/workflow/{taskId}/events` 续流）、会话 PATCH（重命名/归档）、资产分页与删除。Dify key 只存后端 `.env`。

**技术栈：** Spring Boot 4 / JDK 21 / MyBatis-Plus（无分页插件，手写 LIMIT/OFFSET）/ JDK HttpClient / SseEmitter（spring-web）；React 19 / TypeScript 6 / Zustand 5 / fetch + ReadableStream（SSE 流式读取，无 EventSource、无新依赖）。

**设计规格：** `docs/superpowers/specs/2026-08-04-agent-window-design.md`

---

## 文件结构

**后端（AIStoryboardBackend/src/main/java/com/storyboard/）：**
- 修改 `dto/request/AgentSendMessageRequest.java` — 加 `picUrl` 字段
- 新增 `dto/request/AgentFormSubmitRequest.java` — `record(formToken, taskId, action)`
- 新增 `dto/request/AgentConversationUpdateRequest.java` — `record(title, status)`
- 修改 `service/agent/AgentChatService.java` — `streamMessage` / `submitFormAndResume` / Dify SSE 解析转发 / inputs 适配 / sceneCount
- 修改 `controller/AgentConversationController.java` — 新增 5 个端点（stream、form/submit、PATCH、assets 分页、assets 删除）
- 修改 `../resources/application.yml` — 无需改动（`ai.laozhang.dify-*` 占位已在）
- 本地 `.env`（不提交 git）— `AI_DIFY_API_KEY=app-gsYkBxnoGIQV8leBGFzK7v1Y`

**前端（AIStoryboardClient/src/）：**
- 新增 `api/agent.ts` — axios 封装 + `streamChat`（fetch 流式）+ `submitForm`（fetch 流式）
- 新增 `stores/agentStore.ts` — Zustand 全局状态（会话/消息/流/HITL/参考图/互斥/资产）
- 新增 `components/agent/AgentFab.tsx` — 右下角悬浮球
- 新增 `components/agent/AgentDrawer.tsx` — 抽屉容器（左会话栏 + 右对话区 + 资产面板）
- 新增 `components/agent/AgentConversationList.tsx` — 会话列表（新建/切换/重命名/归档/删除/归档筛选）
- 新增 `components/agent/AgentChatPanel.tsx` — 消息流 + 输入区（textarea/参考图/发送）
- 新增 `components/agent/MessageBubble.tsx` — 气泡 + 轻量 markdown + 图片/视频卡片
- 新增 `components/agent/HumanInputCard.tsx` — HITL 确认卡片
- 新增 `components/agent/AgentAssetsPanel.tsx` — 资产网格（分页/删除）
- 修改 `pages/EditorPage.tsx` — 挂载 `<AgentFab /> <AgentDrawer />`
- 修改 `components/editor/LeftSidebar.tsx` — 互斥禁用（agentGeneratedScenes）

---

### 任务 1：后端 DTO 扩展

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentSendMessageRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentFormSubmitRequest.java`
- 创建：`AIStoryboardBackend/src/main/java/com/storyboard/dto/request/AgentConversationUpdateRequest.java`

- [ ] **步骤 1：AgentSendMessageRequest 加 picUrl**

```java
package com.storyboard.dto.request;

/** 发送 Agent 对话消息请求（streaming 时 picUrl 为参考图 URL，来自 /api/agent/upload） */
public record AgentSendMessageRequest(
    String content,
    String picUrl
) {}
```

- [ ] **步骤 2：新建 AgentFormSubmitRequest**

```java
package com.storyboard.dto.request;

/** HITL 人工确认表单提交请求 */
public record AgentFormSubmitRequest(
    String formToken,   // Dify human_input_required 事件返回的表单令牌
    String taskId,      // Dify workflow_run_id（用于续流 /v1/workflow/{taskId}/events）
    String action       // 用户点击的按钮 id（actions[].id）
) {}
```

- [ ] **步骤 3：新建 AgentConversationUpdateRequest**

```java
package com.storyboard.dto.request;

/** 会话更新请求（重命名 / 归档），字段均可选 */
public record AgentConversationUpdateRequest(
    String title,    // 非空则重命名
    String status    // active | archived，非空则归档/恢复
) {}
```

- [ ] **步骤 4：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

预期：BUILD SUCCESS（旧调用方 `AgentSendMessageRequest` 是 record 解构——确认无位置参数构造调用处：`grep -rn "new AgentSendMessageRequest" src/main/java`，有则同步补 null）

- [ ] **步骤 5：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/dto/request/
git commit -m "feat: Agent DTO 扩展（picUrl/formToken/会话更新）"
```

---

### 任务 2：AgentChatService 增加 streaming 与 HITL 代理

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/mapper/SceneMapper.java`（无改动，注入现有 mapper 即可）

- [ ] **步骤 1：注入 SceneMapper 并加 imports**

在 `AgentChatService` 增加字段与构造参数：

```java
import com.storyboard.mapper.SceneMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.CompletableFuture;
import java.io.BufferedReader;
import java.io.InputStreamReader;

    private final SceneMapper sceneMapper;

    public AgentChatService(AgentConversationMapper conversationMapper,
                            AgentMessageMapper messageMapper,
                            ProjectMapper projectMapper,
                            SceneMapper sceneMapper,
                            AiConfigProperties config,
                            PlatformTransactionManager transactionManager) {
        ...
        this.sceneMapper = sceneMapper;
    }
```

- [ ] **步骤 2：改造 callDifyChat 的 inputs 适配 Moon 工作流**

将 `callDifyChat` 中 `body.put("inputs", Map.of("project_id", ..., "project_name", ...))` 替换为：

```java
// Moon 工作流 start 节点变量：currentProjectId（项目 ID）+ PicUrl（参考图 URL）
body.put("inputs", Map.of(
    "currentProjectId", conversation.getProjectId(),
    "PicUrl", ""
));
```

blocking 旧方法继续可用（inputs 变更仅影响 Dify 侧变量，旧调用方无感）。

- [ ] **步骤 3：新增 streamMessage（异步 SseEmitter 代理）**

在类内新增方法（含私有辅助 `forwardDifySse` 与 `parseAndDispatch`，见步骤 4/5）：

```java
/**
 * 流式发送消息：user 消息独立事务提交 → 代理 Dify streaming → 事件裁剪转发到 SseEmitter。
 * 收到 human_input_required → 转发 human_input 事件 → 结束流（Dify 侧 pause 自动关闭）。
 * message_end → 落库 assistant 消息 + 回填 dify_conversation_id + 附带 sceneCount。
 */
public void streamMessage(String userId, String conversationId, String content, String picUrl, SseEmitter emitter) {
    if (content == null || content.isBlank()) {
        sendEvent(emitter, "error", Map.of("code", "40001", "message", "消息内容不能为空"));
        emitter.complete();
        return;
    }
    AgentConversation conversation = getOwnedConversation(userId, conversationId);

    // 1. user 消息独立事务立即提交
    AgentMessage userMessage = new AgentMessage();
    userMessage.setConversationId(conversationId);
    userMessage.setRole("user");
    userMessage.setContent(content);
    transactionTemplate.executeWithoutResult(tx -> messageMapper.insert(userMessage));

    // 2. 异步代理 Dify（SseEmitter 需异步写，否则阻塞 Controller 返回）
    CompletableFuture.runAsync(() -> {
        try {
            Map<String, Object> body = buildChatBody(conversation, content, picUrl);
            body.put("response_mode", "streaming");
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/chat-messages"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(600))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<java.io.InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                log.error("Dify chat-messages streaming 非 200: status={}", resp.statusCode());
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                emitter.complete();
                return;
            }
            forwardDifySse(resp, emitter, conversation, userId, new StringBuilder());
        } catch (Exception e) {
            log.error("Dify streaming 调用失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
            emitter.complete();
        }
    });
}
```

- [ ] **步骤 4：新增 buildChatBody 与 sendEvent 私有方法**

```java
/** 构建 Dify chat-messages 请求体（streaming/blocking 共用） */
private Map<String, Object> buildChatBody(AgentConversation conversation, String query, String picUrl) {
    Map<String, Object> body = new HashMap<>();
    body.put("inputs", Map.of(
        "currentProjectId", conversation.getProjectId(),
        "PicUrl", picUrl == null ? "" : picUrl
    ));
    body.put("query", query);
    body.put("user", conversation.getUserId());
    if (conversation.getDifyConversationId() != null && !conversation.getDifyConversationId().isBlank()) {
        body.put("conversation_id", conversation.getDifyConversationId());
    }
    return body;
}

/** SseEmitter 事件发送（捕获 IOException 忽略——前端已断开） */
private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> data) {
    try {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    } catch (Exception e) {
        log.debug("SseEmitter 发送失败（前端可能已断开）: event={}", eventName);
    }
}
```

- [ ] **步骤 5：新增 forwardDifySse（Dify SSE → 裁剪转发 + 落库）**

```java
/**
 * 逐行读取 Dify SSE 流，裁剪转发给前端。
 * 事件映射：
 *   message                → 累积 answer，转发 {type:message, content:增量}
 *   node_started/finished  → 转发 {type:workflow, title, status}（丢弃 inputs/outputs）
 *   human_input_required   → 转发 {type:human_input, formToken, taskId, formContent, actions, expirationTime}，结束流
 *   message_end            → 落库 assistant + 回填 + 转发 {type:message_end, messageId, sceneCount}，结束流
 *   error                  → 转发 {type:error, code, message}，结束流
 *   ping 等其余            → 忽略
 */
private void forwardDifySse(HttpResponse<java.io.InputStream> resp, SseEmitter emitter,
                            AgentConversation conversation, String userId, StringBuilder answer) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) continue;
            JsonNode node;
            try {
                node = objectMapper.readTree(line.substring(5).trim());
            } catch (Exception e) {
                continue; // 忽略无法解析的行
            }
            String event = node.path("event").asText("");
            switch (event) {
                case "message" -> {
                    String delta = node.path("answer").asText("");
                    answer.append(delta);
                    sendEvent(emitter, "message", Map.of("content", delta));
                }
                case "node_started", "node_finished" -> sendEvent(emitter, "workflow", Map.of(
                    "title", node.path("data").path("title").asText(""),
                    "status", "node_started".equals(event) ? "node_started" : "node_finished"));
                case "human_input_required" -> {
                    JsonNode data = node.path("data");
                    List<Map<String, String>> actions = new ArrayList<>();
                    for (JsonNode a : data.path("actions")) {
                        actions.add(Map.of("id", a.path("id").asText(""), "title", a.path("title").asText("")));
                    }
                    sendEvent(emitter, "human_input", Map.of(
                        "formToken", data.path("form_token").asText(""),
                        "taskId", node.path("task_id").asText(""),
                        "formContent", data.path("form_content").asText(""),
                        "actions", actions,
                        "expirationTime", data.path("expiration_time").asLong(0)));
                    // HITL 暂停：落库已累积的方案文本，结束当前流
                    persistAssistant(conversation, answer.toString(), null);
                    emitter.complete();
                    return;
                }
                case "message_end" -> {
                    String messageId = node.path("message_id").asText("");
                    String difyConvId = node.path("conversation_id").asText("");
                    persistAssistant(conversation, answer.toString(), messageId);
                    if (difyConvId != null && !difyConvId.isBlank()
                            && !difyConvId.equals(conversation.getDifyConversationId())) {
                        conversation.setDifyConversationId(difyConvId);
                        conversationMapper.updateById(conversation);
                    }
                    long sceneCount = sceneMapper.selectCount(
                        new LambdaQueryWrapper<com.storyboard.entity.Scene>()
                            .eq(com.storyboard.entity.Scene::getProjectId, conversation.getProjectId()));
                    sendEvent(emitter, "message_end", Map.of("messageId", messageId, "sceneCount", sceneCount));
                    emitter.complete();
                    return;
                }
                case "error" -> {
                    sendEvent(emitter, "error", Map.of(
                        "code", node.path("code").asText("50202"),
                        "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
                default -> { /* ping 等忽略 */ }
            }
        }
        // 流正常 EOF（无 message_end 的兜底）
        if (answer.length() > 0) persistAssistant(conversation, answer.toString(), null);
        emitter.complete();
    } catch (Exception e) {
        log.error("Dify SSE 读取失败: conversationId={}", conversation.getId(), e);
        sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
        emitter.complete();
    }
}

/** 落库 assistant 消息（事务内：保存消息 + 刷新会话 updatedAt） */
private void persistAssistant(AgentConversation conversation, String content, String difyMessageId) {
    if (content == null || content.isBlank()) return;
    transactionTemplate.executeWithoutResult(tx -> {
        AgentMessage assistantMessage = new AgentMessage();
        assistantMessage.setConversationId(conversation.getId());
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(content);
        assistantMessage.setDifyMessageId(difyMessageId);
        messageMapper.insert(assistantMessage);
        conversationMapper.updateById(conversation); // 触发 updatedAt fill
    });
}
```

- [ ] **步骤 6：新增 submitFormAndResume（HITL 提交 + 续流）**

```java
/**
 * HITL 表单提交并续流：
 * 1. POST {base}/v1/form/human_input/{formToken}（body {action}）
 * 2. 成功 → GET {base}/v1/workflow/{taskId}/events?user={userId} 续传 SSE（复用 forwardDifySse）
 */
public void submitFormAndResume(String userId, String conversationId, String formToken, String taskId, String action, SseEmitter emitter) {
    AgentConversation conversation = getOwnedConversation(userId, conversationId);
    CompletableFuture.runAsync(() -> {
        try {
            HttpRequest submitReq = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/form/human_input/" + formToken))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(Map.of("action", action))))
                .build();
            HttpResponse<String> submitResp = httpClient.send(submitReq, HttpResponse.BodyHandlers.ofString());
            if (submitResp.statusCode() != 200) {
                log.error("Dify 表单提交失败: status={}, body={}", submitResp.statusCode(), submitResp.body());
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                emitter.complete();
                return;
            }
            // 续流：workflow events 端点 user 参数必填（已核实 Dify 源码）
            HttpRequest eventsReq = HttpRequest.newBuilder()
                .uri(URI.create(config.getDifyBaseUrl() + "/v1/workflow/" + taskId
                    + "/events?user=" + userId))
                .header("Authorization", "Bearer " + config.getDifyApiKey())
                .timeout(Duration.ofSeconds(600))
                .GET()
                .build();
            HttpResponse<java.io.InputStream> eventsResp = httpClient.send(eventsReq, HttpResponse.BodyHandlers.ofInputStream());
            if (eventsResp.statusCode() != 200) {
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                emitter.complete();
                return;
            }
            forwardDifySse(eventsResp, emitter, conversation, userId, new StringBuilder());
        } catch (Exception e) {
            log.error("Dify HITL 提交/续流失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
            emitter.complete();
        }
    });
}
```

- [ ] **步骤 7：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

预期：BUILD SUCCESS。若 `Scene::getProjectId` 不存在（字段名不同），`grep -n "projectId\|ProjectId" entity/Scene.java` 修正为实际 getter。

- [ ] **步骤 8：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java
git commit -m "feat: AgentChatService 流式代理 + HITL 表单提交续流 + Moon inputs 适配"
```

---

### 任务 3：AgentConversationController 新增端点

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java`

- [ ] **步骤 1：新增 stream / form / PATCH / assets 分页 / assets 删除端点**

在类内追加（注意 `@GetMapping("/conversations/{id}/assets")` 改为分页版）：

```java
    /** 流式发送消息（SSE） */
    @PostMapping(value = "/conversations/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(Authentication auth, @PathVariable String id,
                                    @RequestBody AgentSendMessageRequest request) {
        // 同步快速校验归属（失败抛 401/404 而非 SSE）
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = new SseEmitter(600_000L);
        chatService.streamMessage(auth.getName(), id, request.content(), request.picUrl(), emitter);
        return emitter;
    }

    /** HITL 表单提交并续流（SSE） */
    @PostMapping(value = "/conversations/{id}/form/submit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter submitForm(Authentication auth, @PathVariable String id,
                                 @RequestBody AgentFormSubmitRequest request) {
        chatService.getOwnedConversation(auth.getName(), id);
        SseEmitter emitter = new SseEmitter(600_000L);
        chatService.submitFormAndResume(auth.getName(), id,
            request.formToken(), request.taskId(), request.action(), emitter);
        return emitter;
    }

    /** 重命名 / 归档会话 */
    @PatchMapping("/conversations/{id}")
    public ApiResponse<AgentConversation> updateConversation(Authentication auth, @PathVariable String id,
                                                             @RequestBody AgentConversationUpdateRequest request) {
        AgentConversation conversation = chatService.getOwnedConversation(auth.getName(), id);
        if (request.title() != null && !request.title().isBlank()) {
            conversation.setTitle(request.title().trim());
        }
        if (request.status() != null && !request.status().isBlank()) {
            if (!"active".equals(request.status()) && !"archived".equals(request.status())) {
                throw new BusinessException(40001, "会话状态非法");
            }
            conversation.setStatus(request.status());
        }
        conversationMapper.updateById(conversation);
        return ApiResponse.ok(conversation);
    }

    /** 资产列表（分页，手写 LIMIT/OFFSET——项目未装 MyBatis-Plus 分页插件） */
    @GetMapping("/conversations/{id}/assets")
    public ApiResponse<Map<String, Object>> listAssets(
            Authentication auth, @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        chatService.getOwnedConversation(auth.getName(), id);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        long total = assetMapper.selectCount(
            new LambdaQueryWrapper<AgentAsset>().eq(AgentAsset::getConversationId, id));
        List<AgentAsset> records = assetMapper.selectList(
            new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getConversationId, id)
                .orderByDesc(AgentAsset::getCreatedAt)
                .last("LIMIT " + safeSize + " OFFSET " + ((safePage - 1) * safeSize)));
        return ApiResponse.ok(Map.of("records", records, "total", total, "page", safePage, "size", safeSize));
    }

    /** 删除资产（仅限归属本人会话；未归属资产拒绝） */
    @DeleteMapping("/assets/{id}")
    public ApiResponse<Void> deleteAsset(Authentication auth, @PathVariable String id) {
        AgentAsset asset = assetMapper.selectById(id);
        if (asset == null || asset.getConversationId() == null || asset.getConversationId().isBlank()) {
            throw new BusinessException(40401, "资产不存在或无权访问");
        }
        chatService.getOwnedConversation(auth.getName(), asset.getConversationId());
        assetMapper.deleteById(id);
        return ApiResponse.ok("删除成功", null);
    }
```

补充 imports：`org.springframework.http.MediaType`、`org.springframework.web.servlet.mvc.method.annotation.SseEmitter`、`com.storyboard.dto.request.AgentFormSubmitRequest`、`com.storyboard.dto.request.AgentConversationUpdateRequest`、`org.springframework.web.bind.annotation.PatchMapping`。

- [ ] **步骤 2：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

预期：BUILD SUCCESS（若旧的无分页 `listAssets` 与新增冲突，确认已替换而不是重载）

- [ ] **步骤 3：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java
git commit -m "feat: Agent 端点扩展（SSE 流式/HITL 提交/会话 PATCH/资产分页删除）"
```

---

### 任务 4：本地 .env 配置 Dify app key

**文件：**
- 修改：`AIStoryboardBackend/.env`（本地文件，已 gitignore，不提交）

- [ ] **步骤 1：写入 app key**

在 `.env` 追加（若已存在 `AI_DIFY_API_KEY=` 则改为实际值）：

```
AI_DIFY_API_KEY=app-gsYkBxnoGIQV8leBGFzK7v1Y
DIFY_BASE_URL=http://localhost
```

确认 `src/main/resources/application.yml` 的 `ai.laozhang.dify-api-key: ${AI_DIFY_API_KEY:}` 与 `dify-base-url: ${DIFY_BASE_URL:http://localhost}` 占位存在（已有，无需改代码）。

- [ ] **步骤 2：确认 .env 不被 git 跟踪**

```bash
cd "E:/Desktop/AI-storyboard"
git check-ignore AIStoryboardBackend/.env && echo "IGNORED OK"
git status --short | head -5   # 不应出现 .env 修改
```

预期：输出 IGNORED OK，git status 无 .env。

---

### 任务 5：前端 api/agent.ts

**文件：**
- 创建：`AIStoryboardClient/src/api/agent.ts`

- [ ] **步骤 1：类型定义 + axios 封装**

```ts
import client from './client';
import { BACKEND_URL } from '../config';

export interface AgentConversation {
  id: string;
  userId: string;
  projectId: string;
  title: string;
  difyConversationId: string | null;
  status: 'active' | 'archived';
  createdAt: string;
  updatedAt: string;
}

export interface AgentMessage {
  id: string;
  conversationId: string;
  role: 'user' | 'assistant';
  content: string;
  difyMessageId: string | null;
  createdAt: string;
}

export interface AgentAsset {
  id: string;
  conversationId: string | null;
  type: 'image' | 'video' | 'reference';
  url: string;
  prompt: string | null;
  model: string | null;
  status: string;
  taskId: string | null;
  error: string | null;
  createdAt: string;
}

export interface AgentPage<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export interface SseEvent {
  type: 'message' | 'workflow' | 'human_input' | 'message_end' | 'error';
  content?: string;
  title?: string;
  status?: string;
  formToken?: string;
  taskId?: string;
  formContent?: string;
  actions?: { id: string; title: string }[];
  expirationTime?: number;
  messageId?: string;
  sceneCount?: number;
  code?: string;
  message?: string;
}

// ── 会话 ──────────────────────────────────────────────

export const agentApi = {
  listConversations: (projectId: string) =>
    client.get<{ data: AgentConversation[] }>('/agent/conversations', { params: { projectId } }),

  createConversation: (projectId: string, title?: string) =>
    client.post<{ data: AgentConversation }>('/agent/conversations', { projectId, title }),

  updateConversation: (id: string, data: { title?: string; status?: string }) =>
    client.patch<{ data: AgentConversation }>(`/agent/conversations/${id}`, data),

  deleteConversation: (id: string) =>
    client.delete(`/agent/conversations/${id}`),

  listMessages: (id: string) =>
    client.get<{ data: AgentMessage[] }>(`/agent/conversations/${id}/messages`),

  listAssets: (id: string, page = 1, size = 20) =>
    client.get<{ data: AgentPage<AgentAsset> }>(`/agent/conversations/${id}/assets`, { params: { page, size } }),

  deleteAsset: (assetId: string) =>
    client.delete(`/agent/assets/${assetId}`),

  uploadImage: (file: File, conversationId?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (conversationId) form.append('conversationId', conversationId);
    return client.post<{ data: { url: string } }>('/agent/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
};
```

- [ ] **步骤 2：SSE 流式函数（fetch + ReadableStream）**

```ts
/** 流式发送消息。onEvent 收到裁剪后的 SseEvent。返回 Promise（流结束/出错时 resolve） */
export async function streamChat(
  conversationId: string,
  content: string,
  picUrl: string | undefined,
  onEvent: (e: SseEvent) => void,
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ content, picUrl: picUrl ?? '' }),
  });
  await consumeSse(res, onEvent);
}

/** HITL 表单提交并续流 */
export async function submitForm(
  conversationId: string,
  formToken: string,
  taskId: string,
  action: string,
  onEvent: (e: SseEvent) => void,
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/form/submit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ formToken, taskId, action }),
  });
  await consumeSse(res, onEvent);
}

/** 通用 SSE 读取：逐行解析 data: {...}，断行缓冲 */
async function consumeSse(res: Response, onEvent: (e: SseEvent) => void): Promise<void> {
  if (!res.ok) {
    let detail = '';
    try { detail = (await res.json()).message ?? ''; } catch { /* ignore */ }
    throw new Error(detail || `请求失败 (${res.status})`);
  }
  if (!res.body) throw new Error('响应无数据流');
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() ?? '';
      for (const part of parts) {
        const dataLine = part.split('\n').find((l) => l.startsWith('data:'));
        if (!dataLine) continue;
        try {
          onEvent(JSON.parse(dataLine.slice(5).trim()) as SseEvent);
        } catch { /* 忽略坏帧 */ }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
```

- [ ] **步骤 3：类型检查**

```bash
cd "E:/Desktop/AI-storyboard/AIStoryboardClient" && npx tsc --noEmit
```

预期：无新增错误

- [ ] **步骤 4：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardClient/src/api/agent.ts
git commit -m "feat: 前端 agent API 封装（axios + SSE 流式）"
```

---

### 任务 6：前端 stores/agentStore.ts

**文件：**
- 创建：`AIStoryboardClient/src/stores/agentStore.ts`

- [ ] **步骤 1：状态与动作定义**

```ts
import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type SseEvent,
} from '../api/agent';
import { useProjectStore } from './projectStore';

export interface HumanInputInfo {
  formToken: string;
  taskId: string;
  formContent: string;
  actions: { id: string; title: string }[];
  expirationTime: number;
}

interface AgentState {
  // 窗口
  windowOpen: boolean;
  setWindowOpen: (v: boolean) => void;

  // 会话
  conversations: AgentConversation[];
  activeConversationId: string | null;
  loadingConversations: boolean;
  loadConversations: () => Promise<void>;
  createConversation: () => Promise<void>;
  renameConversation: (id: string, title: string) => Promise<void>;
  setConversationStatus: (id: string, status: 'active' | 'archived') => Promise<void>;
  deleteConversation: (id: string) => Promise<void>;
  selectConversation: (id: string) => Promise<void>;

  // 消息
  messages: AgentMessage[];
  streaming: boolean;
  waitingHumanInput: HumanInputInfo | null;
  streamError: string | null;

  // 输入
  refImageUrl: string | null;
  setRefImageUrl: (v: string | null) => void;
  uploadRefImage: (file: File) => Promise<void>;

  // 互斥标志（会话级，刷新即恢复）
  agentGeneratedScenes: boolean;
  setAgentGeneratedScenes: (v: boolean) => void;

  // 资产
  assets: AgentPage<AgentAsset> | null;
  loadAssets: (page?: number) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;

  // 发送
  sendMessage: (content: string) => Promise<void>;
  submitHumanInput: (actionId: string) => Promise<void>;
  resetChatState: () => void;
}

let initialSceneCount = 0;

export const useAgentStore = create<AgentState>((set, get) => ({
  windowOpen: false,
  setWindowOpen: (v) => set({ windowOpen: v }),

  conversations: [],
  activeConversationId: null,
  loadingConversations: false,
  loadConversations: async () => {
    const projectId = useProjectStore.getState().currentProject?.id;
    if (!projectId) return;
    set({ loadingConversations: true });
    const res = await agentApi.listConversations(projectId);
    const list = res.data.data ?? [];
    set({ conversations: list, loadingConversations: false });
    // 自动选中最近会话
    if (list.length > 0 && !get().activeConversationId) {
      await get().selectConversation(list[0].id);
    }
  },

  createConversation: async () => {
    const projectId = useProjectStore.getState().currentProject?.id;
    if (!projectId) return;
    const res = await agentApi.createConversation(projectId, '新对话');
    const conv = res.data.data;
    set((s) => ({ conversations: [conv, ...s.conversations] }));
    await get().selectConversation(conv.id);
  },

  renameConversation: async (id, title) => {
    const res = await agentApi.updateConversation(id, { title });
    const updated = res.data.data;
    set((s) => ({
      conversations: s.conversations.map((c) => (c.id === id ? updated : c)),
    }));
  },

  setConversationStatus: async (id, status) => {
    const res = await agentApi.updateConversation(id, { status });
    const updated = res.data.data;
    set((s) => ({
      conversations: s.conversations.map((c) => (c.id === id ? updated : c)),
    }));
  },

  deleteConversation: async (id) => {
    await agentApi.deleteConversation(id);
    set((s) => ({
      conversations: s.conversations.filter((c) => c.id !== id),
      activeConversationId: s.activeConversationId === id ? null : s.activeConversationId,
      messages: s.activeConversationId === id ? [] : s.messages,
      waitingHumanInput: s.activeConversationId === id ? null : s.waitingHumanInput,
    }));
  },

  selectConversation: async (id) => {
    set({ activeConversationId: id, messages: [], waitingHumanInput: null, streamError: null });
    const res = await agentApi.listMessages(id);
    set({ messages: res.data.data ?? [] });
    const conv = get().conversations.find((c) => c.id === id);
    if (conv) {
      initialSceneCount = useProjectStore.getState().scenes.length;
      void get().loadAssets(1);
    }
  },

  messages: [],
  streaming: false,
  waitingHumanInput: null,
  streamError: null,

  refImageUrl: null,
  setRefImageUrl: (v) => set({ refImageUrl: v }),
  uploadRefImage: async (file) => {
    const res = await agentApi.uploadImage(file, get().activeConversationId ?? undefined);
    set({ refImageUrl: res.data.data.url });
  },

  agentGeneratedScenes: false,
  setAgentGeneratedScenes: (v) => set({ agentGeneratedScenes: v }),

  assets: null,
  loadAssets: async (page = 1) => {
    const id = get().activeConversationId;
    if (!id) return;
    const res = await agentApi.listAssets(id, page, 20);
    set({ assets: res.data.data });
  },
  deleteAsset: async (assetId) => {
    await agentApi.deleteAsset(assetId);
    void get().loadAssets(get().assets?.page ?? 1);
  },

  sendMessage: async (content) => {
    const id = get().activeConversationId;
    if (!id || get().streaming || !content.trim()) return;

    // 追加 user 消息（乐观 UI）
    const optimisticUser: AgentMessage = {
      id: `tmp-${Date.now()}`,
      conversationId: id,
      role: 'user',
      content,
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({
      messages: [...s.messages, optimisticUser],
      streaming: true,
      streamError: null,
      waitingHumanInput: null,
    }));
    initialSceneCount = useProjectStore.getState().scenes.length;

    // 追加空 assistant 消息（流式填充）
    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, optimisticAssistant] }));

    const updateAssistant = (delta: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + delta } : m),
      }));

    try {
      await streamChat(id, content, get().refImageUrl ?? undefined, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            updateAssistant(e.content ?? '');
            break;
          case 'workflow':
            break; // 进度提示可后续在 UI 展示，本期仅打字机
          case 'human_input':
            set({
              waitingHumanInput: {
                formToken: e.formToken ?? '',
                taskId: e.taskId ?? '',
                formContent: e.formContent ?? '',
                actions: e.actions ?? [],
                expirationTime: e.expirationTime ?? 0,
              },
            });
            break;
          case 'message_end':
            if (typeof e.sceneCount === 'number' && e.sceneCount > initialSceneCount) {
              get().setAgentGeneratedScenes(true);
              void useProjectStore.getState().loadProject(
                useProjectStore.getState().currentProject!.id,
              );
            }
            break;
          case 'error':
            set({ streamError: e.message ?? '对话出错，请重试' });
            break;
        }
      });
    } catch (err) {
      set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
    } finally {
      set((s) => ({
        streaming: false,
        messages: s.messages.map((m) =>
          m.id === assistantId && !m.content ? { ...m, content: '（未收到回复）' } : m),
      }));
    }
    // 清空参考图（发送即消费）
    set({ refImageUrl: null });
  },

  submitHumanInput: async (actionId) => {
    const info = get().waitingHumanInput;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    set({ streaming: true, waitingHumanInput: null });

    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      difyMessageId: null,
      createdAt: new Date().toISOString(),
    };
    set((s) => ({ messages: [...s.messages, optimisticAssistant] }));

    const updateAssistant = (delta: string) =>
      set((s) => ({
        messages: s.messages.map((m) =>
          m.id === assistantId ? { ...m, content: m.content + delta } : m),
      }));

    try {
      await submitForm(id, info.formToken, info.taskId, actionId, (e: SseEvent) => {
        switch (e.type) {
          case 'message':
            updateAssistant(e.content ?? '');
            break;
          case 'human_input':
            set({
              waitingHumanInput: {
                formToken: e.formToken ?? '',
                taskId: e.taskId ?? '',
                formContent: e.formContent ?? '',
                actions: e.actions ?? [],
                expirationTime: e.expirationTime ?? 0,
              },
            });
            break;
          case 'message_end':
            if (typeof e.sceneCount === 'number' && e.sceneCount > initialSceneCount) {
              get().setAgentGeneratedScenes(true);
              void useProjectStore.getState().loadProject(
                useProjectStore.getState().currentProject!.id,
              );
            }
            break;
          case 'error':
            set({ streamError: e.message ?? '对话出错，请重试' });
            break;
        }
      });
    } catch (err) {
      set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
    } finally {
      set((s) => ({
        streaming: false,
        messages: s.messages.map((m) =>
          m.id === assistantId && !m.content ? { ...m, content: '（未收到回复）' } : m),
      }));
    }
  },

  resetChatState: () =>
    set({ messages: [], waitingHumanInput: null, streamError: null, assets: null, refImageUrl: null }),
}));
```

- [ ] **步骤 2：类型检查**

```bash
cd "E:/Desktop/AI-storyboard/AIStoryboardClient" && npx tsc --noEmit
```

预期：无新增错误（若 `res.data.data` 类型推导报错，`as AgentConversation[]` 显式断言）

- [ ] **步骤 3：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardClient/src/stores/agentStore.ts
git commit -m "feat: agentStore（会话/消息/流式/HITL/互斥/资产）"
```

---

### 任务 7：AgentFab + AgentDrawer 容器

**文件：**
- 创建：`AIStoryboardClient/src/components/agent/AgentFab.tsx`
- 创建：`AIStoryboardClient/src/components/agent/AgentDrawer.tsx`
- 创建：`AIStoryboardClient/src/components/agent/AgentConversationList.tsx`（骨架先建，任务 8 补全）

- [ ] **步骤 1：AgentFab（右下角悬浮球）**

```tsx
import { useAgentStore } from '../../stores/agentStore';

export function AgentFab() {
  const windowOpen = useAgentStore((s) => s.windowOpen);
  const setWindowOpen = useAgentStore((s) => s.setWindowOpen);
  if (windowOpen) return null;
  return (
    <button
      onClick={() => setWindowOpen(true)}
      title="Moon 智能体"
      style={{
        position: 'fixed',
        right: 24,
        bottom: 24,
        width: 52,
        height: 52,
        borderRadius: '50%',
        border: 'none',
        background: 'var(--color-primary)',
        color: '#fff',
        fontSize: 22,
        cursor: 'pointer',
        boxShadow: '0 4px 16px rgba(204, 120, 92, 0.45)',
        zIndex: 90,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        transition: 'transform 0.15s',
      }}
      onMouseEnter={(e) => ((e.target as HTMLElement).style.transform = 'scale(1.06)')}
      onMouseLeave={(e) => ((e.target as HTMLElement).style.transform = 'scale(1)')}
    >
      ☾
    </button>
  );
}
```

- [ ] **步骤 2：AgentDrawer（抽屉容器，含遮罩/Esc/左栏+右区+资产面板）**

```tsx
import { useEffect } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { AgentConversationList } from './AgentConversationList';
import { AgentChatPanel } from './AgentChatPanel';
import { AgentAssetsPanel } from './AgentAssetsPanel';

export function AgentDrawer() {
  const windowOpen = useAgentStore((s) => s.windowOpen);
  const setWindowOpen = useAgentStore((s) => s.setWindowOpen);
  const loadConversations = useAgentStore((s) => s.loadConversations);

  // 打开时加载会话列表
  useEffect(() => {
    if (windowOpen) {
      loadConversations().catch(() => { /* 静默 */ });
    }
  }, [windowOpen, loadConversations]);

  // Esc 关闭
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setWindowOpen(false);
    };
    if (windowOpen) document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [windowOpen, setWindowOpen]);

  if (!windowOpen) return null;

  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 95 }}>
      {/* 遮罩 */}
      <div
        onClick={() => setWindowOpen(false)}
        style={{ position: 'absolute', inset: 0, background: 'rgba(20, 20, 19, 0.25)' }}
      />
      {/* 抽屉 */}
      <div
        style={{
          position: 'absolute',
          top: 0,
          right: 0,
          bottom: 0,
          width: 480,
          maxWidth: '92vw',
          background: 'var(--color-canvas)',
          borderLeft: '1px solid var(--color-hairline)',
          boxShadow: '-8px 0 24px rgba(20, 20, 19, 0.12)',
          display: 'flex',
          animation: 'agentSlideIn 0.2s ease-out',
        }}
      >
        <style>{`@keyframes agentSlideIn { from { transform: translateX(40px); opacity: 0.4; } to { transform: translateX(0); opacity: 1; } }`}</style>
        <AgentConversationList />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          <AgentChatPanel />
          <AgentAssetsPanel />
        </div>
      </div>
    </div>
  );
}
```

- [ ] **步骤 3：AgentConversationList 骨架（占位，任务 8 补全）**

```tsx
export function AgentConversationList() {
  return <div style={{ width: 130, borderRight: '1px solid var(--color-hairline)', background: 'var(--color-surface-soft)' }} />;
}
```

- [ ] **步骤 4：AgentChatPanel / AgentAssetsPanel 骨架（占位，任务 9/10 补全）**

```tsx
// AgentChatPanel.tsx
export function AgentChatPanel() {
  return <div style={{ flex: 1, overflowY: 'auto' }} />;
}
```

```tsx
// AgentAssetsPanel.tsx
export function AgentAssetsPanel() {
  return <div style={{ display: 'none' }} />;
}
```

- [ ] **步骤 5：类型检查**

```bash
cd "E:/Desktop/AI-storyboard/AIStoryboardClient" && npx tsc --noEmit
```

预期：无新增错误

- [ ] **步骤 6：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardClient/src/components/agent/
git commit -m "feat: 智能体窗口容器（悬浮球 + 抽屉骨架）"
```

---

### 任务 8：AgentConversationList 会话栏

**文件：**
- 修改：`AIStoryboardClient/src/components/agent/AgentConversationList.tsx`（替换骨架）

- [ ] **步骤 1：完整实现（新建/切换/重命名/归档/删除/归档筛选）**

```tsx
import { useState } from 'react';
import { useAgentStore } from '../../stores/agentStore';

export function AgentConversationList() {
  const {
    conversations, activeConversationId, selectConversation,
    createConversation, renameConversation, setConversationStatus, deleteConversation,
  } = useAgentStore();
  const [showArchived, setShowArchived] = useState(false);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameText, setRenameText] = useState('');
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const visible = conversations.filter((c) =>
    showArchived ? c.status === 'archived' : c.status !== 'archived');

  const handleRename = async (id: string) => {
    const title = renameText.trim();
    if (title) await renameConversation(id, title);
    setRenamingId(null);
  };

  return (
    <div
      style={{
        width: 138,
        minWidth: 138,
        borderRight: '1px solid var(--color-hairline)',
        background: 'var(--color-surface-soft)',
        display: 'flex',
        flexDirection: 'column',
        overflowY: 'auto',
      }}
    >
      {/* 新建 */}
      <button
        onClick={() => createConversation()}
        style={{
          margin: 10, padding: '8px 0', border: '1px dashed var(--color-hairline)',
          borderRadius: 'var(--rounded-md)', background: 'white',
          color: 'var(--color-primary)', fontSize: 12, fontWeight: 500, cursor: 'pointer',
        }}
      >
        + 新建对话
      </button>

      {/* 归档筛选切换 */}
      <button
        onClick={() => setShowArchived(!showArchived)}
        style={{
          margin: '0 10px 6px', padding: '4px 0', border: 'none', background: 'none',
          color: 'var(--color-muted)', fontSize: 11, cursor: 'pointer', textAlign: 'left',
        }}
      >
        {showArchived ? '◀ 返回进行中' : '🗂 已归档'}
      </button>

      {/* 会话列表 */}
      {visible.map((c) => (
        <div
          key={c.id}
          onClick={() => selectConversation(c.id)}
          style={{
            padding: '8px 10px', cursor: 'pointer',
            background: c.id === activeConversationId ? 'var(--color-surface-card)' : 'transparent',
            borderBottom: '1px solid var(--color-hairline-soft)',
          }}
        >
          {renamingId === c.id ? (
            <input
              autoFocus
              value={renameText}
              onChange={(e) => setRenameText(e.target.value)}
              onBlur={() => handleRename(c.id)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleRename(c.id); if (e.key === 'Escape') setRenamingId(null); }}
              onClick={(e) => e.stopPropagation()}
              style={{ width: '100%', fontSize: 12, padding: '2px 4px', border: '1px solid var(--color-primary)', borderRadius: 4 }}
            />
          ) : (
            <>
              <div style={{ fontSize: 12, fontWeight: c.id === activeConversationId ? 600 : 400, color: 'var(--color-ink)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {c.title}
              </div>
              <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
                <span
                  onClick={(e) => { e.stopPropagation(); setRenamingId(c.id); setRenameText(c.title); }}
                  title="重命名" style={{ fontSize: 11, cursor: 'pointer', color: 'var(--color-muted)' }}
                >✏️</span>
                <span
                  onClick={(e) => { e.stopPropagation(); setConversationStatus(c.id, c.status === 'archived' ? 'active' : 'archived'); }}
                  title={c.status === 'archived' ? '恢复' : '归档'}
                  style={{ fontSize: 11, cursor: 'pointer', color: 'var(--color-muted)' }}
                >🗂</span>
                <span
                  onClick={(e) => { e.stopPropagation(); setConfirmDeleteId(c.id); }}
                  title="删除" style={{ fontSize: 11, cursor: 'pointer', color: 'var(--color-error)' }}
                >🗑️</span>
              </div>
            </>
          )}
        </div>
      ))}

      {visible.length === 0 && (
        <p style={{ padding: 12, fontSize: 11, color: 'var(--color-muted-soft)', textAlign: 'center' }}>
          {showArchived ? '暂无已归档对话' : '暂无对话'}
        </p>
      )}

      {/* 删除二次确认 */}
      {confirmDeleteId && (
        <div style={{ position: 'absolute', bottom: 12, left: 10, right: 10, background: 'white', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)', padding: 10, boxShadow: '0 4px 12px rgba(20,20,19,0.12)', zIndex: 5 }}>
          <p style={{ margin: '0 0 8px', fontSize: 12, color: 'var(--color-ink)' }}>删除该对话？</p>
          <div style={{ display: 'flex', gap: 6 }}>
            <button onClick={() => setConfirmDeleteId(null)} style={{ flex: 1, padding: '4px 0', fontSize: 11, border: '1px solid var(--color-hairline)', borderRadius: 6, background: 'white', cursor: 'pointer' }}>取消</button>
            <button onClick={() => { deleteConversation(confirmDeleteId); setConfirmDeleteId(null); }} style={{ flex: 1, padding: '4px 0', fontSize: 11, border: 'none', borderRadius: 6, background: 'var(--color-error)', color: 'white', cursor: 'pointer' }}>删除</button>
          </div>
        </div>
      )}
    </div>
  );
}
```

- [ ] **步骤 2：类型检查**

```bash
cd "E:/Desktop/AI-storyboard/AIStoryboardClient" && npx tsc --noEmit
```

- [ ] **步骤 3：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardClient/src/components/agent/AgentConversationList.tsx
git commit -m "feat: 会话栏（新建/切换/重命名/归档/删除）"
```

---

### 任务 9：AgentChatPanel + MessageBubble + HumanInputCard

**文件：**
- 修改：`AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`（替换骨架）
- 创建：`AIStoryboardClient/src/components/agent/MessageBubble.tsx`
- 创建：`AIStoryboardClient/src/components/agent/HumanInputCard.tsx`

- [ ] **步骤 1：MessageBubble（气泡 + 轻量 markdown + 图片/视频卡片）**

```tsx
import { assetUrl } from '../../config';

/** 轻量渲染：**加粗**、换行、![]() 图片、[]() 链接、视频 URL 直接识别 */
function renderContent(content: string) {
  const lines = content.split('\n');
  return lines.map((line, i) => {
    // 图片 ![alt](url)
    const imgMatch = line.match(/!\[([^\]]*)\]\(([^)]+)\)/);
    if (imgMatch) {
      return (
        <img
          key={i}
          src={assetUrl(imgMatch[2])}
          alt={imgMatch[1]}
          style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 8, margin: '4px 0', display: 'block' }}
        />
      );
    }
    // 视频 URL（.mp4/.webm 直接渲染）
    const videoMatch = line.match(/https?:\/\/\S+\.(mp4|webm)(\?\S*)?/i);
    if (videoMatch) {
      return (
        <video
          key={i}
          src={assetUrl(videoMatch[0])}
          controls
          style={{ maxWidth: '100%', maxHeight: 240, borderRadius: 8, margin: '4px 0', display: 'block' }}
        />
      );
    }
    // 加粗
    const parts = line.split(/(\*\*[^*]+\*\*)/g).map((part, j) =>
      part.startsWith('**') && part.endsWith('**') ? (
        <strong key={j}>{part.slice(2, -2)}</strong>
      ) : (
        <span key={j}>{part}</span>
      ),
    );
    return <div key={i} style={{ marginBottom: 2 }}>{parts}</div>;
  });
}

export function MessageBubble({ role, content }: { role: 'user' | 'assistant'; content: string }) {
  const isUser = role === 'user';
  return (
    <div style={{ display: 'flex', justifyContent: isUser ? 'flex-end' : 'flex-start', marginBottom: 10 }}>
      <div
        style={{
          maxWidth: '82%',
          padding: '8px 12px',
          borderRadius: isUser ? '10px 10px 2px 10px' : '10px 10px 10px 2px',
          background: isUser ? 'var(--color-primary)' : 'var(--color-surface-card)',
          color: isUser ? 'white' : 'var(--color-body)',
          fontSize: 13,
          lineHeight: 1.6,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {content ? renderContent(content) : <span style={{ opacity: 0.6 }}>…</span>}
      </div>
    </div>
  );
}
```

- [ ] **步骤 2：HumanInputCard（HITL 确认卡片）**

```tsx
import { useAgentStore, type HumanInputInfo } from '../../stores/agentStore';

export function HumanInputCard({ info }: { info: HumanInputInfo }) {
  const submitHumanInput = useAgentStore((s) => s.submitHumanInput);
  const streaming = useAgentStore((s) => s.streaming);
  const expired = info.expirationTime > 0 && Date.now() / 1000 > info.expirationTime;

  return (
    <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 10 }}>
      <div style={{ maxWidth: '82%', padding: 12, borderRadius: 12, background: 'white', border: '1px solid var(--color-hairline)', boxShadow: '0 2px 8px rgba(20,20,19,0.06)' }}>
        <div style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 6, letterSpacing: 1 }}>需要您确认</div>
        <div style={{ fontSize: 13, color: 'var(--color-ink)', lineHeight: 1.6, marginBottom: 10, whiteSpace: 'pre-wrap' }}>
          {info.formContent || '请确认是否继续？'}
        </div>
        {expired ? (
          <div style={{ fontSize: 12, color: 'var(--color-warning)' }}>确认已过期，请重新发起对话</div>
        ) : (
          <div style={{ display: 'flex', gap: 8 }}>
            {info.actions.map((a) => (
              <button
                key={a.id}
                disabled={streaming}
                onClick={() => submitHumanInput(a.id)}
                style={{
                  padding: '6px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-primary)', color: 'white', fontSize: 13,
                  cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
                }}
              >
                {a.title}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **步骤 3：AgentChatPanel（消息流 + 输入区）**

```tsx
import { useEffect, useRef, useState } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { MessageBubble } from './MessageBubble';
import { HumanInputCard } from './HumanInputCard';

export function AgentChatPanel() {
  const { messages, streaming, waitingHumanInput, streamError, refImageUrl, setRefImageUrl, uploadRefImage, sendMessage } = useAgentStore();
  const [text, setText] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const activeConversationId = useAgentStore((s) => s.activeConversationId);

  // 新消息自动滚底
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, streaming, waitingHumanInput]);

  // 切换会话清空草稿
  useEffect(() => { setText(''); }, [activeConversationId]);

  const handleSend = () => {
    const content = text.trim();
    if (!content || streaming || waitingHumanInput) return;
    setText('');
    sendMessage(content);
  };

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      await uploadRefImage(file);
    } catch {
      setStreamErrorLocal('图片上传失败');
    }
  };

  const setStreamErrorLocal = (msg: string) => {
    // 轻量提示：直接复用 streamError 展示
    void msg;
    alert(msg);
  };

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {/* 头部 */}
      <div style={{ padding: '10px 14px', borderBottom: '1px solid var(--color-hairline)', background: 'white' }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-ink)' }}>☾ Moon 智能体</span>
      </div>

      {/* 消息流 */}
      <div ref={scrollRef} style={{ flex: 1, overflowY: 'auto', padding: 14, background: 'var(--color-canvas)' }}>
        {messages.length === 0 && !streaming && (
          <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 12, marginTop: 40 }}>
            与 Moon 智能体对话，设计分镜、图片与视频方案
          </p>
        )}
        {messages.map((m) => (
          <MessageBubble key={m.id} role={m.role} content={m.content} />
        ))}
        {streaming && !waitingHumanInput && (
          <div style={{ color: 'var(--color-muted)', fontSize: 12, marginLeft: 4 }}>正在生成…</div>
        )}
        {waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}
        {streamError && (
          <div style={{ color: 'var(--color-error)', fontSize: 12, margin: '8px 4px' }}>
            ⚠ {streamError}
          </div>
        )}
      </div>

      {/* 输入区 */}
      <div style={{ padding: 10, borderTop: '1px solid var(--color-hairline)', background: 'white' }}>
        {refImageUrl && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
            <img src={refImageUrl} style={{ width: 44, height: 44, objectFit: 'cover', borderRadius: 8 }} />
            <span style={{ fontSize: 11, color: 'var(--color-muted)' }}>参考图已附</span>
            <button onClick={() => setRefImageUrl(null)} style={{ border: 'none', background: 'none', color: 'var(--color-error)', cursor: 'pointer', fontSize: 12 }}>移除</button>
          </div>
        )}
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
          <button
            onClick={() => fileRef.current?.click()}
            title="上传参考图"
            style={{ width: 32, height: 32, border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)', cursor: 'pointer', fontSize: 14, flexShrink: 0 }}
          >📎</button>
          <input ref={fileRef} type="file" accept="image/*" hidden onChange={handleFile} />
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
            }}
            placeholder={waitingHumanInput ? '请先完成上方确认' : streaming ? '智能体正在回复…' : '描述你的需求…'}
            disabled={streaming || !!waitingHumanInput}
            rows={2}
            style={{
              flex: 1, padding: '8px 10px', border: '1px solid var(--color-hairline)',
              borderRadius: 'var(--rounded-md)', font: 'var(--text-body-sm)', color: 'var(--color-ink)',
              resize: 'none', outline: 'none', background: 'var(--color-canvas)',
            }}
          />
          <button
            onClick={handleSend}
            disabled={streaming || !!waitingHumanInput || !text.trim()}
            style={{
              height: 32, padding: '0 16px', border: 'none', borderRadius: 'var(--rounded-md)',
              background: streaming || !text.trim() ? 'var(--color-primary-disabled)' : 'var(--color-primary)',
              color: 'white', fontSize: 13, cursor: 'pointer', flexShrink: 0,
            }}
          >发送</button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **步骤 4：类型检查**

```bash
cd "E:/Desktop/AI-storyboard/AIStoryboardClient" && npx tsc --noEmit
```

预期：无新增错误（`alert` 的临时上传失败提示可保留，简单直接）

- [ ] **步骤 5：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardClient/src/components/agent/
git commit -m "feat: 对话区（消息气泡/轻量 markdown/HITL 确认卡片/输入区）"
```

---

### 任务 10：AgentAssetsPanel 资产面板

**文件：**
- 修改：`AIStoryboardClient/src/components/agent/AgentAssetsPanel.tsx`（替换骨架）

- [ ] **步骤 1：完整实现（网格 + 分页 + 删除）**

```tsx
import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';

export function AgentAssetsPanel() {
  const { assets, loadAssets, deleteAsset } = useAgentStore();
  if (!assets || assets.records.length === 0) return null;

  const totalPages = Math.max(1, Math.ceil(assets.total / assets.size));

  return (
    <div style={{ borderTop: '1px solid var(--color-hairline)', background: 'white', maxHeight: 200, overflowY: 'auto', padding: '10px 14px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)', textTransform: 'uppercase', letterSpacing: 1 }}>
          生成资产（{assets.total}）
        </span>
        <span style={{ fontSize: 11, color: 'var(--color-muted-soft)' }}>
          {assets.page} / {totalPages}
        </span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(64px, 1fr))', gap: 8 }}>
        {assets.records.map((a) => (
          <div key={a.id} style={{ position: 'relative', aspectRatio: '1', borderRadius: 8, overflow: 'hidden', background: 'var(--color-surface-soft)' }}>
            {a.type === 'video' ? (
              <video src={assetUrl(a.url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} muted />
            ) : (
              <img src={assetUrl(a.url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            )}
            <button
              onClick={() => { if (window.confirm('删除该资产？')) deleteAsset(a.id); }}
              title="删除"
              style={{
                position: 'absolute', top: 2, right: 2, width: 18, height: 18,
                border: 'none', borderRadius: '50%', background: 'rgba(198, 69, 69, 0.9)',
                color: 'white', fontSize: 10, cursor: 'pointer', lineHeight: 1,
              }}
            >×</button>
          </div>
        ))}
      </div>
      {totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 8 }}>
          <button disabled={assets.page <= 1} onClick={() => loadAssets(assets.page - 1)} style={{ fontSize: 11, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '2px 10px', background: 'white', cursor: 'pointer' }}>上一页</button>
          <button disabled={assets.page >= totalPages} onClick={() => loadAssets(assets.page + 1)} style={{ fontSize: 11, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '2px 10px', background: 'white', cursor: 'pointer' }}>下一页</button>
        </div>
      )}
    </div>
  );
}
```

- [ ] **步骤 2：类型检查**

```bash
cd "E:/Desktop/AI-storyboard/AIStoryboardClient" && npx tsc --noEmit
```

- [ ] **步骤 3：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardClient/src/components/agent/AgentAssetsPanel.tsx
git commit -m "feat: 资产面板（缩略图网格/分页/删除）"
```

---

### 任务 11：EditorPage 挂载 + LeftSidebar 互斥禁用

**文件：**
- 修改：`AIStoryboardClient/src/pages/EditorPage.tsx`
- 修改：`AIStoryboardClient/src/components/editor/LeftSidebar.tsx`

- [ ] **步骤 1：EditorPage 挂载悬浮球与抽屉**

`EditorPage.tsx` imports 加：

```tsx
import { AgentFab } from '../components/agent/AgentFab';
import { AgentDrawer } from '../components/agent/AgentDrawer';
```

根 div 内（`</div>` 闭合前、三栏布局之后）加：

```tsx
      {/* 智能体窗口 */}
      <AgentFab />
      <AgentDrawer />
```

- [ ] **步骤 2：LeftSidebar 互斥禁用**

`LeftSidebar.tsx` imports 加：

```tsx
import { useAgentStore } from '../../stores/agentStore';
```

组件内加：

```tsx
  const agentGeneratedScenes = useAgentStore((s) => s.agentGeneratedScenes);
```

剧本 textarea 与生成按钮禁用：

```tsx
      {/* Script textarea */}
      <div style={{ flexShrink: 0, display: 'flex', flexDirection: 'column' }}>
        <label style={labelStyle}>剧本 / 描述</label>
        <textarea
          value={scriptText}
          onChange={(e) => setScriptText(e.target.value)}
          disabled={agentGeneratedScenes}
          placeholder={agentGeneratedScenes ? '分镜已由智能体生成，如需手动生成请刷新页面' : '输入剧本或创作描述，AI 将自动拆解为分镜...'}
          style={{
            ...sharedInputStyle,
            minHeight: 100,
            resize: 'vertical',
            lineHeight: 1.55,
            background: agentGeneratedScenes ? 'var(--color-primary-disabled)' : 'white',
            cursor: agentGeneratedScenes ? 'not-allowed' : 'text',
          }}
        />
      </div>
```

生成按钮：

```tsx
      <button
        onClick={handleGenerate}
        disabled={isLoading || !scriptText.trim() || agentGeneratedScenes}
        style={{
          ...
          background:
            isLoading || !scriptText.trim() || agentGeneratedScenes
              ? 'var(--color-primary-disabled)'
              : 'var(--color-primary)',
          ...
          cursor: isLoading || !scriptText.trim() || agentGeneratedScenes ? 'not-allowed' : 'pointer',
        }}
      >
        {agentGeneratedScenes ? '已由智能体生成' : isLoading ? '生成中...' : '生成分镜脚本'}
      </button>
```

- [ ] **步骤 3：类型检查 + 构建**

```bash
cd "E:/Desktop/AI-storyboard/AIStoryboardClient" && npx tsc --noEmit && npm run build
```

预期：两者均成功

- [ ] **步骤 4：Commit**

```bash
cd "E:/Desktop/AI-storyboard"
git add AIStoryboardClient/src/pages/EditorPage.tsx AIStoryboardClient/src/components/editor/LeftSidebar.tsx
git commit -m "feat: 编辑器挂载智能体窗口 + 手动剧本输入互斥禁用"
```

---

### 任务 12：全量验证

**文件：** 无（验证任务）

- [ ] **步骤 1：后端编译 + 前端构建**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

cd "E:/Desktop/AI-storyboard/AIStoryboardClient" && npx tsc --noEmit && npm run build
```

预期：全部成功

- [ ] **步骤 2：后端启动 + curl 冒烟（可选，需数据库运行）**

```bash
# 启动后端（后台）后：
# 1. 登录拿 JWT（POST /api/auth/login）
# 2. 创建会话（POST /api/agent/conversations）
# 3. 流式消息（观察 SSE 事件序列）
curl -N -X POST "http://localhost:8082/api/agent/conversations/<id>/messages/stream" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"content":"帮我设计一个分镜方案","picUrl":""}'
# 预期：data: {"type":"message",...} → ... → data: {"type":"human_input",...}（若触发 HITL）或 message_end
```

- [ ] **步骤 3：手动全流程验证**

1. 打开编辑器 → 右下角出现 ☾ 悬浮球 → 点击 → 抽屉滑出
2. 新建对话 → 输入"设计一个 5 镜头广告分镜" → 发送 → 打字机输出 → 智能体问"满意吗" → HITL 卡片 → 点"满意" → 续流 → 第二栏分镜列表刷新 → 左侧剧本输入禁用
3. 上传参考图 → 发送 → 消息中显示图片卡片
4. 会话重命名/归档/删除 → 资产面板分页/删除
5. 刷新页面 → 剧本输入恢复

- [ ] **步骤 4：更新 CLAUDE.md（新增端点/事件协议/互斥约定）**

在 CLAUDE.md "AI Agent 对话模块" 节追加端点表新增行（stream/form/submit/PATCH/assets 分页/删除）、SSE 事件协议表、互斥规则、`AI_DIFY_API_KEY` 配置说明，然后 commit：

```bash
cd "E:/Desktop/AI-storyboard"
git add CLAUDE.md
git commit -m "docs: CLAUDE.md 补充智能体窗口端点/SSE 协议/互斥约定"
```

---

## 自检记录

**规格覆盖度：**
- ✅ 悬浮球入口 → 任务 7（AgentFab）
- ✅ 右侧抽屉（左会话栏+右对话区）→ 任务 7/8/9
- ✅ 多会话（新建/切换/重命名/归档/删除）→ 任务 8 + 任务 3（PATCH 端点）
- ✅ SSE 流式 + 打字机 → 任务 2（streamMessage）+ 任务 5（consumeSse）+ 任务 6（updateAssistant）
- ✅ HITL 表单提交 + 续流 → 任务 2（submitFormAndResume）+ 任务 9（HumanInputCard）
- ✅ inputs 适配（currentProjectId/PicUrl）→ 任务 2 步骤 2/4
- ✅ 参考图上传 → 任务 5（uploadImage）+ 任务 9（输入区 📎）
- ✅ 分镜列表联动刷新 → 任务 6（message_end sceneCount → loadProject）
- ✅ 互斥禁用 → 任务 6（agentGeneratedScenes）+ 任务 11
- ✅ 资产分页/删除 → 任务 3（assets 分页 + DELETE）+ 任务 10
- ✅ 配置 app key → 任务 4
- ✅ 生图/生视频无 sceneId 不映射分镜 → 已核查满足（零改动，规格基线）

**占位符扫描：** 无 TODO/待定；任务 7 的"骨架"是显式的分阶段实现（后置任务补全），非占位符。

**类型一致性：**
- `streamChat`/`submitForm` 签名与 agentStore 调用一致（conversationId, content/picUrl; formToken/taskId/action）
- `SseEvent.type` 联合类型与后端 5 种事件名一一对应
- 后端 `AgentSendMessageRequest(content, picUrl)` record 变更：全仓库唯一构造点？旧代码无 `new AgentSendMessageRequest(...)`（请求体由 Jackson 反序列化）——任务 1 已要求 grep 确认
- `AgentChatService` 构造参数新增 SceneMapper：仅 AgentConversationController 注入，Spring 自动装配无破坏
