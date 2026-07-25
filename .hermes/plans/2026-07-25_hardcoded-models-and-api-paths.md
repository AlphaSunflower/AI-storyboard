# 硬编码清理计划（二期）：大模型 & 第三方接口

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 将分散在 11 个文件中的模型名称、API 路径、模型别名等硬编码集中到配置层，使 Laozhang API 升级时只需改 1-2 个文件。

**Architecture:** 后端扩展 `AiConfigProperties` 增加 model aliases 和 endpoint paths；前端创建 `src/config.ts` 中的模型列表常量，组件统一引用。

**Tech Stack:** Spring Boot 4 + Java 21, React 19 + TypeScript 6 + Zustand 5

---

## 当前硬编码分布

### 后端：5 类硬编码

| 类型 | 位置 | 值 |
|------|------|------|
| 模型名路由 | `ImageGenerationService.java:51` | `"gemini-3-pro-image-preview"` |
| 模型名路由 | `ImageGenerationService.java:92` | `"gpt-image-2-official"` |
| 模型别名 | `VideoGenerationService.java:40-45` | `MODEL_ALIAS` Map |
| API 路径 | `ImageGenerationService.java:90` | `"/images/generations"` |
| API 路径 | `VideoGenerationService.java:81,114,116,137` | `"/videos"`, `"/video/generations/"`, `"/videos/{id}/content"` |
| 图片尺寸 | `ImageGenerationService.java:83` | `"1024x1024"` |
| 视频时长 | `VideoGenerationService.java:69` | `"8"` |
| 文件路径 | `VideoGenerationService.java:145-151` | `"uploads/videos"`, `".mp4"`, `"/api/files/videos/"` |
| 默认模型 | `AiConfigProperties.java:13-14` | `"gpt-image-2"`, `"gemini-3-flash-preview"` — 这些保留（已通过 yml 配置） |

### 前端：2 类硬编码

| 类型 | 位置 | 值 |
|------|------|------|
| 默认模型 | `projectStore.ts:56-57` | `imageModel: 'gpt-image-2'`, `videoModel: 'veo-3.1-fast'` |
| 模型选项 | `ImageRefineModal.tsx:6-7` | `IMAGE_MODELS` 数组 |
| 模型选项 | `VideoRefineModal.tsx:6-7` | `VIDEO_MODELS` 数组 |
| 模型下拉 | `LeftSidebar.tsx:204-216` | `<option>` 硬编码 |
| 模型下拉 | `ScriptInputPanel.tsx:200-212` | `<option>` 硬编码 |
| 默认模型 | `ImageRefineModal.tsx:75` | `useState('gpt-image-2')` |
| 默认模型 | `VideoRefineModal.tsx:75` | `useState('veo-3.1-fast')` |

---

## Task 1: 后端 — 扩展 AiConfigProperties 增加 endpoint paths

**Objective:** 将 API 路径从 service 代码中提取到配置

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java`
- Modify: `AIStoryboardBackend/src/main/resources/application.yml`

**Step 1: 给 AiConfigProperties 加 endpoint 字段**

```java
// 新增字段（在现有字段下方）
private String endpointImageGenerations = "/images/generations";
private String endpointVideoCreate = "/videos";
private String endpointVideoStatus = "/videos/";       // 会拼 taskId
private String endpointVideoStatusFallback = "/video/generations/";
private String endpointVideoContent = "/videos/";       // 会拼 taskId + "/content"

// getters + setters
```

**Step 2: 给 application.yml 加对应配置**

```yaml
ai:
  laozhang:
    # ... existing ...
    endpoint-image-generations: /images/generations
    endpoint-video-create: /videos
    endpoint-video-status: /videos/
    endpoint-video-status-fallback: /video/generations/
    endpoint-video-content: /videos/
```

**Step 3: 更新 ImageGenerationService 和 VideoGenerationService 使用 config 而非硬编码**

所有 `config.getBaseUrlOpenai() + "/videos"` 改为 `config.getBaseUrlOpenai() + config.getEndpointVideoCreate()`

**Verification:** `mvn compile -q` 通过

---

## Task 2: 后端 — 模型别名与路由配置化

**Objective:** 消除代码中 `if ("gemini-3-pro-image-preview".equals(...))` 和 `MODEL_ALIAS` Map

**Files:**
- Modify: `AiConfigProperties.java`
- Modify: `application.yml`
- Modify: `ImageGenerationService.java`
- Modify: `VideoGenerationService.java`

**Step 1: AiConfigProperties 增加模型分类配置**

```java
// 哪些模型走 Gemini 路径（逗号分隔）
private String geminiImageModels = "gemini-3-pro-image-preview";
// 哪些模型用 sora2 api key
private String sora2Models = "gpt-image-2-official";
// 视频模型别名映射（JSON: {"veo-3.1-fast":"veo-3.1-fast-generate-preview",...}）
private String videoModelAliases = "{\"veo-3.1-fast\":\"veo-3.1-fast-generate-preview\",\"veo-3.1\":\"veo-3.1-generate-preview\"}";

// Helper: 解析逗号分隔的 model 列表
public Set<String> getGeminiImageModelSet() {
    return Set.of(geminiImageModels.split("\\s*,\\s*"));
}
public Set<String> getSora2ModelSet() {
    return Set.of(sora2Models.split("\\s*,\\s*"));
}
public Map<String,String> getVideoModelAliasMap() {
    // parse JSON string to Map
}
```

**Step 2: application.yml 加配置**

```yaml
ai:
  laozhang:
    gemini-image-models: gemini-3-pro-image-preview
    sora2-models: gpt-image-2-official
    video-model-aliases: '{"veo-3.1-fast":"veo-3.1-fast-generate-preview","veo-3.1-fast-fl":"veo-3.1-fast-generate-preview","veo-3.1":"veo-3.1-generate-preview","veo-3.1-fl":"veo-3.1-generate-preview"}'
```

**Step 3: 改写 ImageGenerationService.generateImage()**

```java
// 旧：if ("gemini-3-pro-image-preview".equals(effectiveModel))
// 新：if (config.getGeminiImageModelSet().contains(effectiveModel))

// 旧：if ("gpt-image-2-official".equals(model))
// 新：if (config.getSora2ModelSet().contains(model))
```

**Step 4: 改写 VideoGenerationService，删除 MODEL_ALIAS 静态 Map，改用 config**

```java
// 删除：private static final Map<String, String> MODEL_ALIAS = Map.of(...);
// 新：String actualModel = config.getVideoModelAliasMap().getOrDefault(alias, alias);
```

**Verification:** `mvn compile -q` 通过

---

## Task 3: 后端 — 图片尺寸和视频时长配置化

**Objective:** 消除 `"1024x1024"` 和 `"8"` 硬编码

**Files:**
- Modify: `AiConfigProperties.java`
- Modify: `application.yml`
- Modify: `ImageGenerationService.java`
- Modify: `VideoGenerationService.java`

**Step 1: 加字段**

```java
private String defaultImageSize = "1024x1024";
private String defaultVideoDuration = "8";
```

**Step 2: yml 配置**

```yaml
default-image-size: "1024x1024"
default-video-duration: "8"
```

**Step 3: service 中替换**

`body.put("size", "1024x1024")` → `body.put("size", config.getDefaultImageSize())`
`body.put("duration", "8")` → `body.put("duration", config.getDefaultVideoDuration())`

---

## Task 4: 后端 — 视频文件路径配置化

**Objective:** 消除 `"uploads/videos"`、`".mp4"`、`"/api/files/videos/"` 硬编码

**Files:**
- Modify: `AiConfigProperties.java`
- Modify: `application.yml`
- Modify: `VideoGenerationService.java`

**Step 1: 加字段**

```java
private String videoUploadDir = "uploads/videos";
private String videoFileExtension = ".mp4";
private String videoUrlPrefix = "/api/files/videos/";
```

**Step 2: yml**

```yaml
video-upload-dir: uploads/videos
video-file-extension: .mp4
video-url-prefix: /api/files/videos/
```

**Step 3: 替换**

```java
// 旧：Paths.get("uploads/videos")
// 新：Paths.get(config.getVideoUploadDir())

// 旧：UUID.randomUUID().toString() + ".mp4"
// 新：UUID.randomUUID().toString() + config.getVideoFileExtension()

// 旧："/api/files/videos/" + filename
// 新：config.getVideoUrlPrefix() + filename
```

---

## Task 5: 前端 — 创建模型定义常量文件

**Objective:** 将分散在 5 个文件中的模型名称集中到 `src/config.ts`

**Files:**
- Modify: `AIStoryboardClient/src/config.ts`
- Modify: `AIStoryboardClient/src/stores/projectStore.ts`
- Modify: `AIStoryboardClient/src/components/ai/ImageRefineModal.tsx`
- Modify: `AIStoryboardClient/src/components/ai/VideoRefineModal.tsx`
- Modify: `AIStoryboardClient/src/components/editor/LeftSidebar.tsx`
- Modify: `AIStoryboardClient/src/components/editor/ScriptInputPanel.tsx`

**Step 1: 扩展 config.ts**

```ts
// 模型定义（单一数据源）
export const IMAGE_MODELS = [
  { value: 'gpt-image-2', label: 'GPT Image 2' },
  { value: 'gemini-3-pro-image-preview', label: 'Gemini 3 Pro Image' },
  { value: 'dall-e-3', label: 'DALL·E 3' },
] as const;

export const VIDEO_MODELS = [
  { value: 'veo-3.1-fast', label: 'Veo 3.1 Fast' },
  { value: 'veo-3.1', label: 'Veo 3.1' },
] as const;

export const DEFAULT_IMAGE_MODEL = 'gpt-image-2';
export const DEFAULT_VIDEO_MODEL = 'veo-3.1-fast';
```

**Step 2: projectStore.ts 改用常量**

```ts
// 旧：imageModel: 'gpt-image-2', videoModel: 'veo-3.1-fast',
// 新：
import { DEFAULT_IMAGE_MODEL, DEFAULT_VIDEO_MODEL } from '../config';
imageModel: DEFAULT_IMAGE_MODEL,
videoModel: DEFAULT_VIDEO_MODEL,
```

**Step 3: ImageRefineModal.tsx / VideoRefineModal.tsx 改用常量**

```tsx
// 删除本地 IMAGE_MODELS / VIDEO_MODELS 数组
// 改为 import { IMAGE_MODELS, VIDEO_MODELS, DEFAULT_IMAGE_MODEL, DEFAULT_VIDEO_MODEL }
// useState(DEFAULT_IMAGE_MODEL) 替代 useState('gpt-image-2')
```

**Step 4: LeftSidebar.tsx / ScriptInputPanel.tsx 用 map 渲染替代硬编码 option**

```tsx
// 旧：<option value="gpt-image-2">GPT Image 2</option>
//     <option value="gemini-3-pro-image-preview">Gemini 3 Pro Image</option>
// 新：{IMAGE_MODELS.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
```

**Verification:** `npx tsc --noEmit && npm run build` 通过

---

## Task 6: 全量验证

**Objective:** 确保后端和前端编译通过，无遗漏引用

**Commands:**

```bash
# 后端
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

# 确认无老的硬编码残留
cd AIStoryboardBackend/src && grep -rn '"gpt-image-2"\|"veo-3.1"\|"gemini-3-pro-image-preview"\|"/videos"\|"/images/generations"' --include="*.java" . | grep -v "AiConfigProperties\|application"

# 前端
cd AIStoryboardClient && npx tsc --noEmit && npm run build
```

---

## 变更文件总览

| 文件 | 操作 | 变动 |
|------|------|------|
| `AiConfigProperties.java` | 修改 | +8 字段 + getters/setters/helpers |
| `application.yml` | 修改 | +10 行配置 |
| `ImageGenerationService.java` | 修改 | 3 处硬编码 → config 引用 |
| `VideoGenerationService.java` | 修改 | 6 处硬编码 → config 引用, 删除 MODEL_ALIAS |
| `config.ts` | 修改 | +15 行模型常量 |
| `projectStore.ts` | 修改 | 2 行引入常量 |
| `ImageRefineModal.tsx` | 修改 | 3 行引入常量 |
| `VideoRefineModal.tsx` | 修改 | 3 行引入常量 |
| `LeftSidebar.tsx` | 修改 | 4 行 option → map |
| `ScriptInputPanel.tsx` | 修改 | 4 行 option → map |

---

## 风险 & 注意事项

1. **application.yml 中的 JSON 字符串**（video-model-aliases）需要用单引号包裹避免 YAML 解析问题
2. **AiConfigProperties 新增的 helper 方法**需要用 `@JsonIgnore` 标注或放在单独的工具类中，避免序列化干扰
3. **前端模型列表**改动可能影响运行时行为，需确认所有组件的 model 值一致
4. **不要改动 Laozhang API 的实际端点地址** — 只是把路径字符串从代码移到配置

---

## 完成标志

- 后端 `mvn compile -q` 零错误
- 前端 `tsc --noEmit` + `npm run build` 零错误
- `grep` 扫描确认 service 代码中无残留模型名/API 路径硬编码
