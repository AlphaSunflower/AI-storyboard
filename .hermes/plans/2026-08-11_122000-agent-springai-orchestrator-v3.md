# Moon 智能体 Spring AI 2.0 化 实施计划（v3 修订版）

> **For Hermes:** 用 subagent-driven-development 逐任务执行（每任务独立子代理 + 规格审查 + 代码质量审查）。
>
> **v3 修订说明**（基于 v2 `2026-08-11_151000-agent-springai-orchestrator-v2.md` + 全量现状复核）：v2 计划写于**「后端 Service 接口化 + Controller 薄化」重构（c48e231/0fd3cc4/532e130/c415a73）之前**，以下必须修正：
> - **P1 文件路径全部过时**：`AgentChatService.java`（v2 称 1630 行单体）已拆为接口 `service/agent/AgentChatService.java`（116 行）+ 实现 `service/agent/impl/AgentChatServiceImpl.java`（**1715 行**）；`DifyAgentController` 已薄化为 112 行（业务下沉）；`AgentGenerationService` 已接口化（46 行接口 + 149 行 impl）；`AgentConversationController` 205 行。v2 全部文件路径/行号按下方「现状核实」替换。
> - **P2 新增 DifyAgentService 层（v2 漏了）**：重构抽出了 `service/agent/DifyAgentService.java`（接口 61 行）+ `service/agent/impl/DifyAgentServiceImpl.java`（**378 行**，含 sanitize 真实现）——Phase 4 删除清单须加这 2 个文件。
> - **P3 sanitize 静态依赖**：`AgentGenerationServiceImpl`（75-129 行多处）调用 `DifyAgentController.sanitize(...)` 静态转发（→ `DifyAgentServiceImpl.sanitize`）。删 DifyAgentController 前必须先迁移 sanitize（抽到 AgentGenerationServiceImpl 私有方法或工具类），否则删完编译失败。v2 未覆盖。
> - **P4 内存 Map 行号更新**：formSnapshots(1138)/lastNodeOutputs(1140)/lastPicUrlByConversation(1143)/lastFormContentByConversation(1145)/videoPlanSnapshots(1164)，takeVideoPlanSnapshot(1167)/takeFormSnapshot(1199) 均为 impl 内私有方法；`FormSnapshot` 已是顶层 record（23 行，单文件单类规范已落地）。
> - **P5 关键方法行号**（impl 内）：confirmImageDone(225)、maybeScheduleTitleRename(308)、sendMessage(436)、callDifyChat(494)、streamMessage(550)、buildChatBody(626)、loadRecentHistory(643)、sendEvent(653)、messageEndPayload(666)、mergedHitlContent(1120)、generateVideoFromPlan(1348)、clearDifyVariable(1387)、pollVideoAndPush(1450)、parseScenes(1483)、localizeDifyFileUrls(1557)、submitFormAndResume(1582)、persistUserConfirmation(1694)。forwardDifySse 位于 676-1120 区段。
> - **P6 其余 v2 结论全部维持**：M1 迁移编号 V4/V5、M2 aisplit 剧本优化 gate、M3 复用 writeScript、M4/M5 图片视频路径语义、M6 可提取性、M7 文档引用、M8 状态源映射、M9 删除清单甄别（AgentFormSubmitRequest 保留；DifyAgentController 无调用方确认）。
> - **P7 用户新要求（2026-08-11 追加）**：① agent 将来要**独立为单独的 AI agent 服务** → 决策 7 强化为端口抽象（编排层只依赖 agent 自有端口接口，主后端用现有 service 实现，拆独立服务时换远程实现，编排零改动）；② 后续要对 agent 做**自主循环增强（一直循环直到完成目标）** → 决策 3 强化为显式状态机（状态枚举 + 转移表 + step 计数 + REVIEW 完成度评估），循环 = 转移表加边而非重写；maxSteps/墙钟 deadline/无进展检测为硬约束（防死循环烧钱）。

**Goal:** 用 Spring AI 2.0 应用层编排（ChatClient + @Tool + 结构化输出 + HITL checkpoint）替换 AgentChatServiceImpl 的 Dify 协议代理层（1715 行中的 Dify 耦合段），SSE 事件协议不变 → 前端零改动；删掉 Dify 全部残留（DifyAgentController / DifyAgentService / DifyApiKeyFilter / dify DTO / dify_conversation_id / ai.laozhang.dify-* 配置）；agent 模块边界清晰以便将来整体提取为独立模块。

**Architecture:**
- 分层：ChatClient 边界 + @Tool 工具（方法体复用现有 service）+ 应用层状态机（bounded loop）+ HITL checkpoint 落 DB
- 编排循环：INTENT（现有 IntentRecognitionService）→ PLAN（结构化输出）→ HITL checkpoint（如需人工确认）→ EXECUTE（@Tool → 现有生成 service）→ 结果推送；maxSteps/deadline/幂等/审计
- **循环模式（Phase 0 spike 实测定案，2026-08-11）**：**手动+自动混合**——需求澄清阶段用手动循环（ToolCallingManager + while loop，逐轮确认需求/方案，LLM 随机跳过工具时有重试提醒）；用户确认（HITL checkpoint 落库）后进入**全自动执行**（ChatClient 自动模式 `.tools()`，ToolCallingAdvisor 内建循环一次跑完多步生成）；D3 定案：**主回答非流式**（一次 message 事件，前端打字机兼容，流式后续增强）
- HITL：WAITING_FOR_HUMAN checkpoint 落新表 agent_checkpoints（action/form_token/过期时间/plan 快照/step）；表单提交端点不变（/api/agent/conversations/{id}/form/submit + /video/plan/generate + /confirm-done），前端零改动
- 记忆：继续用本地 agent_messages 拼历史（不引入 ChatMemory）；标题重命名/提示词优化流程原样保留
- SSE 协议不变：message / workflow / human_input / message_end / confirm_result / video_plan / error（前端 agent.ts:45 SseEvent 类型已验证包含全部 7 种；**前端不消费 workflow 事件**，编排保留发射即可，成本一行）

**Tech Stack:** Spring AI 2.0.0（已引入）+ 现有 AgentGenerationService/ImageGenerationService/VideoGenerationService/ImageRefinePromptService/VideoPlanService/IntentRecognitionService + PostgreSQL（agent_checkpoints 新表 V4）

---

## 现状核实（2026-08-11 复核，接口化重构后，含行号）

| 文件 | 现状 | 角色 |
|------|------|------|
| `service/agent/AgentChatService.java` | 116 行接口 | 会话/消息/资产 CRUD + sendMessage/streamMessage/submitFormAndResume/generateVideoFromPlan 声明（**保留接口签名**，实现内部换编排） |
| `service/agent/impl/AgentChatServiceImpl.java` | **1715 行** | Dify 代理 + 业务编排混合体（Dify 耦合全在此） |
| `controller/DifyAgentController.java` | 112 行，`/api/ai/dify/**`（generate-script / generate-image JSON+multipart / generate-video / generate-video/status） | **已无调用方**（YAML 无 http-request 节点；前端 0 引用）→ 可删 |
| `service/agent/DifyAgentService.java` + `impl/DifyAgentServiceImpl.java` | 61 + **378 行** | DifyAgentController 业务逻辑（重构下沉）；**含 sanitize 真实现** → 删 |
| `controller/AgentConversationController.java` | 205 行 | 前端会话端点（**保留**，调 AgentChatService） |
| `security/DifyApiKeyFilter.java` | 50 行 | /api/ai/dify/** API Key 鉴权 → 删 |
| `service/agent/AgentGenerationService.java` + `impl/AgentGenerationServiceImpl.java` | 46 + 149 行 | **writeScript(projectId, List\<DifyGenerateScriptRequest.SceneItem\>)**（agree 写分镜现成实现，签名待换 AgentSceneItem）；**引用 DifyAgentController.sanitize ×8 处（75-129 行）——删 controller 前先迁移** |
| `service/agent/FormSnapshot.java` | 23 行顶层 record | 表单快照（已按单文件单类规范提取，v2 计划中"嵌套 record"已完成） |
| migration | V1 / V2（含 dify_conversation_id / dify_message_id 列）/ V3（agent_assets.url 可空） | V4 = agent_checkpoints；V5 = 删 dify 列 |
| `config/SecurityConfig.java` | 46-49 difyApiKeyFilter bean；66 `/api/ai/dify/**` permitAll；76 addFilterBefore(difyApiKeyFilter) | 删 |
| `application.yml` 57-60 + `service/ai/AiConfigProperties.java` | difyApiKey(38)/difyBaseUrl(55)/getter(169,185)/validateDifyConfig(317) | 删 |
| `dto/request/AgentFormSubmitRequest` | formToken/taskId/action | **保留**（form/submit 端点用） |
| 前端 `api/agent.ts` + `stores/agentStore.ts` | SseEvent 7 事件齐全；端点全走 `/api/agent/**`（stream/form/submit/video/plan/generate/confirm-done）；**/api/ai/dify 零引用** | 零改动 |

**AgentChatServiceImpl 中 Dify 耦合方法（删/改）：** `callDifyChat`(494)、`buildChatBody`(626)、`forwardDifySse`(676-1120 区段，SSE 协议解析/事件转发/信号监听/HITL 暂停)、`confirmImageDone`(225，清 Dify storage_pic_talk)、`sendMessage`(436)、`streamMessage`(550)、`mergedHitlContent`(1120)、`clearDifyVariable`(1387)、`localizeDifyFileUrls`(1557)、`dispatchGeneration` 相关（GENERATION_STAGE_LABELS 1207 起）、内存态快照 Map×5（formSnapshots 1138 / lastNodeOutputs 1140 / lastPicUrlByConversation 1143 / lastFormContentByConversation 1145 / videoPlanSnapshots 1164）。

**信号节点常量（与 YAML 已核对一致，迁移后改为编排内直接调用）：** `AUTO_REFINE_SIGNAL_TITLE="后端执行识别图片加人工介入流程"`（impl:122）、`VIDEO_PLAN_SIGNAL_TITLE="后端执行图生视频方案设计"`（impl:132）。

**保留不动：** conversation/message/asset 的 DB 操作（createConversation/getOwnedConversation/listMessages/clearMessages）、`maybeScheduleTitleRename`(308)、`loadRecentHistory`(643)、`pushGenerationResult`、`executeVideoGeneration`、`triggerAutoImageRefine`/`triggerAutoVideoPlan` 的生成逻辑主体（其 Dify 信号触发点改为编排内直接调用）。

## 关键设计决策（v3 修订版）

1. **@Tool 工具面**（`AgentTools` @Component，方法体复用现有 service）：
   - `writeScenes(projectId, items)` → **复用 `AgentGenerationService.writeScript`**（参数类型换 `AgentSceneItem`，替代 DifyGenerateScriptRequest.SceneItem）→ pushGenerationResult
   - `refineImage(conversationId, prompt, picUrl)` → AgentGenerationService.generateImage(sceneId=null, mode=edit) → pushGenerationResult（= 现 triggerAutoImageRefine 主体）
   - `generateVideo(conversationId, prompt, duration)` → VideoGenerationService.createVideoTask + 轮询（= 现 executeVideoGeneration 主体）
   - 工具参数校验/幂等（action 已确认才执行；工具结果稳定错误对象）
2. **HITL checkpoint**（新表 `agent_checkpoints`，V4）：conversation_id、action、form_token（= resume_token，一次性）、plan JSONB 快照（原 lastNodeOutputs 内容）、step、expiration_time（30min，对齐 FORM_SNAPSHOT_TTL_MS）、status(pending|used|expired)、created_at。表单提交端点（form/submit + video/plan/generate）校验 token + 归属 + 未过期 → status=used（LambdaUpdateWrapper + status 原子条件）→ 恢复对应 step 执行。**action 值全集（已核实）**：表单卡片 `agree`/`disagree`/`generate_image`/`generate_video`（+ `refine` 不触发仅续流）、confirm_result 卡片 `refine`("继续完善")/`done`("满意完成")（done 由 /confirm-done 端点落库）。**顺带修复：现 formSnapshots/videoPlanSnapshots 是内存 Map，重启即失。**
3. **aisplit 编排状态（显式状态机，支持循环增强）**：编排核心是**状态枚举 + 转移表 + step 计数**（非 if-else 链）。`SCRIPT_OPTIMIZE`（LLM 优化剧本，结构化 {type:1|0, message, script}；type=0 → 回答 message 结束本轮，等用户继续；type=1 → 剧本存 script_prompt 状态 → 转移 STORYBOARD_PLAN）→ `STORYBOARD_PLAN`（LLM 分镜方案设计 {type, message}）→ `STORYBOARD_JSON`（LLM 分镜 JSON {items: [8 字段 SceneItem]}）→ `WAITING_FOR_HUMAN`（满意/不满意）→ agree → `EXECUTE`（writeScenes + message + confirm_result(kind=script)）；disagree → 回答「请继续完善设计方案」→ 转移回 STORYBOARD_PLAN。三段 LLM 提示词模板从 YAML 对应节点搬运（deepseek-v4-pro 分镜方案设计 / v4-flash 其余，映射到网关模型由实现时定）。**为后续「循环直到完成目标」预留**：状态表含 REVIEW 位（LLM 评估 {done: bool, reason}，或规则校验），未完成 → 按转移表回退到相关状态；循环代价受 maxSteps / 墙钟 deadline / 无进展检测约束（每次 REVIEW 计一步）。当前版本实现线性链（REVIEW 仅占位），循环边后续追加——状态机形式保证这是加边而非重构。
4. **意图→子流程映射**（对齐 YAML 实际路由）：
   - `intent-aisplit` → 决策 3 链（含剧本优化 gate）
   - `intent-pic` → 会话有图片上下文（storage_pic_talk 等价态：最近方案文本 + 最近图都存在）→ 图片方案设计 LLM {type,message,style,size} → HITL「确认图片方案」(generate_image/refine)；否则 → AUTO_REFINE 自动完善（视觉看图 + 最新 user 诉求 → 图生图 → confirm_result 卡片 继续完善/满意完成）。**与 YAML 条件分支3 语义一致**（pic_generate_talk 空 OR picture 空 → 完善信号）
   - `intent-video` → 有 PicUrl → 后端视觉方案（VideoPlanService → video_plan 卡片 generate_video/refine）；无 PicUrl → 视频方案设计 LLM {message, duration, aspectRatio} → HITL「生成视频确认」(generate_video/refine) → generate_video → MiniMax 文生视频
   - `intent-other` → AgentAnswerService（LLM 回答，等价原「引导回复」，体验升级）
5. **主回答模型**：新 `AgentAnswerService`（ChatClient，defaultVisionModel，拼历史，**先非流式**一次 message 事件——前端打字机按 message 事件逐段拼接，整段也兼容；流式列为后续增强，Phase 0 汇报 D3 结论）。
6. **不做**：ChatMemory/RAG/MCP/多智能体（YAGNI）；entity() 带 response_format（保持纯解析 + BeanOutputConverter）；Dify 历史数据迁移（旧 dify_conversation_id 值废弃，会话历史保留在 agent_messages）。
7. **可提取性边界 + 端口抽象（补 M6 + P7）**：所有新代码（AgentOrchestrator / AgentTools / AgentAnswerService / AgentCheckpoint 实体+Mapper / AgentSceneItem）放 `service/agent`（接口）与 `service/agent/impl`（实现），遵循企业级分层规范；**编排层依赖外部能力一律走 agent 自有端口接口**（`AgentAgentPort` 或按业务拆分：写分镜端口 / 生图端口 / 生视频端口 / 项目读取端口），端口接口在 `service/agent/port` 包，实现在 `service/agent/port/impl`（方法体调 ImageGenerationService / VideoGenerationService / FileStorageService / ProjectMapper / IntentRecognitionService 等现有组件）——将来拆独立 agent 服务（独立进程/容器）时，端口实现换成远程调用实现（REST/gRPC 客户端），编排与 checkpoint/会话/消息/资产表随服务走，**编排代码零改动**；外部 service 依赖清单 + 拆解改造点记录进 CLAUDE.md「agent 模块提取清单」。
8. **Dify conversation variables 状态源映射**（补 M8）：`picture` ↔ 本轮 PicUrl / lastPicUrlByConversation / agent_assets(reference)；`storage_pic_talk{pic_generate_talk,picture,user_finishing}` ↔ 会话级图片上下文（内存态：最近方案文本 + 最近图 + 最近诉求=最新 user 消息；**confirm-done 端点保留**（前端零改动），语义改为清空该会话图片上下文）；`script_prompt` ↔ aisplit 流程内传递（type=1 后暂存会话状态；持久化位置=项目 script 字段或会话上下文，实现时确认，列为开放问题 O1）。
9. **sanitize 迁移（新增，P3）**：`DifyAgentController.sanitize` 静态转发被 `AgentGenerationServiceImpl` 引用 8 处（75-129 行）。Phase 4 删 controller 前：把 sanitize 逻辑抽为 `AgentGenerationServiceImpl` 私有静态方法（实现 = 现 DifyAgentServiceImpl.sanitize 的 copy），或抽工具类 `service/agent/AgentParamSanitizer`（@Component 或静态工具，按团队规范选）；DifyAgentService/DifyAgentServiceImpl 删除后，原 sanitize 实现在新位置保留唯一副本。
10. **DifyAgentService 层删除（新增，P2）**：v2 只列了 DifyAgentController；重构后业务逻辑在 `DifyAgentService` + `DifyAgentServiceImpl`（378 行，含 generateScript/generateImage/generateImageMultipart/generateVideo/pollVideoStatus + sanitize）。该层全部删除（无 controller 即无调用方；AgentGenerationService 是独立接口，不受影响——它已在 HITL 路径直接使用）。

## 分阶段任务

### Phase 0 — Spike（@Tool + 工具循环 + checkpoint 恢复的运行时验证）

- Task 0.1: 隔离 spike 验证 `@Tool` 注册 + ChatClient 自动工具调用循环（真实网关，工具 = 返回固定结果的最小 @Tool）→ 证据 ledger；重点：工具参数 JSON schema 生成、循环 maxSteps 控制、工具异常传递
- Task 0.2: 验证 ChatClient 手动循环模式（ToolCallingManager + while loop）与自动模式对比 → 选定一种写进 README
- Task 0.3: 汇报 D3（回答流式与否）+ 循环模式结论，用户确认后再进 Phase 2

### Phase 1 — DB

- Task 1.1: **V4** migration `agent_checkpoints`（字段见决策 2）+ conversations 表 dify_conversation_id 列**暂不删**（Phase 4 删）
- Task 1.2: 实体 AgentCheckpoint + Mapper（`entity/AgentCheckpoint.java` + `mapper/AgentCheckpointMapper.java`）

### Phase 2 — 编排核心

- Task 2.1: `AgentSceneItem` record（替代 DifyGenerateScriptRequest.SceneItem；字段 8 个对齐现 SceneItem）→ `AgentGenerationService.writeScript` 签名改用它 → 编译通过
- Task 2.2: `AgentTools`（@Tool 类，含 writeScenes/refineImage/generateVideo；构造注入现有 service；放 `service/agent/impl`，接口如需则 `service/agent`）
- Task 2.3: `AgentOrchestrator`（**显式状态机**：状态枚举 + 转移表 + step 计数；INTENT → 决策 3/4 链 → HITL checkpoint → EXECUTE → 结果；maxSteps、墙钟 deadline、单步超时、幂等、审计日志；REVIEW 完成度评估占位（后续循环增强用）；异常 → SSE error 事件）
- Task 2.4: `AgentAnswerService`（主回答 ChatClient，拼历史，message 事件发射）

### Phase 3 — AgentChatServiceImpl 改造

- Task 3.1: streamMessage(550) 改走 Orchestrator（保留 SSE 协议形状：message/workflow/human_input/message_end/confirm_result/video_plan/error；保留 title rename 触发点、历史加载、消息落库、I2 合并语义）
- Task 3.2: sendMessage(436) 改走 Orchestrator 非流式分支
- Task 3.3: HITL 表单提交（submitFormAndResume(1582) + generateVideoFromPlan(1348) + confirmImageDone(225)→confirm-done 端点）改读 agent_checkpoints（替换 5 个内存 Map；confirm-done 改为清会话图片上下文）
- Task 3.4: 删 callDifyChat(494) / buildChatBody(626) / forwardDifySse(676-1120) / mergedHitlContent(1120) / clearDifyVariable(1387) / localizeDifyFileUrls(1557) 的 Dify 取值逻辑（Dify 协议层整体删除）

### Phase 4 — 删除 Dify 残留

- Task 4.1: **先迁移 sanitize（决策 9）**：AgentGenerationServiceImpl 的 8 处 `DifyAgentController.sanitize` 改为新位置 → 删 DifyAgentController（112 行）+ DifyGenerateImageRequest/DifyGenerateVideoRequest/DifyGenerateScriptRequest（SceneItem 已由 AgentSceneItem 替代）；**保留 AgentFormSubmitRequest**（form/submit 用）
- Task 4.2: 删 DifyAgentService + DifyAgentServiceImpl（378 行）——注意先确认 DifyAgentController 删除后无其余引用（grep 全仓）
- Task 4.3: 删 DifyApiKeyFilter + SecurityConfig 三处（46-49 bean、66 permitAll、76 addFilterBefore）
- Task 4.4: AiConfigProperties 删 difyApiKey/difyBaseUrl/getter/validateDifyConfig + yml 删 `ai.laozhang.dify-*` + .env.example 同步
- Task 4.5: **V5** migration 删 conversations.dify_conversation_id + agent_messages.dify_message_id 列（先删代码引用再删列；grep 确认 difyConversationId/difyMessageId 零残留）

### Phase 5 — 验证

- Task 5.1: 后端全量编译 + 启动（验证命令见下）
- Task 5.2: e2e 冒烟（真网关）：建会话 → 发"帮我做个清朝灭亡的分镜" → SSE 收剧本优化 message → 发"满意" → 收 human_input（分镜方案）→ form/submit(agree) → 收 message_end + 分镜落库；发"把这张图调亮"（带 PicUrl）→ AUTO_REFINE → confirm_result 卡片链路；发"根据参考图做个视频"（带 PicUrl）→ video_plan 卡片 → generate → 视频落库；发纯文字视频诉求 → 视频方案 LLM → HITL → 生成
- Task 5.3: 前端零改动回归（tsc + build）+ 浏览器手测 Moon 抽屉
- Task 5.4: 清理无用 import/常量 + CLAUDE.md 更新（删 Dify 段、补编排架构 + **agent 模块提取依赖清单**）

### Phase 6 — 整分支审查（requesting-code-review）

## 变更文件清单（预估，v3 修正后）

- Create: `service/agent/impl/AgentOrchestrator.java`、`service/agent/impl/AgentTools.java`、`service/agent/impl/AgentAnswerService.java`（或接口+impl 按规范）、`service/agent/AgentSceneItem.java`（或 dto/agent 包）、`entity/AgentCheckpoint.java`、`mapper/AgentCheckpointMapper.java`、`db/migration/V4__agent_checkpoints.sql`、`V5__drop_dify_columns.sql`
- Modify: `service/agent/impl/AgentChatServiceImpl.java`（大改）、`service/agent/AgentChatService.java`（接口注释/签名按需）、`service/agent/AgentGenerationService.java` + `impl/AgentGenerationServiceImpl.java`（writeScript 参数类型换 AgentSceneItem + sanitize 迁移）、`config/SecurityConfig.java`、`service/ai/AiConfigProperties.java`、`application.yml`、`.env.example`、`CLAUDE.md`
- Delete: `controller/DifyAgentController.java`、`service/agent/DifyAgentService.java`、`service/agent/impl/DifyAgentServiceImpl.java`、`security/DifyApiKeyFilter.java`、`dto/request/DifyGenerate*.java`、dify_message_id/dify_conversation_id 列

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
| R9 | **sanitize 静态依赖（v3 新增）**：删 DifyAgentController 时 AgentGenerationServiceImpl 8 处引用编译失败 | Task 4.1 先迁移 sanitize 再删 controller，迁移后立即编译验证 |
| R10 | **DifyAgentService 层遗漏（v3 新增）**：只删 controller 不删 service 层导致死代码残留 | Task 4.2 明确列出，grep 确认零引用后删 |
| R11 | **循环增强的失控成本**（后续「循环直到完成目标」）：LLM/生图/生视频调用烧钱 + 延迟 | 状态机硬约束：maxSteps、墙钟 deadline、无进展检测、每次 REVIEW 计一步；循环边加入时同步收紧预算（可配置，默认保守） |
| O1 | script_prompt 确认后剧本持久化位置（project.script 字段是否存在待查） | Phase 2 实现时确认；无字段则会话上下文内存态 + CLAUDE.md 注明 |

## 执行方式

- subagent-driven-development：每 Task 独立子代理（携带本计划对应段落 + 技能 `spring-ai-2-0-development` evidence-first 规则 + `ai-storyboard-dev` 项目规范），规格审查 → 代码质量审查
- 红线：前端零改动（SSE 事件字段名/顺序不变）；master 直接开发；git add 只加计划内文件；Java 中文注释
- Phase 0 spike 结论（D3 + 循环模式）先汇报用户确认再进 Phase 2
- 分阶段提交（每 Task 一次独立提交，可独立回滚），遵循项目 Conventional Commits 中文规范
