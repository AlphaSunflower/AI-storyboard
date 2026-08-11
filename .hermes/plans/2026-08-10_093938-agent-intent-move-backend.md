# 意图识别从 Dify 提取到后端 实施计划

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 把「意图识别」从 Dify 工作流移除，改为后端调 LLM 识别用户输入得到 `type`（intent-aisplit / intent-pic / intent-video / intent-other），随请求传给 Dify 的 start 变量 `type` 直接路由。前端 UI 零改动。

**Architecture:** 后端新增 `IntentRecognitionService`（复用 ConversationTitleService 的 LLM 网关调用模式，模型 deepseek-v4-flash，快、便宜），在调用 Dify chat-messages 之前同步识别一次 → `type` → 写入 Dify 请求体 inputs。Dify 侧由用户在 UI 删除「意图识别」LLM 节点，把「意图路由」if-else 改为读 start 变量 `type`，「引导回复」answer 改为固定引导文案。识别失败兜底 intent-other，不阻塞对话。**只传 type，不传 message/intentMessage。**

**Tech Stack:** Spring Boot 4 / JDK 21 / JDK HttpClient / 网关 /v1/chat/completions / Dify workflow

---

## 现状（已核实）

### Dify 工作流（AIStoryboardDify/Moon智能体.yml，2187 行）

```
start（变量：currentProjectId / PicUrl / type[default=intent-other, 用户已加]）
  → 意图识别 LLM（id 1785286652605，deepseek-v4-pro，结构化输出 {type, message}）
  → 意图路由 if-else（id 17852867601920）
       4 个 case 条件读 ['1785286652605','structured_output','type']
       ├─ intent-aisplit → 分镜方案设计 LLM（id 1785286839392）
       ├─ intent-pic     → 图片方案设计 LLM
       ├─ intent-video   → 视频方案设计 LLM
       └─ intent-other   → 引导回复 answer（id 1785287790005，answer='{{#1785286652605.structured_output.message#}}'）
```

**删除「意图识别」节点后受影响的两处（已全量查证 `structured_output.message` 引用）：**
1. 「意图路由」if-else 的 4 个 case：`variable_selector` 指向 `['1785286652605','structured_output','type']` → 必须改为 start 变量 `['1785286288428','type']`（1785286288428 = start 节点 id）
2. 「引导回复」answer（id 1785287790005）：answer 引用意图识别节点的 message → **改为固定引导文案**（message 不再由后端生成，写死即可）

其余 `structured_output.message` 引用（1558/1744/1777/1853 行）各自引用本分支方案 LLM 或其它 LLM 节点，与意图识别节点无关，不受影响。

### 后端（AIStoryboardBackend）

| 路径 | 位置 | 说明 |
|------|------|------|
| `sendMessage`（blocking） | AgentChatService.java:339 → callDifyChat :393 | 调 Dify 前需要 type |
| `streamMessage`（SSE） | AgentChatService.java:448 → buildChatBody :521 | 调 Dify 前需要 type |
| `submitHumanInput` 续流 | 走 /v1/workflow/{taskId}/events，**不经过 start 节点** | 无需 type，不改 |

两个请求体构造方法（callDifyChat :394-420、buildChatBody :521-535）目前 inputs 只有 `currentProjectId` + `PicUrl`，都要加 `type`。

### 可复用模板：ConversationTitleService（service/agent/ConversationTitleService.java）

- 调 `config.getGatewayBaseUrl() + "/v1/chat/completions"`，Bearer `config.getGatewayApiKey()`
- 模型 `deepseek-v4-flash`（用户指定；fast 档，分类任务足够，无思考参数要求，**不加 thinking_level**）
- 30s 超时，解析 `choices[0].message.content`

---

## 方案

### 后端（本计划实现）

1. **新建** `IntentRecognitionService`（service/agent/IntentRecognitionService.java）
   - 入参：`String query` + `List<AgentMessage> recentMessages`（最近对话历史）
   - 出参：`String type`（intent-aisplit / intent-pic / intent-video / intent-other；**不返回 message**）
   - prompt：移植 Dify 意图识别节点的系统提示（4 类意图定义 + 判断规则，重点保留"继续/接着上次 → 结合历史判断"规则）+ 历史对话拼接段
   - 输出约束：只输出意图标识符本身（如 `intent-pic`），解析后白名单校验（不在 4 类中 → 兜底）
   - 调用异常 / 解析失败 / 校验不过 → 兜底返回 `"intent-other"`（不阻塞对话）

2. **改造** `AgentChatService`
   - `streamMessage`：buildChatBody 之前调识别（查 agent_messages 最近 8 条作历史上下文）→ 把 type 传入 buildChatBody
   - `sendMessage`：callDifyChat 之前同样识别（**注意：maybeScheduleTitleRename 的"首条"判定必须在 user 消息落库前，识别调用放在 title rename 之后、callDifyChat 之前即可**）
   - `buildChatBody` / `callDifyChat`：inputs 加 `"type"`

### Dify 工作流（用户自己在 Dify UI 操作，本计划只交付改动清单，不生成 yml）

| # | 操作 | 详情 |
|---|------|------|
| 1 | 删除节点 | 删除「意图识别」LLM 节点（title: 意图识别） |
| 2 | 改「意图路由」if-else | 4 个 case 的 variable_selector 从 `['意图识别节点','structured_output','type']` 改为 start 变量 `type` |
| 3 | 改「引导回复」answer | answer 改为固定引导文案（如「你希望 AI 分镜、图片生成还是视频生成呢？」），不再引用任何节点输出 |

---

## 任务分解（每任务 2-5 分钟）

### Task 1: 新建 IntentRecognitionService

**Objective:** 意图识别核心服务，调 LLM 网关返回 type 字符串

**Files:**
- Create: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/IntentRecognitionService.java`

**Step 1: 写服务骨架 + 常量**

```java
package com.storyboard.service.agent;

import ... // 参照 ConversationTitleService 的 import 集

@Service
public class IntentRecognitionService {
    /** 意图识别专用模型：deepseek-v4-flash（用户指定；fast 档，分类任务足够） */
    private static final String INTENT_MODEL = "deepseek-v4-flash";
    /** 识别超时 30s（参照标题服务） */
    private static final Duration INTENT_TIMEOUT = Duration.ofSeconds(30);
    /** 历史上下文：最多取最近 8 条消息 */
    private static final int HISTORY_LIMIT = 8;
    /** 兜底意图：识别失败不阻塞对话，走引导分支 */
    public static final String FALLBACK_TYPE = "intent-other";
    /** 四类合法意图 */
    private static final Set<String> VALID_TYPES = Set.of(
            "intent-aisplit", "intent-pic", "intent-video", "intent-other");

    private final AiConfigProperties config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    public IntentRecognitionService(AiConfigProperties config) {
        this.config = config;
    }

    public String recognize(String query, List<AgentMessage> recentMessages) {
        // Task 1 先实现单条 query 识别；历史拼接在 Task 3 补
    }
}
```

**Step 2: 实现 recognize（调网关 + 解析 + 白名单校验）**

- 参照 ConversationTitleService.generateTitle：body = `{model, messages:[system+user]}`（deepseek 无思考参数，**不加 thinking_level**），POST `gatewayBaseUrl + /v1/chat/completions`，Bearer `gatewayApiKey`
- system prompt（移植 Dify 意图识别节点原文，4 类意图定义 + 判断规则；**无 message 字段要求**）
- 输出约束改为：只输出意图标识符本身（如 `intent-pic`），禁止解释/JSON/代码块
- user content = `query`（>500 字截断）
- 解析 `choices[0].message.content` → trim → 白名单校验：`VALID_TYPES.contains(raw) ? raw : FALLBACK_TYPE`；任何异常 → `FALLBACK_TYPE`

**Step 3: 编译**

Run: `export JAVA_HOME="C:\\Program Files\\Java\\jdk-21" && "/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q`
Expected: BUILD SUCCESS（无编译错误）

**Step 4: Commit**

```bash
cd /e/Desktop/AI-storyboard && git add AIStoryboardBackend/src/main/java/com/storyboard/service/agent/IntentRecognitionService.java
git commit -m "feat: 新增意图识别服务（LLM 网关识别 type，失败兜底 intent-other）"
```

---

### Task 2: AgentChatService 集成 — streamMessage + sendMessage 带 type 调 Dify

**Objective:** 两条消息路径在调 Dify 前识别意图，请求体 inputs 带 type

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java`
  - :393 callDifyChat（blocking）
  - :448 streamMessage
  - :521 buildChatBody

**Step 1: 注入 IntentRecognitionService**

AgentChatService 构造函数加 `IntentRecognitionService intentRecognitionService`（现有构造注入风格）。

**Step 2: 改 callDifyChat（sendMessage 路径）**

签名加 `String type`，inputs 改为：

```java
body.put("inputs", Map.of(
    "currentProjectId", conversation.getProjectId(),
    "PicUrl", "",
    "type", type
));
```

**Step 3: 改 buildChatBody（streamMessage 路径）**

签名加 `String type`，inputs 同样加 `"type", type`。

**Step 4: 改 sendMessage / streamMessage 调用点**

两处都在调 Dify 之前：

```java
// 意图识别（type 控制 Dify 工作流路由；失败已兜底 intent-other，不阻塞）
String intentType = intentRecognitionService.recognize(content, Collections.emptyList());
// sendMessage: callDifyChat(conversation, content, userId, intentType);
// streamMessage: buildChatBody(conversation, content, picUrl, intentType);
```

（历史上下文在 Task 3 替换 `Collections.emptyList()`）

**Step 5: 编译**

Run: 同 Task 1 Step 3 命令
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git commit -am "feat: 消息调 Dify 前做后端意图识别，inputs 携带 type"
```

---

### Task 3: 历史上下文接入（处理"继续/接着上次"）

**Objective:** 识别时带最近 8 条消息，规则（继续/接着上次结合历史）才成立

**Files:**
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/IntentRecognitionService.java`（prompt 加历史段）
- Modify: `AIStoryboardBackend/src/main/java/com/storyboard/service/agent/AgentChatService.java`（两处调用点查历史）

**Step 1: 识别服务支持历史上下文**

- recognize 里把 recentMessages 拼进 prompt 历史段（user/assistant 交替，每条截断 100 字，总历史 ≤ 800 字，超出丢最旧）
- 历史段格式：

```
## 历史对话（供"继续/接着上次"判断）
user: ...
assistant: ...
```

**Step 2: AgentChatService 两处调用点查历史**

```java
// 查最近 8 条消息（时间倒序取 8 条再反转）
List<AgentMessage> history = messageMapper.selectList(
    new LambdaQueryWrapper<AgentMessage>()
        .eq(AgentMessage::getConversationId, conversationId)
        .orderByDesc(AgentMessage::getCreatedAt)
        .last("LIMIT 8"));
Collections.reverse(history);
```

（参照 ConversationTitleService 已有 import；AgentMessageMapper 已在 AgentChatService 注入）

**Step 3: 编译 + 冒烟**

Run: 编译命令同前，Expected BUILD SUCCESS

冒烟（后端 8082 + Dify 已启动）：
```bash
curl -s -X POST http://localhost:8082/api/agent/conversations/{id}/messages/stream \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"content":"帮我把这个剧本做成三分钟分镜"}' \
  | head -20
```
Expected: SSE 流中 workflow 事件出现「分镜方案设计」节点进度（说明 type=aisplit 路由生效）

**Step 4: Commit**

```bash
git commit -am "feat: 意图识别携带最近 8 条历史消息（继续/接着上次判断）"
```

---

### Task 4: 回归验证

**Objective:** 全链路验证 4 类意图路由 + 识别失败兜底 + 原有功能不回归

**验证清单：**

| # | 场景 | 输入 | 预期 |
|---|------|------|------|
| 1 | 分镜 | "帮我写个古风复仇故事的分镜" | Dify 走分镜方案设计分支 |
| 2 | 图片 | "画一张赛博朋克风格海报" | 走图片方案设计分支 |
| 3 | 视频 | "做一个 15 秒产品宣传视频" | 走视频方案设计分支 |
| 4 | 闲聊 | "你好" | 引导回复输出固定引导文案 |
| 5 | 继续 | 先完成分镜方案，再发"继续" | 结合历史判定 aisplit（走分镜分支） |
| 6 | 兜底 | 临时停网关 / 输入乱码 | 不报错，走 intent-other 引导分支 |
| 7 | 回归 | 图生视频 / 完善图片（PicUrl + HITL 续流） | 原有 AUTO_REFINE 链路不受影响 |

**验证方式：**
- 后端日志 + SSE workflow 事件观察路由分支
- 前端 AgentChatPanel 全流程人工点验（发送、参考图、HITL 表单、资产产出）

**Step 5: Commit（如需修复则一起）**

```bash
git commit -am "fix: 意图识别链路回归修复"
```

---

### Task 5: 交付 Dify 工作流改动清单（用户 UI 操作）

**Objective:** 用户按清单在 Dify UI 手动改工作流（偏好：用户自己 UI 操作）

改动清单（共 3 步，见上文「方案 → Dify 工作流」表）：
1. 删除「意图识别」LLM 节点
2. 「意图路由」if-else 4 个 case 的 variable_selector 改为 start 变量 `type`（不是删节点后断链报错状态）
3. 「引导回复」answer 改为**固定引导文案**（如「你希望 AI 分镜、图片生成还是视频生成呢？」），不再引用任何节点输出

⚠️ 顺序提示：**先改 if-else 引用和引导回复引用，再删意图识别节点**，避免 Dify 校验报"变量引用不存在"卡住保存。
⚠️ 节点 title 与后端常量无交集（后端 AUTO_REFINE_SIGNAL_TITLE = "后端执行识别图片加人工介入流程" 不受影响），无需改后端常量。

---

## 风险 / 取舍 / 开放问题

1. **识别模型降级**：Dify 原用 deepseek-v4-pro 识别，后端改用 deepseek-v4-flash（用户指定；fast 档，快、省）。分类任务足够，若实测边界案例（"继续"歧义）不准 → 模型提为常量可配，或换 deepseek-v4-pro。
2. **每轮 +1~2s 延迟**：识别在 Dify 调用前同步执行。可接受；deepseek-v4-flash 响应快，实际增量小。
3. **每轮多一次 LLM 调用成本**：flash 档极便宜；若想省可在会话变量缓存上一轮 type（ponytail: 先不做，识别准确率优先，缓存导致"继续"误判更贵）。
4. **intent-other 回复固定文案**：闲聊分支不再是动态回复，只输出固定引导语（与原工作流引导回复节点行为一致——原节点也是引用识别节点的 message 输出引导，本来就没有闲聊 LLM）。若后续要自然闲聊，在 Dify 加一个闲聊 LLM 节点即可，与本改动无关。
5. **前端零改动**：type 由后端填充，请求体结构不变。

## 交付物

1. 后端：`IntentRecognitionService.java`（新）+ `AgentChatService.java`（改 2 处调用点 + 2 个请求体构造）
2. Dify 工作流改动清单（Task 5，用户 UI 操作，3 步）
3. 验证：编译 + 7 项冒烟清单（Task 4）
