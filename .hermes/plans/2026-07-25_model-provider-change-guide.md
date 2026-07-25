# 大模型 / BaseURL 变更操作指南

> 本文档回答两个问题：
> 1. 同一个提供商的 BaseURL 变了，我该改哪？
> 2. 换了一个完全不同的大模型/提供商，我该怎么做？

---

## 场景一：只换 BaseURL（提供商没变，地址变了）

例如：Laozhang 通知 `api2.laozhang.ai` 迁移到 `api3.laozhang.ai`。

**只需改 1 个文件：**

`AIStoryboardBackend/src/main/resources/application.yml`

```yaml
ai:
  laozhang:
    base-url-openai: https://api3.laozhang.ai/v1        # ← 改这里
    base-url-gemini: https://api3.laozhang.ai/v1beta/... # ← 改这里
    base-url-vision: https://api3.laozhang.ai/v1/chat/... # ← 改这里
```

如果视频端点也变了（比如 `/videos` → `/video-tasks`），同样在这个文件改：

```yaml
    endpoint-video-create: /video-tasks        # ← 改路径
    endpoint-video-status: /video-tasks/       # ← 改路径
```

**不需要动任何 Java 代码** — 所有 Service 都通过 `config.getBaseUrlOpenai()` / `config.getEndpointVideoCreate()` 读取。

---

## 场景二：换大模型/提供商

假设从 Laozhang 的 `gpt-image-2` 换到某新提供商的 `super-image-v3`，API 格式也可能不同。

### 步骤 1：改配置文件（2 个文件）

**`application.yml`** — 更新密钥和地址：

```yaml
ai:
  laozhang:
    api-key: ${NEW_PROVIDER_API_KEY}
    sora2-official-api-key: ${NEW_PROVIDER_SORA2_KEY}   # 如果新提供商没这个，留空
    base-url-openai: https://api.new-provider.com/v1
    base-url-gemini: https://api.new-provider.com/gemini  # 如果新提供商不兼容 Gemini，删掉
    base-url-vision: https://api.new-provider.com/chat/completions
    default-image-model: super-image-v3      # ← 新默认模型
    default-vision-model: super-vision-v1    # ← 新 Vision 模型
    gemini-image-models: super-image-v3      # ← 走 Gemini 路径的模型列表
    video-model-aliases: '{"super-video":"super-video-generate"}'  # ← 新视频模型别名
```

**`AIStoryboardClient/src/config.ts`** — 更新前端模型列表：

```ts
export const IMAGE_MODELS = [
  { value: 'super-image-v3', label: 'Super Image V3' },      // ← 新模型
  // { value: 'gpt-image-2', label: 'GPT Image 2' },          // ← 删除旧模型
] as const;

export const VIDEO_MODELS = [
  { value: 'super-video', label: 'Super Video' },             // ← 新模型
] as const;
```

### 步骤 2：如果 API 请求/响应格式不同 → 改 Service

这是最需要判断的地方。当前三个 Service 的 API 格式：

| Service | 当前请求格式 | 当前响应解析 |
|---------|-------------|-------------|
| `ImageGenerationService` | `{model, prompt, n, size}` → `/images/generations` | `data[0].b64_json` 或 `data[0].url` |
| `VideoGenerationService` | `{model, prompt, duration, n}` → `/videos` | `root.path("id")` → 轮询 `root.path("status")` |
| `ScriptGenerationService` | OpenAI Chat Completions 格式 | `choices[0].message.content` |

**如果新提供商的 API 兼容 OpenAI 格式 → 不需改 Service。**

**如果不兼容 → 需要改对应 Service 的请求体构建和响应解析逻辑。**

示例：新提供商的生图 API 用 `{image_model, text, width, height}` 而非 `{model, prompt, n, size}`：

```java
// ImageGenerationService.java callOpenAIImage() 中
// 旧的：
body.put("model", model);
body.put("prompt", prompt);
body.put("n", 1);
body.put("size", config.getDefaultImageSize());

// 新的：
body.put("image_model", model);
body.put("text", prompt);
body.put("width", 1024);
body.put("height", 1024);
```

同理，响应解析也要改：

```java
// 旧的：
JsonNode data = root.path("data").get(0);
String url = data.path("url").asText();

// 新的（假设新 API 返回 {result: {image_url: "..."}}）：
String url = root.path("result").path("image_url").asText();
```

### 步骤 3：如果鉴权方式不同 → 改 Service

当前用 `Authorization: Bearer <key>`。如果新提供商用 `X-API-Key: <key>`：

```java
// ImageGenerationService.java 中
// 旧的：
.header("Authorization", "Bearer " + apiKey)

// 新的：
.header("X-API-Key", apiKey)
```

### 步骤 4：验证

```bash
# 后端编译
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q

# 前端
cd AIStoryboardClient && npx tsc --noEmit && npm run build
```

---

## 影响范围速查表

| 变更内容 | 需要改的文件 | 数量 |
|---------|-------------|------|
| 只换 BaseURL | `application.yml` | 1 |
| 换 API 端点路径 | `application.yml` | 1 |
| 换模型名 | `application.yml` + `config.ts` | 2 |
| 换 API 密钥 | `application-local.yml` 或环境变量 | 1 |
| 换鉴权方式 | `ImageGenerationService.java` + `VideoGenerationService.java` + `ScriptGenerationService.java` | 3 |
| 换请求体格式 | `ImageGenerationService.java` 或 `VideoGenerationService.java` | 1-2 |
| 换响应解析逻辑 | 同上 | 1-2 |
| 换提供商（全部重来） | 上面所有文件 | 5-6 |

---

## 关键文件清单

| 文件 | 作用 | 什么情况要改 |
|------|------|-------------|
| `application.yml` | 所有后端配置 | 总是要改 |
| `AiConfigProperties.java` | 配置的 Java 绑定 | 新增配置字段时 |
| `ImageGenerationService.java` | 生图逻辑 | API 格式/鉴权变化时 |
| `VideoGenerationService.java` | 生视频逻辑 | API 格式/鉴权变化时 |
| `ScriptGenerationService.java` | 脚本生成逻辑 | API 格式/鉴权变化时 |
| `config.ts` | 前端模型列表 | 模型名变化时 |
