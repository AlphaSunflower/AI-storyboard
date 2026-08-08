# 图改图（edits）接入 LLM 网关设计

日期：2026-08-08
状态：已批准
关联：docs/superpowers/specs/2026-08-08-llm-gateway-design.md（v2 网关主设计，本设计为其 §8 的增量扩展）

## 1. 背景与目标

LLM 网关 v2 已覆盖 chat / 文生图 / 视频双通道，唯一保留直连的生成接口是**图改图（edits）**：Backend 的 `ImageGenerationService.callImageEdit` 仍直连 Laozhang `/v1/images/edits`（使用 `config.getApiKey()`）。

目标：把 edits 也收进网关，实现**所有直接调大模型的生成接口全覆盖**。收益与主设计一致：密钥集中管理（Backend 不再持有 Laozhang Key）、模型→渠道路由 DB 化、调用日志统一、将来多渠道可切换。

## 2. 现状（改动前）

**Backend 侧（保留，只改两点）：**
`ImageGenerationService.callImageEdit(model, prompt, referenceImages, generatedImageUrl)`：
1. 确定源图字节：generatedImageUrl（本地 uploads 读文件）或 referenceImages[0]（base64 解码）
2. `MultipartBuilder` 组装 multipart：`model` / `prompt` 字段 + `image` 文件 part
3. 直连：`POST {config.getBaseUrlOpenai()}{endpointImageEdits}`，`Authorization: Bearer {config.getApiKey()}`，超时 180s + 超时重试 1 次（`sendImageWithRetry`）
4. 解析响应 `data[0].b64_json`（缺则 url），cleanBase64 后返回

**网关侧（无 edits 能力）：**
- `OpenAiCompatController`：chat/completions、images/generations、models、videos 4 类端点
- `UpstreamClient`：只有 `postJson` / `postGemini` / `get`（无 multipart 发送）
- `GatewayRoutingService.route(path, body)`：images/generations 的渠道解析 + 转发 + 切换逻辑

## 3. 设计决策（已确认）

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 转发形态 | **multipart 原样透传** | Backend 的 MultipartBuilder 已存在，只换 URL/Key；零体积膨胀；最忠实 |
| 路由方式 | **复用 model_route** | gpt-image-2 已配路由；与 images/generations 行为一致；将来多渠道免改代码 |
| 错误/切换语义 | **与 images/generations 完全一致** | 429/5xx 切渠道、4xx 透传、全失败 50301、每次落 call_log |
| model 来源 | **从 multipart 字节流解析 `name="model"` part** | 符合 OpenAI edits 原生协议（Laozhang 从 multipart part 读 model），Backend 无需改协议 |

## 4. 网关侧改动

### 4.1 `OpenAiCompatController` 新增端点

```java
@PostMapping(value = "/images/edits", produces = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<String> imageEdits(@RequestBody byte[] body,
                                         @RequestHeader("Content-Type") String contentType)
```

- `@RequestBody byte[]`：Spring 直接收原始字节流（不解析）
- **传输协议（实测修正 2026-08-08）**：调用方用 `application/octet-stream` 发送 multipart 字节流——`@RequestBody byte[]` 收 `multipart/form-data` 会被 Spring multipart 解析器消费 body（实测必 500 "Required request body is missing"）；网关从 body 首行提取 boundary 重建转发头（见 4.2）
- 构造器注入新增 `ImageEditService`（独立 Service，职责单一；不动 GatewayRoutingService）

### 4.2 新增 `ImageEditService`（网关侧）

职责：edits 的渠道路由 + 转发 + 切换 + 日志。逻辑镜像 `GatewayRoutingService.route` 的渠道部分：

1. **解析 model**：从 multipart 字节流中提取 `name="model"` part 的值（字节级轻量解析：定位 `name="model"` 边界 → 读该 part 的 body 直到 `\r\n--`）
   - model 缺失 → `BusinessException(40001, "model is required")`
2. **查渠道**：`model_route` 按 model_name 查（无 → 40401 "no route for model: X"）→ 候选渠道 enabled + priority 升序（无 → 50301）
3. **逐渠道转发**：`UpstreamClient.postMultipart(baseUrl, "/images/edits", apiKey, upstreamContentType, bodyBytes)`（路径不带 /v1——渠道 baseUrl 已含 /v1，与 GatewayRoutingService 的 path 约定一致；`upstreamContentType` 由 `buildMultipartContentType(body)` 从 body 首行提取 boundary 重建为 `multipart/form-data; boundary=<纯boundary>`——跳过前导 `--`，Content-Type 参数规范不含）
   - 200 → 透传 body + 落 success call_log
   - 429/5xx → log.warn + 切下一渠道
   - 4xx → 透传错误体 + 落 error call_log（不切渠道）
4. **全渠道失败** → `BusinessException(50301, "all channels failed for model: X")`

### 4.3 `UpstreamClient` 新增方法

```java
public HttpResponse<String> postMultipart(String baseUrl, String path, String apiKey,
                                          String contentType, byte[] bodyBytes)
```

- 与 `postJson` 同构：Bearer 渠道 Key + 透传 contentType + requestTimeout + `sendWithRetry`（429/5xx 轻量重试 2 次，与现有方法一致）
- 注意：path 用 `"/v1/images/edits"` 常量；base_url 末尾斜杠 strip 逻辑复用 `stripTrailingSlash`

### 4.4 call_log 落库

- status 值：`success` / `error`（与 GatewayRoutingService 语义一致）
- model 取 multipart 解析出的 model；channelId 取实际转发渠道；durationMs 计转发耗时；error 透传上游错误信息（`extractError`）

## 5. Backend 侧改动（极小）

`ImageGenerationService.callImageEdit` 只改 3 处（第 3 步直连处，实测修正 2026-08-08：第 3 处 Content-Type 改 octet-stream 是规避 Spring multipart 解析器所必需）：

```java
// 改前
.uri(URI.create(config.getBaseUrlOpenai() + config.getEndpointImageEdits()))
.header("Authorization", "Bearer " + apiKey)
// 改后
.uri(URI.create(config.getGatewayBaseUrl() + "/v1/images/edits"))
.header("Authorization", "Bearer " + config.getGatewayApiKey())
.header("Content-Type", "application/octet-stream")   // octet-stream 发送 multipart 字节流（绕 Spring 解析器，网关重建转发头）
```

其余全部不动：源图读取、MultipartBuilder 组装、`sendImageWithRetry`（超时重试保留）、b64_json 解析、cleanBase64、场景状态落库。

## 6. 数据流

```
Backend callImageEdit（MultipartBuilder 组装 multipart 字节流）
  → POST localhost:8083/v1/images/edits（Bearer 网关 Key + octet-stream 传 multipart 字节流）
  → ImageEditService 解析 model + 从 body 重建 multipart Content-Type → model_route 查渠道（gpt-image-2 → laozhang）
  → UpstreamClient.postMultipart 原样转发 Laozhang /images/edits（渠道 Key，multipart Content-Type 含重建 boundary）
  → 200：透传 {data:[{b64_json}]} → Backend 解析 b64_json（不变）
  → 429/5xx：切下一渠道 → 全失败 50301
  → 4xx：透传真实状态码 + 错误体 → Backend 抛 "Image Edit API returned ..."
  → call_log 落库（model/channel/status/duration/error）
```

## 7. 错误处理

完全复用 `GlobalExceptionHandler` 现有映射：
- 40001→400（model 缺失）
- 40401→404（无路由）
- 50301→503（无渠道/全失败）
- 40101→401、40301→403、其余→500
- 上游非 200 错误体透传（`{error:{message}}` OAI 风格，Backend 的 `extractReadableError` 已能解析）

## 8. 不做的事（YAGNI）

- 不做 edits 的 Gemini/MiniMax 协议转换（这两家无 edits 接口；将来接入走渠道配置扩展，无需本设计改动）
- 不做 multipart 解析成结构化对象（只需 model 字段；全量解析引入边界解析复杂度，无收益）
- 不改 `GatewayRoutingService`（edits 逻辑独立成 Service；chat/images 路由不动）
- 不改 edits 的请求/响应协议（OpenAI 原生 multipart + b64_json，Backend 零协议适配）

## 9. 验证

1. **编译**：网关 + Backend `mvn compile` 均 exit 0
2. **ad-hoc 断言**（网关侧）：model 解析（含 model 缺失 40001）、渠道切换（429/5xx 切下一渠道）、4xx 透传不切、全失败 50301、postMultipart 转发字节一致
3. **E2E**（真实 edits）：
   - 完善图片场景：生成一张图 → 以 generatedImageUrl 调 `/api/ai/generate-image`（mode=edit）→ 网关 → Laozhang → b64 返回 → 本地转存
   - 参考图生图场景：referenceImages base64 → 同上
   - 验证 call_log 出现 edits 记录（model/channel/status/duration）

## 10. 影响面

- 网关：`OpenAiCompatController`（+1 端点）、`ImageEditService`（新建）、`UpstreamClient`（+1 方法）
- Backend：`ImageGenerationService.callImageEdit`（改 2 行）
- 数据库：无变更（复用 model_route / channel / call_log 现有表）
- 配置：无变更（复用 ai.gateway.* 已配置的 base-url / api-key）

## 11. 参考

- 主设计：docs/superpowers/specs/2026-08-08-llm-gateway-design.md §5.1（OpenAI 兼容入口）、§6.1（路由）、§7（错误处理）
- Backend 现状：AIStoryboardBackend/src/main/java/com/storyboard/service/ai/ImageGenerationService.java:193-250（callImageEdit）
- 网关现状：AILLMGateway/src/main/java/com/llmgateway/controller/OpenAiCompatController.java、service/GatewayRoutingService.java、service/UpstreamClient.java
