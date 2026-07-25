# 大模型数据库化配置方案

> **Goal:** 将模型/提供商配置从 YAML 迁移到数据库，实现运行时可动态增删模型，无需重启服务。与适配器模式配合：Provider 接口不变，配置来源从 yml 变为 DB。

**Architecture:** DB 表存储 provider/model/endpoint → 启动时 `AiProviderRegistry` 从 DB 加载并注册 Provider 实例 → 前端从后端 API 拉取模型列表。

---

## 数据库设计

### 三张表

```sql
-- ═══════════════════════════════════════
--  ai_providers — 模型提供商
-- ═══════════════════════════════════════
CREATE TABLE ai_providers (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,       -- 显示名: "老张 OpenAI 兼容"
    provider_type   VARCHAR(50) NOT NULL,        -- 适配器类型: openai_compatible | gemini | custom
    base_url        VARCHAR(500),                -- API 基础地址
    api_key         VARCHAR(500),                -- 密钥（加密存储）
    auth_type       VARCHAR(50) DEFAULT 'bearer', -- bearer | api_key_header | x_goog_api_key
    auth_header     VARCHAR(50) DEFAULT 'Authorization',
    is_enabled      BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ═══════════════════════════════════════
--  ai_models — 可用模型
-- ═══════════════════════════════════════
CREATE TABLE ai_models (
    id              VARCHAR(36) PRIMARY KEY,
    provider_id     VARCHAR(36) REFERENCES ai_providers(id),
    model_name      VARCHAR(100) NOT NULL,       -- API 调用名: "gpt-image-2"
    display_name    VARCHAR(100),                -- 前端显示名: "GPT Image 2"
    model_type      VARCHAR(20) NOT NULL,        -- image | video | vision
    is_default      BOOLEAN DEFAULT FALSE,       -- 该类型下的默认模型
    is_enabled      BOOLEAN DEFAULT TRUE,
    sort_order      INT DEFAULT 0,
    config_json     TEXT,                        -- 模型参数: {"size":"1024x1024"}
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ═══════════════════════════════════════
--  ai_endpoints — API 端点
-- ═══════════════════════════════════════
CREATE TABLE ai_endpoints (
    id              VARCHAR(36) PRIMARY KEY,
    provider_id     VARCHAR(36) REFERENCES ai_providers(id),
    endpoint_type   VARCHAR(50) NOT NULL,        -- image_generate | video_create | video_status | video_content | vision_chat
    path            VARCHAR(200) NOT NULL,       -- "/images/generations"
    http_method     VARCHAR(10) DEFAULT 'POST',
    is_enabled      BOOLEAN DEFAULT TRUE
);
```

### 种子数据（首次启动从 yml 迁移）

```sql
-- OpenAI 兼容 Provider
INSERT INTO ai_providers (id, name, provider_type, base_url, auth_type)
VALUES ('prov-001', '老张 OpenAI', 'openai_compatible', 'https://api2.laozhang.ai/v1', 'bearer');

-- Gemini Provider
INSERT INTO ai_providers (id, name, provider_type, base_url, auth_type, auth_header)
VALUES ('prov-002', '老张 Gemini', 'gemini', 'https://api2.laozhang.ai/v1beta/...', 'x_goog_api_key', 'x-goog-api-key');

-- 模型
INSERT INTO ai_models VALUES ('m-001', 'prov-001', 'gpt-image-2', 'GPT Image 2', 'image', TRUE, ...);
INSERT INTO ai_models VALUES ('m-002', 'prov-001', 'dall-e-3', 'DALL·E 3', 'image', FALSE, ...);
INSERT INTO ai_models VALUES ('m-003', 'prov-002', 'gemini-3-pro-image-preview', 'Gemini Image', 'image', FALSE, ...);
INSERT INTO ai_models VALUES ('m-004', 'prov-001', 'veo-3.1-fast', 'Veo 3.1 Fast', 'video', TRUE, ...);
INSERT INTO ai_models VALUES ('m-005', 'prov-001', 'gemini-3-flash-preview', 'Gemini Flash', 'vision', TRUE, ...);

-- 端点
INSERT INTO ai_endpoints VALUES (..., 'prov-001', 'image_generate', '/images/generations', 'POST');
INSERT INTO ai_endpoints VALUES (..., 'prov-001', 'video_create', '/videos', 'POST');
INSERT INTO ai_endpoints VALUES (..., 'prov-001', 'video_status', '/videos/', 'GET');
```

---

## 后端架构

### 新增文件

```
provider/
├── ImageProvider.java          ← 接口（不变）
├── VideoProvider.java          ← 接口（不变）
├── ScriptProvider.java         ← 接口（不变）
├── impl/
│   ├── OpenAIImageProvider.java   ← 从 yml 读 → 改为从 DB entity 读
│   ├── GeminiImageProvider.java
│   ├── VeoVideoProvider.java
│   └── OpenAIVisionProvider.java
├── registry/
│   └── AiProviderRegistry.java    ← 核心：启动时加载 DB → 注册 Provider
├── entity/
│   ├── AiProviderEntity.java
│   ├── AiModelEntity.java
│   └── AiEndpointEntity.java
├── mapper/
│   ├── AiProviderMapper.java      ← MyBatis-Plus BaseMapper
│   ├── AiModelMapper.java
│   └── AiEndpointMapper.java
├── service/
│   └── AiConfigService.java       ← DB 配置管理 + API 暴露
└── controller/
    └── AiConfigController.java    ← 管理后台 CRUD（可选，先用 SQL 管理）
```

### 核心流程

```
启动时:
  AiProviderRegistry.@PostConstruct
    → AiConfigService.loadAll()
    → 从 ai_providers/ai_models/ai_endpoints 三表 JOIN 查询
    → 按 provider_type 创建对应的 Provider 实例
    → 注入到 AIGenerationOrchestrator

运行时:
  AIController → Orchestrator → 遍历 Provider[] → canHandle(model) → execute()

前端:
  新增 GET /api/ai/models 接口 → 返回 image/video/vision 三组模型列表
  config.ts 改为 fetch + cache 而非硬编码常量
```

### AiProviderRegistry 伪代码

```java
@Component
public class AiProviderRegistry {

    private final AiConfigService configService;
    private final Map<String, ImageProvider> imageProviders = new ConcurrentHashMap<>();
    private final Map<String, VideoProvider> videoProviders = new ConcurrentHashMap<>();
    private final Map<String, ScriptProvider> scriptProviders = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        List<AiProviderEntity> providers = configService.loadEnabledProviders();
        for (AiProviderEntity p : providers) {
            List<AiModelEntity> models = configService.loadModels(p.getId());
            List<AiEndpointEntity> endpoints = configService.loadEndpoints(p.getId());

            ProviderConfig config = ProviderConfig.from(p, models, endpoints);

            switch (p.getProviderType()) {
                case "openai_compatible" -> {
                    ImageProvider ip = new OpenAIImageProvider(config);
                    VideoProvider vp = new VeoVideoProvider(config);
                    ScriptProvider sp = new OpenAIVisionProvider(config);
                    // 注册到 Map
                    models.forEach(m -> {
                        if ("image".equals(m.getModelType())) imageProviders.put(m.getModelName(), ip);
                        if ("video".equals(m.getModelType())) videoProviders.put(m.getModelName(), vp);
                        if ("vision".equals(m.getModelType())) scriptProviders.put(m.getModelName(), sp);
                    });
                }
                case "gemini" -> { /* Gemini 只处理 image */ }
            }
        }
    }

    // 动态刷新（管理后台改了 DB 后调用）
    public void reload() { /* 清空 Map + 重新 init */ }
}
```

---

## 前端改动

### 之前（硬编码）

```ts
// config.ts
export const IMAGE_MODELS = [
  { value: 'gpt-image-2', label: 'GPT Image 2' },
  ...
];
```

### 之后（从后端拉取）

```ts
// config.ts — 改为缓存层
let _modelsCache: { image: ModelDef[]; video: ModelDef[]; vision: ModelDef[] } | null = null;

export async function loadModels(): Promise<typeof _modelsCache> {
  if (_modelsCache) return _modelsCache;
  const res = await client.get('/ai/models');
  _modelsCache = res.data.data;
  return _modelsCache;
}

// 组件中
useEffect(() => {
  loadModels().then(models => setImageModels(models.image));
}, []);
```

---

## 实施计划

### Task 1: 建表 + Entity + Mapper

**新建:**
- `src/main/java/com/storyboard/entity/AiProviderEntity.java`
- `src/main/java/com/storyboard/entity/AiModelEntity.java`
- `src/main/java/com/storyboard/entity/AiEndpointEntity.java`
- `src/main/java/com/storyboard/mapper/AiProviderMapper.java`
- `src/main/java/com/storyboard/mapper/AiModelMapper.java`
- `src/main/java/com/storyboard/mapper/AiEndpointMapper.java`

**新建:** `src/main/resources/db/ai_config_schema.sql`（建表 DDL + 种子数据）

**验证:** `mvn compile -q`

### Task 2: AiConfigService — DB 读写

**新建:** `src/main/java/com/storyboard/service/ai/AiConfigService.java`

方法：
- `loadEnabledProviders()` — 查 `ai_providers WHERE is_enabled=true`
- `loadModels(providerId)` — 查 `ai_models`
- `loadEndpoints(providerId)` — 查 `ai_endpoints`
- `getModelsByType(String type)` — 给前端接口用

**验证:** `mvn compile -q`

### Task 3: ProviderConfig — 统一配置 POJO

**新建:** `src/main/java/com/storyboard/service/ai/registry/ProviderConfig.java`

```java
// 把 entity 数据聚合为 Provider 可用的配置对象
public record ProviderConfig(
    String providerId, String providerType,
    String baseUrl, String apiKey,
    String authType, String authHeader,
    Set<String> imageModels, Set<String> videoModels, Set<String> visionModels,
    Map<String, String> endpoints,  // endpointType → path
    Map<String, String> modelDefaults // modelName → configJson
) {
    public static ProviderConfig from(AiProviderEntity p, List<AiModelEntity> models, List<AiEndpointEntity> endpoints) {
        // 聚合逻辑
    }
}
```

### Task 4: 改造 Provider 实现 — 从 yml 注入 → 从 ProviderConfig 注入

**修改:** `OpenAIImageProvider`, `GeminiImageProvider`, `VeoVideoProvider`, `OpenAIVisionProvider`

构造函数改为接收 `ProviderConfig`，内部从中取 `baseUrl`、`apiKey`、`endpoints`。

**验证:** `mvn compile -q`

### Task 5: AiProviderRegistry — 启动加载 + 动态刷新

**新建:** `src/main/java/com/storyboard/service/ai/registry/AiProviderRegistry.java`

- `@PostConstruct init()` — 从 DB 加载并实例化所有 Provider
- `reload()` — 清空重建
- `getImageProviders()` / `getVideoProviders()` / `getScriptProviders()`

### Task 6: AiConfigController — 管理接口 + 前端模型列表接口

**新建:** `src/main/java/com/storyboard/controller/AiConfigController.java`

```java
@GetMapping("/api/ai/models")     // 前端拉取模型列表
@PostMapping("/api/admin/ai/providers")   // 管理：新增 provider
@PutMapping("/api/admin/ai/providers/{id}")
@DeleteMapping("/api/admin/ai/providers/{id}")
@PostMapping("/api/admin/ai/providers/{id}/reload")  // 动态刷新
```

### Task 7: 前端 — config.ts 改为后端拉取

**修改:** `config.ts` — 删掉 `IMAGE_MODELS` / `VIDEO_MODELS` 常量，改为 `loadModels()` 异步函数

**修改:** 所有引用的组件 — 改为 `useEffect` 加载

### Task 8: 种子数据迁移 + 验证

- 跑 DDL + 种子 SQL
- `mvn compile -q` + `tsc --noEmit` + `npm run build`
- 端到端测试：生图/生视频/脚本生成

---

## 操作对比

| 操作 | 之前 | 之后 |
|------|------|------|
| 新增模型 | 改 `application.yml` + `config.ts` | `INSERT INTO ai_models` 一行 SQL |
| 新增提供商 | 改 5 个文件 | `INSERT INTO ai_providers` + 新建 Provider 类 |
| 换 BaseURL | 改 `application.yml` | `UPDATE ai_providers SET base_url=...` + 调 reload |
| 下线一个模型 | 改 yml + 前端 | `UPDATE ai_models SET is_enabled=false` |
| 前端模型列表 | 硬编码常量 | 从 `/api/ai/models` 拉取，实时生效 |

## 降级方案（如果觉得太重）

不做 DB 化，只做 **适配器模式 + yml 配置**（前一个计划的 8 个 Task）。

DB 化适合以下情况：
- 需要运维人员不碰代码就能换模型
- 模型列表频繁变动
- 有多个环境且配置不同（dev 用 gpt-image-2，prod 用 dall-e-3）
- 未来要做管理后台

---

## 管理端设计（API Key / BaseURL 存 DB + 后台管理）

### ⚠️ API Key 安全方案

**必须加密存储**，不能明文存 DB。方案：AES-256-GCM。

```java
// 新增工具类
@Component
public class ApiKeyEncryptor {
    // 加密密钥来自环境变量，不存 DB
    @Value("${AI_CONFIG_ENCRYPTION_KEY}")  // 32 字节，生产环境必须更换
    private String encryptionKey;

    public String encrypt(String plainText) { /* AES-256-GCM */ }
    public String decrypt(String cipherText) { /* AES-256-GCM */ }
}
```

`ai_providers.api_key` 存密文，`AiProviderRegistry` 加载时解密。

**关键**：`AI_CONFIG_ENCRYPTION_KEY` 只存在于环境变量/`application-local.yml`，永远不入库。

### 管理端鉴权

复用现有 JWT 体系。新增管理员角色判断：

```java
// AiConfigController.java
@RestController
@RequestMapping("/api/admin/ai")
public class AiConfigController {

    // 所有管理接口要求 ADMIN 角色
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/providers")
    public ApiResponse<List<ProviderVO>> listProviders() { ... }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/providers")
    public ApiResponse<ProviderVO> addProvider(@RequestBody AddProviderReq req) {
        // 1. 加密 apiKey
        // 2. INSERT INTO ai_providers
        // 3. 调 registry.reload() 立即生效
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/providers/{id}")
    public ApiResponse<ProviderVO> updateProvider(@PathVariable String id, @RequestBody UpdateProviderReq req) {
        // UPDATE → registry.reload()
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/providers/{id}")
    public ApiResponse<Void> deleteProvider(@PathVariable String id) {
        // DELETE → registry.reload()
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/reload")
    public ApiResponse<Void> reload() {
        registry.reload();  // 手动触发全量重载
    }

    // 这个不需要管理员，前端用
    @GetMapping("/api/ai/models")
    public ApiResponse<ModelsVO> getModels() { ... }
}
```

### 管理端前端页面（最小可行版）

单独一个路由 `/admin/models`，仅管理员可访问：

```
┌─────────────────────────────────────────────┐
│  🤖 AI 模型管理                              │
│                                              │
│  📡 提供商                                    │
│  ┌──────────────────────────────────────┐    │
│  │ 老张 OpenAI  [openai_compatible] ✏️ 🗑️ │    │
│  │ baseUrl: api2.laozhang.ai/v1          │    │
│  │ 模型: gpt-image-2, dall-e-3, veo...   │    │
│  ├──────────────────────────────────────┤    │
│  │ 老张 Gemini  [gemini]          ✏️ 🗑️ │    │
│  └──────────────────────────────────────┘    │
│  [+ 新增提供商]                                │
│                                              │
│  🎨 图片模型                                  │
│  ┌────────────┬──────────────┬───────┐       │
│  │ 模型名      │ 提供商        │ 默认   │       │
│  │ gpt-image-2 │ 老张 OpenAI   │ ⭐    │       │
│  │ dall-e-3   │ 老张 OpenAI   │       │       │
│  │ gemini...  │ 老张 Gemini   │       │       │
│  └────────────┴──────────────┴───────┘       │
│  [+ 新增模型]                                  │
└─────────────────────────────────────────────┘
```

新增前端文件：
- `AIStoryboardClient/src/pages/AdminModelsPage.tsx`
- `AIStoryboardClient/src/api/adminAi.ts`
- `App.tsx` 加路由 `/admin/models`

### 完整操作流程

```
运维人员打开 /admin/models
  → 点 "新增提供商"
  → 填 name=新提供商, type=openai_compatible, baseUrl=..., apiKey=...
  → 提交 → POST /api/admin/ai/providers
  → 后端加密 apiKey → INSERT DB → registry.reload()
  → 返回成功
  → 再点 "新增模型" 关联到该 provider
  → 提交 → POST /api/admin/ai/models
  → registry.reload()
  → 前端模型列表自动刷新（重新调 GET /api/ai/models）
```

全程不碰代码、不重启服务、不接触服务器文件系统。
