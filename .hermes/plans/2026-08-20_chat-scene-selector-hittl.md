# 对话中触发分镜列表 + 交互式选择 + HITL 操作

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 用户在对话框输入"展示所有分镜"/"查看此项目的分镜"/"生成对应分镜的视频/图片"等文字时，系统在对话中弹出项目分镜列表（带勾选框），用户勾选后点击"优化方案"/"生成图片"/"生成视频"/"跳过"进行操作。

**Architecture:** 复用现有 `human_input` 事件 + `HumanInputCard` 资产勾选 UI，将分镜映射为 `AssetOption` 格式下发。前端 `HumanInputCard` 仅对 `asset-confirm`/`asset-skip` 传递勾选 ID，需扩展为所有 action 均传递。

**Tech Stack:** Java (Spring Boot 4), TypeScript (React 19), existing HITL infrastructure

---

## 现状分析

### 现有流程
- `intent-scene-review` 规则：`分析/处理/审查 + 分镜` → `SceneReviewIntentHandler.handle()`
- `handle()` 获取项目分镜 → 分析状态 → 发 `human_input` 事件（纯文本摘要 + 4 个按钮）
- 问题：用户无法在对话中选择特定分镜，只能通过 "+" 按钮的 `SceneSelectorModal` 选

### 复用点
- `HumanInputCard` 已有 `info.assets` 勾选列表 UI（checkbox + 缩略图 + 名称 + 类型标签）
- `AssetOption` 接口：`{id, name, type, image}` — 分镜可直接映射
- `submitHumanInput(actionId, customText, params, assetIds)` 已支持传递勾选 ID

### 需要改的
1. 前端 `HumanInputCard.handleActionClick`：仅 `asset-confirm`/`asset-skip` 传递 `assetIds`，其他 action 不传 → 扩展为：有 `info.assets` 时所有 action 均传
2. 后端 `SceneReviewIntentHandler.handle()`：当前发纯文本 → 改为带 `assets` 字段的 `human_input`
3. 后端 `resume()`：从 `assetIds` 提取选中的分镜 ID，而非从 checkpoint plan 的 `sceneNums`
4. 意图识别：增加关键词覆盖"展示分镜"/"查看分镜"/"生成分镜图片/视频"

---

### Task 1: 扩展 HumanInputCard — 有 assets 时所有 action 均传递勾选 ID

**Objective:** 让 `review-optimize`/`review-gen-image`/`review-gen-video`/`review-skip` 也能收到用户勾选的分镜 ID。

**Files:**
- Modify: `AIStoryboardClient/src/components/agent/HumanInputCard.tsx:38-48`

**实现:**

```tsx
// 原代码
const handleActionClick = (a: { id: string; title: string }) => {
    if (a.id === 'asset-confirm') {
      submitHumanInput(a.id, undefined, selectedParams, Array.from(selectedAssets));
    } else if (a.id === 'asset-skip') {
      submitHumanInput(a.id, undefined, selectedParams, []);
    } else if (a.id === 'custom') {
      setCustomOpen(true);
    } else {
      submitHumanInput(a.id, undefined, selectedParams);
    }
};

// 改为：有 assets 时，所有非 custom action 均传递勾选 ID
const handleActionClick = (a: { id: string; title: string }) => {
    if (a.id === 'custom') {
      setCustomOpen(true);
      return;
    }
    const hasAssets = info.assets && info.assets.length > 0;
    if (a.id === 'asset-confirm') {
      submitHumanInput(a.id, undefined, selectedParams, Array.from(selectedAssets));
    } else if (a.id === 'asset-skip') {
      submitHumanInput(a.id, undefined, selectedParams, []);
    } else if (hasAssets) {
      // 有资产/分镜列表时，所有 action 均携带勾选 ID
      submitHumanInput(a.id, undefined, selectedParams, Array.from(selectedAssets));
    } else {
      submitHumanInput(a.id, undefined, selectedParams);
    }
};
```

**验证:** `npx tsc -p tsconfig.app.json --noEmit` 通过。

---

### Task 2: 后端 handle() — 发送分镜列表作为 assets

**Objective:** `SceneReviewIntentHandler.handle()` 在 `human_input` 事件中携带 `assets` 字段，前端渲染为可勾选的分镜列表。

**Files:**
- Modify: `MoonAgent/.../handler/SceneReviewIntentHandler.java` 的 `handle()` 方法

**实现要点:**

1. 将 `List<Scene>` 映射为 `List<Map<String, Object>>`（AssetOption 格式）：
```java
private List<Map<String, Object>> buildSceneOptions(List<Scene> scenes) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Scene s : scenes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", "分镜" + s.getSceneNumber() + ": " +
              (s.getScriptContent() != null && s.getScriptContent().length() > 30
               ? s.getScriptContent().substring(0, 30) + "…" : s.getScriptContent()));
        m.put("type", "scene");
        if (s.getImageUrl() != null && !s.getImageUrl().isBlank()) m.put("image", s.getImageUrl());
        out.add(m);
    }
    return out;
}
```

2. 修改 `handle()` 中的 `runHITLStage` 调用，最后一个参数从 `List.of()` 改为 `buildSceneOptions(targetScenes)`：
```java
return support.runHITLStage(request, null, new AgentOrchestratorSupport.StagePlan(
        planText, "review-action",
        List.of(Map.of("projectId", projectId)),
        "human_input",
        actions,
        List.of(), Map.of(), Map.of(), List.of(), List.of(),  // models, recommended, reasons, imageModels, videoModels
        buildSceneOptions(targetScenes)));  // assets = 分镜列表
```

3. 注意：`StagePlan` 构造器需要确认支持 `assets` 参数。检查现有构造器：
```java
// 已有构造器（最后一个参数是 assets）
public StagePlan(String planText, String action, List<Map<String, Object>> planPayload,
                 String eventName, List<Map<String, Object>> actions,
                 List<Map<String, Object>> models,
                 Map<String, String> recommended, Map<String, String> reasons,
                 List<Map<String, Object>> imageModels, List<Map<String, Object>> videoModels,
                 List<Map<String, Object>> assets)
```

**验证:** `mvn compile -q` 通过。

---

### Task 3: 后端 resume() — 从 assetIds 提取选中的分镜

**Objective:** `resume()` 使用前端传来的 `assetIds`（用户勾选的分镜 ID）替代 checkpoint plan 中的 `sceneNums`。

**Files:**
- Modify: `MoonAgent/.../handler/SceneReviewIntentHandler.java` 的 `resume()` 方法

**实现要点:**

```java
@Override
public String resume(OrchestrationRequest request, AgentCheckpoint checkpoint) {
    String action = request.getAction();
    String projectId = request.getConversation().getProjectId();

    // 优先用前端传来的 assetIds（用户勾选的分镜 ID），回退到 checkpoint 的 sceneNums
    List<String> selectedIds = request.getAssetIds();
    List<Scene> allScenes;
    try {
        allScenes = storyboardClient.getProjectScenes(projectId);
    } catch (Exception e) {
        return support.sendFriendlyError(request, e.getMessage(), "获取分镜列表失败。");
    }
    if (allScenes == null) allScenes = List.of();

    List<Scene> targetScenes;
    if (selectedIds != null && !selectedIds.isEmpty()) {
        targetScenes = allScenes.stream()
                .filter(s -> selectedIds.contains(s.getId()))
                .toList();
    } else {
        // 回退：从 checkpoint 提取 sceneNums
        List<Integer> sceneNums = extractSceneNums(checkpoint);
        if (!sceneNums.isEmpty()) {
            targetScenes = allScenes.stream()
                    .filter(s -> sceneNums.contains(s.getSceneNumber()))
                    .toList();
        } else {
            targetScenes = allScenes;
        }
    }
    // ... switch(action) 不变
}
```

**验证:** `mvn compile -q` 通过。

---

### Task 4: 意图识别 — 增加关键词

**Objective:** "展示所有分镜"/"查看此项目的分镜"/"生成分镜图片/视频" 也能触发 `intent-scene-review`。

**Files:**
- Modify: `MoonAgent/.../impl/IntentRecognitionServiceImpl.java` 的 `RULE_TABLE`

**实现:**

```java
Map.entry("intent-scene-review", java.util.regex.Pattern.compile(
    "分析.{0,6}分镜|处理.{0,6}分镜|审查.{0,6}分镜|review.{0,6}scene"
    + "|展示.{0,6}分镜|查看.{0,6}分镜|列出.{0,6}分镜|所有分镜"
    + "|生成.{0,8}分镜.{0,6}(图|视频)|分镜.{0,8}生成")),
```

**验证:** `mvn compile -q` 通过。

---

### Task 5: 前端 SceneSelectorModal 保持不变

**Objective:** "+" 按钮的 `SceneSelectorModal` 仍可用于直接上传分镜，与对话触发的流程共存。

无需改动，跳过。

---

## Files Summary

| 文件 | 改动 |
|------|------|
| `AIStoryboardClient/src/components/agent/HumanInputCard.tsx:38-48` | 有 assets 时所有 action 传递勾选 ID |
| `MoonAgent/.../handler/SceneReviewIntentHandler.java` | handle() 发 assets；resume() 用 assetIds |
| `MoonAgent/.../impl/IntentRecognitionServiceImpl.java` | RULE_TABLE 增加关键词 |

## Risks

1. **HumanInputCard 行为变更**: 扩展 `assetIds` 传递范围可能影响现有资产选择流程。但 `asset-confirm`/`asset-skip` 走原有分支不受影响，其他 action 原本不传 `assetIds`（空数组），现在传了也不会破坏后端（后端只在需要时读取）。
2. **分镜图片缩略图**: `scene.imageUrl` 可能是相对路径（`/api/files/...`），前端 `assetUrl()` 已处理，无需额外适配。
3. **大量分镜**: 项目有 50+ 分镜时勾选列表很长。ponytail: 先不做分页，后续按需加。
