# 理解模型 + 剧本输入设置精简 实现计划

> **For Hermes:** 使用 subagent-driven-development 技能按任务逐个实现本计划。
>
> **状态：** 设计已澄清（素材仅图片；理解模型 = 网关 `vision` 类型；参数网关已具备，见 §0）。

**Goal:** 剧本输入面板精简为「只保留模型选择」，并新增「理解模型」——用户上传多张参考图时，理解模型先看图生成描述，再连同用户提示词交给分镜模型生成分镜；未上传时直接走后端默认模型。

**Architecture:** 两段式 LLM 链。理解模型（网关 `vision` 类型，多模态 `Media` 输入）→ 输出图一/图二…文字描述 → 拼入 user prompt → 分镜模型（现有 `defaultVisionModel`，`ScriptGenerationService`）生成分镜。理解模型与上传约束参数全部复用网关既有能力，**网关零改动**。

**Tech Stack:** Spring Boot 4（Spring AI ChatClient 多模态）+ React 19 / TypeScript / Zustand。

---

## 0. 关键结论（调研确定，减少返工）

| 问题 | 结论 |
|------|------|
| 理解模型从哪来 | 网关 `/v1/models?type=vision`（网关 `model_route.type` 已支持 `vision`，实体注释「vision（图片视频理解）」） |
| 理解模型参数（图片最大数量 / 单张最大 MB） | 网关 `model_params` 表**已存在** `ref_images_min/max` + `max_image_size_mb`（V6 migration），`GatewayRoutingServiceImpl.buildParams` 已下发为 `params.refImages{min,max}` + `params.maxImageSizeMB` |
| 网关要不要改 | **不需要**。后端 `fetchModels(type)` 已支持任意 type 透传 params JSON 字符串；只需后端多拉一次 `vision` |
| 上传素材 | 只图片（用户已确认）；视频暂不支持 |
| 分镜模型 | 保持后端默认 `config.getDefaultVisionModel()`，不做前端选择器 |
| 现有单图「风格参考图」 | 是死功能（`_refImageFile` 上传后未传给后端）→ 本次替换为多图上传 |

后端多模态调用范式（复用现有 `ImageRefinePromptServiceImpl`）：

```java
Media media = Media.builder().mimeType(MimeType.valueOf("image/png")).data(dataUri).build();
UserMessage msg = UserMessage.builder().text("...").media(media1, media2, ...).build();
chatClient.prompt().system(sp).messages(msg).call().content();
```

---

## 任务 1：后端 DTO + 接口签名扩展

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/GenerateScriptRequest.java`

将 `String referenceImageUrl` 替换为多图列表 + 理解模型名：

```java
public record GenerateScriptRequest(
    String projectId,
    String scriptText,
    String creationType,
    String customTypeDesc,
    String aspectRatio,
    String model,                 // 分镜模型（null=后端默认）
    String understandingModel,    // 理解模型（null=后端默认 vision 模型）
    java.util.List<String> referenceImages   // 参考图 base64 data URI 列表（可为 null/空）
) {}
```

---

## 任务 2：Service 接口 + 实现（两段式生成）

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ScriptGenerationService.java`
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/impl/ScriptGenerationServiceImpl.java`

**Step 1 — 接口加参（两个方法都加）：**

```java
List<Map<String, Object>> generateScenes(String projectId, String scriptText,
        String creationType, String customTypeDesc, String aspectRatio,
        String model, String understandingModel, List<String> referenceImages);

Map<String, Object> generateAndSaveScenes(String projectId, String scriptText,
        String creationType, String customTypeDesc, String aspectRatio,
        String model, String understandingModel, List<String> referenceImages);
```

**Step 2 — 实现两段式（generateScenes 内）：**

```java
// 1) 有参考图 → 先调理解模型看图，产出「图一/图二…」描述
String understanding = null;
if (referenceImages != null && !referenceImages.isEmpty()) {
    understanding = callUnderstandingModel(understandingModel, referenceImages);
}
// 2) 拼 user prompt
String userPrompt = buildUserPrompt(scriptText, understanding);
// 3) 调分镜模型（model 为 null 走默认 vision 模型，逻辑不变）
String content = callLLM(model, systemPrompt, userPrompt);
```

**Step 3 — 新增理解模型调用（多模态，复用 Media 范式）：**

```java
private String callUnderstandingModel(String model, List<String> referenceImages) {
    List<Media> medias = new ArrayList<>();
    for (int i = 0; i < referenceImages.size(); i++) {
        medias.add(Media.builder().mimeType(MimeType.valueOf("image/png"))
                .data(referenceImages.get(i)).build());
    }
    UserMessage msg = UserMessage.builder()
            .text("请逐一描述以下参考图的内容与风格（主体、构图、色调、光线、氛围、画风），"
                  + "用「图一：…」「图二：…」的格式输出，供后续分镜生成参考。")
            .media(medias.toArray(new Media[0]))
            .build();
    ChatClient.ChatClientRequestSpec spec = chatClient().prompt()
            .system("你是分镜前期视觉理解助手，擅长提炼参考图的关键视觉要素。")
            .messages(msg);
    if (model != null && !model.isBlank()) {
        spec = spec.options(OpenAiChatOptions.builder().model(model));
    }
    return spec.call().content();
}

private String buildUserPrompt(String scriptText, String understanding) {
    String base = "请根据以下剧本内容生成分镜脚本，每个分镜包含：镜头号、剧本内容、生图提示词（格式：【镜头构图】→【场景主体】→【环境细节/道具】→【光线与色彩】→【氛围情绪】→【画质/风格】）、生视频提示词、反向提示词、机位和运动、镜头类型、声音设计。\n\n";
    if (understanding != null && !understanding.isBlank()) {
        base += "参考图视觉要素（请在分镜中体现这些风格与要素）：\n" + understanding + "\n\n";
    }
    return base + "剧本：\n" + scriptText;
}
```

需要新增 import：`org.springframework.ai.chat.messages.UserMessage`、`org.springframework.ai.content.Media`、`org.springframework.util.MimeType`、`java.util.ArrayList`。

> ⚠️ 现有 `chatClient()` 懒加载固定默认 vision 模型；理解模型默认也复用同一 ChatClient 实例，仅当 `model` 非空时用 `.options(...)` 单次覆盖——与现有 `callLLM` 的覆盖方式一致。

**Step 4 — 理解模型默认值（已确认决策 1）：** 在 `AiConfigProperties` 新增 `defaultUnderstandingModel` 字段（默认 `"gemini-3-flash-preview"`，`.env` `ai.laozhang.default-understanding-model` 可覆盖）。`callUnderstandingModel` 中当传入 `model` 为 null/空时回退 `config.getDefaultUnderstandingModel()` 而非硬编码。

---

## 任务 3：Controller 透传 + 模型列表加 vision

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/controller/AIController.java`

**Step 1 — generateScript 传新参：**

```java
return ApiResponse.ok(scriptService.generateAndSaveScenes(
    request.projectId(), request.scriptText(), request.creationType(),
    request.customTypeDesc(), request.aspectRatio(), request.model(),
    request.understandingModel(), request.referenceImages()
));
```

**Step 2 — aiModels 加 understandingModels（vision 类型）：**

```java
result.put("imageModels", gatewayModelService.fetchModels("image"));
result.put("videoModels", gatewayModelService.fetchModels("video"));
result.put("understandingModels", gatewayModelService.fetchModels("vision"));
```

---

## 任务 4：前端配置（类型 + 静态兜底）

**Files:**
- Modify: `AIStoryboardClient/src/config.ts`

```ts
/** 理解模型（vision 类型）参数能力：上传参考图约束 */
export interface UnderstandingModelParams {
  refImages?: { min?: number; max?: number };
  maxImageSizeMB?: number;
}

// ModelOption.params 联合类型追加
export interface ModelOption {
  value: string;
  label: string;
  params?: ImageModelParams | VideoModelParams | TextModelParams | UnderstandingModelParams | null;
}

export const UNDERSTANDING_MODELS = [
  { value: 'gemini-3-flash-preview', label: 'Gemini 3 Flash' },
] as const;

export const DEFAULT_UNDERSTANDING_MODEL: string = UNDERSTANDING_MODELS[0].value;
```

> 静态兜底仅网关不可用/未配置 `vision` 路由时使用；网关可用时被 `understandingModelOptions` 替换。

---

## 任务 5：前端 API 层

**Files:**
- Modify: `AIStoryboardClient/src/api/ai.ts`

```ts
generateScript: (data: {
  projectId: string;
  scriptText: string;
  creationType: string;
  customTypeDesc?: string;
  aspectRatio: string;
  model?: string;
  understandingModel?: string;
  referenceImages?: string[];
}) => client.post('/ai/generate-script', data),

aiModels: () =>
  client.get<ApiResponse<{
    imageModels: GatewayModelOption[];
    videoModels: GatewayModelOption[];
    understandingModels: GatewayModelOption[];
  }>>('/ai/models'),
```

---

## 任务 6：前端 store

**Files:**
- Modify: `AIStoryboardClient/src/stores/projectStore.ts`

**Step 1 — 状态 + 选项：**

```ts
understandingModel: string;
understandingModelOptions: ModelOption[];
setUnderstandingModel: (m: string) => void;
```

初始值 `understandingModel: DEFAULT_UNDERSTANDING_MODEL`、`understandingModelOptions: UNDERSTANDING_MODELS.map(m => ({ value: m.value, label: m.label }))`。

**Step 2 — fetchAiModels 解析（复用 safeParseParams）：**

```ts
const understandingModels = res.data.data?.understandingModels ?? [];
const parsedUnderstanding: ModelOption[] = understandingModels.map((m) => ({
  ...m, params: m.params ? safeParseParams(m.params) : null,
}));
set((s) => ({
  ...,
  understandingModelOptions: parsedUnderstanding.length ? parsedUnderstanding : s.understandingModelOptions,
}));
```

**Step 3 — generateScript 签名加参并透传：**

```ts
generateScript: async (projectId, scriptText, creationType, aspectRatio, model, understandingModel, referenceImages) => {
  ...
  await aiApi.generateScript({ projectId, scriptText, creationType, aspectRatio, model, understandingModel, referenceImages });
  ...
}
```

---

## 任务 7：剧本输入面板（精简 + 理解模型 + 多图上传）

**Files:**
- Modify: `AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx`

**Step 1 — 删掉「其他部分」：**
- 生图设置：删除「生图尺寸」「生图质量」「生成数量」三个控件（连同 `sizeOptions/qualityOptions/nRange/nOptions` 相关计算），只留「生图模型」下拉。
- 生视频设置：删除「时长和画幅」（`VideoPresetSelector`），只留「生视频模型」下拉。
- 相应移除 store 解构中不再使用的 `imageSize/imageQuality/imageN/videoPreset/setImageSize/setImageQuality/setImageN/setVideoPreset`（未用变量会触发 `tsc` noUnusedLocals 报错）。

**Step 2 — 新增「理解模型」选择器（放生图/生视频之间，标题 `🧠 理解设置`）：**

```tsx
<div style={sectionHeaderStyle}>🧠 理解设置</div>
<div>
  <label style={labelStyle}>理解模型</label>
  <select value={understandingModel} onChange={(e) => setUnderstandingModel(e.target.value)}
          style={{ ...sharedInputStyle, cursor: 'pointer' }}>
    {understandingModelOptions.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
  </select>
</div>
```

**Step 3 — 多图上传替换原单图「风格参考图」：**

- 用 `<input type="file" accept="image/*" multiple>` 收集多张图，FileReader 转 base64 data URI 存 `string[]`。
- 上传数量/大小上限来自当前理解模型 params：

```tsx
const uParams = understandingModelOptions.find(m => m.value === understandingModel)?.params as UnderstandingModelParams | undefined;
const maxCount = uParams?.refImages?.max ?? REFERENCE_LIMITS.image.maxCount;
const maxSizeMB = uParams?.maxImageSizeMB ?? REFERENCE_LIMITS.image.maxSizeMB;
```

- 超出数量/大小（`file.size > maxSizeMB * 1024 * 1024`）时直接拦截并提示（不静默丢弃）。
- 缩略图预览列表（`objectFit: contain` 完整显示，符合项目图片完整显示偏好）。

**Step 4 — handleGenerate 传参：**

```tsx
await generateScript(projectId, scriptText, creationType, preset.aspectRatio, undefined, understandingModel, refImages);
```

> `resolveVideoPreset(videoPreset)` 仍保留（`createProject` 用其 aspectRatio；videoPreset 回落 `DEFAULT_VIDEO_PRESET`）。

---

## 验证

```bash
# 后端（JAVA_HOME 必须 Windows 路径）
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

# 前端（solution-style tsconfig，必须 -p tsconfig.app.json）
cd /e/Desktop/AI-storyboard/AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```

人工冒烟：
1. 网关 admin 配置一个 `vision` 类型路由 + `model_params` 行（`ref_images_max` / `max_image_size_mb` 填值）。
2. 编辑页刷新 → 理解模型下拉出现网关模型；上传 2 张图 → 生成分镜 → 日志确认先调理解模型再调分镜模型。
3. 不传图 → 直接分镜模型（行为与现状一致）。
4. 上传超过数量/大小 → 前端拦截提示。

---

## 已确认决策（2026-08-13 用户逐项确认）

1. **理解模型默认模型名**：配一个专门的理解模型（vision 类型，经网关 admin 配置），后端加独立配置 `defaultUnderstandingModel`（AiConfigProperties，`.env` 可覆盖，默认先取 `defaultVisionModel`）。不再与分镜模型共用同一硬编码。
2. **上传参考图限制**：每个模型有各自的上传参考图限制（网关 `model_params` 的 `refImages.max` + `maxImageSizeMB`），前端按**当前选中的理解模型** params 拦截，不得超过该模型限制。未配置时回退静态 `REFERENCE_LIMITS.image`。
3. **「其他部分不要」范围**：仅剧本输入面板（ScriptInputPanel）。分镜卡片/预览面板的逐分镜参数选择器（「卡片参数选择」）不动。
4. **素材类型**：只图片（视频暂不支持）。

## 风险 / 权衡

1. **多图 base64 体积**：多张图 base64 内联进 /v1/chat/completions 请求体，可能触碰网关/上游请求体上限（网关已有 `maxRequestBodyMB`）。首版按「数量 × 单张上限」约束，超限前端提示。
2. **理解模型两步延迟**：有图时多一次 LLM 调用（理解 + 分镜），耗时约为无图两倍。可接受（仅上传时触发）。
