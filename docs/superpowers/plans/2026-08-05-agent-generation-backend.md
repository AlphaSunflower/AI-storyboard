# 智能体生成后端化重构 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将智能体窗口的生成执行（写分镜/生图/生视频）从 Dify 工作流 HTTP 节点移到后端，由 HITL 表单提交事件触发，生成结果以消息 + 确认卡片推送到聊天框。

**架构：** Dify 保留意图识别/方案设计/多轮完善/方案确认 HITL；后端在表单提交 200 后按 action 分发生成（复用现有 ImageGenerationService/VideoGenerationService），生成完成落 agent_assets + 推 `message`（结果）+ `confirm_result`（看图确认卡片）。提交后的 SSE 连接延长到生成完成，保证实时推送。

**技术栈：** Spring Boot 4（Jackson 3 = tools.jackson）、MyBatis-Plus、React 19 + Zustand 5、Dify Service API。

**规格：** `docs/superpowers/specs/2026-08-05-agent-generation-backend-design.md`

---

## 文件结构

| 文件 | 职责 |
|------|------|
| 修改 `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java` | 方案快照缓存（node_finished 捕获 outputs + formToken 快照）；submitFormAndResume 提交后分发生成；forwardDifySse 支持"延后 complete" |
| 新建 `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentGenerationService.java` | 生成编排：writeScript / generateImage / createVideoTask / pollVideoTask（逻辑从 DifyAgentController 抽取） |
| 修改 `AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java` | 仅 `sanitize` 改为 `public static`（复用） |
| 修改 `AIStoryboardClient/src/stores/agentStore.ts` | `confirmResult` 状态；SSE `confirm_result` 事件处理；`refineAsset`/`dismissConfirm` actions |
| 新建 `AIStoryboardClient/src/components/agent/ConfirmResultCard.tsx` | 看图确认卡片（继续完善/满意完成） |
| 修改 `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx` | 渲染 ConfirmResultCard |
| `AIStoryboardDify/Moon智能体.yml` | 不直接改文件；任务 6 给用户在 Dify UI 的操作清单（删节点/加节点/改 action id） |

**约定（写死，勿偏离）：**
- 工作流方案确认 HITL 节点的 action id：图片 = `generate_image` / `refine`，视频 = `generate_video` / `refine`，分镜（人工介入 3）= `agree` / `disagree`（现状已有）
- 后端归属一律用 `conversation.getProjectId()`，不信任 Dify 回传
- 每次 chat-messages 必带 `inputs.currentProjectId`（现状已实现，勿改）

---

## 任务 1：后端——方案快照缓存

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java`

- [ ] **步骤 1：新增快照缓存字段与 record（类尾部、`persistAssistant` 之后追加）**

```java
    // ============ 智能体生成后端化：HITL 方案快照缓存 ============

    /** 表单快照 TTL（与 Dify form_token 过期时间对齐，30 分钟） */
    private static final long FORM_SNAPSHOT_TTL_MS = 30 * 60 * 1000L;
    /** formToken → 表单快照 */
    private final Map<String, FormSnapshot> formSnapshots = new ConcurrentHashMap<>();
    /** conversationId → 最近 LLM 节点输出（node_finished 捕获的 outputs） */
    private final Map<String, Map<String, Object>> lastNodeOutputs = new ConcurrentHashMap<>();

    /**
     * HITL 表单快照：用户点"确认"时后端需要的一切。
     * formContent = 确认卡片文案（含完整方案文本）；plan = 最近 LLM 节点结构化输出
     * （分镜 items / 图片 message+style+size / 视频方案），缺失时降级用 formContent。
     */
    public record FormSnapshot(String formContent, List<Map<String, String>> actions,
                               Map<String, Object> plan, String conversationId,
                               String projectId, long createdAt) {
        boolean expired() { return System.currentTimeMillis() - createdAt > FORM_SNAPSHOT_TTL_MS; }
    }

    /** 缓存表单快照（human_input_required / workflow_paused 转发前调用） */
    private void cacheFormSnapshot(String formToken, String formContent,
                                   List<Map<String, String>> actions, AgentConversation conversation) {
        if (formToken == null || formToken.isBlank()) return;
        Map<String, Object> plan = lastNodeOutputs.get(conversation.getId());
        formSnapshots.put(formToken, new FormSnapshot(formContent, actions, plan,
                conversation.getId(), conversation.getProjectId(), System.currentTimeMillis()));
        log.info("已缓存 HITL 方案快照: formToken={}, conversationId={}, planKeys={}", formToken,
                conversation.getId(), plan != null ? plan.keySet() : "null");
    }

    /** 取表单快照（惰性 TTL 清理）；无快照返回 null（调用方降级） */
    private FormSnapshot takeFormSnapshot(String formToken) {
        FormSnapshot snap = formSnapshots.get(formToken);
        if (snap == null) return null;
        if (snap.expired()) {
            formSnapshots.remove(formToken);
            return null;
        }
        return snap;
    }
```

- [ ] **步骤 2：node_finished 分支捕获 LLM 输出（替换现有 `case "node_started", "node_finished"` 分支）**

现有代码（约 396-398 行）：
```java
                    case "node_started", "node_finished" -> sendEvent(emitter, "workflow", Map.of(
                        "title", node.path("data").path("title").asText(""),
                        "status", "node_started".equals(event) ? "node_started" : "node_finished"));
```
替换为：
```java
                    case "node_started", "node_finished" -> {
                        // 生成后端化：node_finished 捕获 LLM 方案输出（分镜 items / 图片 message+style+size /
                        // 视频方案），暂存供表单提交时取用。outputs 仅进内存，绝不转发前端。
                        if ("node_finished".equals(event)) {
                            JsonNode outputs = node.path("data").path("outputs");
                            if (outputs.isObject() && !outputs.isEmpty()) {
                                lastNodeOutputs.put(conversation.getId(),
                                        objectMapper.convertValue(outputs, Map.class));
                            }
                        }
                        sendEvent(emitter, "workflow", Map.of(
                            "title", node.path("data").path("title").asText(""),
                            "status", "node_started".equals(event) ? "node_started" : "node_finished"));
                    }
```

- [ ] **步骤 3：human_input_required 分支转发前缓存快照（在现有 case 内、`sendEvent` 之前插入）**

现有代码（约 399-415 行）：
```java
                    case "human_input_required" -> {
                        JsonNode data = node.path("data");
                        List<Map<String, String>> actions = new ArrayList<>();
                        for (JsonNode a : data.path("actions")) {
                            actions.add(Map.of("id", a.path("id").asText(""), "title", a.path("title").asText("")));
                        }
                        sendEvent(emitter, "human_input", Map.of(
```
在 `for` 循环后、`sendEvent` 前插入：
```java
                        // 生成后端化：转发前缓存方案快照，表单提交时按 formToken 取用
                        cacheFormSnapshot(data.path("form_token").asText(""),
                                data.path("form_content").asText(""), actions, conversation);
```

- [ ] **步骤 4：workflow_paused 分支同样缓存（在现有 case 内、`sendEvent` 之前插入）**

现有代码（约 452-468 行）：
```java
                    case "workflow_paused" -> {
                        JsonNode reasons = node.path("data").path("reasons");
                        if (reasons.isArray() && !reasons.isEmpty()) {
                            JsonNode reason = reasons.get(0);
                            List<Map<String, String>> actions = new ArrayList<>();
                            for (JsonNode a : reason.path("actions")) {
                                actions.add(Map.of("id", a.path("id").asText(""), "title", a.path("title").asText("")));
                            }
                            sendEvent(emitter, "human_input", Map.of(
```
在 `for` 循环后、`sendEvent` 前插入：
```java
                            cacheFormSnapshot(reason.path("form_token").asText(""),
                                    reason.path("form_content").asText(""), actions, conversation);
```

- [ ] **步骤 5：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
预期：BUILD SUCCESS，无编译错误。（`Map.class` 转 `Map<String,Object>` 若出 unchecked 警告属正常，非错误。）

- [ ] **步骤 6：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java
git commit -m "feat: HITL 方案快照缓存（node_finished 捕获 outputs + formToken 快照）"
```

---

## 任务 2：后端——AgentGenerationService（生成编排）

**文件：**
- 新建：`AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentGenerationService.java`
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java:378`（sanitize 改 public）

- [ ] **步骤 1：DifyAgentController.sanitize 改为 public static**

```java
    static String sanitize(String value) {
```
改为：
```java
    public static String sanitize(String value) {
```

- [ ] **步骤 2：新建 AgentGenerationService.java（完整文件）**

```java
package com.storyboard.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.storyboard.controller.DifyAgentController;
import com.storyboard.dto.request.DifyGenerateScriptRequest;
import com.storyboard.entity.AgentAsset;
import com.storyboard.entity.AgentConversation;
import com.storyboard.entity.Scene;
import com.storyboard.mapper.AgentAssetMapper;
import com.storyboard.mapper.SceneMapper;
import com.storyboard.service.ai.ImageGenerationService;
import com.storyboard.service.ai.VideoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent 生成编排服务（智能体生成后端化重构）。
 *
 * 在 HITL 表单提交事件后由后端直接执行生成（写分镜 / 生图 / 生视频），
 * 替代原 Dify 工作流内 HTTP 节点回调（/api/ai/dify/**）。
 * 逻辑从 DifyAgentController 抽取复用；归属校验一律以 conversation.getProjectId() 为准。
 */
@Service
public class AgentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AgentGenerationService.class);

    private final ImageGenerationService imageService;
    private final VideoGenerationService videoService;
    private final SceneMapper sceneMapper;
    private final AgentAssetMapper agentAssetMapper;

    public AgentGenerationService(ImageGenerationService imageService,
                                  VideoGenerationService videoService,
                                  SceneMapper sceneMapper,
                                  AgentAssetMapper agentAssetMapper) {
        this.imageService = imageService;
        this.videoService = videoService;
        this.sceneMapper = sceneMapper;
        this.agentAssetMapper = agentAssetMapper;
    }

    /** 批量写分镜（原 DifyAgentController.generateScript 逻辑，宽松 items 直接透传） */
    public int writeScript(String projectId, List<DifyGenerateScriptRequest.SceneItem> scenes) {
        if (scenes == null || scenes.isEmpty()) return 0;
        int count = 0;
        for (var item : scenes) {
            Scene scene = new Scene();
            scene.setProjectId(projectId);
            scene.setSceneNumber(item.sceneNumber());
            scene.setScriptContent(item.scriptContent());
            scene.setImagePrompt(item.imagePrompt());
            scene.setVideoPrompt(item.videoPrompt());
            scene.setNegativePrompt(item.negativePrompt());
            scene.setCameraMovement(item.cameraMovement());
            scene.setShotType(item.shotType());
            scene.setSoundDesign(item.soundDesign());
            sceneMapper.insert(scene);
            count++;
        }
        log.info("Agent 生成编排：写入 {} 个分镜到项目 {}", count, projectId);
        return count;
    }

    /**
     * 生图并落库。
     * sceneId 非空 → 更新真实分镜（返回 imageUrl）；为空 → 落 agent_assets（返回 imageUrl + assetId）。
     * mode="edit" 走图改图（generatedImageUrl 为源图）。
     */
    public Map<String, String> generateImage(AgentConversation conversation, String sceneId,
                                             String prompt, String model, String size, String mode,
                                             List<String> referenceImages, String generatedImageUrl) {
        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        String imageUrl = imageService.generateImage(
            effectiveSceneId,
            DifyAgentController.sanitize(prompt), DifyAgentController.sanitize(model),
            DifyAgentController.sanitize(size), null, null,
            referenceImages, mode, DifyAgentController.sanitize(generatedImageUrl));
        if (effectiveSceneId == null) {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setType("image");
            asset.setUrl(imageUrl);
            asset.setPrompt(DifyAgentController.sanitize(prompt));
            asset.setModel(DifyAgentController.sanitize(model));
            asset.setStatus("completed");
            agentAssetMapper.insert(asset);
            log.info("Agent 生成编排：图片资产已落库 assetId={}, conversationId={}", asset.getId(), conversation.getId());
            return Map.of("imageUrl", imageUrl, "assetId", asset.getId());
        }
        return Map.of("imageUrl", imageUrl);
    }

    /**
     * 创建视频任务并落库（queued）。
     * duration 为字符串（快照 plan 中可能为 String），解析失败用默认值。
     */
    public String createVideoTask(AgentConversation conversation, String sceneId,
                                  String prompt, String model, String resolution, String size,
                                  String aspectRatio, String duration, String negativePrompt,
                                  List<String> referenceImages, String generatedImageUrl) {
        Integer durationInt = null;
        if (duration != null && !duration.isBlank()) {
            try {
                durationInt = Integer.parseInt(duration);
            } catch (NumberFormatException e) {
                log.warn("Agent 生成编排：视频 duration 值非法({}), 使用 service 默认值", duration);
            }
        }
        String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
        String taskId = videoService.createVideoTask(
            effectiveSceneId,
            DifyAgentController.sanitize(prompt), DifyAgentController.sanitize(model),
            DifyAgentController.sanitize(resolution), DifyAgentController.sanitize(size),
            DifyAgentController.sanitize(aspectRatio),
            durationInt, DifyAgentController.sanitize(negativePrompt), null,
            referenceImages, DifyAgentController.sanitize(generatedImageUrl));
        if (effectiveSceneId == null) {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setType("video");
            asset.setPrompt(DifyAgentController.sanitize(prompt));
            asset.setModel(DifyAgentController.sanitize(model));
            asset.setStatus("queued");
            asset.setTaskId(taskId);
            try {
                agentAssetMapper.insert(asset);
                log.info("Agent 生成编排：视频资产已落库 assetId={}, taskId={}", asset.getId(), taskId);
            } catch (Exception e) {
                // 资产落库失败不影响已创建的 Laozhang 任务（避免白扣费）
                log.error("Agent 生成编排：视频资产落库失败(不影响任务), taskId={}, 原因: {}", taskId, e.getMessage());
            }
        }
        return taskId;
    }

    /** 轮询视频任务；终态时同步更新 agent_assets 的 url/status/error */
    public Map<String, String> pollVideoTask(String taskId) {
        Map<String, String> result = videoService.pollVideoTask(taskId);
        String status = result.get("status");
        if ("completed".equals(status) || "failed".equals(status)) {
            AgentAsset asset = agentAssetMapper.selectOne(new LambdaQueryWrapper<AgentAsset>()
                .eq(AgentAsset::getTaskId, taskId)
                .last("LIMIT 1"));
            if (asset != null) {
                asset.setStatus(status);
                if ("completed".equals(status)) {
                    asset.setUrl(result.get("videoUrl"));
                } else {
                    asset.setError(result.getOrDefault("error", "未知错误"));
                }
                agentAssetMapper.updateById(asset);
                log.info("Agent 生成编排：视频资产已更新 assetId={}, status={}", asset.getId(), status);
            }
        }
        return result;
    }
}
```

- [ ] **步骤 3：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
预期：BUILD SUCCESS。

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentGenerationService.java AIStoryboardBackend/src/main/java/com/storyboard/controller/DifyAgentController.java
git commit -m "feat: AgentGenerationService 生成编排（写分镜/生图/生视频，复用现有 service）"
```

---

## 任务 3：后端——表单提交事件分发 + 生成结果推送

**文件：**
- 修改：`AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java`

- [ ] **步骤 1：注入 AgentGenerationService + 引入所需 import**

构造器新增参数（现有构造器约 82-99 行，在 `AgentConversationMapper conversationMapper` 之后加）：
```java
    private final AgentGenerationService generationService;
```
构造器参数与赋值同步加：
```java
                            AgentGenerationService generationService) {
        ...
        this.generationService = generationService;
```
import 追加（现有 import 区）：
```java
import com.storyboard.dto.request.DifyGenerateScriptRequest;
import java.util.concurrent.TimeUnit;
```

- [ ] **步骤 2：forwardDifySse 增加 deferComplete 参数（改造收尾分支）**

签名改为：
```java
    private void forwardDifySse(HttpResponse<java.io.InputStream> resp, SseEmitter emitter,
                                AgentConversation conversation, String userId, StringBuilder answer,
                                AtomicBoolean cancel, boolean deferComplete) {
```
（现有调用点 `streamMessage` 与 `submitFormAndResume` 各补一个 `false` / 新逻辑参数——streamMessage 传 `false`，submitFormAndResume 改造见步骤 4。）

收尾分支改造（仅当 `!deferComplete` 才 complete；事件照常发送）：

message_end 分支（约 416-439 行），把 `emitter.complete();` 换成：
```java
                        if (!deferComplete) emitter.complete();
                        return;
```
human_input_required 分支（约 413 行）、workflow_paused 分支（约 470 行）、workflow_finished 分支（约 496/515 行）、error 分支（约 522 行）、EOF 兜底（约 530 行）的 `emitter.complete()` 同样替换为 `if (!deferComplete) emitter.complete();`。

> 注意：`deferComplete=true` 时流内事件照常推送（前端打字机/确认卡片不受影响），仅把"连接关闭"推迟到外层（生成完成后再 complete）。

- [ ] **步骤 3：新增生成分发与推送逻辑（类内新增方法）**

```java
    /** 生成中/完成的工作流进度事件（title 供前端展示生成阶段） */
    private static final Map<String, String> GENERATION_STAGE_LABELS = Map.of(
        "script", "正在生成分镜…",
        "image", "正在生成图片…",
        "video", "正在生成视频…"
    );

    /**
     * 表单提交后按 action 分发生成（生成后端化核心）。
     * 工作流方案确认节点的 action id 约定：
     *   agree         → 分镜写库（人工介入 3 的"满意"）
     *   generate_image → 生图（图片方案确认的"生成图片"）
     *   generate_video → 生视频（视频方案确认的"开始生成视频"）
     *   refine        → 不触发生成（前端带 PicUrl 发消息走 Dify 完善分支）
     * 返回异步任务；调用方 await 后 complete emitter。
     */
    private CompletableFuture<Void> dispatchGeneration(FormSnapshot snapshot, String action,
                                                       SseEmitter emitter) {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> plan = snapshot.plan() != null ? snapshot.plan() : Map.of();
                switch (action) {
                    case "agree" -> {
                        // 分镜写库：plan 里取 items（宽松：string 形式 JSON 也解析）
                        sendEvent(emitter, "workflow", Map.of("title", GENERATION_STAGE_LABELS.get("script"), "status", "node_started"));
                        List<DifyGenerateScriptRequest.SceneItem> scenes = parseScenes(plan.get("items"));
                        int count = generationService.writeScript(snapshot.projectId(), scenes);
                        String msg = count > 0
                            ? "✅ 分镜方案已确认，已生成 **" + count + " 个分镜**，请查看左侧分镜列表"
                            : "⚠ 分镜方案已确认，但未解析到分镜内容，请重新描述需求";
                        sendEvent(emitter, "message", Map.of("content", msg));
                        sendEvent(emitter, "confirm_result", Map.of(
                            "kind", "script", "sceneCount", count, "url", "", "actions", List.of()));
                    }
                    case "generate_image" -> {
                        sendEvent(emitter, "workflow", Map.of("title", GENERATION_STAGE_LABELS.get("image"), "status", "node_started"));
                        // mode/edit 与源图：完善路径（快照有 picture 且 mode=edit）走图改图
                        String mode = "edit".equals(plan.get("mode")) ? "edit" : null;
                        String source = plan.get("picture") instanceof String p && !p.isBlank() ? p : null;
                        Map<String, String> result = generationService.generateImage(
                            conversationOf(snapshot), null,
                            str(plan.get("message")), str(plan.get("model")), str(plan.get("size")),
                            mode, null, source);
                        pushGenerationResult(emitter, "image", result.get("imageUrl"),
                            result.get("assetId"), 0, true);
                    }
                    case "generate_video" -> {
                        sendEvent(emitter, "workflow", Map.of("title", GENERATION_STAGE_LABELS.get("video"), "status", "node_started"));
                        String source = plan.get("picture") instanceof String p && !p.isBlank() ? p : null;
                        String taskId = generationService.createVideoTask(
                            conversationOf(snapshot), null,
                            str(plan.get("message")), str(plan.get("model")), null, null, null,
                            str(plan.get("duration")), null, null, source);
                        // 异步轮询：完成/失败推结果
                        CompletableFuture.runAsync(() -> pollVideoAndPush(taskId, snapshot, emitter), agentExecutor);
                    }
                    default -> log.info("action={} 不触发生成（refine/其他），由 Dify 继续完善", action);
                }
            } catch (Exception e) {
                log.error("Agent 生成分发失败: action={}, error={}", action, e.getMessage(), e);
                sendEvent(emitter, "error", Map.of("code", "50202", "message", "生成失败，请稍后重试"));
            }
        }, agentExecutor);
    }

    /** 取会话（快照仅存 id，重新查库；查不到返回 null 由调用方降级） */
    private AgentConversation conversationOf(FormSnapshot snapshot) {
        return conversationMapper.selectById(snapshot.conversationId());
    }

    /** 推送生成结果：image 完成推图消息 + 看图确认卡片；script 已由调用方推 */
    private void pushGenerationResult(SseEmitter emitter, String type, String url,
                                      String assetId, int sceneCount, boolean withConfirmCard) {
        if (url == null || url.isBlank()) {
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "生成失败，请稍后重试"));
            return;
        }
        if ("image".equals(type)) {
            sendEvent(emitter, "message", Map.of("content", "![生成图片](" + url + ")"));
        } else {
            sendEvent(emitter, "message", Map.of("content", url));
        }
        if (withConfirmCard) {
            sendEvent(emitter, "confirm_result", Map.of(
                "kind", type, "url", url, "assetId", assetId == null ? "" : assetId,
                "sceneCount", sceneCount,
                "actions", List.of(
                    Map.of("id", "refine", "title", "继续完善"),
                    Map.of("id", "done", "title", "满意完成"))));
        }
    }

    /** 轮询视频任务直至终态（复用 service 重试逻辑），终态推结果与确认卡片 */
    private void pollVideoAndPush(String taskId, FormSnapshot snapshot, SseEmitter emitter) {
        try {
            for (int i = 0; i < 90; i++) { // 90 * 5s ≈ 7.5min 上限
                if (Thread.currentThread().isInterrupted()) return;
                Map<String, String> result = generationService.pollVideoTask(taskId);
                String status = result.get("status");
                if ("completed".equals(status)) {
                    pushGenerationResult(emitter, "video", result.get("videoUrl"), null, 0, true);
                    return;
                }
                if ("failed".equals(status)) {
                    sendEvent(emitter, "error", Map.of("code", "50202",
                        "message", "视频生成失败：" + result.getOrDefault("error", "未知错误")));
                    return;
                }
                Thread.sleep(5000);
            }
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "视频生成超时，请重试"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("视频轮询失败: taskId={}, error={}", taskId, e.getMessage(), e);
            sendEvent(emitter, "error", Map.of("code", "50202", "message", "视频生成失败，请稍后重试"));
        }
    }

    /** 宽松解析 scenes：Map items / List / String 形式 JSON / null → 空列表（绝不抛） */
    @SuppressWarnings("unchecked")
    private List<DifyGenerateScriptRequest.SceneItem> parseScenes(Object raw) {
        if (raw == null) return List.of();
        try {
            if (raw instanceof List<?> list) {
                return objectMapper.convertValue(list,
                    new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
            }
            if (raw instanceof String s && !s.isBlank() && !s.contains("{{#")) {
                Object parsed = objectMapper.readValue(s, Object.class);
                if (parsed instanceof List<?> list) {
                    return objectMapper.convertValue(list,
                        new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                }
                if (parsed instanceof java.util.Map<?, ?> m && m.get("items") instanceof List<?> items) {
                    return objectMapper.convertValue(items,
                        new com.fasterxml.jackson.core.type.TypeReference<List<DifyGenerateScriptRequest.SceneItem>>() {});
                }
            }
            return List.of();
        } catch (Exception e) {
            log.warn("解析分镜 scenes 失败, 降级为空列表: {}", e.getMessage());
            return List.of();
        }
    }

    /** 快照 plan 取值辅助：null 安全 + String 化 */
    private static String str(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        return String.valueOf(v);
    }
```

> 注意：`parseScenes` 用 `com.fasterxml.jackson.core.type.TypeReference`（AgentChatService 的 ObjectMapper 是 `com.fasterxml.jackson.databind.ObjectMapper`，保持同一 Jackson 版本即可；项目运行时 Jackson 3 包下 `tools.jackson.databind.ObjectMapper`——需确认 AgentChatService 的 objectMapper 是哪个包。若为 `tools.jackson.databind.ObjectMapper`，TypeReference 必须用 `tools.jackson.core.type.TypeReference` 并 `@SuppressWarnings`。实现时按 AgentChatService 现有 `import com.fasterxml.jackson.databind.ObjectMapper;` 为准——**该文件第 5 行 import 的是 `com.fasterxml.jackson.databind.ObjectMapper`**，故上面用 `com.fasterxml.jackson.core.type.TypeReference` 正确。）

- [ ] **步骤 4：submitFormAndResume 改造（提交 200 后分发生成 + 延后 complete）**

现有方法（约 623-689 行）在 `submitResp.statusCode() != 200` 检查后、构造 eventsReq 前插入分发；并在续流调用处传 `deferComplete=true`，续流结束后等生成完成再 complete：

将：
```java
                HttpResponse<String> submitResp = httpClient.send(submitReq, HttpResponse.BodyHandlers.ofString());
                if (submitResp.statusCode() != 200) {
                    log.error("Dify 表单提交失败: status={}, body={}", submitResp.statusCode(), submitResp.body());
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
```
改为：
```java
                HttpResponse<String> submitResp = httpClient.send(submitReq, HttpResponse.BodyHandlers.ofString());
                if (submitResp.statusCode() != 200) {
                    log.error("Dify 表单提交失败: status={}, body={}", submitResp.statusCode(), submitResp.body());
                    sendEvent(emitter, "error", Map.of("code", "50202", "message", "Dify 服务异常，请稍后重试"));
                    emitter.complete();
                    return;
                }
                // 生成后端化：表单提交成功即按快照 + action 分发生成（与 Dify 续流并行）
                FormSnapshot snapshot = takeFormSnapshot(formToken);
                CompletableFuture<Void> generationFuture = null;
                if (snapshot != null) {
                    generationFuture = dispatchGeneration(snapshot, action, emitter);
                } else {
                    log.info("无方案快照(formToken={}), 仅续流不生成", formToken);
                }
```
将末尾的：
```java
                forwardDifySse(eventsResp, emitter, conversation, userId, new StringBuilder(), cancel);
```
改为：
```java
                // 续流 Dify（deferComplete：收尾不关闭连接，等生成完成统一收尾）
                forwardDifySse(eventsResp, emitter, conversation, userId, new StringBuilder(), cancel, true);
                // 等待生成任务完成（图片 30-60s / 视频 2-5min），完成后再关闭 SSE
                if (generationFuture != null) {
                    generationFuture.get(8, TimeUnit.MINUTES);
                }
                if (!cancel.get()) emitter.complete();
```
同时把 `streamMessage` 里的 `forwardDifySse(resp, emitter, conversation, userId, new StringBuilder(), cancel);` 补 `false` 参数。

> 说明：`generationFuture.get(8min)` 阻塞虚拟线程（agentExecutor 为虚拟线程池，不占平台线程）；视频轮询内部上限 7.5min，总超时 8min 兜底。取消标志 `cancel` 置位时生成仍会跑完（Laozhang 任务已发起不可撤），仅跳过推送。

- [ ] **步骤 5：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```
预期：BUILD SUCCESS。若 `Map.of("url", "", ...)` 之类出现类型推断问题，将字面量显式 `Map.of("url", (Object) "", ...)` 或保持同构。

- [ ] **步骤 6：Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java
git commit -m "feat: 表单提交事件分发生成 + confirm_result 推送（生成后端化）"
```

---

## 任务 4：前端——agentStore 支持 confirm_result

**文件：**
- 修改：`AIStoryboardClient/src/stores/agentStore.ts`

- [ ] **步骤 1：新增类型与状态字段**

顶部类型区（`HumanInputInfo` 定义附近）新增：
```ts
/** 生成完成后的看图确认卡片（后端 confirm_result 事件） */
export interface ConfirmResultInfo {
  kind: 'script' | 'image' | 'video';
  url: string;
  assetId?: string;
  sceneCount?: number;
  actions: { id: string; title: string }[];
}
```
接口字段区（约 39 行 `streamError` 附近）新增：
```ts
  confirmResult: ConfirmResultInfo | null;
```
初始 state（约 145 行附近）新增：
```ts
    confirmResult: null,
```

- [ ] **步骤 2：sendMessage 与 submitHumanInput 的 SSE switch 各加 `confirm_result` case**

sendMessage 的 switch（约 235-276 行）`case 'error'` 之前加：
```ts
          case 'confirm_result':
            if (get().activeConversationId !== snapshotId) break;
            set({ confirmResult: e as unknown as ConfirmResultInfo });
            break;
```
submitHumanInput 的 switch（约 349-386 行）`case 'error'` 之前加同样代码块。

- [ ] **步骤 3：新增 actions：refineAsset / dismissConfirm**

`submitHumanInput` 之后新增：
```ts
  /** 看图确认卡片：继续完善 → 带当前图作为 PicUrl 发消息（走 Dify 完善分支） */
  refineAsset: async () => {
    const { confirmResult, inputDraft } = get();
    if (!confirmResult || confirmResult.kind === 'script') return;
    set({ confirmResult: null });
    const content = (inputDraft ?? '').trim() || '请基于这张图片继续完善';
    await get().sendMessage(content, { picUrl: confirmResult.url });
  },
  /** 看图确认卡片：满意完成 → 收起卡片，刷新资产面板 */
  dismissConfirm: () => {
    set({ confirmResult: null });
    const id = get().activeConversationId;
    if (id) void get().loadAssets(id);
  },
```
> 说明：`sendMessage` 现签名若为 `(content: string, opts?)` 且 PicUrl 来自 `refImageUrl`，则需在其内部兼容"显式 picUrl 优先"：`sendMessage` 调用 `streamChat(id, content, get().refImageUrl ?? undefined, ...)` 处改为 `(opts?.picUrl ?? get().refImageUrl ?? undefined)`。若 store 有 `inputDraft` 状态则用，没有则直接 `''`（用默认文案）。

- [ ] **步骤 4：类型检查与构建**

```bash
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```
预期：无 TS 错误，build 成功。

- [ ] **步骤 5：Commit**

```bash
git add AIStoryboardClient/src/stores/agentStore.ts
git commit -m "feat: agentStore 支持 confirm_result 看图确认卡片（refine/done）"
```

---

## 任务 5：前端——ConfirmResultCard 组件 + 渲染

**文件：**
- 新建：`AIStoryboardClient/src/components/agent/ConfirmResultCard.tsx`
- 修改：`AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`

- [ ] **步骤 1：新建 ConfirmResultCard.tsx（完整文件）**

```tsx
import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';

/** 生成完成后的看图确认卡片（后端 confirm_result 事件） */
export function ConfirmResultCard() {
  const info = useAgentStore((s) => s.confirmResult);
  const refineAsset = useAgentStore((s) => s.refineAsset);
  const dismissConfirm = useAgentStore((s) => s.dismissConfirm);
  const streaming = useAgentStore((s) => s.streaming);
  if (!info) return null;

  const isScript = info.kind === 'script';
  return (
    <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 10 }}>
      <div
        style={{
          maxWidth: '82%', padding: 12, borderRadius: 12,
          background: 'white', border: '1px solid var(--color-hairline)',
          boxShadow: '0 2px 8px rgba(20,20,19,0.06)', textAlign: 'left',
        }}
      >
        <div style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 6, letterSpacing: 1 }}>
          {isScript ? '分镜生成完成' : '图片生成完成'}
        </div>
        {info.url && (
          <img
            src={assetUrl(info.url)}
            alt="生成结果"
            style={{ maxWidth: '100%', maxHeight: 200, borderRadius: 8, margin: '4px 0 8px', display: 'block' }}
            onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
          />
        )}
        {isScript && (
          <div style={{ fontSize: 13, color: 'var(--color-ink)', lineHeight: 1.6, marginBottom: 8 }}>
            {typeof info.sceneCount === 'number' && info.sceneCount > 0
              ? `已生成 ${info.sceneCount} 个分镜，请查看左侧分镜列表`
              : '分镜已确认，请查看左侧分镜列表'}
          </div>
        )}
        {!isScript && (
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              disabled={streaming}
              onClick={() => refineAsset()}
              style={{
                padding: '6px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
                background: 'var(--color-primary)', color: 'white', fontSize: 13,
                cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
              }}
            >
              继续完善
            </button>
            <button
              disabled={streaming}
              onClick={() => dismissConfirm()}
              style={{
                padding: '6px 16px', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
                background: 'white', color: 'var(--color-muted)', fontSize: 13,
                cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
              }}
            >
              满意完成
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
```

- [ ] **步骤 2：AgentChatPanel 渲染（消息列表区，`HumanInputCard` 渲染行附近）**

现有代码（`{n && (0, U.jsx)(Bs, {info: n})}` 等价 TSX 约 `{waitingHumanInput && <HumanInputCard info={waitingHumanInput} />}`）旁新增：
```tsx
      {confirmResult && <ConfirmResultCard />}
```
（从 store 取 `confirmResult`，import ConfirmResultCard。）

- [ ] **步骤 3：类型检查与构建**

```bash
cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```
预期：无 TS 错误，build 成功。

- [ ] **步骤 4：Commit**

```bash
git add AIStoryboardClient/src/components/agent/ConfirmResultCard.tsx AIStoryboardClient/src/components/agent/AgentChatPanel.tsx
git commit -m "feat: 看图确认卡片 ConfirmResultCard（继续完善/满意完成）"
```

---

## 任务 6：工作流 UI 操作清单（用户在 Dify UI 执行，与任务 3-5 并行）

**文件：** 无代码改动；在 Dify 控制台打开 Moon 智能体工作流，按序操作。

- [ ] **步骤 1：删除执行链节点**（选中节点 → 删除；被删节点上游直连 answer）

| 删除节点 | 备注 |
|---------|------|
| POST分镜脚本 | 分镜确认后直接 answer 收尾（文案如"分镜方案已确认"） |
| POST生图 | 图片方案确认节点后 answer 收尾 |
| POST生视频 | 视频方案确认节点后 answer 收尾 |
| 获取imageUrl、HTTP 请求 4 | Dify 不再下载/展示生成图 |
| 设置step=5(生图)、'设置step=5(生图) ' | 状态机不再有生图阶段 |
| 从json抽出值、赋全局值(×2)、公共变量赋值 | 数据源已删 |
| 赋全局值为空值、重置step、图片公用变量(图片生成完成) | 触发点已删 |

- [ ] **步骤 2：图片分支新增"方案确认" HITL 节点**（连接：图片方案设计 → 新 HITL → answer）

- 节点类型：人工输入（human-input）
- form_content：`{{#图片方案设计节点.structured_output.message#}}`（或引用 storage_pic_talk 方案文本）
- actions 两个按钮，**id 必须**：`generate_image`（标题"生成图片"）、`refine`（标题"继续完善方案"）
- 原"图片确认" if-else 删除或改接该节点

- [ ] **步骤 3：视频方案确认节点 actions id 调整**

- "生成视频确认" HITL 节点 actions id 改为：`generate_video`（"开始生成视频"）、`refine`（"继续完善"）
- 原 `generate` / `no_generate` id 不再被后端识别（后端按新 id 分发）

- [ ] **步骤 4：start 后新增 assigner：step = -1**

- 节点类型：变量赋值（assigner）
- 操作：`conversation.step` set 为 `-1`
- 连接：start → assigner → 意图识别/Step路由
- 目的：每轮消息强制重置，防止上一轮残留 step 导致路由错乱

- [ ] **步骤 5：完善图片设计方案 LLM 变量引用调整**

- "上一个生图的风格" 引用从生图链抽值节点（`17853956637370`）改为 `conversation.storage_pic_talk.pic_generate_talk`

- [ ] **步骤 6：storage_pic_talk 清理逻辑（文生图不残留旧风格）**

- "传到公共变量" code 节点：当 mode 非 edit（文生图）时，`pic_generate_talk` 用当前方案文本（不复用 stored_talk）

- [ ] **步骤 7：导出保存并自测**

- 工作流导出备份 yml（保留原文件，新版本另存）
- 在 Dify 调试面板跑一轮完整对话验证：方案确认 → 后端生成 → 聊天框出结果 + 确认卡片

---

## 任务 7：联调验证 + 回归

- [ ] **步骤 1：后端启动（本地 8082），前端 dev（5173）**

```bash
# 后端
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" spring-boot:run
# 前端（另开终端）
cd AIStoryboardClient && npm run dev
```

- [ ] **步骤 2：全链路手测（三条分支各一轮）**

1. 分镜：发"把 XX 剧本设计成分镜" → 人工介入 3 点"满意" → 左侧分镜列表出现 N 个分镜 + 聊天框"已生成 N 个分镜"
2. 图片：发"设计一张 XX 图片" → 方案确认点"生成图片" → 聊天框出图 + 确认卡片
3. 图片完善：点"继续完善" → 输入"更亮一点" → Dify 出完善方案 → 点"生成图片" → 出新图（图改图，与上张同源）
4. 视频：发"设计一段 XX 视频" → 方案确认点"开始生成视频" → 聊天框出现视频播放 + 确认卡片（生成中显示"正在生成视频…"）
5. 满意完成：点"满意完成" → 卡片消失，资产面板出现该资产

- [ ] **步骤 3：回归**

- 清除聊天记录（上下文重置）功能正常
- HITL 多级确认（若有第二个 HITL 节点）续流正常
- 消息刷新后图片/视频仍可显示（本地 URL 无签名时效）
- 视频生成失败路径：故意给非法 prompt → 聊天框显示失败文案 + 资产 failed

- [ ] **步骤 4：最终提交（如联调中有修整）**

```bash
git add <改动文件>
git commit -m "fix: 联调修整（生成后端化）"
```

---

## 自检记录

- **规格覆盖度**：4.0 输入约定（任务 0 说明，代码不动）✓；4.1 快照缓存（任务 1）✓；4.2 提交分发（任务 3）✓；4.3 结果回聊天框（任务 3 pushGenerationResult）✓；4.4 confirm_result 卡片（任务 3 + 任务 4/5）✓；第 3 节工作流改动（任务 6）✓；第 7 节错误处理（任务 3 兜底分支）✓；第 8 节验证（任务 7）✓
- **占位符扫描**：无 TODO/待定；所有代码步骤含完整代码
- **类型一致性**：action id 约定（agree/generate_image/generate_video/refine）在任务 3 后端与任务 6 工作流两侧一致；`confirm_result` 事件负载（kind/url/assetId/sceneCount/actions）后端推送与前端 interface 一致；AgentGenerationService 方法签名与调用点一致
