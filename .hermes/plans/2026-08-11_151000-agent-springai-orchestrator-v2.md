# Moon 智能体脱离 Dify → Spring AI 2.0 编排 实施计划（v2 修订版）

> **For Hermes:** 用 subagent-driven-development 逐任务执行（每任务独立子代理 + 规格审查 + 代码质量审查）。
>
> **v2 修订说明**（2026-08-11，基于 v1 `2026-08-11_110000-agent-springai-orchestrator.md` + 项目现状复核）：本次复核了 Moon智能体.yml（1853 行全节点）、AgentChatService 全文关键段、前端 agentStore/agent.ts、migration V1-V3、SecurityConfig、application.yml。**v1 整体方向正确（架构/分层/SSE 协议不变/前端零改动），但有以下必须修正的点**：
> - **M1 迁移编号过期**：V3 已被 `V3__agent_assets_url_nullable.sql` 占用 → agent_checkpoints 用 **V4**、删 dify 列用 **V5**（v1 写 V3/V4，会冲突）。
> - **M2 aisplit 链路漏了「剧本优化设计 + 剧情确认」阶段**：YAML 实际链 = 剧本优化设计(LLM) → 剧情确认(type==1 gate) → 分镜方案设计 → 分镜JSON生成 → 人工介入3(满意/不满意) → agree 写分镜。v1 决策 4 只写了 HITL 一段，须补 SCRIPT_OPTIMIZE 步骤。
> - **M3 决策 3（ScriptGenerationService propose/commit 拆分）多余**：分镜写库已有现成实现 `AgentGenerationService.writeScript(projectId, List<SceneItem>)`（dispatchGeneration action=agree 正在用）。编排直接复用，只把 `DifyGenerateScriptRequest.SceneItem` 换成 agent 自有类型 `AgentSceneItem`。ScriptGenerationService（AIController 路径）零改动。
> - **M4 视频路径语义修正**：YAML 视频路由 = 无 PicUrl → 视频方案设计 LLM + HITL「生成视频确认」(generate_video/refine)；有 PicUrl → 后端视觉方案信号 → video_plan 卡片。v1 写"文生视频（直接生成）"不对——无图也有 LLM 方案 + HITL 确认。
> - **M5 图片路径语义修正**：YAML 条件分支3 = pic_generate_talk 空 **OR** picture 空 → 后端 AUTO_REFINE 完善信号；两者都非空 → 图片方案设计 LLM + HITL。v1 决策 4 的"PicUrl 存在 → refine"描述不完整。
> - **M6 可提取性（用户新要求）未覆盖**：agent 将来独立为单独模块 → 新增决策 7：新代码全部收 `service/agent` 包，工具面只依赖 service 接口与 agent 自有实体/mapper，checkpoint 表随模块走；不新建 Maven 模块（YAGNI），只保证包边界与依赖方向，CLAUDE.md 记录提取清单。
> - **M7 设计文档引用不存在**：v1 引用"技能 dify-migration-design.md"，实际没有该文件 → 改为 docs/spring-ai-2-0-agent/SKILL.md（orchestration-patterns.md / agent-patterns.md）。
> - **M8 Dify conversation variables 状态源未定义**（picture / storage_pic_talk / script_prompt 随 Dify 消亡）→ 新增决策 8。
> - **M9 Phase 4 删除清单需区分**：AgentFormSubmitRequest 是前端 form/submit 端点用的 → **保留**；DifyGenerateScriptRequest 的 SceneItem 被 writeScript 引用 → 先换 AgentSceneItem 再删。DifyAgentController 已无任何调用方（YAML 无 http-request 节点、前端 0 引用）→ 确认后删。

**Goal:** 用 Spring AI 2.0 应用层编排（ChatClient + @Tool + 结构化输出 + HITL checkpoint）替换 AgentChatService 的 Dify 协议代理层（~1630 行中的 Dify 耦合段），SSE 事件协议不变 → 前端零改动；删掉 Dify 全部残留（DifyAgentController / DifyApiKeyFilter / dify DTO / dify_conversation_id / ai.laozhang.dify-* 配置）；编排具备可拓展性，agent 模块边界清晰以便将来整体提取为独立模块。

**Architecture:**
- 分层：ChatClient 边界 + @Tool 工具（方法体复用现有 service）+ 应用层状态机（bounded loop）+ HITL checkpoint 落 DB
- 编排循环：INTENT（现有 IntentRecognitionService）→ PLAN（结构化输出方案）→ HITL checkpoint（如需人工确认）→ EXECUTE（@Tool → 现有生成 service）→ 结果推送；maxSteps/deadline/幂等/审计
- HITL：WAITING_FOR_HUMAN checkpoint 落新表 agent_checkpoints（action/form_token/过期时间/plan 快照/step）；表单提交端点不变（/api/agent/conversations/{id}/form/submit + /video/plan/generate + /confirm-done），前端零改动
- 记忆：继续用本地 agent_messages 拼历史（不引入 ChatMemory）；标题重命名/提示词优化流程原样保留
- SSE 协议不变：message / workflow / human_input / message_end / confirm_result / video_plan / error（前端 SseEvent 类型已验证包含全部；**前端不消费 workflow 事件**，编排保留发射即可，成本一行）

**Tech Stack:** Spring AI 2.0.0（已引入）+ 现有 AgentGenerationService/ImageGenerationService/VideoGenerationService/ImageRefinePromptService/VideoPlanService/IntentRecognitionService + PostgreSQL（agent_checkpoints 新表 V4）

---

## 现状核实（2026-08-11 复核，含行号）

| 文件 | 现状 | 角色 |
|------|------|------|
| `service/agent/AgentChatService.java` | 1630 行 | Dify 代理 + 业务编排混合体 |
| `controller/DifyAgentController.java` | 402 行，`/api/ai/dify/**`（generate-script / generate-image JSON+multipart / generate-video / generate-video/status） | **已无调用方**（YAML 无 http-request 节点；前端 0 引用）→ 可删 |
| `controller/AgentConversationController.java` | 279 行 | 前端会话端点（**保留**，调 AgentChatService） |
| `security/DifyApiKeyFilter.java` | 50 行 | /api/ai/dify/** API Key 鉴权 → 删 |
| `service/agent/AgentGenerationService.java` | ~169 行 | 无 sceneId 生成落库；**含 `writeScript(projectId, List<SceneItem>)`**（agree 写分镜现成实现） |
| migration | V1 / V2（conversations+agent_messages+agent_assets，含 dify_conversation_id / dify_message_id 列）/ **V3 已存在**（agent_assets.url 可空） | V4 = agent_checkpoints；V5 = 删 dify 列 |
| `config/SecurityConfig.java:66` | `/api/ai/dify/**` permitAll | 删（DifyApiKeyFilter 同删） |
| `application.yml:58,60` + AiConfigProperties | `ai.laozhang.dify-api-key`（AI_DIFY_API_KEY）/ `dify-base-url`（DIFY_BASE_URL） | 删 |
| `dto/request/AgentFormSubmitRequest` | formToken/taskId/action | **保留**（form/submit 端点用） |

**AgentChatService 中 Dify 耦合方法（删/改）：** `callDifyChat`、`forwardDifySse`(601-822，SSE 协议解析/事件转发/信号监听/HITL 暂停)、`confirmImageDone`(222，清 Dify storage_pic_talk)、`sendMessage`(342)、`streamMessage`(456)、`mergedHitlContent`/`cacheFormSnapshot`/`takeFormSnapshot`/`dispatchGeneration`(1026-1201)、内存态快照 Map×4（formSnapshots / lastNodeOutputs / lastPicUrlByConversation / lastFormContentByConversation / videoPlanSnapshots）。

**信号节点常量（与 YAML 已逐一核对一致，迁移后改为编排内直接调用）：** `AUTO_REFINE_SIGNAL_TITLE="后端执行识别图片加人工介入流程"`（YAML node 1786086274364 ✓）、`VIDEO_PLAN_SIGNAL_TITLE="后端执行图生视频方案设计"`（YAML node 1786091427703 ✓，**注意该节点在"图片判断"的 false 分支=有参考图时到达**）。

**保留不动：** conversation/message/asset 的 DB 操作（createConversation/getOwnedConversation/listMessages/clearMessages）、`maybeScheduleTitleRename`(305)、`loadRecentHistory`(549)、`pushGenerationResult`(1210)、`executeVideoGeneration`(1243)、`generateVideoFromPlan`(1263)、`triggerAutoImageRefine`(838)/`triggerAutoVideoPlan`(917) 的生成逻辑主体（其 Dify 信号触发点改为编排内直接调用）。

## 关键设计决策（v2 修订版）

1. **@Tool 工具面**（`AgentTools` @Component，方法体复用现有 service）：
   - `writeScenes(projectId, items)` → **复用 `AgentGenerationService.writeScript`**（参数类型换 `AgentSceneItem`，替代 DifyGenerateScriptRequest.SceneItem）→ pushGenerationResult
   - `refineImage(conversationId, prompt, picUrl)` → AgentGenerationService.generateImage(sceneId=null, mode=edit) → pushGenerationResult（= 现 triggerAutoImageRefine 主体）
   - `generateVideo(conversationId, prompt, duration)` → VideoGenerationService.createVideoTask + 轮询（= 现 executeVideoGeneration 主体）
   - 工具参数校验/幂等（action 已确认才执行；工具结果稳定错误对象）
2. **HITL checkpoint**（新表 `agent_checkpoints`，V4）：conversation_id、action、form_token（= resume_token，一次性）、plan JSONB 快照（原 lastNodeOutputs 内容）、step、expiration_time（30min，对齐现 FORM_SNAPSHOT_TTL_MS）、status(pending|used|expired)、created_at。表单提交端点（form/submit + video/plan/generate）校验 token + 归属 + 未过期 → status=used（LambdaUpdateWrapper + status 原子条件）→ 恢复对应 step 执行。**action 值全集（已核实）**：表单卡片 `agree`/`disagree`/`generate_image`/`generate_video`（+ `refine` 不触发仅续流）、confirm_result 卡片 `refine`("继续完善")/`done`("满意完成")（done 由 /confirm-done 端点落库）。**顺带修复：现 formSnapshots/videoPlanSnapshots 是内存 Map，重启即失。**
3. **aisplit 编排状态**（补 M2）：`SCRIPT_OPTIMIZE`（LLM 优化剧本，结构化 {type:1|0, message, script}；type=0 → 回答 message 结束本轮，等用户继续；type=1 → 剧本存 script_prompt 状态 → 继续）→ `STORYBOARD_PLAN`（LLM 分镜方案设计 {type, message}）→ `STORYBOARD_JSON`（LLM 分镜 JSON {items: [8 字段 SceneItem]}）→ HITL（满意/不满意）→ agree → `writeScenes` + message + confirm_result(kind=script)；disagree → 回答「请继续完善设计方案」。三段 LLM 提示词模板从 YAML 对应节点搬运（deepseek-v4-pro 分镜方案设计 / v4-flash 其余，映射到网关模型由实现时定）。
4. **意图→子流程映射**（对齐 YAML 实际路由）：
   - `intent-aisplit` → 决策 3 链（含剧本优化 gate）
   - `intent-pic` → 会话有图片上下文（storage_pic_talk 等价态：最近方案文本 + 最近图都存在）→ 图片方案设计 LLM {type,message,style,size} → HITL「确认图片方案」(generate_image/refine)；否则 → AUTO_REFINE 自动完善（视觉看图 + 最新 user 诉求 → 图生图 → confirm_result 卡片 继续完善/满意完成）。**与 YAML 条件分支3 语义一致**（pic_generate_talk 空 OR picture 空 → 完善信号）
   - `intent-video` → 有 PicUrl → 后端视觉方案（VideoPlanService → video_plan 卡片 generate_video/refine）；无 PicUrl → 视频方案设计 LLM {message, duration, aspectRatio} → HITL「生成视频确认」(generate_video/refine) → generate_video → MiniMax 文生视频
   - `intent-other` → AgentAnswerService（LLM 回答，等价原「引导回复」，体验升级）
5. **主回答模型**：新 `AgentAnswerService`（ChatClient，defaultVisionModel，拼历史，**先非流式**一次 message 事件——前端打字机按 message 事件逐段拼接，整段也兼容；流式列为后续增强，Phase 0 汇报 D3 结论）。
6. **不做**：ChatMemory/RAG/MCP/多智能体（YAGNI）；entity() 带 response_format（保持纯解析 + BeanOutputConverter）；Dify 历史数据迁移（旧 dify_conversation_id 值废弃，会话历史保留在 agent_messages）。
7. **可提取性边界**（补 M6）：所有新代码（AgentOrchestrator / AgentTools / AgentAnswerService / AgentCheckpoint 实体+Mapper / AgentSceneItem）放 `service/agent`（与 agent 自有 entity/mapper 同包）；AgentTools 构造注入的**外部 service 清单**（ImageGenerationService / VideoGenerationService / FileStorageService / ProjectMapper / IntentRecognitionService 等）记录进 CLAUDE.md「agent 模块提取依赖清单」——将来拆独立 Maven 模块时，这些是模块边界依赖；checkpoint/会话/消息/资产表随模块走。**不新建模块**（YAGNI），只保包边界与依赖方向。
8. **Dify conversation variables 状态源映射**（补 M8）：`picture` ↔ 本轮 PicUrl / lastPicUrlByConversation / agent_assets(reference)；`storage_pic_talk{pic_generate_talk,picture,user_finishing}` ↔ 会话级图片上下文（内存态：最近方案文本 + 最近图 + 最近诉求=最新 user 消息；**confirm-done 端点保留**（前端零改动），语义改为清空该会话图片上下文）；`script_prompt` ↔ aisplit 流程内传递（type=1 后暂存会话状态；持久化位置=项目 script 字段或会话上下文，实现时确认，列为开放问题 O1）。

## 分阶段任务

### Phase 0 — Spike（@Tool + 工具循环 + checkpoint 恢复的运行时验证）

- Task 0.1: 隔离 spike 验证 `@Tool` 注册 + ChatClient 自动工具调用循环（真实网关，工具 = 返回固定结果的最小 @Tool）→ 证据 ledger；重点：工具参数 JSON schema 生成、循环 maxSteps 控制、工具异常传递
- Task 0.2: 验证 ChatClient 手动循环模式（ToolCallingManager + while loop）与自动模式对比 → 选定一种写进 README
- Task 0.3: 汇报 D3（回答流式与否）+ 循环模式结论，用户确认后再进 Phase 2

### Phase 1 — DB

- Task 1.1: **V4** migration `agent_checkpoints`（字段见决策 2）+ conversations 表 dify_conversation_id 列**暂不删**（Phase 4 删）
- Task 1.2: 实体 AgentCheckpoint + Mapper

### Phase 2 — 编排核心

- Task 2.1: `AgentTools`（@Tool 类，含 writeScenes/refineImage/generateVideo；`AgentSceneItem` record 替代 DifyGenerateScriptRequest.SceneItem，AgentGenerationService.writeScript 签名改用它）
- Task 2.2: `AgentOrchestrator`（状态机：INTENT → 决策 3/4 链 → HITL checkpoint → EXECUTE → 结果；maxSteps、单步超时、幂等、审计日志；异常 → SSE error 事件）
- Task 2.3: `AgentAnswerService`（主回答 ChatClient，拼历史，message 事件发射）
- Task 2.4: ~~ScriptGenerationService propose/commit 拆分~~（**已取消**，M3：复用 writeScript；ScriptGenerationService 零改动）

### Phase 3 — AgentChatService 改造

- Task 3.1: streamMessage 改走 Orchestrator（保留 SSE 协议形状：message/workflow/human_input/message_end/confirm_result/video_plan/error；保留 title rename 触发点、历史加载、消息落库、I2 合并语义）
- Task 3.2: sendMessage（blocking）改走 Orchestrator 非流式分支
- Task 3.3: HITL 表单提交（form/submit + video/plan/generate + confirm-done）改读 agent_checkpoints（替换 4 个内存 Map；confirm-done 改为清会话图片上下文）
- Task 3.4: 删 callDifyChat / forwardDifySse / mergedHitlContent / cacheFormSnapshot / takeFormSnapshot / dispatchGeneration 的 Dify 取值逻辑（Dify 协议层整体删除）

### Phase 4 — 删除 Dify 残留

- Task 4.1: 删 DifyAgentController（删前 grep 全仓确认无调用方）+ DifyGenerateImageRequest/DifyGenerateVideoRequest/DifyGenerateScriptRequest（SceneItem 已由 AgentSceneItem 替代）；**保留 AgentFormSubmitRequest**（form/submit 用）
- Task 4.2: 删 DifyApiKeyFilter + SecurityConfig `/api/ai/dify/**` 条目
- Task 4.3: AiConfigProperties 删 difyApiKey/difyBaseUrl + yml 删 `ai.laozhang.dify-*` + .env.example 同步
- Task 4.4: **V5** migration 删 conversations.dify_conversation_id + agent_messages.dify_message_id 列（先删代码引用再删列）

### Phase 5 — 验证

- Task 5.1: 后端全量编译 + 启动
- Task 5.2: e2e 冒烟（真网关）：建会话 → 发"帮我做个清朝灭亡的分镜" → SSE 收剧本优化 message → 发"满意" → 收 human_input（分镜方案）→ form/submit(agree) → 收 message_end + 分镜落库；发"把这张图调亮"（带 PicUrl）→ AUTO_REFINE → confirm_result 卡片链路；发"根据参考图做个视频"（带 PicUrl）→ video_plan 卡片 → generate → 视频落库；发纯文字视频诉求 → 视频方案 LLM → HITL → 生成
- Task 5.3: 前端零改动回归（tsc + build）+ 浏览器手测 Moon 抽屉
- Task 5.4: 清理无用 import/常量 + CLAUDE.md 更新（删 Dify 段、补编排架构 + **agent 模块提取依赖清单**）

### Phase 6 — 整分支审查（requesting-code-review）

## 变更文件清单（预估）

- Create: `service/agent/AgentOrchestrator.java`、`service/agent/AgentTools.java`、`service/agent/AgentAnswerService.java`、`service/agent/AgentSceneItem.java`（或 dto/agent 包）、`entity/AgentCheckpoint.java`、`mapper/AgentCheckpointMapper.java`、`db/migration/V4__agent_checkpoints.sql`、`V5__drop_dify_columns.sql`
- Modify: `service/agent/AgentChatService.java`（大改）、`service/agent/AgentGenerationService.java`（writeScript 参数类型换 AgentSceneItem）、`config/SecurityConfig.java`、`service/ai/AiConfigProperties.java`、`application.yml`、`.env.example`、`CLAUDE.md`
- Delete: `controller/DifyAgentController.java`、`security/DifyApiKeyFilter.java`、`dto/request/DifyGenerate*.java`、dify_message_id/dify_conversation_id 列

## 验证命令

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21"
"/e/Development/apache-maven-3.9.15/bin/mvn.cmd" -f "E:\\Desktop\\AI-storyboard\\AIStoryboardBackend\\pom.xml" compile -q
cd E:/Desktop/AI-storyboard/AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build
```

## 风险与开放问题

| # | 风险/问题 | 处理 |
|---|------|------|
| R1 | @Tool 工具循环 2.0 运行时行为未验（自动 vs 手动循环） | Phase 0 spike 实测选定，汇报后进 Phase 2 |
| R2 | HITL 表单 token 语义：前端传 formToken/taskId/action，新 checkpoint 的 form_token 需与现有前端传参对齐（video/plan/generate 走 planToken） | 对照 AgentFormSubmitRequest + submitVideoPlan 字段逐一映射；前端零改动是硬约束 |
| R3 | confirm_result 卡片动作映射（继续完善=本地关卡+picUrl 保留 / 满意完成=confirm-done 端点） | 编排保留 pushGenerationResult 语义 + confirm-done 端点（语义改为清会话图片上下文） |
| R4 | 回答体验差异（Dify 应用模型 vs 新 AgentAnswerService） | D3 决策：先非流式一次 message 事件（前端打字机兼容），流式后续 |
| R5 | 旧会话 dify_conversation_id 失效（历史会话无法续聊） | 可接受：旧会话只读历史，新消息走新编排 |
| R6 | 并发：标题异步线程 vs checkpoint 写库 | checkpoint 独立事务（TransactionTemplate REQUIRES_NEW，参照标题服务）；更新用 LambdaUpdateWrapper + status 原子条件 |
| R7 | SSE 事件时序回归（message_end 携带 sceneCount/title 一次性推送、I2 消息合并） | 保留 messageEndPayload/I2 逻辑，e2e 逐事件断言 |
| R8 | 图片上下文语义：YAML「pic_generate_talk 空 OR picture 空 → 完善信号」导致首次无图纯文生图诉求报"未检测到参考图片"（现网行为） | **行为等价迁移**，不借机改动；若用户后续在 Dify UI 调整该分支语义，编排同步（用户自己改工作流，见约定） |
| O1 | script_prompt 确认后剧本持久化位置（project.script 字段是否存在待查） | Phase 2 实现时确认；无字段则会话上下文内存态 + CLAUDE.md 注明 |

## 执行方式

- subagent-driven-development：每 Task 独立子代理（携带本计划对应段落 + 技能 `spring-ai-2-0-development` evidence-first 规则 + `ai-storyboard-dev` 项目规范），规格审查 → 代码质量审查
- 红线：前端零改动（SSE 事件字段名/顺序不变）；master 直接开发；git add 只加计划内文件；Java 中文注释
- Phase 0 spike 结论（D3 + 循环模式）先汇报用户确认再进 Phase 2
