# 大模型适配器模式重构计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 将三个 Service 中的 if/else 路由和 API 调用逻辑抽取为可插拔的适配器，新增模型/提供商只需实现接口并注册为 Spring Bean。

**Architecture:** 策略模式 + 适配器模式。每个模型提供商实现统一接口（ImageProvider / VideoProvider / ScriptProvider），Service 层变为轻量编排器，遍历已注册的 Provider 找到匹配的实现并委托。

**Tech Stack:** Spring Boot 4 + Java 21, Spring `@Component` 自动注册

---

## 当前问题

### 现在的代码结构

```
ImageGenerationService.java
├── generateImage()           ← 模型路由 + 生图逻辑 + 文件存储 混在一起
│   ├── if (gemini) → callGeminiImage()
│   └── else        → callOpenAIImage()
├── callOpenAIImage()          ← OpenAI 特有格式
└── callGeminiImage()          ← Gemini 特有格式

VideoGenerationService.java
├── createVideoTask()          ← 只能调一个 BaseURL
├── pollVideoTask()            ← 硬编码两个轮询端点
└── callGet()

ScriptGenerationService.java
├── generateScenes()           ← 只能调一个 Vision endpoint
└── callVisionApi()
```

### 痛点

| 场景 | 当前做法 | 问题 |
|------|---------|------|
| 新增一个模型 | 在 Service 里加 if 分支 | 改核心逻辑，容易引入 bug |
| 换一个提供商 | 改 Service 里的 URL/格式/鉴权 | 要改 3 个 Service |
| 同时支持两个提供商 | 不可能，代码耦合在单一 BaseURL 上 | 架构限制 |
| 单元测试 | 要 mock 整个 Service | Provider 逻辑无法独立测试 |

---

## 目标架构

```
                    ┌──────────────────────────┐
                    │  AIController              │
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼─────────────┐
                    │  AIGenerationOrchestrator  │  ← 轻量编排器：遍历 Provider 列表
                    │  - generateImage()         │    找到 canHandle(model)==true 的实例
                    │  - createVideoTask()       │    委托给它执行
                    │  - generateScenes()        │
                    └──┬─────────┬───────────┬──┘
                       │         │           │
           ┌───────────▼─┐ ┌─────▼──────┐ ┌─▼───────────┐
           │ ImageProvider│ │VideoProvider│ │ScriptProvider│
           │ <<interface>>│ │<<interface>>│ │<<interface>> │
           └───────┬──────┘ └─────┬──────┘ └─┬───────────┘
                   │              │           │
    ┌──────────────┼──────┐       │    ┌──────┼──────────┐
    │              │      │       │    │      │          │
┌───▼──────┐ ┌────▼───┐ ┌▼───┐ ┌─▼──┐ ┌▼───┐ ┌▼─────┐ ┌▼──────┐
│OpenAI    │ │Gemini   │ │新   │ │Veo │ │GPT  │ │新    │ │新     │
│ImageProv │ │ImageProv│ │Provider│ │Vid │ │Vision│ │Vision│ │Script │
└──────────┘ └─────────┘ └────┘ └────┘ └─────┘ └──────┘ └───────┘
   @Component   @Component         @Component ...
```

### 接口设计

```java
// 图片生成
public interface ImageProvider {
    /** 该 Provider 能处理哪些模型名 */
    Set<String> supportedModels();
    /** 生成图片，返回图片 URL 或 base64 */
    String generate(GenerateImageRequest req) throws Exception;
}

// 视频生成
public interface VideoProvider {
    Set<String> supportedModels();
    /** 创建任务，返回 taskId */
    String createTask(VideoCreateRequest req) throws Exception;
    /** 轮询状态 */
    TaskStatus pollTask(String taskId) throws Exception;
    /** 下载视频内容流 */
    InputStream downloadContent(String taskId) throws Exception;
}

// 脚本生成
public interface ScriptProvider {
    Set<String> supportedModels();
    /** 调用 LLM 生成分镜 JSON */
    String generateScript(ScriptRequest req) throws Exception;
}
```

### DTO 设计（统一请求/响应对象）

```java
// 通用请求对象，Provider 各取所需
public record GenerateImageRequest(
    String model, String prompt, String size, String aspectRatio,
    List<String> referenceImages
) {}

public record VideoCreateRequest(
    String model, String prompt, String duration,
    List<String> referenceImages, String generatedImageUrl
) {}

public record TaskStatus(String status, String progress, String videoUrl, String error) {}

public record ScriptRequest(String model, String systemPrompt, String userPrompt) {}
```

### Provider 实现示例

```java
@Component
public class OpenAIImageProvider implements ImageProvider {

    private final AiConfigProperties config;  // 读自己的 baseUrl / apiKey

    @Override
    public Set<String> supportedModels() {
        return config.getOpenAIImageModels();  // 从 yml 读: "gpt-image-2,dall-e-3"
    }

    @Override
    public String generate(GenerateImageRequest req) throws Exception {
        // 构建 OpenAI 格式请求体
        // 调 config.getBaseUrlOpenai() + "/images/generations"
        // 解析响应 → 返回 imageUrl/base64
    }
}

@Component
public class GeminiImageProvider implements ImageProvider {

    @Override
    public Set<String> supportedModels() {
        return config.getGeminiImageModelSet();
    }

    @Override
    public String generate(GenerateImageRequest req) throws Exception {
        // 构建 Gemini 格式请求体
        // 调 config.getBaseUrlGemini()
        // 解析 Gemini 特殊响应格式
    }
}
```

### 编排器实现

```java
@Service
public class AIGenerationOrchestrator {

    private final List<ImageProvider> imageProviders;      // Spring 自动注入所有 @Component
    private final List<VideoProvider> videoProviders;
    private final List<ScriptProvider> scriptProviders;

    public AIGenerationOrchestrator(
            List<ImageProvider> imageProviders,
            List<VideoProvider> videoProviders,
            List<ScriptProvider> scriptProviders) {
        this.imageProviders = imageProviders;
        this.videoProviders = videoProviders;
        this.scriptProviders = scriptProviders;
    }

    public String generateImage(GenerateImageRequest req) throws Exception {
        for (ImageProvider p : imageProviders) {
            if (p.supportedModels().contains(req.model())) {
                return p.generate(req);
            }
        }
        throw new IllegalArgumentException("No ImageProvider found for model: " + req.model());
    }

    // createVideoTask / pollVideoTask / generateScript 同理
}
```

---

## 实施计划

### Task 1: 创建 Provider 接口和 DTO

**目标:** 定义三个 Provider 接口 + 统一请求/响应 DTO

**新建文件:**
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/provider/ImageProvider.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/provider/VideoProvider.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/provider/ScriptProvider.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/provider/GenerateImageRequest.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/provider/VideoCreateRequest.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/provider/TaskStatus.java`
- `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/provider/ScriptRequest.java`

**验证:** `mvn compile -q` 通过

---

### Task 2: 实现 OpenAIImageProvider（迁移 callOpenAIImage）

**目标:** 把 `ImageGenerationService.callOpenAIImage()` 迁移到独立 Provider

**新建:** `src/main/java/com/storyboard/service/ai/provider/OpenAIImageProvider.java`

关键点：
- `supportedModels()` 从 `yml` 的 `openai-image-models` 读取
- `generate()` 搬 `callOpenAIImage()` 逻辑
- 鉴权：根据 model 选择 `apiKey` 或 `sora2OfficialApiKey`

**修改:** `application.yml` 新增配置项：
```yaml
ai:
  laozhang:
    openai-image-models: gpt-image-2,dall-e-3
```

**验证:** `mvn compile -q`

---

### Task 3: 实现 GeminiImageProvider（迁移 callGeminiImage）

**新建:** `src/main/java/com/storyboard/service/ai/provider/GeminiImageProvider.java`

**验证:** `mvn compile -q`

---

### Task 4: 实现 AIGenerationOrchestrator + 重构 ImageGenerationService

**新建:** `src/main/java/com/storyboard/service/ai/AIGenerationOrchestrator.java`

**修改:** `ImageGenerationService.java`
- 删除 `callOpenAIImage()` 和 `callGeminiImage()`
- `generateImage()` 改为委托 `orchestrator.generateImage(req)`
- 保留文件存储逻辑（存本地、更新 scene 状态）— 这些不是 Provider 的职责

```java
// 重构后的 ImageGenerationService.generateImage()
public String generateImage(String sceneId, String prompt, String model, ...) {
    // 1. 查 scene、标记 generating
    // 2. 委托 orchestrator
    String result = orchestrator.generateImage(
        new GenerateImageRequest(effectiveModel, prompt, size, aspectRatio, referenceImages));
    // 3. 文件存储（不变）
    // 4. 更新 scene 状态（不变）
}
```

**验证:** `mvn compile -q`

---

### Task 5: 重构 VideoGenerationService → Provider 模式

**新建:**
- `src/main/java/com/storyboard/service/ai/provider/VeoVideoProvider.java`（迁移现有逻辑）

**修改:** `VideoGenerationService.java`
- 委托 `orchestrator.createVideoTask()` / `orchestrator.pollVideoTask()` / `orchestrator.downloadContent()`

**验证:** `mvn compile -q`

---

### Task 6: 重构 ScriptGenerationService → Provider 模式

**新建:**
- `src/main/java/com/storyboard/service/ai/provider/OpenAIVisionScriptProvider.java`（迁移 `callVisionApi()` 逻辑）

**修改:** `ScriptGenerationService.java`
- 委托 `orchestrator.generateScript()`

**验证:** `mvn compile -q`

---

### Task 7: 清理 AiConfigProperties

**目标:** 细化配置，每个 Provider 有自己独立的配置段

```yaml
ai:
  laozhang:
    # 通用 API 密钥
    api-key: ${LAOZHANG_API_KEY}
    sora2-official-api-key: ${LAOZHANG_SORA2_API_KEY}

    # OpenAI 兼容 Provider
    openai:
      base-url: https://api2.laozhang.ai/v1
      image-models: gpt-image-2,dall-e-3
      image-endpoint: /images/generations
      video-endpoint-create: /videos
      video-endpoint-status: /videos/
      video-endpoint-content: /videos/

    # Gemini Provider
    gemini:
      base-url: https://api2.laozhang.ai/v1beta/models/gemini-3-pro-image-preview:generateContent
      image-models: gemini-3-pro-image-preview

    # Vision (Chat Completions) Provider
    vision:
      base-url: https://api2.laozhang.ai/v1/chat/completions
      models: gemini-3-flash-preview
```

**修改:** `AiConfigProperties.java` 改为嵌套配置类

**验证:** `mvn compile -q`

---

### Task 8: 端到端验证 + 更新 config.ts

**验证:**
- `mvn compile -q`
- `npx tsc --noEmit && npm run build`
- 确认前端模型列表与后端 `supportedModels()` 一致

**更新:** `config.ts` 注释指向新的模型配置方式

---

## 预估影响范围

| 操作 | 新建文件 | 修改文件 | 删除代码 |
|------|---------|---------|---------|
| 新增 Provider | 1 个 `@Component` 类 | `application.yml` 加配置 | 0 |
| 删除 Provider | 0 | `application.yml` 删配置 | 1 个类 |
| 换 BaseURL | 0 | `application.yml` | 0 |
| 新增模型到已有 Provider | 0 | `application.yml` 加模型名 | 0 |

## 风险

1. **回归测试** — 重构涉及三个核心 Service，必须手动验证生图/生视频/脚本生成全流程
2. **Provider 发现顺序** — Spring 注入 `List<Provider>` 的顺序不确定，如果两个 Provider 声明支持同一模型，谁先生效取决于 Spring 的 bean 排序
3. **配置复杂度提升** — yml 从平铺变嵌套，但换来的是每个 Provider 独立可配

## 替代方案（更轻量）

如果觉得 8 个 Task 太重，可以只做最小改造：

- 只抽 `ImageProvider` 接口（不拆 Video/Script）
- 保持 yml 平铺结构
- 预计 3 个 Task 完成

代价是 video/script 换提供商时仍然要改编排器代码。
