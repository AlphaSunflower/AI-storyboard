# AI Storyboard 交互优化实现计划

> **For Hermes:** 用 subagent-driven-development 逐任务实现（每个任务一个子代理 + 两阶段 review）。

**Goal:** 修复 5 个前端/后端交互问题：① AI Agent 图片生成支持改参数+生成个数且多图全部展示到聊天框；② 任务列表悬浮球点击后无法再收起；③ 刷新后默认进入最近修改项目（空则自动建默认项目且不可删）；④ Ctrl+S 保存项目；⑤ 未选对话时发消息自动新建对话。

**Architecture:** 后端 Spring Boot 4 + MyBatis-Plus（AI 编排走 LLM 网关），前端 React 19 + Zustand 5。图片生成链 `PicIntentHandler → AgentTools.refineImage → AgentGenerationService.generateImage → ImageGenerationService`；项目/会话在 `projectStore`/`agentStore`。

**Tech Stack:** Java 21 / Spring Boot 4 / React 19 / Zustand 5 / GSAP

---

## Task 1: AI Agent 图片生成——可改质量/数量参数 + 多图展示到聊天框

**Objective:** 让 pic 意图链把用户选的 `quality`/`n`（生成个数）真正传到生图服务，生成多张时全部落库为资产并在聊天框逐张展示。

**现状根因：**
- 前端 `AgentParamSelector` 的 `PARAM_META` 只有 size/quality，没有「生成个数」；且 pic 卡片走单选择器（无 keyPrefix），改 quality 后后端忽略。
- 后端 `PicIntentHandler.resume` 只读 `model`+`size`，`quality`/`n` 未读也未透传。
- `AgentGenerationService.generateImage` 把 quality/n 固定传 `null`，且 `ImageGenerationService.generateImage` 只返回首图（`localPaths.getFirst()`），多图丢失。
- `presentImage` 只发一张 `![生成图片](url)`，`confirm_result` 只带单 `url`。

### 1a. 后端：ImageGenerationService 增加返回全量列表的方法

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java`
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/impl/ImageGenerationServiceImpl.java`

接口新增（保留旧 `generateImage(String)` 两个重载不变，供分镜/Dify 链路零改动）：

```java
/** 生成/编辑图片，返回全部本地路径列表（n>1 时多张；edits 分支恒单张） */
List<String> generateImages(String sceneId, String prompt, String model,
                            String size, String quality, String aspectRatio,
                            List<String> referenceImages,
                            String mode, String generatedImageUrl, Integer n);
```

实现：把现有 `generateImage(..., Integer n)` 方法体改造为 `generateImages`（返回 `localPaths` 全量，不再 `.getFirst()`），`generateImage` 两个重载改为薄封装：

```java
@Override
public String generateImage(String sceneId, String prompt, String model,
        String size, String quality, String aspectRatio,
        List<String> referenceImages, String mode, String generatedImageUrl, Integer n) {
    List<String> paths = generateImages(sceneId, prompt, model, size, quality,
            aspectRatio, referenceImages, mode, generatedImageUrl, n);
    return paths.isEmpty() ? null : paths.getFirst();
}

@Override
public List<String> generateImages(String sceneId, String prompt, String model,
        String size, String quality, String aspectRatio,
        List<String> referenceImages, String mode, String generatedImageUrl, Integer n) {
    // …原方法体，末尾 return localPaths;（scene 落库逻辑不变，imageUrl=首图，imageUrls=逗号拼接）
}
```

### 1b. 后端：AgentGenerationService 透传 quality/n 并返回多图

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentGenerationService.java`
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/AgentGenerationServiceImpl.java`

接口签名改为（返回 `Map<String,Object>` 含图片列表）：

```java
Map<String, Object> generateImage(AgentConversation conversation, String sceneId,
        String prompt, String model, String size, String quality, Integer n,
        String mode, List<String> referenceImages, String generatedImageUrl);
```

实现（sceneId=null 时每张图各落一条 agent_assets）：

```java
public Map<String, Object> generateImage(AgentConversation conversation, String sceneId,
        String prompt, String model, String size, String quality, Integer n,
        String mode, List<String> referenceImages, String generatedImageUrl) {
    String effectiveSceneId = (sceneId != null && !sceneId.isBlank()) ? sceneId : null;
    List<String> urls = imageService.generateImages(effectiveSceneId,
            sanitize(prompt), sanitize(model), sanitize(size), sanitize(quality), null,
            referenceImages, mode, sanitize(generatedImageUrl),
            (n != null && n > 0) ? n : 1);
    if (effectiveSceneId == null) {
        List<String> assetIds = new ArrayList<>();
        for (String u : urls) {
            AgentAsset asset = new AgentAsset();
            asset.setConversationId(conversation.getId());
            asset.setType("image");
            asset.setUrl(u);
            asset.setPrompt(sanitize(prompt));
            asset.setModel(sanitize(model));
            asset.setStatus("completed");
            try { agentAssetMapper.insert(asset); assetIds.add(asset.getId()); }
            catch (Exception e) { log.error("图片资产落库失败(不影响已生成图)…", e); }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("imageUrls", urls);
        out.put("assetIds", assetIds);
        return out;
    }
    Map<String, Object> out = new HashMap<>();
    out.put("imageUrls", urls);
    out.put("assetIds", List.of());
    return out;
}
```

### 1c. 后端：AgentTools.refineImage 增加 quality/n 参数

**File:** Modify `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentTools.java`

`refineImage` 签名追加 `@ToolParam String quality, @ToolParam String n`，返回 `{ok, imageUrls: List<String>, assetIds: List<String>}`：

```java
public Map<String, Object> refineImage(String conversationId, String prompt, String picUrl,
        String model, String size, String quality, String n) {
    try {
        AgentConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) return error("40401", "会话不存在");
        String mode = (picUrl != null && !picUrl.isBlank()) ? "edit" : null;
        Integer nInt = null;
        if (n != null && !n.isBlank()) { try { nInt = Integer.parseInt(n.trim()); } catch (NumberFormatException ignored) {} }
        Map<String, Object> result = generationService.generateImage(
                conv, null, prompt, model, size, quality, nInt, mode, null, picUrl);
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) result.getOrDefault("imageUrls", List.of());
        if (urls.isEmpty()) return error("50202", "图片生成失败，请稍后重试");
        return Map.of("ok", true, "imageUrls", urls, "assetIds", result.getOrDefault("assetIds", List.of()));
    } catch (Exception e) { … return error("50202", …); }
}
```

### 1d. 后端：PicIntentHandler 读取 quality/n + 多图收尾

**File:** Modify `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/PicIntentHandler.java`

`resume` 分支 3（默认 generate_image）与自救重试处，读取参数并传递：

```java
String model = request.getParams().getOrDefault("model", null);
String size  = request.getParams().getOrDefault("size", null);
String quality = request.getParams().getOrDefault("quality", null);
String n      = request.getParams().getOrDefault("n", null);
Map<String, Object> result = agentTools.refineImage(request.getConversation().getId(), prompt, source, model, size, quality, n);
if (Boolean.TRUE.equals(result.get("ok"))) {
    @SuppressWarnings("unchecked")
    List<String> urls = (List<String>) result.getOrDefault("imageUrls", List.of());
    @SuppressWarnings("unchecked")
    List<String> assetIds = (List<String>) result.getOrDefault("assetIds", List.of());
    return presentImages(request, urls, assetIds, null);
}
```

自救重试 `agentTools.refineImage(...)` 同样追加 `quality, n`。

把 `presentImage` 泛化为 `presentImages`（单图即列表长度 1）：

```java
private String presentImages(OrchestrationRequest request, List<String> urls, List<String> assetIds, String promptNote) {
    String content = urls.stream().map(u -> "![生成图片](" + u + ")")
            .collect(java.util.stream.Collectors.joining("\n"));
    String display = (promptNote == null || promptNote.isBlank()) ? content : promptNote + "\n\n" + content;
    Map<String, Object> confirm = new LinkedHashMap<>();
    confirm.put("kind", "image");
    confirm.put("url", urls.isEmpty() ? "" : urls.getFirst());
    confirm.put("urls", urls);
    confirm.put("assetId", assetIds.isEmpty() ? "" : assetIds.getFirst());
    confirm.put("assetIds", assetIds);
    confirm.put("sceneCount", 0);
    confirm.put("actions", List.of(
            Map.of("id", "refine", "title", "继续完善"),
            Map.of("id", "done", "title", "满意完成")));
    support.resumeStage(request, "正在生成图片…", Map.of("content", display, "confirm", confirm, "sceneCount", -1L));
    return display;
}
```

### 1e. 前端：AgentParamSelector 增加「生成个数」选项

**File:** Modify `AIStoryboardClient/src/components/agent/AgentParamSelector.tsx`

- `PARAM_META` 末尾追加 `{ key: 'n', label: '生成个数', field: 'n', defaultField: 'nDefault' }`。
- `parseParamLists` 对 `n` 特判（网关 params 的 `n` 是 `{min,max,default}` 对象而非数组）：

```ts
for (const meta of PARAM_META) {
  if (meta.key === 'n') {
    const nObj = p['n'];
    if (nObj && typeof nObj === 'object' && !Array.isArray(nObj)) {
      const lo = Number(nObj.min ?? 1), hi = Number(nObj.max ?? nObj.min ?? 1);
      const opts = Array.from({ length: Math.max(0, hi - lo + 1) }, (_, i) => String(lo + i));
      if (opts.length) out['n'] = { options: opts, default: nObj.default != null ? String(nObj.default) : opts[0] };
    }
    continue;
  }
  const arr = p[meta.field];
  if (Array.isArray(arr) && arr.length > 0) { …原逻辑… }
}
```

> 说明：`n` 键在 `selected`/`initialValues`/`onParamsChange` 全链路已按 `PARAM_META` 通用处理，零额外改动。视频模型无 `n` 能力 → 不渲染，零回归。

### 1f. 前端：confirm_result 支持多图展示

**Files:**
- Modify: `AIStoryboardClient/src/api/agent.ts` — `SseEvent` 增加 `urls?: string[]; assetIds?: string[];`
- Modify: `AIStoryboardClient/src/stores/agentStore.ts` — `ConfirmResultInfo` 增加 `urls?: string[]; assetIds?: string[];`
- Modify: `AIStoryboardClient/src/components/agent/ConfirmResultCard.tsx` — 渲染 `const imgs = info.urls && info.urls.length ? info.urls : (info.url ? [info.url] : []);` 用 `imgs.map(url => <img …/>)` 网格展示；「继续完善」仍作用于首图（`info.url`，edits 源图）。

`sendMessage` / `submitHumanInput` 两处 `case 'confirm_result'` 的 `set({ confirmResult: e as ConfirmResultInfo })` 已透传新增字段，零改动。

### Task 1 验证

- 后端编译：`export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"` + mvn compile（见 CLAUDE.md）。
- 前端：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build`。
- 手测：Moon 智能体「生成一张 X 图」→ 确认卡片出现「生成个数」下拉 → 选 3 → 提交 → 聊天框出现 3 张图（各自可点灯箱），📁 产出素材含 3 条 image 资产。

---

## Task 2: 任务列表悬浮球点击后无法再收起

**Objective:** 点击一次展开、再点收起，来回可切换。

**File:** Modify `AIStoryboardClient/src/components/common/TaskFab.tsx`

**根因:** `mounted` + `closing` 双标志 + GSAP `revertOnUpdate` 的状态机存在卡死路径——`windowOpen` 互斥 effect 在面板未挂载时也会 `setClosing(true)`，而退场 `useGSAP` 因 `!mounted` 提前 return、不会把 `closing` 复位，导致 `closing` 恒为 true；此后 `toggle` 的 `if (!closing) setClosing(true)` 恒空转，面板无法关闭（也无法正常打开）。

**修复：** 删除 `closing` 状态，改为单一 `open` 布尔，`toggle` 无条件翻转；退场动画改为直接卸载（省 0.22s 淡出，可后续用 CSS transition 补）。

```tsx
const [open, setOpen] = useState(false);
const toggle = () => setOpen(v => !v);

// 互斥 & 外部点击：直接关
useEffect(() => { if (windowOpen) setOpen(false); }, [windowOpen]);
useEffect(() => {
  if (!open) return;
  const onDown = (e: PointerEvent) => {
    if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
  };
  document.addEventListener('pointerdown', onDown);
  return () => document.removeEventListener('pointerdown', onDown);
}, [open]);
```

- 入场动画 `useGSAP` 改为依赖 `[open]`（`if (!open) return;`），删除退场 useGSAP 与 `closing` 相关分支。
- 渲染条件 `{mounted && (…)}` → `{open && (…)}`。
- `handleItemClick` 里 `setClosing(true)` → `setOpen(false)`。

### Task 2 验证

`npx tsc -p tsconfig.app.json --noEmit && npm run build`；手测反复点 ⚡ 球多次，展开/收起始终切换，且打开智能体抽屉时任务面板正确关闭。

---

## Task 3: 刷新默认进入最近修改项目 + 空则建默认项目（不可删）

**Objective:** 刷新后不再停留在「选择项目」空态；始终进入最近修改的项目；无项目时自动建「默认项目」；最后一个项目禁止删除。

### 3a. 后端：最后一个项目禁止删除

**File:** Modify `AIStoryboardBackend/src/main/java/com/storyboard/service/impl/ProjectServiceImpl.java`

`delete` 方法在删前加守卫（`findByUserId` 已按 `updated_at DESC` 排序）：

```java
if (projectMapper.findByUserId(userId).size() <= 1) {
    throw new BusinessException(40301, "至少保留一个项目");
}
```

### 3b. 前端：刷新后自动进入最近项目 / 建默认项目

**File:** Modify `AIStoryboardClient/src/pages/EditorPage.tsx`

把 mount effect 改为异步初始化（`loadProjects` 返回后 `projects` 已按 `updated_at DESC` 排好，`projects[0]` 即最近修改）：

```tsx
useEffect(() => {
  const init = async () => {
    await loadProjects();
    fetchAiModels();
    const { projects, currentProject, createProject } = useProjectStore.getState();
    if (!currentProject) {
      if (projects.length > 0) {
        await loadProject(projects[0].id);
      } else {
        const p = await createProject('默认项目', 'movie', '16:9');
        await loadProject(p.id);
      }
    }
    checkDraft().then((draft) => { if (draft) { setDraftProject(draft); setShowDraftBanner(true); } }).catch(() => {});
    // …原 URL token 登录逻辑保留…
  };
  init();
}, [loadProjects, checkDraft, loadProject, createProject]);
```

### 3c. 前端：禁用最后一个项目的删除按钮

**File:** Modify `AIStoryboardClient/src/components/layout/AppHeader.tsx`

下拉列表中 🗑️ 按钮加 `disabled={projects.length <= 1}`，禁用时 `opacity: 0.35`、`cursor: 'not-allowed'`、`title="默认项目不可删除"`（后端 3a 已兜底）。

### Task 3 验证

后端 compile + 前端 build；手测：清空某用户所有项目后刷新 → 自动进入「默认项目」，可加分镜、可开智能体；AppHeader 下拉中仅剩 1 个项目时 🗑️ 灰置；直接调 DELETE 接口删除最后一个项目 → 返回 40301。

---

## Task 4: Ctrl+S 保存项目

**Objective:** 快捷键保存（等价于点「💾 保存」）。

**File:** Modify `AIStoryboardClient/src/components/layout/AppHeader.tsx`

抽出 `handleSave` 并在 `useEffect` 挂 window keydown：

```tsx
const handleSave = () => {
  if (currentProject) updateProject(currentProject.id, { status: 'active' });
};

useEffect(() => {
  const onKey = (e: KeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
      e.preventDefault();
      handleSave();
    }
  };
  window.addEventListener('keydown', onKey);
  return () => window.removeEventListener('keydown', onKey);
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [currentProject, updateProject]);
```

保存按钮 `onClick={() => updateProject(...)}` 改为 `onClick={handleSave}`。

### Task 4 验证

`npx tsc -p tsconfig.app.json --noEmit`；手测：编辑后 Ctrl+S → 项目状态变为 active（下拉列表「草稿」标签消失）。

---

## Task 5: 未选对话时发消息自动新建对话

**Objective:** 选定了项目但未选对话时，发送消息不再被「吃掉」，而是自动建会话并继续沟通。

**File:** Modify `AIStoryboardClient/src/stores/agentStore.ts`

`sendMessage` 开头守卫改造（`createConversation` 内部会 `selectConversation` 选中新会话）：

```ts
sendMessage: async (content, opts?: { picUrl?: string }) => {
  if (get().streaming || !content.trim()) return;
  let id = get().activeConversationId;
  if (!id) {
    const projectId = useProjectStore.getState().currentProject?.id;
    if (!projectId) { set({ streamError: '请先在左上角选择一个项目' }); return; }
    await get().createConversation();   // 创建 + 选中
    id = get().activeConversationId;
  }
  if (!id) return;
  // …原逻辑不变（乐观 user 气泡、assistant 占位、streamChat…）
},
```

> 说明：`AgentChatPanel.handleSend` 的发送按钮当前未因「无会话」禁用，store 侧修复后发送即自动建会话，无需改 UI。文本域/发送按钮的 `disabled` 逻辑保持。

### Task 5 验证

前端 build；手测：进入项目但未选会话（`activeConversationId=null`）→ 输入消息发送 → 会话列表自动出现「新对话」并选中 → 消息正常流式返回；无项目时发送 → 显示「请先选择项目」提示。

---

## 风险 / 权衡 / 待确认

- **「默认项目」语义**：本计划采用「最后一个项目不可删」作为「默认项目不可删」的通用实现（更稳、永不落入无项目态）。若你要的是「永久标记某个默认项目、即使有多个项目也不可删」，需在 `projects` 加 `is_default` 列 + migration + 建默认时置位，改动更大——按需再扩。
- **生成个数与图改图**：`n` 仅纯文生图（无参考图）分支生效；图改图（edits API）恒单张。图改图卡片仍会显示「生成个数」但会被忽略——如需按源图隐藏该选项，需前端额外感知卡片是否有源图，暂不实现（可后续补）。
- **任务球退场动画**：从 0.22s 淡出改为即时卸载，视觉略降；如需保留可后续加 CSS transition（非本次范围）。
- **草稿恢复横幅**：自动进入最近项目后，「恢复草稿」横幅大概率冗余（最近项目通常即草稿）。本次保留原逻辑不删，确认无副作用后可再清理。

## 提交策略（遵循用户偏好）

- master 直接开发；`git add` 只加上述计划内文件；工作区其余未提交修改原样保留。
- 每完成一个 Task 跑一次对应验证命令后再进入下一个。
