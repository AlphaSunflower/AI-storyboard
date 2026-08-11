# Moon 智能体脱离 Dify → Spring AI 2.0 编排 实施计划

> **For Hermes:** 用 subagent-driven-development 逐任务执行（每任务独立子代理 + 规格审查 + 代码质量审查）。
>
> **前置（已完成）**：SB4.0 + Spring AI 2.0.0 spike PASS；6 个 LLM 服务 + 生图 generations 已换 Spring AI；意图识别已提取后端（IntentRecognitionService）；完善图/图生视频方案已后端化（ImageRefinePromptService / VideoPlanService / triggerAuto*）。

**Goal:** 用 Spring AI 2.0 应用层编排（ChatClient + @Tool + 结构化输出 + HITL checkpoint）替换 AgentChatService 的 Dify 协议代理层（~800 行），SSE 事件协议不变 → 前端零改动；删掉 Dify 全部残留（DifyAgentController / DifyApiKeyFilter / dify DTO / dify_conversation_id / ai.laozhang.dify-* 配置）。

**Architecture（沿用技能 dify-migration-design.md）**：
- 分层：ChatClient 边界 + @Tool 工具（方法体复用现有 service）+ 应用层状态机（bounded loop）+ HITL checkpoint 落 DB
- loop：INTENT（现有 IntentRecognitionService）→ PLAN（结构化输出方案）→ EXECUTE（@Tool → 现有生成 service）→ OBSERVE → REVIEW；maxSteps/deadline/幂等/审计
- HITL：WAITING_FOR_HUMAN checkpoint 落新表 agent_checkpoints（action/formToken/过期时间/plan 快照/step/resume_token）；提交端点不变（/api/agent/conversations/{id}/form/submit）
- 记忆：继续用本地 agent_messages 拼历史（不引入 ChatMemory）；标题重命名/提示词优化流程原样保留
- SSE 协议不变：message / workflow / human_input / message_end / error

**Tech Stack:** Spring AI 2.0.0（已引入）+ 现有 AgentGenerationService/ImageGenerationService/VideoGenerationService/ScriptGenerationService/ImageRefinePromptService/VideoPlanService/IntentRecognitionService + PostgreSQL（agent_checkpoints 新表）

---

## 现状核实（2026-08-11）

| 文件 | 行数 | 角色 |
|------|------|------|
| `service/agent/AgentChatService.java` | 1630 | Dify 代理 + 业务编排混合体 |
| `controller/DifyAgentController.java` | 402 | Dify 工作流→后端生成端点（回调） |
| `controller/AgentConversationController.java` | 279 | 前端会话端点（保留，调 AgentChatService） |
| `security/DifyApiKeyFilter.java` | 50 | /api/ai/dify/** API Key 鉴权 |
| `service/agent/AgentGenerationService.java` | 155 | 无 sceneId 的生成落库（agent_assets） |
| dify DTO ×4 + dify_conversation_id 列 + ai.laozhang.dify-* 配置 | — | Dify 残留 |

AgentChatService 中 Dify 耦合方法：`callDifyChat`(400)、`forwardDifySse`(601-838，SSE 协议解析/事件转发/信号监听/HITL 暂停)、`confirmImageDone`(222，storage_pic_talk 会话变量)、`sendMessage`(342，blocking 路径)、`streamMessage`(456，SSE 路径)、`mergedHitlContent`/`cacheFormSnapshot`/`takeFormSnapshot`/`dispatchGeneration`(1026-1203，HITL 表单快照内存态)。
**保留不动**：conversation/message/asset 的 DB 操作（createConversation/getOwnedConversation/listMessages/clearMessages）、`maybeScheduleTitleRename`(305)、`loadRecentHistory`(549)、`pushGenerationResult`(1210)、`executeVideoGeneration`(1243)、`generateVideoFromPlan`(1263)、`triggerAutoImageRefine`(838)/`triggerAutoVideoPlan`(917) 的生成逻辑（其 Dify 信号触发点改为编排内直接调用）。

## 关键设计决策

1. **@Tool 工具面**（方法体复用现有 service，工具类 `AgentTools` @Component）：
   - `writeScenes(projectId, planJson)` → ScriptGenerationService 方案落库（新增 propose/commit 拆分，见决策 3）
   - `refineImage(conversationId, prompt, picUrl)` → AgentGenerationService.generateImage(sceneId=null, mode=edit) → pushGenerationResult
   - `generateVideo(conversationId, prompt, duration)` → VideoGenerationService.createVideoTask + 轮询（复用 executeVideoGeneration）
   - 工具参数校验/幂等（action 已确认才执行；工具结果稳定错误对象）
2. **HITL checkpoint**（新表 `agent_checkpoints`）：conversation_id、action（agree|refine|generate|confirm-done 等）、form_token（= resume_token）、plan JSONB 快照、step、expiration_time、status(pending|used|expired)、created_at。表单提交端点校验 token + 归属 + 未过期 → 更新 status=used → 恢复对应 step 执行。**表单快照从内存 Map 迁到 DB**（现 cacheFormSnapshot 是内存态，重启即失——顺带修复）。
3. **脚本方案 propose/commit 拆分**：现 ScriptGenerationService.generateScenes 直接落库（sceneMapper.maxSceneNumber + insert），HITL 需要"先提案后确认"。新增 `proposeScenes(scriptText, ...) → List<SceneSpec>`（不写库，输出结构化方案）与 `commitScenes(projectId, List<SceneSpec>)`（落库）；`generateScenes` 改为内部调用两者（保持 AIController 兼容）。
4. **意图→子流程映射**（等价 Dify 工作流路由）：intent-aisplit → 脚本方案 HITL（agree/disagree → agree 写分镜 + 回答文案）；intent-pic → 完善图片链路（PicUrl 存在 → refine 方案 → confirm_result 卡片 继续完善/满意完成）；intent-video → 图生视频方案（有 PicUrl）或文生视频（直接生成）；intent-other → 纯回答。
5. **主回答模型**：Dify 的应用模型由"后端调网关 chat"替代——新 `AgentAnswerService`（ChatClient，defaultVisionModel，拼历史消息，非流式一次性回答 or 流式打字机）；**决策点 D3：回答走流式还是非流式**（现有前端 SSE 打字机按 message 事件逐段拼接，流式体验一致；先做非流式一次 message 事件，前端零改动，流式列为后续增强）。
6. **不做**：ChatMemory/RAG/MCP/多智能体（YAGNI）；entity() 带 response_format 的升级（保持纯解析）；Dify 相关表数据迁移（旧 dify_conversation_id 值废弃，会话历史保留在 agent_messages）。

## 分阶段任务

### Phase 0 — Spike（@Tool + 工具循环 + checkpoint 恢复的运行时验证）

- Task 0.1: 隔离 spike 验证 `@Tool` 注册 + ChatClient 自动工具调用循环（真实网关，工具 = 返回固定结果的最小 @Tool）→ 证据 ledger；重点：工具参数 JSON schema 生成、循环 maxSteps 控制、工具异常传递
- Task 0.2: 验证 ChatClient 手动循环模式（ToolCallingManager + while loop，用户控制工具执行——官方 user-controlled 模式，编排控制力更强）与自动模式对比 → 选定一种写进 README
- Task 0.3: 汇报 D3（回答流式与否）+ 循环模式结论

### Phase 1 — DB

- Task 1.1: V3 migration `agent_checkpoints` 表（字段见决策 2）+ conversations 表 dify_conversation_id 列**暂不删**（最后阶段删）
- Task 1.2: 实体 AgentCheckpoint + Mapper

### Phase 2 — 编排核心

- Task 2.1: `AgentTools`（@Tool 类，方法体复用现有 service，含参数校验/幂等）
- Task 2.2: `AgentOrchestrator`（状态机：INTENT → PLAN → HITL checkpoint → EXECUTE → 结果；maxSteps 3、单步超时、幂等、审计日志；异常 → SSE error 事件）
- Task 2.3: `AgentAnswerService`（主回答 ChatClient，拼历史，message 事件发射）
- Task 2.4: ScriptGenerationService propose/commit 拆分（AIController 兼容）

### Phase 3 — AgentChatService 改造

- Task 3.1: streamMessage 改走 Orchestrator（保留 SSE 协议形状：message/workflow/human_input/message_end/error；保留 title rename 触发点、历史加载、消息落库）
- Task 3.2: sendMessage（blocking）改走 Orchestrator 非流式分支
- Task 3.3: HITL 表单提交（form/submit + video/plan/generate + confirm-done）改读 agent_checkpoints（替换内存 Map）
- Task 3.4: 删 callDifyChat / forwardDifySse / mergedHitlContent / cacheFormSnapshot / takeFormSnapshot（Dify 协议层整体删除）

### Phase 4 — 删除 Dify 残留

- Task 4.1: 删 DifyAgentController + dify DTO（DifyGenerateImageRequest/ScriptRequest/VideoRequest/AgentFormSubmitRequest 若仅 Dify 用）
- Task 4.2: 删 DifyApiKeyFilter + SecurityConfig 白名单条目
- Task 4.3: AiConfigProperties 删 difyApiKey/difyBaseUrl + yml 删 ai.laozhang.dify-* + .env.example 同步
- Task 4.4: V4 migration 删 conversations.dify_conversation_id 列（agent_messages.dify_message_id 同删）

### Phase 5 — 验证

- Task 5.1: 后端全量编译 + 启动
- Task 5.2: e2e 冒烟（真网关）：建会话 → 发"帮我做个清朝灭亡的分镜" → SSE 收 human_input → form/submit(agree) → 收 message_end + 分镜落库；发"把这张图调亮"（带 PicUrl）→ refine → confirm 卡片链路；发视频诉求 → video_plan → generate 链路
- Task 5.3: 前端零改动回归（tsc + build）+ 浏览器手测 Moon 抽屉
- Task 5.4: 清理无用 import/常量 + CLAUDE.md 更新（删 Dify 段、补编排架构）

### Phase 6 — 整分支审查（requesting-code-review）

## 变更文件清单（预估）

- Create: `service/agent/AgentOrchestrator.java`、`service/agent/AgentTools.java`、`service/agent/AgentAnswerService.java`、`entity/AgentCheckpoint.java`、`mapper/AgentCheckpointMapper.java`、`db/migration/V3__agent_checkpoints.sql`、`V4__drop_dify_columns.sql`
- Modify: `service/agent/AgentChatService.java`（大改）、`service/ai/ScriptGenerationService.java`（propose/commit 拆分）、`config/SecurityConfig.java`、`service/ai/AiConfigProperties.java`、`application.yml`、`.env.example`、`CLAUDE.md`
- Delete: `controller/DifyAgentController.java`、`security/DifyApiKeyFilter.java`、`dto/request/DifyGenerate*.java`（按引用清理）、dify_message_id/dify_conversation_id 列

## 验证命令

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
cd E:/Desktop/AI-storyboard/AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```

## 风险与开放问题

| # | 风险 | 处理 |
|---|------|------|
| R1 | @Tool 工具循环 2.0 运行时行为未验（自动 vs 手动循环） | Phase 0 spike 实测选定 |
| R2 | HITL 表单 token 语义：前端传 formToken/taskId/action，新 checkpoint 的 form_token 需与现有前端传参对齐 | 对照 AgentFormSubmitRequest 字段逐一对齐；前端零改动是硬约束 |
| R3 | 完善图片链路现有 confirm_result 卡片（继续完善/满意完成）与 video_plan 卡片的动作映射 | 迁移时逐个对照现有 dispatchGeneration 的 action 分发 |
| R4 | 回答体验差异（Dify 应用模型 vs 新 AgentAnswerService） | D3 决策：先非流式一次 message 事件（前端打字机仍可用），流式列为后续 |
| R5 | 旧会话 dify_conversation_id 失效（历史会话无法续聊） | 可接受：旧会话只读历史，新消息走新编排 |
| R6 | 并发：标题异步线程 vs checkpoint 写库 | checkpoint 独立事务（TransactionTemplate REQUIRES_NEW，参照标题服务）；checkpoint 更新用 LambdaUpdateWrapper + status 原子条件 |
| R7 | SSE 事件时序回归（message_end 携带 sceneCount/title 一次性推送） | 保留现有 messageEndPayload 逻辑，e2e 逐事件断言 |

## 执行方式

- subagent-driven-development：每 Task 独立子代理（携带本计划对应段落 + 技能 `spring-ai-2-0-development` evidence-first 规则），规格审查 → 代码质量审查
- 红线：前端零改动（SSE 事件字段名/顺序不变）；master 直接开发；git add 只加计划内文件；Java 中文注释
- Phase 0 spike 结论（D3 + 循环模式）先汇报用户确认再进 Phase 2
