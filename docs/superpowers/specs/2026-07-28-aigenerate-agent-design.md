# AiGenerateAgent Dify 智能体设计文档

> 状态：已确认

## 目标

设计并实现一个 Dify Advanced-Chat 模式的 AI 智能体，通过多轮对话引导用户完成：分镜脚本生成、图片生成、视频生成。智能体负责意图识别、方案讨论、用户确认的编排层，具体 API 调用（Laozhang、后端存储）委托给 Spring Boot 后端或 Dify HTTP 节点。

## 架构

```
┌─────────────────────────────────────────────────────────┐
│                    Dify AiGenerateAgent                  │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │ 意图识别 │→│ 分镜方案  │→│ JSON生成  │→│ POST后端 │ │
│  │  LLM    │  │ LLM(循环)│  │   LLM    │  │  HTTP    │ │
│  └─────────┘  └──────────┘  └──────────┘  └──────────┘ │
│       │                                          │       │
│       ├─ pic ──→ 图片方案LLM(循环) → POST生图HTTP        │
│       └─ video → 视频方案LLM(循环) → POST视频HTTP        │
└──────────────────────┬──────────────────────────────────┘
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
   Spring Boot 后端           Laozhang API
   /api/ai/generateScript     /v1/images/generations
   /api/ai/generateImage      /v1/videos
   /api/ai/generateVideo(代理)
```

## 技术栈

- **Dify**: App mode `advanced-chat`, 自托管 v1.16.1
- **管理工具**: difyctl CLI v0.2.0-alpha
- **LLM**: deepseek-v4-flash (langgenius/deepseek)
- **后端**: Spring Boot 4, JDK 21, 端口 8082
- **AI API**: Laozhang api2 (api2.laozhang.ai), gpt-image-2 / veo-3.1-fast

## 整体流程（Step 状态机）

```
用户输入 → [Step 路由]
│
├─ step=-1 → 意图识别 LLM → 意图路由
│   ├─ ai_split → step=1
│   ├─ pic      → step=3
│   ├─ video    → step=4
│   └─ other    → 引导回复, 保持 step=-1
│
├─ step=1 → 分镜方案 LLM（循环完善）
│   ├─ 认可(type=1) → step=2
│   └─ 修改(type=0) → 保持 step=1
│
├─ step=2 → 分镜JSON LLM → Code(格式适配) → HTTP(POST /api/ai/generateScript)
│   └─ step=-1, 返回结果
│
├─ step=3 → 图片方案 LLM（循环完善, 讨论风格/画幅/构图）
│   ├─ 认可(type=1) → step=5
│   └─ 修改(type=0) → 保持 step=3
│
├─ step=4 → 视频方案 LLM（循环完善, 讨论运镜/时长/分辨率）
│   ├─ 认可(type=1) → step=6
│   └─ 修改(type=0) → 保持 step=4
│
├─ step=5 → HTTP(POST Laozhang /images/generations) → step=-1
└─ step=6 → HTTP(POST 后端视频代理, 后端负责轮询Laozhang) → step=-1
```

### step 值空间

| step | 含义 | 循环 |
|------|------|------|
| -1 | 初始 / 意图识别 | 否 |
| 1 | 分镜方案设计 | 是 |
| 2 | 分镜 JSON 生成 + 写入后端 | 否 |
| 3 | 图片方案设计 | 是 |
| 4 | 视频方案设计 | 是 |
| 5 | 调用 Laozhang 生图 | 否 |
| 6 | 调用后端生视频代理 | 否 |

## conversation_variables

```yaml
- name: step
  type: integer
  default: -1
  description: 状态机步骤标识

- name: historytalk
  type: array[string]
  default: []
  description: 最近 20 轮对话历史

- name: currentProjectId
  type: string
  default: ""
  description: 当前操作的项目ID
```

## 节点清单

### 路由/控制节点

| ID | 类型 | 名称 | 职责 |
|----|------|------|------|
| start | start | 用户输入 | 接收用户消息 |
| step-router | if-else | Step路由 | 7分支：-1/1/2/3/4/5/6 |
| intent-router | if-else | 意图路由 | 4分支：ai_split/pic/video/other |
| storyboard-confirm | if-else | 分镜确认 | type=1→step=2, type=0→保持 |
| image-confirm | if-else | 图片确认 | type=1→step=5, type=0→保持 |
| video-confirm | if-else | 视频确认 | type=1→step=6, type=0→保持 |

### LLM 节点

| 名称 | 模型 | 输出类型 | 职责 |
|------|------|---------|------|
| 意图识别 | deepseek-v4-flash | structured: {type, message} | 识别 ai_split/pic/video/other |
| 分镜方案 | deepseek-v4-flash | structured: {type, message} | 讨论分镜设计，type=1/0 |
| 分镜JSON | deepseek-v4-flash | structured: {items[]} | 生成标准分镜 JSON |
| 图片方案 | deepseek-v4-flash | structured: {type, message, style, size} | 讨论图片风格参数 |
| 视频方案 | deepseek-v4-flash | structured: {type, message, duration, resolution, aspectRatio} | 讨论视频参数 |

### HTTP 节点

| 名称 | 方法 | URL | 说明 |
|------|------|-----|------|
| POST分镜 | POST | http://localhost:8082/api/ai/generateScript | 写回后端数据库 |
| POST生图 | POST | https://api2.laozhang.ai/v1/images/generations | 直接调用 Laozhang |
| POST生视频 | POST | http://localhost:8082/api/ai/generateVideo | 后端代理轮询 |

### Code 节点

| 名称 | 语言 | 职责 |
|------|------|------|
| 格式适配 | JavaScript | LLM JSON → GenerateScriptRequest 格式 |
| 裁剪历史 | JavaScript | historytalk 保留最近 20 条 |

### Answer 节点

| 名称 | 输出 |
|------|------|
| 引导回复 | 引导用户选择功能 |
| 分镜方案回复 | 展示方案文本 |
| 分镜结果回复 | 展示生成的分镜数据 |
| 图片方案回复 | 展示图片设计 |
| 图片结果回复 | 展示生成的图片（URL） |
| 视频方案回复 | 展示视频设计 |
| 视频结果回复 | 展示生成的视频（URL） |

## LLM Prompt 设计

### 意图识别

```
system: 结合用户当前输入 + 全部历史对话记录，识别用户意图归属四类：
  ai_split = AI 分镜制作
  pic = 图片生成
  video = 视频生成
  other = 打招呼、结束语、闲聊等其他意图

规则：
- 若无法明确区分，message 引导用户："你希望 AI 分镜还是视频生成或者图片生成呢？本系统主要围绕着 AI 分镜的主题开展的。"
- 最终只输出纯 JSON 结构，无额外解释

输出格式：
{"type":"ai_split|pic|video|other","message":"回复内容"}
```

### 分镜方案

```
system: 你是一个专业的AI分镜设计师。根据用户提供的剧本内容，设计分镜方案。

方案需包含：
1. 整体创作风格（写实/动漫/水墨/赛博朋克/胶片等）
2. 画幅比例（16:9 横屏 / 9:16 竖屏 / 1:1 方形）
3. 分镜数量建议
4. 每个分镜的简要描述（镜头内容、人物动作、场景切换）

展示方案后询问用户是否认可。认可 → type=1，不认可/需修改 → type=0 含新方案。

输出格式：
{"type":1|0,"message":"完整方案文本"}
```

### 分镜JSON

```
system: 根据已确认的分镜方案，以 JSON 数组格式返回分镜列表。

每个分镜对象包含：
- sceneNumber(整数): 分镜序号
- scriptContent: 镜头剧本、剧情台词
- imagePrompt: 生图提示词，格式：【镜头构图】→【场景主体】→【环境细节/道具】→【光线与色彩】→【氛围情绪】→【画质/风格】
- videoPrompt: 视频动态提示词
- negativePrompt: 负面规避提示词
- cameraMovement: 镜头运镜方式
- shotType: 镜头景别（特写/近景/中景/全景/远景）
- soundDesign: 音效与声音设计

输出格式：
{"items":[{"sceneNumber":1,"scriptContent":"...","imagePrompt":"...",...}]}
```

### 图片方案

```
system: 根据用户需求设计图片生成方案。

方案需包含：
1. 画面风格（写实/二次元/油画/水彩/3D渲染/摄影等）
2. 画幅尺寸（1024x1024方形 / 1536x1024横屏 / 1024x1536竖屏）
3. 画面构图描述（主体位置、视角、景深）
4. 色调与光线设计（暖色/冷色、自然光/戏剧光）
5. 关键元素说明

展示方案后询问用户是否认可。

输出格式：
{"type":1|0,"message":"完整方案文本","style":"风格关键词","size":"1024x1024|1536x1024|1024x1536"}
```

### 视频方案

```
system: 根据用户需求设计视频生成方案。

方案需包含：
1. 视频时长（4秒 或 8秒）
2. 分辨率预设（720p / 1080p / 4K）— 4K 仅与 8s 组合
3. 画幅（横屏 16:9 / 竖屏 9:16）
4. 运镜方式（推/拉/摇/移/跟/升/降）
5. 画面内容详细描述
6. 可选负面提示词（不希望出现的元素）

展示方案后询问用户是否认可。

输出格式：
{"type":1|0,"message":"完整方案文本","duration":"4|8","resolution":"720p|1080p|4k","aspectRatio":"16:9|9:16"}
```

## 认证方案

### 后端 API Key 认证

后端新增固定 API Key 认证机制：

1. `application-local.yml` 中配置：`ai.dify.api-key: xxx`
2. 后端新增 `DifyApiKeyFilter`，检查请求头 `X-Dify-Key` 匹配
3. Dify HTTP 节点请求头携带 `X-Dify-Key: {{DIFY_KEY}}`（环境变量）

## 边界处理

| 场景 | 处理 |
|------|------|
| 用户说"重新开始"/"取消" | 意图识别检测 → 重置 step=-1, historytalk=[] |
| historytalk 超过 20 条 | Code 节点裁剪到最近 20 条 |
| 后端 API 调用失败 | HTTP 节点 on-error → answer 返回错误信息 + step=-1 |
| 意图识别在 step≠-1 时 | 保留现有 step，在回答中提示当前阶段 |
| 用户输入为空 | 引导回复：请描述你的需求 |
| projectId 缺失 | 分镜方案前询问项目ID，用户提供后存入 currentProjectId，后续所有 API 调用复用 |

## 分镜方案前的项目关联

由于 `POST /api/ai/generateScript` 需要 `projectId`，Agent 在进入分镜方案（step=1）前需确认：

1. 用户首次进入分镜 → Agent 询问："请提供项目ID（可在编辑器地址栏中找到）"
2. 用户回复项目ID → Code 节点提取数字/字符串，存入 `conversation.currentProjectId`
3. 后续所有后端调用（generateScript/generateImage/generateVideo）携带此 projectId
4. projectId 在一次对话周期内不变，仅在"重新开始"时清空

## 关键决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 分镜 vs 图片/视频职责 | 分镜管故事结构，图片/视频管审美细节 | 关注点分离 |
| 后端认证 | 固定 API Key (X-Dify-Key header) | 改动最小 |
| 视频轮询 | 后端代理端点 | 复用已有 VideoGenerationService 轮询逻辑 |
| 方案 A vs B vs C | 方案 A：修复+扩展现有 step 状态机 | 改动最小、可增量验证 |

## 后端新增端点

### POST /api/ai/generateImage (Dify 代理)

Dify 调用此端点，后端转发 Laozhang API，处理认证、base64 解码、本地存储。

### POST /api/ai/generateVideo (Dify 代理)

Dify 调用此端点，后端处理：创建任务 → 轮询 → 下载 → 本地存储，一次性返回结果。

### DifyApiKeyFilter

`X-Dify-Key` header 认证过滤器，仅在 `/api/ai/dify/**` 路径生效。
