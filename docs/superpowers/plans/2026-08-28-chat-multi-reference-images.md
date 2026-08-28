# /chat 参考图多张 + 资产多图投喂生视频 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把 /chat 链路的单图参考图 `picUrl` 升级为多图 `picUrls: List<String>`，并让"从资产库选资产/生视频"都取资产**全部**图，从而多张参考图投喂生视频模型。

**架构：** 后端（agent-core + MoonAgent）整条「controller → service → orchestrator → PlanGraph → OrchestrationRequest → handler → 视觉模型服务」的单图贯口改为多图 `List<String>`；视频落地层 `createVideoTask(referenceImages)` / 资产库 `Asset.images[]` 已支持多图，零 DB 迁移。前端 `refImageUrl` → `refImageUrls: string[]`，上传/资产选择累积多图，发送携带 `picUrls`。

**技术栈：** Spring Boot 4（JDK 21，MyBatis-Plus，Spring AI 2.0 ChatClient 多模态 Media）+ React 19/TS + Zustand。

---

## 先决条件（本仓库特殊工具链——必须遵守）

- `JAVA_HOME` 必须用 Windows 路径格式：`export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"`。POSIX 路径（`/c/...`）会 `ClassNotFoundException: plexus-classworlds`。
- Maven 用 cmd 批处理直调，不是 bash `mvn` alias：
  - `"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\agent-core\\pom.xml" install -q`
  - `"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q`
- 前端：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build`（tsconfig 为 solution-style，裸 `npx tsc --noEmit` 不检查任何文件）。
- Maven 本地仓库在 `D:\dev\maven\repository`（非 `~/.m2`），已在 settings 配置。
- **工作区有大量用户未提交改动，`git add` 只加本计划触及的文件，绝不 `git add -A`。** 分支：`morrow-design-assistant`（master 直接开发，不建 worktree）。

## Checklist（全局，逐任务勾选）

- [ ] 任务 A：agent-core 视觉模型服务多图（ImageRefinePrompt / VideoPlan）
- [ ] 任务 B：MoonAgent DTO + Controller 接收 `picUrls`
- [ ] 任务 C：AgentChatService/Impl 单图→多图 + 认领循环
- [ ] 任务 D：OrchestrationRequest + AgentOrchestrator + PlanGraph 单图→多图
- [ ] 任务 E：handler 层多图（Pic / Video / Support / AgentTools）
- [ ] 任务 F：前端 api + store 多图
- [ ] 任务 G：前端 ChatComposer + AgentChatPanel UI
- [ ] 任务 H：后端编译 + 前端 build 全量验证

---

## 任务 A：agent-core 视觉模型服务多图

**文件：**
- 修改：`agent-core/src/main/java/com/moon/moonagent/ai/ImageRefinePromptService.java`
- 修改：`agent-core/src/main/java/com/moon/moonagent/ai/impl/ImageRefinePromptServiceImpl.java`
- 修改：`agent-core/src/main/java/com/moon/moonagent/ai/VideoPlanService.java`
- 修改：`agent-core/src/main/java/com/moon/moonagent/ai/impl/VideoPlanServiceImpl.java`

两个服务签名从单图 String 改为多图 `List<String>`，实现里由「读单文件→单 Media」改为「循环读→多 Media 全塞 UserMessage」。空列表退化为纯文本（无图）。

- [ ] **步骤 1：改 ImageRefinePromptService 接口**

`ImageRefinePromptService.java`：
```java
/** 改图提示词增强：视觉模型看多张源图 + 诉求 → refined_prompt。imagePaths 空列表=纯文本（无图）。 */
String buildRefinedPrompt(java.util.List<String> imagePaths, String userRequest);
```

- [ ] **步骤 2：改 ImageRefinePromptServiceImpl 实现**

`ImageRefinePromptServiceImpl.java` 的 `buildRefinedPrompt` 方法改为：
```java
@Override
public String buildRefinedPrompt(java.util.List<String> imagePaths, String userRequest) {
    try {
        com.moon.moonagent.ai.UserMessage userMessage; // 见下方直接用 Spring AI UserMessage
        var mb = UserMessage.builder()
                .text("用户的修改诉求：" + (userRequest == null || userRequest.isBlank()
                        ? "（未提供，请结合图片自行判断合理的优化方向）" : userRequest));
        if (imagePaths != null) {
            for (String imagePath : imagePaths) {
                if (imagePath == null || imagePath.isBlank()) continue;
                String filename = extractFilename(imagePath);
                byte[] imageBytes = fileStorageService.readImage(filename);
                if (imageBytes == null) { log.warn("参考图不存在，跳过: {}", filename); continue; }
                String dataUri = "data:image/" + guessImageType(filename) + ";base64,"
                        + Base64.getEncoder().encodeToString(imageBytes);
                mb.media(Media.builder()
                        .mimeType(MimeType.valueOf("image/" + guessImageType(filename)))
                        .data(dataUri).build());
            }
        }
        UserMessage userMessage = mb.build();
        String content = chatClient().prompt()
                .system(promptConfig.get("services/image-refine-prompt"))
                .messages(userMessage).call().content();
        // 解析逻辑不变（BeanOutputConverter<RefinePlan> → refined_prompt；兜底 extractRefinedPrompt）
        ... 原解析代码原样保留 ...
    } catch (Exception e) { ... 原样 ... }
}
```
注意：顶部 import 需增加 `java.util.List`。`UserMessage.Builder` 确认支持 `.media(Media)` 追加调用（Spring AI 2.0 多模态，`.media(...)` 可多次调用；若无则改用 `List<Media>` 重载，见下面步骤 3 兜底）。

- [ ] **步骤 3：确认 Spring AI 多 Media 组装方式（编译前 spike）**

`VideoPlanServiceImpl` 现用 `.media(media)` 单次；若 Builder 不支持链式多次调用，改为：
```java
List<Media> medias = new ArrayList<>();
for (String imagePath : imagePaths) { ... medias.add(media); }
UserMessage userMessage = UserMessage.builder().text(...).media(medias).build(); // media(List<Media>)
```
以 `mvn -f agent-core/pom.xml compile -q` 实测为准；编译报错则按此兜底。

- [ ] **步骤 4：改 VideoPlanService 接口**

`VideoPlanService.java`：
```java
VideoPlan buildVideoPlan(java.util.List<String> imagePaths, String userRequest, String modelOptionsText);
```

- [ ] **步骤 5：改 VideoPlanServiceImpl 实现**

`VideoPlanServiceImpl.java` 的 `buildVideoPlan`：改为循环读多图 → 多 Media，`UserMessage` 文本诉求 + 全部图片。解析逻辑不变。

- [ ] **步骤 6：编译 agent-core 验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\agent-core\\pom.xml" compile -q
```
预期：BUILD SUCCESS，无编译错误。

- [ ] **步骤 7：Install + Commit**

```bash
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\agent-core\\pom.xml" install -q
git add agent-core/src/main/java/com/moon/moonagent/ai/ImageRefinePromptService.java \
        agent-core/src/main/java/com/moon/moonagent/ai/impl/ImageRefinePromptServiceImpl.java \
        agent-core/src/main/java/com/moon/moonagent/ai/VideoPlanService.java \
        agent-core/src/main/java/com/moon/moonagent/ai/impl/VideoPlanServiceImpl.java
git commit -m "feat(agent-core): 视觉模型服务支持多参考图（buildRefinedPrompt/buildVideoPlan 改 List<String>）"
```

---

## 任务 B：MoonAgent DTO + Controller 接收 picUrls

**文件：**
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/dto/request/AgentSendMessageRequest.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/controller/AgentConversationController.java`

- [ ] **步骤 1：DTO 加 picUrls 字段（兼容保留 picUrl）**

`AgentSendMessageRequest.java`：
```java
public record AgentSendMessageRequest(
    @NotBlank(message = "消息内容不能为空") String content,
    String picUrl,                               // 兼容保留（弃用）
    java.util.List<String> picUrls               // 新增
) {}
```

- [ ] **步骤 2：Controller 读 picUrls（兼容包装）**

`AgentConversationController.java` 新增私有 helper 并替换 `streamMessage`/`sendMessage` 中的 `request.picUrl()`：
```java
/** picUrls 兼容包装：picUrls 优先；为空回退 [picUrl]（旧客户端只传 picUrl） */
private static java.util.List<String> picUrls(AgentSendMessageRequest r) {
    if (r.picUrls() != null && !r.picUrls().isEmpty()) return r.picUrls();
    // 过滤空字符串
    if (r.picUrl() != null && !r.picUrl().isBlank()) return java.util.List.of(r.picUrl());
    return java.util.List.of();
}
```
`streamMessage`（第 164 行）改为：
```java
chatService.streamMessage(auth.getName(), id, request.content(), picUrls(request), emitter);
```
`sendMessage` 里 `request.picUrl()` 相关仅作兼容（`AgentChatService.sendMessage` 无 picUrl 参数，无需改）。

- [ ] **步骤 3：Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/dto/request/AgentSendMessageRequest.java \
        MoonAgent/src/main/java/com/moon/moonagent/controller/AgentConversationController.java
git commit -m "feat(moonagent): DTO/Controller 接收 picUrls 多图（兼容单图 picUrl）"
```

---

## 任务 C：AgentChatService/Impl 单图→多图 + 认领循环

**文件：**
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentChatService.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentChatServiceImpl.java`

- [ ] **步骤 1：改接口 streamMessage 签名**

`AgentChatService.java`：
```java
void streamMessage(String userId, String conversationId, String content,
                   java.util.List<String> picUrls, SseEmitter emitter);
```

- [ ] **步骤 2：改 Impl —— 内存键 + 认领循环**

`AgentChatServiceImpl.java`：
- 字段 `lastPicUrlByConversation`（`Map<String,String>`）→ `lastPicUrlsByConversation`（`Map<String, java.util.List<String>>`）。找到声明处（搜索 `lastPicUrlByConversation`）逐一改名，涉及：`confirmImageDone`、`deleteConversation`、`streamMessage`。
- `streamMessage` 内：
```java
if (picUrls != null && !picUrls.isEmpty()) {
    lastPicUrlsByConversation.put(conversationId, picUrls);
    // 认领未归属资产：循环每张 url，把该 url 且 conversation_id 为 null 的 reference 资产归属到本会话
    for (String url : picUrls) {
        if (url == null || url.isBlank()) continue;
        assetMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AgentAsset>()
                .eq(AgentAsset::getUrl, url)
                .isNull(AgentAsset::getConversationId)
                .set(AgentAsset::getConversationId, conversationId));
    }
} else {
    lastPicUrlsByConversation.remove(conversationId);
}
```
- `confirmImageDone`：`lastPicUrlsByConversation.remove(conversationId)`。
- 传给 orchestrator：`orchestrator.run(conversation, content, picUrls, emitter)`。

- [ ] **步骤 3：Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentChatService.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentChatServiceImpl.java
git commit -m "feat(moonagent): AgentChatService 参考图改多图 picUrls + 逐张认领资产归属"
```

---

## 任务 D：OrchestrationRequest + AgentOrchestrator + PlanGraph 单图→多图

**文件：**
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/OrchestrationRequest.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentOrchestrator.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentOrchestratorImpl.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/PlanGraph.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/SpecialistContext.java`

- [ ] **步骤 1：OrchestrationRequest 单图→多图 + getPicUrl() 兼容**

`OrchestrationRequest.java`：
```java
private final java.util.List<String> picUrls;   // 原 picUrl: String

/** 兼容：返回首张（无图返回 null）。新逻辑一律用 getPicUrls()。 */
public String getPicUrl() {
    return (picUrls == null || picUrls.isEmpty()) ? null : picUrls.getFirst();
}
```
构造函数参数 `String picUrl` → `java.util.List<String> picUrls`（含 Lombok `@RequiredArgsConstructor` 自动生成构造器，调用方需同步新值）。

- [ ] **步骤 2：改 AgentOrchestrator 接口**

`AgentOrchestrator.java`：
```java
String run(AgentConversation conversation, String content,
           java.util.List<String> picUrls, SseEmitter emitter);
```

- [ ] **步骤 3：改 AgentOrchestratorImpl**

`AgentOrchestratorImpl.java` 的 `run`：
```java
public String run(AgentConversation conversation, String content,
                  java.util.List<String> picUrls, SseEmitter emitter) {
    OrchestrationRequest request = new OrchestrationRequest(conversation, content, picUrls, emitter);
    ...
    String answer = planGraph.run(conversation, content, picUrls, emitter);
    ...
}
```
`resume` 里 `new OrchestrationRequest(conversation, "", null, emitter)` → 第二处构造改为 `java.util.List.of()`（或保留 null，getPicUrl() 已判空）。resume 无多图传入，用 `java.util.List.of()`。

- [ ] **步骤 4：改 PlanGraph —— state 键 + run + stateRequest + SpecialistContext**

`PlanGraph.java`：
- 常量 `K_PIC_URL = "picUrl"` → `K_PIC_URLS = "picUrls"`。所有引用该常量的地方替换（策略注册表 `m.put(K_PIC_URLS, ReplaceStrategy)`、`run` 的 `inputs.put`）。
- `run` 签名：`run(AgentConversation conversation, String content, java.util.List<String> picUrls, SseEmitter emitter)`，`inputs.put(K_PIC_URLS, picUrls == null ? java.util.List.of() : picUrls)`。
- `stateRequest`：
```java
java.util.List<String> picUrls = state.<java.util.List<String>>value(K_PIC_URLS).orElse(java.util.List.of());
return new OrchestrationRequest(conversation, content,
        picUrls == null || picUrls.isEmpty() ? java.util.List.of() : picUrls, emitter);
```
- `SpecialistContext` 构造处：`state.<String>value(K_PIC_URL).orElse("")` → `state.<java.util.List<String>>value(K_PIC_URLS).orElse(java.util.List.of())`。
- resume 路径的 `inputs` 无 K_PIC_URLS，不需要加（resume 不带新图）。

- [ ] **步骤 5：改 SpecialistContext record**

`SpecialistContext.java`：
```java
public record SpecialistContext(
        AgentConversation conversation,
        String userMessage,
        java.util.List<String> picUrls,   // 原 String picUrl
        SseEmitter emitter,
        ...
) {
    public static SpecialistContext from(OrchestrationRequest request, AgentSkill skill) {
        return new SpecialistContext(
                request.getConversation(),
                request.getContent(),
                request.getPicUrls(),       // 原 request.getPicUrl()
                request.getEmitter(),
                ...);
    }
}
```
确认 `SpecialistAgent.execute(ctx)` / `SpecialistAgentRegistry` 不读取 `ctx.picUrl()`（已 grep 确认无引用），无消费方需同步。

- [ ] **步骤 6：Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/OrchestrationRequest.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentOrchestrator.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/impl/AgentOrchestratorImpl.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/PlanGraph.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/specialist/SpecialistContext.java
git commit -m "refactor(moonagent): OrchestrationRequest/Orchestrator/PlanGraph 参考图改多图 picUrls"
```

---

## 任务 E：handler 层多图（Pic / Video / Support / AgentTools）

**文件：**
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/PicIntentHandler.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/VideoIntentHandler.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/AgentOrchestratorSupport.java`
- 修改：`MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentTools.java`

- [ ] **步骤 1：PicIntentHandler 多图**

`PicIntentHandler.java`：
- `handle`：`String source = request.getPicUrl()` → `java.util.List<String> sources = request.getPicUrls()`；`buildRefinedPrompt(sources, request.getContent())`。
- `resumeRefineOption`：`String source = planField(...SOURCE)` 单图逻辑 → 改为 `java.util.List<String>`。`buildRefinedPrompt(source, ...)` → `buildRefinedPrompt(sourceList, ...)`；`source` 为空串时 `java.util.List.of()`。
- `resume` 里 `safeRefineImage(..., source, ...)` → source 传首张（`source.isEmpty()?null:source.getFirst()`，图改图 edits 单源图）。
- 所有 `List.of(Map.of("prompt", prompt, "source", source...))` 的 plan 存 `source` 改为存 `picUrls`（JSON 数组序列化）。checkpoint plan 里 `source` → `picUrls` 数组字符串。

- [ ] **步骤 2：VideoIntentHandler 多图**

`VideoIntentHandler.java`：
- `handle`：`handleFromVideoClarifyGate(request, content, List.of(), request.getPicUrl())` → `request.getPicUrls()`。
- `handleFromVideoClarifyGate(..., String source)` → `(..., java.util.List<String> sources)`。判空 `(source!=null && !source.isBlank())` → `(sources!=null && !sources.isEmpty())`。
- `handleVideoPlan`：`String source` → `java.util.List<String> sources`。拉勾选资产全部图：`firstAssetImageUrl` → `assetImageUrls`；`source` 为空时取 `assetImageUrls`（仍空则空列表）。
- `buildVideoPlan` 传 `sources`（List）。
- `resume` 里 `support.startVideoGenerationAsync(request, message, duration, null, source, ...)` → `(request, message, duration, null, sources, ...)`；`firstAssetImageUrl` → `assetImageUrls`。
- plan 里 `"source", source` → `"picUrls", sources`（数组）。

- [ ] **步骤 3：AgentOrchestratorSupport —— runAssetGate / assetImageUrls / startVideoGenerationAsync**

`AgentOrchestratorSupport.java`：
- `runAssetGate(..., String source, String picUrl)` → `(..., String source, java.util.List<String> picUrls)`。plan 存 `"picUrl"` → `"picUrls"` 数组。
- `firstAssetImageUrl(projectId, assetIdsCsv)` → 新增 `assetImageUrls(projectId, assetIdsCsv): java.util.List<String>`，取勾选资产**全部**图 URL（`flatMap(vo.images → url)`，去重，空则空列表）。保留 `firstAssetImageUrl` 委托 `assetImageUrls` 取首图（供其他调用点兼容）：
```java
public List<String> assetImageUrls(String projectId, String assetIdsCsv) {
    if (projectId == null || assetIdsCsv == null || assetIdsCsv.isBlank()) return List.of();
    try {
        List<String> ids = Arrays.stream(assetIdsCsv.split(",")).map(String::trim).filter(x -> !x.isBlank()).toList();
        if (ids.isEmpty()) return List.of();
        Set<String> urls = new LinkedHashSet<>();
        for (AssetVO vo : storyboardClient.getProjectAssets(projectId)) {
            if (!ids.contains(vo.id())) continue;
            if (vo.images() != null) for (var img : vo.images()) if (img.url() != null && !img.url().isBlank()) urls.add(img.url());
        }
        return new ArrayList<>(urls);
    } catch (Exception e) { log.warn("获取资产图片失败: {}", e.getMessage()); return List.of(); }
}
public String firstAssetImageUrl(String projectId, String assetIdsCsv) {
    List<String> urls = assetImageUrls(projectId, assetIdsCsv);
    return urls.isEmpty() ? "" : urls.getFirst();
}
```
- `startVideoGenerationAsync(req, prompt, duration, aspectRatio, String source, params)` → `(..., java.util.List<String> referenceImages, params)`。内部：
```java
String taskId = generationService.createVideoTask(
        req.getConversation(), null, prompt, model, resolution, null,
        effAspectRatio, effDuration, null,
        referenceImages == null ? List.of() : referenceImages,
        null);   // generatedImageUrl 传 null，参考图走 referenceImages
```
保留无 params 的重载 `(..., java.util.List<String> referenceImages)` 委托。

- [ ] **步骤 4：AgentTools 多图**

`AgentTools.java`：
- `refineImage`：`String picUrl` → `java.util.List<String> picUrls`。`mode = (picUrls非空) ? "edit" : null`；`generateImage(..., referenceImages=picUrls, generatedImageUrl=null)`。
- `generateVideo`：`String picUrl` → `java.util.List<String> picUrls`；`createVideoTask(..., referenceImages=picUrls, generatedImageUrl=null)`。

- [ ] **步骤 5：编译验证**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\agent-core\\pom.xml" install -q
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q
```
预期：BUILD SUCCESS。若报 `getPicUrl()`/`getPicUrls()` 不匹配，检查任务 D 步骤 1 的兼容方法是否就位。

- [ ] **步骤 6：Commit**

```bash
git add MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/PicIntentHandler.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/VideoIntentHandler.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/handler/AgentOrchestratorSupport.java \
        MoonAgent/src/main/java/com/moon/moonagent/ai/agent/AgentTools.java
git commit -m "feat(moonagent): handler 层多图——Pic/Video 取资产全部图、startVideoGeneration 走 referenceImages"
```

---

## 任务 F：前端 api + store 多图

**文件：**
- 修改：`AIStoryboardClient/src/api/agent.ts`
- 修改：`AIStoryboardClient/src/stores/agentStore.ts`

- [ ] **步骤 1：api/agent.ts —— streamChat 改 picUrls**

`api/agent.ts` 的 `streamChat`：
```ts
export async function streamChat(
  conversationId: string,
  content: string,
  picUrls: string[],
  onEvent: (e: SseEvent) => void,
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ content, picUrls: picUrls ?? [] }),
  });
  await consumeSse(res, onEvent);
}
```

- [ ] **步骤 2：agentStore.ts —— refImageUrl → refImageUrls**

`agentStore.ts`：
- Interface `AgentState` 中：
```ts
refImageUrls: string[];
setRefImageUrls: (v: string[]) => void;
addRefImageUrls: (urls: string[]) => void;
removeRefImageUrl: (index: number) => void;
uploadRefImage: (file: File) => Promise<void>;
uploadRefImages: (files: File[]) => Promise<void>;
```
- `pendingPicUrl: string | null` → `pendingPicUrls: string[]`。
- `sendMessage(content, opts?: { picUrls?: string[] })`。
- 实现：`refImageUrls: []`；`setRefImageUrls`/`addRefImageUrls`/`removeRefImageUrl` 对应 set。
- `uploadRefImage(file)` 改为追加单张到 `refImageUrls`：
```ts
uploadRefImage: async (file) => {
  const res = await agentApi.uploadImage(file, get().activeConversationId ?? undefined);
  const url = res.data.data.url;
  set((s) => ({ refImageUrls: [...s.refImageUrls, url] }));
  const cid = get().activeConversationId;
  const refMsg = { id: `tmp-ref-${Date.now()}`, conversationId: cid ?? '', role: 'user' as const, content: url, createdAt: new Date().toISOString() };
  set((s) => ({ messages: [...s.messages, refMsg] }));
},
```
- `uploadRefImages(files)`：for 循环调 `uploadRefImage`（内部已累积）。
- `sendMessage` 里 `const refBefore = get().refImageUrl` → `const refBefore = get().refImageUrls.map(u=>u)`（快照），且首会话回填时把多条图消息插回；`streamChat(..., opts?.picUrls ?? pendingPicUrls ?? refBefore ?? [], ...)`。
- `pendingPicUrl` 相关（继续完善暂存）改 `pendingPicUrls` 数组，`cancelRefine` 清数组，`resetChatState` 清数组。

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/api/agent.ts AIStoryboardClient/src/stores/agentStore.ts
git commit -m "feat(client): agentStore 参考图改多图 refImageUrls + sendMessage 携带 picUrls"
```

---

## 任务 G：前端 ChatComposer + AgentChatPanel UI

**文件：**
- 修改：`AIStoryboardClient/src/components/agent/ChatComposer.tsx`
- 修改：`AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`

- [ ] **步骤 1：ChatComposer —— 多选上传 + 计数角标 + 资产多选取全部图**

`ChatComposer.tsx`：
- store 解构：`refImageUrl, setRefImageUrl, uploadRefImage` → `refImageUrls, removeRefImageUrl, uploadRefImages`（及保留 `uploadRefImage` 如仍被内部引用）。
- `handleFile` 改多文件：`const files = Array.from(e.target.files ?? []); await uploadRefImages(files);`，`<input multiple>`。
- 预览区（`refImageUrl && (...)`) 改为 `refImageUrls.length > 0 && (...)`：第一张缩略图 + 若 `refImageUrls.length > 1` 显示 `还有 {length-1} 张`；点击展开横向列表逐张删除（`removeRefImageUrl(i)`）。
- 资产选择弹窗改多选：`assetSelectedId` → `assetSelectedIds: Set<string>`；`handleAssetConfirm` 取所选资产**全部** `images[].url` 合并：`setRefImageUrls([...refImageUrls, ...urls])`（或 `addRefImageUrls`）。

- [ ] **步骤 2：AgentChatPanel —— 同步 store 改动**

`AgentChatPanel.tsx`：
- store 解构同步 `refImageUrls` / `uploadRefImages` / `removeRefImageUrl`（该组件当前有 `refImageUrl, setRefImageUrl, uploadRefImage`，见第 18 行）。
- 上传入口 `<input multiple>`，预览区第一张 + 计数角标。

- [ ] **步骤 3：Commit**

```bash
git add AIStoryboardClient/src/components/agent/ChatComposer.tsx AIStoryboardClient/src/components/agent/AgentChatPanel.tsx
git commit -m "feat(client): ChatComposer/AgentChatPanel 多图上传+计数角标+资产多选取全部图"
```

---

## 任务 H：后端编译 + 前端 build 全量验证

- [ ] **步骤 1：后端编译**

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\agent-core\\pom.xml" install -q
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\MoonAgent\\pom.xml" compile -q
```
预期：BUILD SUCCESS。

- [ ] **步骤 2：前端 typecheck + build**

```bash
cd AIStoryboardClient
npx tsc -p tsconfig.app.json --noEmit && npm run build
```
预期：tsc 无错误，build 成功。

- [ ] **步骤 3：跑通核心逻辑自检**

后端：`AgentSendMessageRequest` 构造 `new AgentSendMessageRequest("你好", null, List.of("a.png","b.png"))` → `picUrls` 返回 `[a.png, b.png]`；`new AgentSendMessageRequest("你好", "x.png", null)` → 兼容回退 `[x.png]`。可在该 record 的测试类或编译期断言。

前端：`refImageUrls` 累积/删除/计数角标逻辑，`sendMessage` 携带 `picUrls` 数组。

- [ ] **步骤 4：Commit（如有验证改动）**

```bash
git add <验证改动文件>
git commit -m "test: 兼容回退与多图累积断言"
```

---

## 自检

**1. 规格覆盖度：**
- 设计 §三后端 → 任务 A（视觉服务多图）、B（DTO/Controller）、C（ChatService）、D（Orchestrator/PlanGraph/OrchRequest/SpecialistContext）、E（handler/Support/Tools）✅
- 设计 §四前端 → 任务 F（api/store）、G（ChatComposer/AgentChatPanel）✅
- 设计 §五错误边界（单张失败跳过、空列表退化、图改图取首张、≤9 截断已在底层）→ 任务 A/E 覆盖，≤9 截断为既有 `MAX_REFERENCE_IMAGES`（未改）✅
- 设计 §六测试 → 任务 H 编译/build 自检 ✅

**2. 占位符扫描：** 无 "待定/TODO/补充细节"，每步含真实代码或精确命令 ✅

**3. 类型一致性：**
- `OrchestrationRequest` 构造器第 3 参数：任务 D 步骤 1 改 `List<String>`，调用方 `AgentOrchestratorImpl`（步骤 3）、`PlanGraph.stateRequest`（步骤 4）同步传 List ✅
- `buildRefinedPrompt(List<String>)` / `buildVideoPlan(List<String>)`：任务 A 定义，任务 E Pic/Video 调用方同步 List ✅
- `startVideoGenerationAsync(..., List<String>, params)`：任务 E 步骤 3 定义，任务 E 步骤 2 Video 调用方同步 ✅
- `streamMessage(..., List<String>, emitter)`：任务 B Controller → 任务 C 接口/实现 → 任务 D Orchestrator，全链 List ✅
- `AgentTools.refineImage/generateVideo` 第 3 参数改 List：任务 E 步骤 4，其调用方在 handler（`safeRefineImage` → `agentTools.refineImage`）已同步 ✅

**遗漏风险提示：** `PlanGraph` resume 路径未加 `K_PIC_URLS`（resume 不带新图，仅从 checkpoint plan 的 `picUrls` 字段读，已由任务 E 的 plan 序列化覆盖）。若 `SpecialistAgent` 子类未来读 `ctx.picUrl()` 会编译告警，当前 grep 确认无消费方。
