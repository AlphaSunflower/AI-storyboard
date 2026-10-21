# 数据保留与定时清理设计（2026-10-21）

> 背景：数据库随会话/事件增长，需要「什么该留、什么到期清、清的时候绝对不能碰什么」的明确口径。
> 本文记录本次落地的两条清理链路，并给出后续建议新增的清理项（含优先级与前置条件）。

## 0. 一条硬约束

**MinIO 里的图片/文档对象是交付物，不是缓存。** 归档、回收站、定期清理三条链路都不得删除
`agent_assets` 行与对象存储里的原件；对象删除只允许发生在「用户在项目库回收站里彻底删除」
或「删除会话」这两条显式用户动作上（走 `storage_cleanup_queue` / `library_storage_cleanup_tasks`）。

## 1. 本次落地

### 1.1 项目库回收站：保留期 1 天

| 环节 | 组件 | 行为 |
|------|------|------|
| 进回收站 | `AssetCatalogServiceImpl.trash` / `bulkTrash` / `LibraryFolderServiceImpl` / `DuplicateServiceImpl` | `status='TRASHED'`，`purge_after = now() + trash-retention-days` |
| 到期领取 | `asset-service` `TrashPurgeWorker`（每 5 分钟，批 50） | 领取 `purge_after <= now()` 的 TRASHED 行 |
| 判定与入队 | `AssetPurgeService.purgeDue` | 仍有活跃引用 → `PURGE_BLOCKED` 并**保留对象**；否则 `PURGE_PENDING` → 写 `library_storage_cleanup_tasks` |
| 真正删对象 | `StorageCleanupWorker`（每 30 秒） | 再判一次引用；无引用才删 MinIO 对象 |

配置：`storyboard.storage.trash-retention-days`（默认 **1**，env `STORYBOARD_TRASH_RETENTION_DAYS`）。
存量数据：Flyway `asset-service/src/main/resources/db/migration/V10__trash_retention_one_day.sql`
把仍可恢复的 TRASHED 行 `purge_after` 收紧到 `deleted_at + 1 day`。

前端文案同步为「保留 1 天」（`LibraryPage`、`LibraryWorkspacePage`）。

> 注意：这是**项目库素材**的回收站。当前「删除会话」是立即硬删并删除该会话 MinIO 对象，
> 没有会话级回收站（见 §3 建议 15）。

### 1.2 已归档会话：定期清理运行时数据（MinIO 对象保留）

```
ArchivedConversationCleanupJob            @Scheduled 每天 03:40（单飞锁）
  └─ ArchivedConversationCleanupService.runOnce()
       ├─ AgentArchiveCleanupMapper.findDueForCleanup(graceDays, limit)
       │    conversations.status='archived' AND updated_at < now()-graceDays
       │    AND 台账里没有该会话
       └─ cleanupOne(conversation)
            ├─ 已清理 / 状态已非 archived / 会话锁被占用 → 跳过
            ├─ ConversationContextCleanupService.cleanupArchivedRuntime(id)   [单会话一个事务]
            ├─ MoonAgentRuntime.clearSession(userId, id)                      删 agentscope_sessions + 清本地 stateCache
            ├─ MoonSandboxClient.cleanup(id) + SandboxSessionRegistry.markCleaned
            └─ 写 agent_archive_cleanups 台账（每会话只清一次）
```

**删除（硬删，可重建或无继续使用价值）**

| 表 | 理由 |
|----|------|
| `agent_turn_events` | 每个 delta 一行，库里增长最快的表；归档后不再重放/补偿 |
| `agent_checkpoints` | HITL 一次性卡片，归档后不得复活 |
| `agent_canvas_run_operations` → `agent_canvas_runs` → `agent_canvas_sessions` | 画布运行态 |
| `agent_document_index` | 文档摘要，需要时从 MinIO 原件重新解析 |
| `agent_async_tasks` 终态行（completed/partial/failed/cancelled） | 请求/结果 JSON 大；运行中的任务保留给恢复链路 |
| `agent_workflow_steps` → `agent_workflow_runs` | 工作流执行态 |
| `agentscope_sessions` | AgentScope 运行时上下文（经 runtime 清，同时失效本地缓存） |
| 沙箱工作区 `/workspaces/{cid}` | Docker 命名卷目录；产物早已发布到 MinIO |

**保留（不得随归档删除）**

| 保留项 | 理由 |
|--------|------|
| `conversations` 行、`agent_messages` | 归档会话仍要能打开看历史 |
| `agent_assets` 行 + **MinIO 图片/文档对象** | 消息文件卡、项目库引用继续可下载 |
| `agent_turns` | 轻量行，保留 `turnId` 引用（消息 metadata / 观测页跳转） |
| `agent_tool_invocations`、`agent_subagent_runs`、`agent_external_actions` | 合规审计，按各自保留期清理 |
| `agent_timeline_manifests`、`agent_media_critiques`、`agent_conversation_asset_groups` | 产出证据与资产归属 |
| `agent_memories` / `agent_global_memories` | 按项目/账号隔离的用户数据，与会话生命周期无关 |

清理时同时把 `conversations.agentscope_session_initialized_at` 与 `canvas_context` 置空：
运行时状态已删，若保留初始化标记，取消归档后的下一轮会带着**空上下文**继续；置空后走既有的
「从最近 20 条消息重建会话」路径（与 `/清空上下文` 同一语义）。

配置（`MoonAgent/src/main/resources/application.yml`）：

| 键 | 默认 | 说明 |
|----|------|------|
| `ai.cleanup.archive.enabled` | `true` | 总开关 |
| `ai.cleanup.archive.cron` | `0 40 3 * * *` | 每天 03:40，避开 checkpoint（03:00）与工具审计（03:30） |
| `ai.cleanup.archive.grace-days` | `3` | 「误归档 → 恢复」窗口；`0` = 下一次任务即清 |
| `ai.cleanup.archive.batch-size` | `50` | 单批会话数 |
| `ai.cleanup.archive.max-batches` | `10` | 单次任务最多跑几批 |

台账表 `public.agent_archive_cleanups`（`V54__agent_archive_cleanups.sql`，**需手动执行**）。
缺表时任务只打印可执行的迁移提示并跳过，不影响其它链路（不会静默反复清理同一批会话）。

### 1.3 保留期清理：`DataRetentionJob`（默认演练）

`DataRetentionJob`（每天 04:20，单飞锁）→ `DataRetentionService.runOnce()` 依次跑四个目标，
每个目标都能用「保留天数 = 0」单独关掉：

| 目标 | 默认保留 | 说明 |
|------|---------|------|
| `agent_turn_events` | 30 天 | 直播事件，按 `created_at` 分批删；只影响历史回放，不影响消息展示 |
| `agent_subagent_runs` | 90 天 | 子智能体节点审计（`output` 是整段脚本） |
| `asset_outbox` | 7 天 | 只删 SUCCEEDED / DEAD |
| AgentScope 状态表 | 7 天 | **只删会话已不存在的孤儿行**（见下） |

**孤儿判定刻意保守**：槽位形如 `<userId>:<conversationId>`（AgentScope `slotId`），SQL 要求
`^[^/:]+:[0-9a-fA-F-]{32,36}$`，因此 `__anon__:sandbox/session/...`、`...:sub-<uuid>` 这类
沙箱子会话槽位不会被误判；后缀 UUID 拿主键等值去 `conversations` 查，会话还在（含已归档）一律保留。
**不按年龄删活跃会话的状态** —— 那会让下一轮带着空上下文继续；活跃/归档会话的状态只由
§1.2 的归档清理在重置初始化标记的前提下删除。

表位置**不写死**，按 `MoonAgentRuntime` 的构建口径解析：
开了 DistributedStore（默认）时 state store 来自 `PostgresDistributedStore.agentStateStore()`，
固定使用库默认的 **`agentscope.agentscope_sessions`**（此时 `state-schema/state-table` 不生效）；
关掉 DistributedStore 才使用 `ai.agent.agentscope.state-schema / state-table`。
两者都走标识符白名单校验后才拼 SQL。

配置（`ai.cleanup.retention.*`）：

| 键 | 默认 | 说明 |
|----|------|------|
| `enabled` | `true` | 总开关 |
| `cron` | `0 20 4 * * *` | 每天 04:20，排在归档清理之后 |
| `dry-run` | **`true`** | 只统计并打日志，不删除；确认后置 `AI_RETENTION_DRY_RUN=false` |
| `batch-size` / `max-batches` | `5000` / `20` | 单批行数 / 单次最多跑几批 |
| `turn-events-days` | `30` | 0=关闭 |
| `subagent-runs-days` | `90` | 0=关闭 |
| `orphan-sessions-days` | `7` | 0=关闭 |
| `outbox-days` | `7` | 0=关闭 |

依赖索引 [V55__data_retention_indexes.sql](AIStoryboardBackend/src/main/resources/db/migration/V55__data_retention_indexes.sql)：
`agent_turn_events(created_at)`、`agent_subagent_runs(created_at)`、`asset_outbox(status, updated_at)`、
状态表 `updated_at`。没有这些索引时 `DELETE ... WHERE created_at < cutoff` 会退化成全表扫描。

## 2. 现有定时任务全表

| 任务 | 服务 | 频率 | 清理口径 |
|------|------|------|----------|
| `TrashPurgeWorker` | asset-service | 5 min | 回收站到期（1 天）且无引用 → 入对象清理队列 |
| `StorageCleanupWorker` | asset-service | 30 s | 对象清理队列，20 条/轮，引用判定二次保护 |
| `StorageOrphanScanner` | asset-service | 6 h | MinIO `images/`、`videos/` 中 >24h 且无引用的对象（默认关闭，`STORYBOARD_ORPHAN_SCAN_ENABLED`） |
| `MediaUploadServiceImpl.abortExpiredMultipartUploads` | asset-service | 5 min | 24h 未完成的分片上传会话 |
| `CheckpointCleanupService` | MoonAgent | 03:00 | used/expired/failed 且过期超 30 天 |
| `ToolInvocationRetentionJob` | MoonAgent | 03:30 | 工具审计 90 天（`ai.agent.tool-audit.retention-days`） |
| `ArchivedConversationCleanupJob` | MoonAgent | 03:40 | **本次新增**，见 §1.2 |
| `DataRetentionJob` | MoonAgent | 04:20 | **本次新增**，保留期清理，默认演练，见 §1.3 |
| `SandboxCleanupJob` | MoonSandbox | 10 min | 过期工作区 + 活跃会话的 `tmp/`、`work/.runner` |
| `cleanupExpiredSideChats` | MoonAgent | 15 min | `temporary` 临时侧边超 2 小时 |
| `CallLogArchiverJob` | AILLMGateway | 03:30 | `call_log` 超保留期 → `call_log_archive` |
| `ModelRouteServiceImpl` 收敛 | AILLMGateway | 5 min | 非删除，只把默认模型与路由表对齐 |

## 3. 后续建议（P0 已落地，见 §1.3）

### P1：中等收益、口径清晰

| # | 目标 | 建议 |
|---|------|------|
| 5 | `agent_async_tasks` | 终态行保留 30 天（归档会话已由 §1.2 处理） |
| 6 | `storyboard_image_task` / `storyboard_video_queue` | 终态行保留 14 天（结果已回写 `scenes`，本表只跟踪队列） |
| 7 | `user_notifications` | `read_at` 非空且超 30 天 |
| 8 | `user_feedback` | 已处理且超 90 天（截图对象是否一起删需产品确认，属用户提交物） |
| 9 | `agent_external_actions` | 保留 30 天（幂等键只需覆盖重试窗口） |
| 10 | `call_log_archive` | 保留 180 天后删除，或按月分区 drop |
| 11 | `agent_recovery_leases` | 兜底清 7 天前（正常完成/失败会 DELETE，异常退出才残留） |

### P2：结构性优化，不是「加一个 job」

- **`agent_turn_events` 按月分区**：`DELETE` 大表会产生大量死元组与 WAL；分区 + `DROP PARTITION`
  是 O(1)。分区键用 `created_at`，与 §1.3 的 30 天保留期天然对齐。
- **PostgreSQL 维护**：大表删除后确认 autovacuum 跟得上（必要时对大表单独设
  `autovacuum_vacuum_scale_factor`），并排查未使用索引。
- **项目删除的级联审计**：核对删除 `projects` 时 `conversations` / `scenes` / 资产关联是否都被
  级联或显式清理，避免出现「父行没了、子表还在长」的孤儿数据（这类孤儿不会被任何保留期任务清掉）。
- **会话级回收站（产品决策）**：当前删除会话是立即硬删 + 删 MinIO 对象。若希望「删对话也能
  1 天内恢复」，需要新增 `status='trashed'` + `purge_after`，与本设计的归档清理是两条不同链路，
  且必须明确「恢复窗口内不删 MinIO 对象」。

## 4. 验证

```powershell
mvn -pl MoonAgent -am "-Dtest=DataRetentionServiceTest,ArchivedConversationCleanupServiceTest,ConversationContextCleanupServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl asset-service -am "-Dtest=AssetPropertiesConfigTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

覆盖点：

- 已归档会话只删运行时表，`agent_messages` / `agent_turns` / 资产组 / 时间线 / 评审 / 外部动作**一行不删**，
  且会置空 `agentscope_session_initialized_at` 与 `canvas_context`。
- 会话锁占用、已清理、已恢复的会话都跳过，不重复写台账。
- 缺台账表（未执行 V54）时任务吞掉异常、返回 0，不影响其它定时任务。
- 回收站保留期默认值与 yml 绑定为 1 天。
- 保留期清理：dry-run 只统计不删；按批删到不足一批即停；保留天数 0 时完全不碰数据库；
  单目标失败（表不存在）不影响其它目标。

## 5. 部署步骤

1. 主库执行 `V54__agent_archive_cleanups.sql`（归档清理台账）与 `V55__data_retention_indexes.sql`（保留期索引）。
   `asset-service` 的 `V10__trash_retention_one_day.sql` 由 Flyway 自动执行。
2. 重启 MoonAgent（新 Job/Service 需要重新加载）。
3. 观察 03:40 的归档清理日志与 04:20 的 `[dry-run]` 统计行；确认数字合理后置
   `AI_RETENTION_DRY_RUN=false` 开启真正的保留期删除。
