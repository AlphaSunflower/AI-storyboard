# Moon 智能体「提示词优化」按钮 — 实现计划（v2：自动覆盖 + LLM 自判方向）

> **For Hermes:** 使用 subagent-driven-development 技能按任务逐个执行本计划。

**Goal:** 在 Moon 智能体输入框附近新增「✨ 优化」按钮：用户输入 ≥6 字符的需求草稿后点击，后端调用大模型（质量优先）由 **LLM 自行判断**优化方向（剧情/图片/视频或综合），返回优化后的提示词；前端**自动覆盖输入框原文**，优化过程中**发送按钮不可点击**。

**Architecture:** 纯文本转换工具，不走 Dify 工作流（符合用户既定约束：生成/优化由后端直接执行）。后端新增 `POST /api/agent/prompt/optimize`（JWT 鉴权，随 `/api/agent/**` 自动覆盖），新建 `PromptOptimizeService` 调 Laozhang chat completions（复用 `ConversationTitleService` 调用范式：`baseUrlVision` + Bearer apiKey + `choices[0].message.content`）。**优化方向由 LLM 自行判断**（prompt 说明草稿可能是剧情/图片设计/视频设计或综合需求，输出一段优化后的专业提示词，不强制 JSON 结构），响应 `{optimized: string}` 单串。前端在 `AgentChatPanel` 输入区加按钮（`text.trim().length < 6` 禁用），点击后 `optimizing` 置位：优化按钮与发送按钮同时禁用，完成后 `setText(optimized)` 覆盖输入框，用户可直接发送或再编辑。**无弹窗、不落库、不关联会话**。

**Tech Stack:** Spring Boot 4 / JDK 21、JDK HttpClient、Laozhang API（chat completions）、React 19 + Zustand 5（组件本地 state，不引入全局 store 状态）。

---

## 现状 / 关键代码位置

| 环节 | 位置 | 说明 |
|------|------|------|
| 输入区 | `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx` L179-213 | 📎 按钮 + textarea（`text` state）+ 发送按钮；本功能按钮加在 textarea 与发送按钮之间 |
| API client | `AIStoryboardClient/src/api/agent.ts` | `client.post<T>` 模式，`/agent/...` 前缀，JWT 由拦截器注入 |
| 全局 store | `agentStore.ts` | 本功能**不新增**全局状态（仅影响当前组件输入框，本地 state 足够） |
| LLM 调用范式 | `ConversationTitleService.generateTitle`（新近落地） | POST `config.getBaseUrlVision()` + `Bearer apiKey`，body `{model, messages}`，解析 `choices[0].message.content` |
| 配置 | `AiConfigProperties` | `baseUrlVision`、`apiKey`、`defaultVisionModel`（gemini-3-flash-preview） |
| Controller | `AgentConversationController` | `/api/agent` 前缀，`Authentication auth` 取用户；DTO 为 Java record |
| 后端校验 | `BusinessException(40001, msg)` | 参数非法统一抛法；GlobalExceptionHandler 已映射 |

## 设计决策（含取舍）

1. **优化方向由 LLM 自行判断（用户明确要求）**：不固定输出三类 JSON。prompt 告知模型「草稿可能是剧情脚本、图片设计或视频设计需求，也可能是综合需求」，要求判断后输出**一段**优化后的专业提示词（中文，无需 JSON、无解释前后缀）。响应 `{optimized: string}` 单串——比三字段方案更简单、更稳（无 JSON 解析失败风险），且贴合"自行判断"语义。
2. **交互（用户明确要求）**：点击优化 → 优化中（优化按钮转「优化中…」+ **发送按钮禁用**）→ 完成**自动覆盖输入框原文**（`setText(optimized)`）→ 用户可再编辑/发送/再次优化（迭代优化天然支持）。**无弹窗**。
3. **长度校验（双端）**：前端按钮 `text.trim().length < 6` 禁用 + tooltip「至少 6 个字符才能优化」；后端 `content.trim().length < 6` 抛 `BusinessException(40001, "内容至少 6 个字符才能优化")`（防绕过前端）。
4. **模型（用户确认：质量优先）**：`config.getDefaultVisionModel()`（gemini-3-flash-preview），**不传 thinking_level**（用户主动操作，质量 > 速度；flash-preview 网关不透传思考参数已实测，传了也无意义）。
5. **后端端点**：`POST /api/agent/prompt/optimize`，请求 `{content: string}`，响应 `{optimized: string}`。JWT 鉴权（`/api/agent/**` 非白名单，SecurityConfig 零改动）。**不落库、不关联 conversationId**。
6. **Prompt 与处理**：system prompt 要求「判断草稿类型，输出一段优化后的专业提示词（剧情：完整脉络与情绪基调；图片：构图/主体/环境/光线/色彩/风格/镜头；视频：运镜/节奏/转场/动势/时长感），直接输出提示词本身，不要 JSON、不要解释、不要换行符」，user 消息 = 草稿（截断 500 字）。响应直接取 `choices[0].message.content` trim 后作为 `optimized`（**无 JSON 解析**）。空结果 → 40001「优化结果为空，请重试」。超时 60s。
7. **前端状态**：组件本地 state：`optimizing: boolean`、`optimizeError: string`。发送按钮 disabled 条件追加 `optimizing`（用户明确要求优化中禁发送）。不新增 store action。
8. **不做的事（YAGNI）**：不落库 / 不记历史 / 不做弹窗 / 不做流式 / 不做重试按钮 / 不加新配置项 / 不新建 store 状态 / 不动 SecurityConfig / 不做优化中锁定 textarea（用户只要求禁发送，输入框仍可编辑；但优化完成后结果覆盖原文是明确语义）。

## 任务清单

### Task 1: 后端新建 PromptOptimizeService

**Objective:** LLM 优化调用（自判方向，单文本返回），封装独立服务类。

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/PromptOptimizeService.java`

**Step 1: 编写服务**

```java
package com.storyboard.service.agent;

// imports: AiConfigProperties, ObjectMapper, JsonNode, HttpClient/Request/Response, SLF4J, @Service,
//          Duration, URI, HashMap/ArrayList/List/Map

@Service
public class PromptOptimizeService {
    private static final Logger log = LoggerFactory.getLogger(PromptOptimizeService.class);

    /**
     * 优化 System Prompt：LLM 自行判断草稿类型（剧情/图片/视频/综合），
     * 输出一段优化后的专业提示词；不要求 JSON，直接给提示词文本。
     */
    private static final String OPTIMIZE_PROMPT =
        "你是一名专业的分镜提示词优化师。用户会给你一段需求草稿，可能是剧情脚本、"
        + "图片设计或视频设计需求，也可能是综合需求。请你自行判断其类型，"
        + "输出一段优化后的专业提示词：剧情类给出完整脉络与情绪基调；图片类给出构图、"
        + "主体、环境、光线、色彩、风格、镜头类型；视频类给出运镜、节奏、转场、画面动势、时长感。"
        + "直接输出优化后的提示词本身，不要 JSON、不要解释、不要编号前缀。";

    private final AiConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    public PromptOptimizeService(AiConfigProperties config) {
        this.config = config;
    }

    /** 优化草稿为专业提示词（LLM 自判方向）。失败抛 RuntimeException（Controller 统一转 50202）。 */
    public String optimize(String content) { ... }
}
```

**Step 2: 补全 optimize 方法**

```java
public String optimize(String content) {
    try {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getDefaultVisionModel()); // 质量优先（用户确认）
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", OPTIMIZE_PROMPT));
        // 草稿截断 500 字：提示词优化只需要核心需求，防超长输入拖慢
        messages.add(Map.of("role", "user", "content",
                content.length() > 500 ? content.substring(0, 500) : content));
        body.put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getBaseUrlVision()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.getApiKey())
            .timeout(Duration.ofSeconds(60)) // 优化任务用户主动等待，60s 内一次性返回
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("提示词优化 API 返回 " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        String optimized = root.path("choices").get(0).path("message").path("content").asText("").trim();
        if (optimized.isBlank()) {
            throw new RuntimeException("优化结果为空");
        }
        return optimized;
    } catch (Exception e) {
        log.warn("提示词优化失败: {}", e.getMessage());
        throw new RuntimeException("提示词优化失败: " + e.getMessage(), e);
    }
}
```

（中文注释，风格对齐 `ConversationTitleService`。）

**Step 3: 编译验证**

Run: `export JAVA_HOME="C:\\Program Files\\Java\\jdk-21" && "/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/PromptOptimizeService.java
git commit -m "feat: 提示词优化服务（LLM 自判类型，单文本输出，质量优先模型）"
```

### Task 2: 后端端点 + DTO + 长度校验

**Objective:** 暴露 `POST /api/agent/prompt/optimize`，≥6 字符校验，返回 `{optimized}`。

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/PromptOptimizeRequest.java`
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java`（注入 PromptOptimizeService + 新端点）

**Step 1: DTO**

```java
package com.storyboard.dto.request;

/** 提示词优化请求 */
public record PromptOptimizeRequest(String content) {}
```

**Step 2: Controller 注入 + 端点**

```java
// 字段 + 构造注入 PromptOptimizeService optimizeService

/** 提示词优化：草稿 → 优化后的专业提示词（LLM 自判类型；≥6 字符；不落库） */
@PostMapping("/prompt/optimize")
public ApiResponse<Map<String, String>> optimizePrompt(
        Authentication auth, @RequestBody PromptOptimizeRequest request) {
    if (request.content() == null || request.content().trim().length() < 6) {
        throw new BusinessException(40001, "内容至少 6 个字符才能优化");
    }
    return ApiResponse.ok(Map.of("optimized", optimizeService.optimize(request.content().trim())));
}
```

**Step 3: 编译验证**

Run: 同 Task 1 的 mvn compile
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add AIStoryboardBackend/src/main/java/com/storyboard/dto/request/PromptOptimizeRequest.java AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java
git commit -m "feat: /api/agent/prompt/optimize 端点（≥6 字符校验，返回优化后提示词）"
```

### Task 3: 前端 API + 优化按钮 + 自动覆盖 + 发送禁用

**Objective:** `agentApi.optimizePrompt` + 输入区「✨ 优化」按钮；优化中禁发送；完成自动覆盖输入框。

**Files:**
- Modify: `AIStoryboardClient/src/api/agent.ts`
- Modify: `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx`

**Step 1: api/agent.ts 加方法**

```ts
// agentApi 内：
optimizePrompt: (content: string) =>
  client.post<{ data: { optimized: string } }>('/agent/prompt/optimize', { content }),
```

**Step 2: AgentChatPanel 组件本地 state**

```tsx
const [optimizing, setOptimizing] = useState(false);
const [optimizeError, setOptimizeError] = useState('');
```

（`agentApi` import：`import { agentApi } from '../../api/agent';`——若该文件未 import。）

**Step 3: 优化处理函数**

```tsx
const handleOptimize = async () => {
  const content = text.trim();
  if (content.length < 6 || streaming || waitingHumanInput || optimizing) return;
  setOptimizing(true);
  setOptimizeError('');
  try {
    const res = await agentApi.optimizePrompt(content);
    const optimized = res.data.data?.optimized;
    if (optimized) {
      setText(optimized); // 优化完成自动覆盖输入框原文（用户明确要求）
    } else {
      setOptimizeError('优化结果为空，请重试');
    }
  } catch {
    setOptimizeError('优化失败，请重试'); // 失败保持原文，仅轻提示
  } finally {
    setOptimizing(false);
  }
};
```

**Step 4: 优化按钮（textarea 与发送按钮之间，L203 之后）**

```tsx
<button
  onClick={handleOptimize}
  disabled={streaming || !!waitingHumanInput || optimizing || text.trim().length < 6}
  title={optimizing ? '正在优化…' : text.trim().length < 6 ? '至少输入 6 个字符才能优化' : '优化为专业的剧情/图片/视频提示词（自动覆盖输入框）'}
  style={{
    height: 32, padding: '0 12px', border: '1px solid var(--color-hairline)',
    borderRadius: 'var(--rounded-md)', background: 'var(--color-canvas)',
    color: 'var(--color-primary)', fontSize: 12, cursor: 'pointer', flexShrink: 0,
    opacity: streaming || !!waitingHumanInput || optimizing || text.trim().length < 6 ? 0.45 : 1,
  }}
>{optimizing ? '⏳ 优化中…' : '✨ 优化'}</button>
```

**Step 5: 发送按钮禁用条件追加 optimizing（用户明确要求）**

L206 `disabled={streaming || !!waitingHumanInput || !text.trim()}` 改为：

```tsx
disabled={streaming || !!waitingHumanInput || optimizing || !text.trim()}
```

背景色逻辑 L209 同步加 `optimizing` 置灰（与 disabled 一致）。

**Step 6: 优化错误轻提示（输入区上方，参考图提示条同层）**

在 L152 输入区容器内、`{refImageUrl && ...}` 块之前加：

```tsx
{optimizeError && (
  <p style={{ margin: '0 0 6px', fontSize: 11, color: 'var(--color-error)' }}>⚠ {optimizeError}</p>
)}
```

**Step 7: 前端验证（临时）**

Run: `cd E:\Desktop\AI-storyboard\AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit`
Expected: 无类型错误

**Step 8: Commit**

```bash
git add AIStoryboardClient/src/api/agent.ts AIStoryboardClient/src/components/agent/AgentChatPanel.tsx
git commit -m "feat: Moon 输入区提示词优化按钮（≥6 字符启用，优化中禁发送，完成自动覆盖输入框）"
```

### Task 4: 联调验证（后端需重启）

**Objective:** 全链路验证长度校验、优化质量、覆盖输入框、优化中禁发送。

**Step 1: 后端重启后 curl 冒烟**

```bash
# 无 JWT 应 401（/api/agent/** 需鉴权）
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/api/agent/prompt/optimize \
  -H "Content-Type: application/json" -d '{"content":"太空冒险"}'
# 期望 401
```

带 JWT 的完整 curl（登录拿 token 后）：
- `<6 字符`（如「太空」）→ 期望 40001「内容至少 6 个字符才能优化」
- `>=6 字符`（如「一个少年在太空站发现外星信号」）→ 期望 200 返回 `{"optimized":"..."}` 非空文本
- 图片类草稿（「一只橘猫在夕阳下的屋顶上睡觉」）→ 期望输出含构图/光线/风格等图片提示词要素
- 视频类草稿 → 期望输出含运镜/节奏/转场等要素

**Step 2: UI 手工验证**

| # | 场景 | 步骤 | 预期 |
|---|------|------|------|
| 1 | 禁用态 | 输入 4 个字符「测试提示」 | 按钮置灰，hover 提示「至少输入 6 个字符才能优化」 |
| 2 | 优化中禁发送 | 输入 ≥6 字符 → 点「✨ 优化」 | 按钮变「⏳ 优化中…」，发送按钮同时置灰不可点 |
| 3 | 自动覆盖 | 优化完成 | 输入框原文被优化后的提示词整体替换，按钮恢复「✨ 优化」，发送恢复可点 |
| 4 | 迭代优化 | 对优化结果再点「✨ 优化」 | 可再次优化（结果继续覆盖） |
| 5 | 失败保持原文 | 后端断网/改错 key | 输入框内容不变，输入区上方出现「⚠ 优化失败，请重试」 |
| 6 | 生成中禁用 | streaming 时（Dify 回复中） | 优化按钮不可点（与发送按钮一致） |

**Step 3: 回归**：发送、参考图、HITL 表单、标题重命名均不受影响（未触碰相关代码路径）。

### Task 5: 收尾

- 后端 `mvn compile -q` + 前端 `tsc -p tsconfig.app.json --noEmit` + `npm run build` 全绿；
- `git status` 只含计划内文件（用户偏好：git add 只加计划内文件，工作区遗留未提交修改原样保留）；
- 更新 `CLAUDE.md`「AI Agent 对话模块」：新增「提示词优化」小节（端点 / ≥6 校验 / LLM 自判类型 / 响应 `{optimized}` / 前端交互：优化中禁发送、完成自动覆盖输入框 / 不落库）。

---

## 文件变更总览

| 文件 | 动作 | 内容 |
|------|------|------|
| `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/PromptOptimizeService.java` | 新建 | LLM 优化调用（自判类型，单文本，质量优先模型，约 80 行） |
| `AIStoryboardBackend/src/main/java/com/storyboard/dto/request/PromptOptimizeRequest.java` | 新建 | `record PromptOptimizeRequest(String content)` |
| `AIStoryboardBackend/src/main/java/com/storyboard/controller/AgentConversationController.java` | 修改 | 注入 + `POST /prompt/optimize`（≥6 校验） |
| `AIStoryboardClient/src/api/agent.ts` | 修改 | `optimizePrompt` 方法 |
| `AIStoryboardClient/src/components/agent/AgentChatPanel.tsx` | 修改 | 按钮 + optimizing 状态 + 自动覆盖 + 发送禁用 + 错误提示 |
| `CLAUDE.md` | 修改 | 模块文档补充 |

无 DB 迁移、无 SecurityConfig 改动、无 store 状态改动、无新配置项、无弹窗组件。

## 风险 / 取舍 / 开放问题

1. **单文本输出 vs 三字段**：LLM 自判方向 → 单文本 `{optimized}`（用户确认）。输出是一段综合提示词；若草稿含多类型需求（图片+视频），模型会合并输出——这是"自行判断"的自然结果，符合预期。
2. **覆盖语义**：优化完成直接覆盖输入框（用户确认）。若用户在优化期间编辑了输入框，结果仍覆盖为优化快照的产物（简单语义，不做防冲突）。
3. **模型**：`defaultVisionModel`（质量优先，用户确认）；不传 thinking_level（实测 preview 系网关不透传，传了无意义）。
4. **失败处理**：失败保持原文 + 轻提示「优化失败，请重试」，不打断输入。可用性优先。
5. **超时体验**：60s 内一次性返回，按钮显示「⏳ 优化中…」；不做流式（YAGNI）。
6. **测试策略**：项目无新增测试惯例，采用「编译 + curl 冒烟 + UI 手工矩阵」；`optimize` 为外部 LLM 胶水，单测收益低，不强制。
