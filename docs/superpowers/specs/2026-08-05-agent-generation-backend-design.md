# 智能体生成后端化重构设计

- 日期：2026-08-05
- 状态：待审查
- 范围：AI Storyboard 智能体窗口（Moon 工作流 + 后端 `/api/agent/**` + 前端智能体抽屉）

## 1. 背景与目标

### 现状

Moon 工作流（advanced-chat）内嵌 3 个 HTTP 请求节点回调后端执行生成：

| 节点 | 端点 | 作用 |
|------|------|------|
| POST分镜脚本 | `/api/ai/dify/generate-script` | 分镜 JSON 批量写 scenes |
| POST生图 | `/api/ai/dify/generate-image` | 代理 Laozhang 生图 |
| POST生视频 | `/api/ai/dify/generate-video` | 创建 Laozhang 视频任务 |

生成动作内联在 Dify 工作流里，带来一整类问题：

- `X-Dify-Key` 平台环境变量与 app key 需手动同步，不一致时回调静默 401（零日志）
- 视频生成 2-5 分钟占用 Dify workflow run 时间，经 Squid/Docker 代理易超时
- LLM `structured_output` 偶发解析故障（=schema / 空数组 / json_repair 脏 JSON），变量渲染成空串/垃圾导致后端 500
- 调整生成逻辑（模型、重试、尺寸白名单）要改工作流节点 + 导出导入 yml

### 目标

**生成执行从 Dify 工作流移出到后端**。Dify 只保留"大脑"职责（意图识别、方案设计 LLM、多轮完善循环、HITL 确认卡片）；后端在"用户提交 HITL 表单"这一事件上接管执行（写分镜 / 生图 / 生视频），生成结果以 assistant 消息 + 确认卡片形式推送到聊天框。

### 非目标

- 不重写 Dify 的意图识别状态机、LLM 方案设计逻辑、多轮完善循环
- 不动 agent_messages / agent_assets / conversations 表结构
- 不迁移既有历史数据
- 不修改图片/视频生成服务本身的 Laozhang 调用逻辑（复用）

## 2. 架构

```
┌─────────────────────── Dify（大脑）───────────────────────┐
│ 意图识别状态机(step) · 方案设计 LLM · 多轮完善循环         │
│ 方案确认 HITL 卡片（生成前） → answer 收尾                 │
└───────────────────────────┬───────────────────────────────┘
                            │ SSE 流（node_finished / human_input_required）
                            ▼
┌─────────────────────── 后端（执行手）─────────────────────┐
│ 1. 方案快照缓存：formToken → {formContent, LLM 方案变量}   │
│ 2. 表单提交事件分发：按 action 触发对应生成                 │
│ 3. 执行：写 scenes / 生图 / 生视频（复用现有 service）       │
│ 4. 结果回聊天框：agent_assets 落库 + assistant 消息推送     │
│ 5. 看图确认卡片：生成完成后推 [继续完善/满意完成]           │
└────────────────────────────────────────────────────────────┘
```

关键时序（图片分支为例，每轮循环相同）：

```
start(assigner: step=-1 强制重置)
  → 意图识别 → 图片方案设计(LLM) → 传到公共变量(方案写 storage_pic_talk)
  → 方案确认卡片(HITL: 生成图片/继续完善方案)
  → 用户点"生成图片" → 后端收表单提交事件
      → 后端调 Laozhang 生图 → 存 uploads + agent_assets
      → 后端推 [生成结果图 + 继续完善/满意完成 卡片]
      → 点"继续完善" → 前端发消息(PicUrl=新图) → Dify 完善分支
      → 点"满意完成" → 前端收起卡片，结束（Dify 不参与）
```

## 3. 工作流改动（用户在 Dify UI 操作）

### 3.1 删除（A 类：Dify 内部生成执行链）

| 节点 | 删除原因 |
|------|---------|
| POST分镜脚本 / POST生图 / POST生视频 | 生成移出，不再回调 |
| 获取imageUrl、HTTP 请求 4 | Dify 不再下载/展示生成图（图由后端消息直接给前端） |
| 设置step=5(生图)、'设置step=5(生图) ' | 状态机不再有"生图阶段" |
| 从json抽出值、赋全局值(×2)、公共变量赋值 | 数据源（POST生图 响应）已删，抽值链断 |
| 赋全局值为空值、重置step、图片公用变量(图片生成完成) | 触发点（生成完成后的 HITL）不存在 |

被删节点的上游直连 answer 收尾（方案确认节点后 answer 一句"正在为您生成，请稍候…"即可，实际生成在后端）。

### 3.2 保留/调整（B 类：方案状态维护）

- **传到公共变量**（code 节点）：保留。输入是方案设计 LLM 输出（不依赖生成响应），把方案 {message/style/size} 写入会话变量 `storage_pic_talk`，供完善 LLM 引用。
- **完善图片设计方案 LLM**：变量引用"上一个生图的风格"从生图链抽值节点（`17853956637370`）改为 `conversation.storage_pic_talk.pic_generate_talk`。
- **完善分支入口**：由"意图识别 + PicUrl 非空"路由进入（现状已是意图识别驱动，确认保留该路由）。

### 3.3 新增（必须）

- **图片分支新增"方案确认" HITL 节点**（生成图片 / 继续完善方案）：现状"图片确认"是 if-else 自动判断，无法暂停等用户授权；生成移出后必须有一个人工确认点作为后端触发源。原 if-else 删除或改接该节点。视频分支已有"生成视频确认" HITL、分镜分支已有"人工介入 3" HITL，直接复用，无需新增。
- **start 之后加 assigner：`step = -1`**（每轮消息强制重置）。step 是会话变量跨轮保留，现状靠"重置step"节点在生成完成时清理；生成移出后该触发点消失，不重置会导致下一轮 Step路由 走错分支。
- **storage_pic_talk 清理逻辑**：文生图（mode 非 edit）新方案写入时清空 `pic_generate_talk` 再写入，避免上一轮风格残留（改 code 节点逻辑，用户在 UI 操作）。

### 3.4 视频 / 分镜分支同等处理

- 视频：视频方案设计(LLM) → 方案确认 HITL（开始生成/继续完善）→ answer 收尾；完善循环走新消息（PicUrl 非空）进入。
- 分镜：分镜方案设计(LLM) → 人工介入 3（满意/不满意）→ answer 收尾；满意后由后端写 scenes（见 4.2）。

## 4. 后端改动

### 4.1 方案快照缓存（AgentChatService）

- `forwardDifySse` 处理 `node_finished` 时**不再丢弃 outputs**：捕获 LLM 节点输出（分镜 JSON / 图片 message+style+size / 视频 prompt），按 conversation 暂存"最近方案"。
- 转发 `human_input_required` / `workflow_paused` 事件时，以 `formToken` 为 key 缓存快照：`{formContent, actions, 最近方案}`，存 `ConcurrentHashMap`，TTL 30 分钟（与 Dify form_token 过期时间对齐）。
- 快照缺失（重启/超时）降级：用 formContent 方案文本作为生成 prompt 兜底，不中断链路。

### 4.2 表单提交事件分发（submitFormAndResume）

提交 POST `/v1/form/human_input/{formToken}` 返回 200 后，按 formToken 快照 + action 分发（与 Dify 续流并行执行，互不阻塞）：

| action | 触发动作 | 复用逻辑 |
|--------|---------|---------|
| 人工介入 3 的 `agree` | 快照分镜 JSON 批量写 scenes | `DifyAgentController.generateScript` 的建 scene 逻辑 |
| 图片方案确认的 `generate` | `imageService.generateImage`（图生图/图改图按快照 mode） | `ImageGenerationService` |
| 视频方案确认的 `generate` | `videoService.createVideoTask` + 异步轮询 `pollVideoTask` | `VideoGenerationService` |
| 看图确认卡片的 `refine` | 不触发生成（前端发消息带 PicUrl 走 Dify 完善分支） | — |
| 看图确认卡片的 `done` | 纯收尾，无后端动作 | — |

- 生成执行在专用虚拟线程 executor（复用 `agentExecutor`），不阻塞 SseEmitter 续流。
- 生成结果写 `agent_assets`（type=image/video、prompt、model、status），资产面板照常展示。
- sceneId 关联：快照中若含 sceneId 则写真实分镜；否则写 agent_assets（沿用现有"无 sceneId 写资产"语义）。

### 4.3 生成结果回聊天框

- 生成完成 → 以 **assistant 消息**落库（content 带 markdown 图片 `![图](url)` / 视频 URL），通过 SSE `message` 事件推给前端——MessageBubble 现有渲染零改动，刷新后历史消息完整。
- 生成中 → 推 `workflow` 事件（title="正在生成图片/视频…", status=node_started），前端现成进度展示。
- 图/视频 URL 为本地 `/api/files/images|videos/xxx`（复用 FileStorageService 落盘），无签名时效问题。
- Dify 工具文件本地化逻辑（`localizeDifyFileUrls`）对后端生成结果不适用（已是本地 URL），无需处理。

### 4.4 看图确认卡片（取代原"完善图片"HITL 节点）

- 生成完成时后端推新 SSE 事件 `confirm_result`：`{assetId, type, url, actions:[{id:"refine",title:"继续完善"},{id:"done",title:"满意完成"}]}`。
- 该卡片**不落库为消息**（会话历史里保留生成结果图消息即可，卡片是瞬时交互 UI）。
- `refine` 点击：前端以当前图 URL 作为 PicUrl 发消息（sendMessage 已有 picUrl 参数）→ Dify 意图识别 → 完善图片设计方案分支 → 方案确认 HITL → 后端再生成 → 循环闭环。
- `done` 点击：前端收起卡片，可选刷新资产面板；无后端调用。

## 5. SSE 事件协议扩展

| 事件 | 负载字段 | 说明 |
|------|---------|------|
| `confirm_result` | `assetId, type, url, actions` | 生成完成后的看图确认卡片（新） |
| `workflow` | `title, status` | 复用；生成中进度（"正在生成图片…"） |
| `message` | `content` | 复用；生成结果图/视频消息 |

既有事件（message / human_input / message_end / error）协议不变。

## 6. 前端改动

| 文件 | 改动 |
|------|------|
| `AgentChatPanel.tsx` | 处理 `confirm_result` 事件：渲染"生成结果图 + 继续完善/满意完成"卡片（UI 复用 HumanInputCard 样式） |
| `agentStore.ts` | `refine` → `sendMessage(picUrl=url)`；`done` → 本地移除卡片态 + 刷新资产列表 |
| `HumanInputCard.tsx` | 不变（方案确认卡片照旧） |

其余全部复用（MessageBubble 渲染图/视频、资产面板、打字机）。

## 7. 错误处理与兜底

- 方案快照缺失 → formContent 文本作 prompt 兜底。
- 生图失败 → `error` 事件（复用脱敏文案）+ agent_assets 记 failed；Laozhang 稳定性逻辑（尺寸白名单归一、重试）已在 service 内。
- 视频任务创建失败 → 复用 `isRetryableVideoCreate` 换池重试；轮询下载失败跨轮询重试（现有逻辑）。
- Dify 续流失败（工作流 answer 收尾失败）**不影响后端生成**——两路解耦，生成结果照常推送。
- 前端断连：`cancel` 标志沿用，生成完成后不推送（资产已落库，刷新可见）。

## 8. 验证

1. 后端：`mvn compile`（JAVA_HOME 用 Windows 路径）；手动跑通"方案确认 → 提交 → 后端生成 → 聊天框出图/视频 → 继续完善循环"全链路。
2. 工作流：用户在 Dify UI 按 3.1-3.4 清单改动后，导入运行一次完整对话（分镜/图片/视频三条分支各一轮 + 完善循环一轮）。
3. 前端：`npx tsc -p tsconfig.app.json --noEmit && npm run build`（solution-style tsconfig 必须带 `-p tsconfig.app.json`）。
4. 回归：清除聊天记录、HITL 续流合并（difyMessageId null 追加）、资产删除等既有功能不受影响。

## 9. 里程碑（整体切换，不做向后兼容）

1. **后端**（约 1-2 天）：方案快照缓存 + 提交事件分发 + 生成执行 + `confirm_result` 事件推送。
2. **前端**（约半天）：`confirm_result` 卡片处理 + refine/done 交互。
3. **工作流**（用户 UI 操作，约半小时）：按第 3 节清单删节点、加 step 重置、调整变量引用。
4. **联调**：三条分支各跑一轮 + 完善循环，验证闭环与回归项。

> 注意：重构为整体切换——工作流未改前（HTTP 节点仍在），后端提交分发会导致双触发（Dify 回调 + 后端分发各生成一次）。故后端分发逻辑上线与工作流改动必须同步，期间不暴露测试环境给真实用户操作。
