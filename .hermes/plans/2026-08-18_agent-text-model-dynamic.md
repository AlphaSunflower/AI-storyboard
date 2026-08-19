# Agent 文本模型动态化 Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 把 Agent 编排 6 个服务中硬编码的 `deepseek-v4-flash` 改为从 LLM 网关动态获取默认文本模型；兜底模型名、API Key、策略注释全部写入配置文件。

**Architecture:** 复用现有 `GatewayModelService` 基建——新增 `getDefaultTextModel()` 方法，注入到 6 个硬编码服务。`application.yml` 新增 `ai.gateway.fallback-text-model` + `ai.gateway.fallback-text-api-key` 配置项（含兜底策略注释），`GatewayModelServiceImpl.FALLBACK_DEFAULTS` 改为从配置读取。

**Tech Stack:** Spring Boot 4, Spring AI 2.0, LLM Gateway `/v1/models?type=text`

---

### Task 1: application.yml 新增兜底配置项

**Objective:** 在配置文件中声明兜底文本模型名 + API Key + 策略注释

**Files:**
- Modify: `AIStoryboardBackend/src/main/resources/application.yml`

**Step:** 在 `ai:` 段末尾（`ai.agent` 之后）新增：

```yaml
  # ── LLM 网关兜底策略 ──
  # 当网关不可达或未标记 is_default 时，以下值作为各类型模型的兜底默认值。
  # fallback-text-api-key 为兜底模型的独立 API Key（网关正常时由网关统一管理密钥，
  # 仅当网关完全不可达且需直连模型厂商时才使用此 key）。
  gateway:
    fallback-text-model: deepseek-v4-flash
    fallback-text-api-key: ${LLM_GATEWAY_API_KEY:}
```

注意：`ai.gateway.base-url` 和 `ai.gateway.api-key` 已在 `application-local.yml` / `application-prod.yml` 中定义（profile 专属），此处只加兜底字段。

**Commit:** `feat: add fallback text model config to application.yml`

---

### Task 2: AiConfigProperties.Gateway 新增兜底字段

**Objective:** 配置属性类绑定新字段

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/AiConfigProperties.java` — `Gateway` 内部类

**Step:** 在 `Gateway` 类中新增两个字段（紧跟 `apiKey` 之后）：

```java
/** 兜底文本模型名（网关不可达或未标记 is_default 时使用；默认 deepseek-v4-flash） */
private String fallbackTextModel = "deepseek-v4-flash";

/** 兜底文本模型独立 API Key（网关正常时由网关管理密钥，仅直连兜底时使用） */
private String fallbackTextApiKey;
```

Lombok `@Getter @Setter` 已在类上（或手写 getter/setter，看现有风格）。确认 `Gateway` 类有 getter setter 注解。

**Commit:** `feat: add fallbackTextModel/fallbackTextApiKey to Gateway config`

---

### Task 3: GatewayModelService 接口新增 getDefaultTextModel()

**Objective:** 接口层声明新方法

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/GatewayModelService.java`

**Step:** 在接口末尾加一个方法：

```java
/** 默认文本/对话模型（Agent 编排意图识别/标题/主回答/优化/资产判定共用；权威源网关 model_params.is_default） */
String getDefaultTextModel();
```

**Commit:** `feat: add getDefaultTextModel() to GatewayModelService interface`

---

### Task 4: GatewayModelServiceImpl 实现 getDefaultTextModel() + 兜底改配置读取

**Objective:** 实现新方法 + FALLBACK_DEFAULTS 从配置读取（text 类型）

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/ai/impl/GatewayModelServiceImpl.java`

**Step 1:** `FALLBACK_DEFAULTS` 中加 `"text"` entry（从 config 读取，非硬编码）：

由于 `FALLBACK_DEFAULTS` 是 static final Map，不能引用实例字段。改为在 `getDefaultModel()` 中对 `"text"` 类型特殊处理——先查 cache → 查网关 → 都没有时读 `config.getGateway().getFallbackTextModel()`：

```java
@Override
public String getDefaultTextModel() {
    return getDefaultModel("text");
}
```

在 `getDefaultModel(String type)` 方法中，兜底逻辑改为：

```java
private String getDefaultModel(String type) {
    String cached = defaultCache.get(type);
    if (cached != null) return cached;
    String fetched = fetchDefaultModel(type);
    if (fetched != null) {
        defaultCache.put(type, fetched);
        return fetched;
    }
    // text 类型兜底从配置文件读取（非硬编码）
    if ("text".equals(type)) {
        return config.getGateway().getFallbackTextModel();
    }
    return FALLBACK_DEFAULTS.getOrDefault(type, "");
}
```

`warmDefaultModels()` 的 `FALLBACK_DEFAULTS.keySet()` 不含 `"text"`，需手动加一行预热：

```java
@EventListener(ApplicationReadyEvent.class)
public void warmDefaultModels() {
    for (String type : FALLBACK_DEFAULTS.keySet()) {
        String d = fetchDefaultModel(type);
        if (d != null) defaultCache.put(type, d);
    }
    // text 类型单独预热（兜底值从配置文件读取，不在 FALLBACK_DEFAULTS 中）
    String textDefault = fetchDefaultModel("text");
    if (textDefault != null) defaultCache.put("text", textDefault);
}
```

**Commit:** `feat: implement getDefaultTextModel() with config-based fallback`

---

### Task 5: AgentOrchestratorSupport — planClient 改动态模型

**Objective:** 编排 planClient 从硬编码改为网关默认文本模型

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/handler/AgentOrchestratorSupport.java:~440-448`

**Step:** `planClient()` 方法中 `.model("deepseek-v4-flash")` 改为 `.model(gatewayModelService.getDefaultTextModel())`

注：`gatewayModelService` 已注入（字段存在于第 69 行），无需新增依赖。

**Commit:** `refactor: planClient use dynamic default text model from gateway`

---

### Task 6: AgentAnswerServiceImpl — 主回答改动态模型

**Objective:** 主回答 ChatClient 从硬编码改为网关默认文本模型

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/AgentAnswerServiceImpl.java:~125-130`

**Step 1:** 注入 `GatewayModelService`：
- 字段新增 `private final GatewayModelService gatewayModelService;`
- 加 import

**Step 2:** `chatClient()` 方法中 `.model("deepseek-v4-flash")` 改为 `.model(gatewayModelService.getDefaultTextModel())`

**Commit:** `refactor: AgentAnswerService use dynamic default text model`

---

### Task 7: AssetMatchingServiceImpl — 资产判定改动态模型

**Objective:** 资产判定 ChatClient 从硬编码改为网关默认文本模型

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/AssetMatchingServiceImpl.java:~130-135`

**Step 1:** 注入 `GatewayModelService`：
- 字段新增 `private final GatewayModelService gatewayModelService;`
- 加 import

**Step 2:** `chatClient()` 方法中 `.model("deepseek-v4-flash")` 改为 `.model(gatewayModelService.getDefaultTextModel())`

**Commit:** `refactor: AssetMatchingService use dynamic default text model`

---

### Task 8: ConversationTitleServiceImpl — 标题生成改动态模型

**Objective:** 标题生成 ChatClient 从硬编码改为网关默认文本模型

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/ConversationTitleServiceImpl.java`

**Step 1:** 注入 `GatewayModelService`：
- 字段新增 `private final GatewayModelService gatewayModelService;`
- 删除 `private static final String TITLE_MODEL = "deepseek-v4-flash";`
- 加 import

**Step 2:** `chatClient()` 方法中 `.model(TITLE_MODEL)` 改为 `.model(gatewayModelService.getDefaultTextModel())`

**Commit:** `refactor: ConversationTitleService use dynamic default text model`

---

### Task 9: IntentRecognitionServiceImpl — 意图识别改动态模型

**Objective:** 意图识别 ChatClient 从硬编码改为网关默认文本模型

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/IntentRecognitionServiceImpl.java`

**Step 1:** 注入 `GatewayModelService`：
- 字段新增 `private final GatewayModelService gatewayModelService;`
- 删除 `private static final String INTENT_MODEL = "deepseek-v4-flash";`
- 加 import

**Step 2:** `chatClient()` 方法中 `.model(INTENT_MODEL)` 改为 `.model(gatewayModelService.getDefaultTextModel())`

**Commit:** `refactor: IntentRecognitionService use dynamic default text model`

---

### Task 10: PromptOptimizeServiceImpl — 提示词优化改动态模型

**Objective:** 提示词优化 ChatClient 从硬编码改为网关默认文本模型

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/impl/PromptOptimizeServiceImpl.java`

**Step 1:** 注入 `GatewayModelService`：
- 字段新增 `private final GatewayModelService gatewayModelService;`
- 加 import

**Step 2:** `chatClient()` 方法中 `.model("deepseek-v4-flash")` 改为 `.model(gatewayModelService.getDefaultTextModel())`

**Commit:** `refactor: PromptOptimizeService use dynamic default text model`

---

### Task 11: 验证编译

**Objective:** 确保全量编译通过

**Step:**
```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
```

Expected: BUILD SUCCESS

---

## 变更范围总结

| 文件 | 改动 |
|------|------|
| `application.yml` | +兜底配置项（fallback-text-model / fallback-text-api-key + 注释） |
| `AiConfigProperties.java` | Gateway 内部类 +2 字段 |
| `GatewayModelService.java` | +1 方法声明 |
| `GatewayModelServiceImpl.java` | +1 实现方法 + 兜底改配置读取 + 预热 |
| `AgentOrchestratorSupport.java` | 1 行 model 替换 |
| `AgentAnswerServiceImpl.java` | +1 注入字段 + 1 行替换 |
| `AssetMatchingServiceImpl.java` | +1 注入字段 + 1 行替换 |
| `ConversationTitleServiceImpl.java` | +1 注入字段 -1 常量 + 1 行替换 |
| `IntentRecognitionServiceImpl.java` | +1 注入字段 -1 常量 + 1 行替换 |
| `PromptOptimizeServiceImpl.java` | +1 注入字段 + 1 行替换 |

## 风险

- 网关 `/v1/models?type=text` 需确认 `is_default` 标记存在（否则兜底配置接管，行为不变）
- `fallback-text-api-key` 当前架构下不直接使用（全部走网关），保留为后续直连兜底扩展
- ChatClient 懒加载 = 首次调用时取模型；网关首次不可达则取兜底配置值并缓存（与现有 image/video/vision 行为一致）
