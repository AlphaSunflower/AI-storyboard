# 基于 DeepSeek Harness 的 Agent 服务二次开发方案

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** 基于 DeepSeek Harness 构建独立 Agent 服务，支持 Skill 知识库 + 业务工具，通过 HTTP API 暴露给多系统调用

**Architecture:** DeepSeek Harness（Agent 框架）+ 自定义插件（Skill/Tool）+ HTTP Wrapper（REST API）

**Tech Stack:** TypeScript + Cordis + DeepSeek Harness + Express

---

## 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    系统A（AI Storyboard，8082）                │
│                    系统B（另一个系统）                          │
└───────────────────────┬─────────────────────────────────────┘
                        │ HTTP REST API
                        ▼
┌─────────────────────────────────────────────────────────────┐
│              HTTP Wrapper（Express，8084）                    │
│              POST /sessions → session/new                    │
│              POST /sessions/:id/prompt → session/prompt      │
│              GET /sessions/:id/events → SSE 流               │
└───────────────────────┬─────────────────────────────────────┘
                        │ JSON-RPC over stdio
                        ▼
┌─────────────────────────────────────────────────────────────┐
│              DeepSeek Harness（子进程）                        │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ SkillProvider │  │ 业务 Tools   │  │ LLM Provider │       │
│  │ (DB → skill) │  │ (生图/视频等) │  │ (LLM网关)    │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                              │
│  ctx.skills.registerProvider(dbProvider)                     │
│  ctx.tools.defineTool(generateVideo)                        │
│  ctx.tools.defineTool(generateImage)                        │
│  ctx.tools.defineTool(generateCanvas)                       │
└─────────────────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│              LLM 网关（8083）                                │
│              PostgreSQL（skill 存储）                         │
└─────────────────────────────────────────────────────────────┘
```

---

## ACP 协议说明

**ACP = Agent Client Protocol**，DeepSeek Harness 的自动化协议：

- **传输层**：JSON-RPC over stdio（stdin/stdout）
- **核心方法**：
  - `session/new` — 创建会话，返回 sessionId
  - `session/prompt` — 发送消息，返回 messageId
  - `session/cancel` — 取消进行中的请求
  - `session/update` — 服务端推送消息块（SSE 风格）
- **特点**：
  - 同步等待完整回答（prompt → idle）
  - 支持并发会话（同一连接多个 session）
  - 权限请求（`session/request_permission`）

**问题**：ACP 是 stdio 协议，其他系统需要 HTTP 调用。

**解决**：写 HTTP Wrapper，包装成 REST API。

---

## 实施计划

### Phase 1: 项目初始化

**Task 1.1: 创建 Agent 服务项目**

```bash
mkdir ai-storyboard-agent
cd ai-storyboard-agent
pnpm init
pnpm add express cors
pnpm add -D typescript @types/express @types/cors
```

**目录结构：**
```
ai-storyboard-agent/
├── src/
│   ├── index.ts              # HTTP Wrapper 入口
│   ├── harness-launcher.ts   # DeepSeek Harness 子进程管理
│   ├── plugins/
│   │   ├── skill-provider.ts # DB Skill Provider
│   │   ├── tools/
│   │   │   ├── generate-video.ts
│   │   │   ├── generate-image.ts
│   │   │   ├── generate-canvas.ts
│   │   │   └── index.ts
│   │   └── llm-provider.ts   # LLM 网关适配
│   └── types.ts
├── cordis.yml                # DeepSeek Harness 配置
├── package.json
└── tsconfig.json
```

**Task 1.2: 配置 DeepSeek Harness**

`cordis.yml`：
```yaml
# 启用核心插件
- id: core
  entry: '@deepseek-ai/dsh-core'

# 启用 skill 系统
- id: skills
  entry: '@deepseek-ai/dsh-skill'

# 启用 tool 系统
- id: tools
  entry: '@deepseek-ai/dsh-tools'

# 启用 agent-loop
- id: agent-loop
  entry: '@deepseek-ai/dsh-agent-loop'

# 自定义插件
- id: skill-provider
  entry: './dist/plugins/skill-provider.js'

- id: business-tools
  entry: './dist/plugins/tools/index.js'

- id: llm-provider
  entry: './dist/plugins/llm-provider.js'
```

---

### Phase 2: 核心插件开发

**Task 2.1: DB Skill Provider**

`src/plugins/skill-provider.ts`：
```typescript
import { Context } from '@deepseek-ai/cordis'
import type { SkillProvider, SkillCandidate, SkillDefinition } from '@deepseek-ai/dsh-skill'

// 从你的 PostgreSQL 读取 skill
class DatabaseSkillProvider implements SkillProvider {
  name = 'database'

  constructor(private dbUrl: string) {}

  async list(options) {
    // 查询 agent_skills 表
    const skills = await fetch(`${this.dbUrl}/api/skills`).then(r => r.json())
    
    return skills.map(s => ({
      name: s.name,
      description: s.description,
      source: 'custom',
      rank: 100,
      provider: 'database',
      locator: s.id,  // 用于后续 get()
      invocation: { modelInvocable: true, userInvocable: true }
    }))
  }

  async get(candidate, options) {
    // 按 ID 加载完整 skill
    const skill = await fetch(`${this.dbUrl}/api/skills/${candidate.locator}`).then(r => r.json())
    
    return {
      ...candidate,
      content: skill.content
    }
  }
}

export default function apply(ctx: Context) {
  const dbUrl = process.env.BACKEND_URL || 'http://localhost:8082'
  
  ctx.skills.registerProvider((control) => {
    const provider = new DatabaseSkillProvider(dbUrl)
    return provider
  })
}
```

**Task 2.2: 业务工具（以 generateVideo 为例）**

`src/plugins/tools/generate-video.ts`：
```typescript
import { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'
import z from '@deepseek-ai/schemastery'

export default function apply(ctx: Context) {
  const gatewayUrl = process.env.LLM_GATEWAY_URL || 'http://localhost:8083'
  const gatewayKey = process.env.LLM_GATEWAY_API_KEY

  ctx.tools.defineTool({
    name: 'generate_video',
    description: '生成视频。提供提示词和可选参数，返回视频 URL。',
    parameters: z.object({
      prompt: z.string().describe('视频生成提示词'),
      imageUrl: z.string().optional().describe('首帧图片 URL（图生视频）'),
      duration: z.number().optional().describe('视频时长（秒）'),
      resolution: z.string().optional().describe('分辨率如 720p'),
      model: z.string().optional().describe('模型名')
    }),
    async execute(args, exec) {
      // 调用你的 LLM 网关创建视频任务
      const response = await fetch(`${gatewayUrl}/v1/videos`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${gatewayKey}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          model: args.model || 'minimax-video-model',
          prompt: args.prompt,
          imageUrl: args.imageUrl,
          duration: args.duration || 5,
          resolution: args.resolution || '720p'
        })
      })
      
      const { task_id } = await response.json()
      
      // 轮询等待完成
      let status = 'queued'
      let videoUrl = ''
      
      while (status === 'queued' || status === 'running') {
        await new Promise(r => setTimeout(r, 5000))
        
        const pollRes = await fetch(`${gatewayUrl}/v1/videos/${task_id}`, {
          headers: { 'Authorization': `Bearer ${gatewayKey}` }
        })
        const pollData = await pollRes.json()
        status = pollData.status
        
        if (status === 'succeeded') {
          // 下载视频
          const contentRes = await fetch(`${gatewayUrl}/v1/videos/${task_id}/content`, {
            headers: { 'Authorization': `Bearer ${gatewayKey}` }
          })
          // 保存到本地...
          videoUrl = `/api/files/videos/${task_id}.mp4`
        }
      }
      
      if (status === 'failed') {
        throw new Error('视频生成失败')
      }
      
      return { url: videoUrl, taskId: task_id }
    }
  })
}
```

**Task 2.3: LLM Provider 适配**

`src/plugins/llm-provider.ts`：
```typescript
import { Context } from '@deepseek-ai/cordis'

export default function apply(ctx: Context) {
  const gatewayUrl = process.env.LLM_GATEWAY_URL || 'http://localhost:8083'
  const gatewayKey = process.env.LLM_GATEWAY_API_KEY

  // 注册 OpenAI 兼容的 LLM provider
  ctx.llm.registerAdapter({
    name: 'gateway',
    async *stream(request) {
      const response = await fetch(`${gatewayUrl}/v1/chat/completions`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${gatewayKey}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          model: request.model || 'deepseek-v4-flash',
          messages: request.messages,
          stream: true,
          tools: request.tools
        })
      })
      
      // 解析 SSE 流
      const reader = response.body.getReader()
      // ... 解析并 yield chunks
    }
  })
}
```

---

### Phase 3: HTTP Wrapper

**Task 3.1: Express HTTP 服务**

`src/index.ts`：
```typescript
import express from 'express'
import cors from 'cors'
import { spawn } from 'child_process'
import { DeepSeekHarness } from '@deepseek-ai/dsh-sdk-client'

const app = express()
app.use(cors())
app.use(express.json())

// DeepSeek Harness 实例管理
const sessions = new Map<string, { harness: DeepSeekHarness, sessionId: string }>()

// 启动 Harness
async function getHarness(): Promise<DeepSeekHarness> {
  return new DeepSeekHarness({
    launch: {
      command: 'node',
      args: ['node_modules/@deepseek-ai/dsh/lib/bin.js', 'cordis.yml']
    },
    provider: 'gateway',
    model: 'deepseek-v4-flash'
  })
}

// 创建会话
app.post('/sessions', async (req, res) => {
  const harness = await getHarness()
  const result = await harness.run('初始化会话')
  const sessionId = result.sessionId
  
  sessions.set(sessionId, { harness, sessionId })
  
  res.json({ sessionId })
})

// 发送消息
app.post('/sessions/:id/prompt', async (req, res) => {
  const { id } = req.params
  const { content } = req.body
  
  const session = sessions.get(id)
  if (!session) {
    return res.status(404).json({ error: '会话不存在' })
  }
  
  // 调用 Harness
  const result = await session.harness.run(content, { sessionId: id })
  
  res.json({
    response: result.finalResponse,
    events: result.events
  })
})

// SSE 流式响应
app.get('/sessions/:id/events', (req, res) => {
  const { id } = req.params
  
  res.setHeader('Content-Type', 'text/event-stream')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('Connection', 'keep-alive')
  
  // 订阅 session 事件
  // ... 实现 SSE 推送
})

// 列出可用 skill
app.get('/skills', async (req, res) => {
  // 直接查 DB 或调用 Harness
  const skills = await fetch('http://localhost:8082/api/skills').then(r => r.json())
  res.json(skills)
})

app.listen(8084, () => {
  console.log('Agent 服务运行在 http://localhost:8084')
})
```

---

### Phase 4: 系统集成

**Task 4.1: 系统A（AI Storyboard）集成**

```java
// AgentServiceClient.java
@Service
public class AgentServiceClient {
    private final String agentUrl = "http://localhost:8084";
    
    public String createSession() {
        // POST /sessions
    }
    
    public String sendPrompt(String sessionId, String content) {
        // POST /sessions/{sessionId}/prompt
    }
    
    public SseEmitter streamEvents(String sessionId) {
        // GET /sessions/{sessionId}/events (SSE)
    }
}
```

**Task 4.2: 前端集成**

```typescript
// agentApi.ts
const AGENT_URL = 'http://localhost:8084'

export const agentApi = {
  createSession: () => 
    fetch(`${AGENT_URL}/sessions`, { method: 'POST' }).then(r => r.json()),
    
  sendPrompt: (sessionId: string, content: string) =>
    fetch(`${AGENT_URL}/sessions/${sessionId}/prompt`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content })
    }).then(r => r.json()),
    
  streamEvents: (sessionId: string, onEvent: (e: any) => void) => {
    const es = new EventSource(`${AGENT_URL}/sessions/${sessionId}/events`)
    es.onmessage = (e) => onEvent(JSON.parse(e.data))
    return () => es.close()
  }
}
```

---

## 与现有架构的对比

| 维度 | 现有架构 | 新架构（DeepSeek Harness） |
|------|---------|--------------------------|
| Skill 管理 | DB + @Tool 方法 | ctx.skills.registerProvider（插件化） |
| 工具定义 | @Tool 注解（Spring AI） | ctx.tools.defineTool（Cordis） |
| Agent 循环 | handler 编排（硬编码） | agent-loop（框架驱动） |
| 多系统调用 | 各系统独立实现 | ACP/HTTP Wrapper（统一入口） |
| 扩展性 | 加 @Tool + Handler | 加插件（零改动核心） |

---

## 迁移策略

### 渐进式迁移（推荐）

**Phase 1（1-2周）：验证可行性**
- 搭建 DeepSeek Harness 基础环境
- 实现 1个 Skill Provider + 1个 Tool（如 generateVideo）
- HTTP Wrapper 暴露 API
- 系统A 调用验证

**Phase 2（2-4周）：迁移核心能力**
- 迁移现有 AgentTools（writeScenes/refineImage/generateVideo）
- 迁移 Skill 知识库（从 DB 到 SkillProvider）
- 系统A 完全切换到新 Agent 服务

**Phase 3（4-8周）：扩展新能力**
- 添加画布/PPT/扩图等新 Tool
- 系统B 接入
- 优化性能和稳定性

### 并行运行（过渡期）

```
系统A → 现有 AgentOrchestrator（8082）
     → 新 Agent 服务（8084）← 新功能走这边
```

---

## 风险与应对

| 风险 | 应对 |
|------|------|
| DeepSeek Harness 处于开发者预览 | 锁定版本，关注 changelog |
| Cordis 学习曲线 | 先跑通 demo，再深入 |
| LLM 网关集成 | 写自定义 llm adapter |
| 性能（子进程开销） | 保持 Harness 长驻，不频繁启停 |
| 调试复杂 | 日志 + session event 可观测性 |

---

## 文件清单

### 新建项目
```
ai-storyboard-agent/
├── src/
│   ├── index.ts
│   ├── harness-launcher.ts
│   ├── plugins/
│   │   ├── skill-provider.ts
│   │   ├── tools/
│   │   │   ├── generate-video.ts
│   │   │   ├── generate-image.ts
│   │   │   ├── generate-canvas.ts
│   │   │   └── index.ts
│   │   └── llm-provider.ts
│   └── types.ts
├── cordis.yml
├── package.json
└── tsconfig.json
```

### 修改现有项目
```
AIStoryboardBackend/
└── src/main/java/com/storyboard/service/agent/
    └── AgentServiceClient.java  # 新增，调用 Agent 服务
```

---

## 验证步骤

1. **启动 DeepSeek Harness**：`pnpm dsh web`
2. **验证 Skill 加载**：检查 `ctx.skills.list()` 返回 DB 中的 skill
3. **验证 Tool 调用**：发送 prompt，LLM 调用 `generate_video` 工具
4. **验证 HTTP API**：curl 测试 `/sessions` 和 `/sessions/:id/prompt`
5. **验证系统A 集成**：前端调用新 Agent 服务，完成视频生成流程

---

## 总结

**核心思路：**
- DeepSeek Harness 作为 Agent 框架（不从零写）
- 自定义插件实现业务能力（Skill + Tool + LLM Provider）
- HTTP Wrapper 暴露给多系统调用

**优势：**
- 复用框架能力（skill/tool/agent-loop/session）
- 插件架构，业务与框架解耦
- 统一入口，多系统复用

**代价：**
- 学习 Cordis 架构
- 框架处于开发者预览
- 需要写 LLM Provider 适配器
