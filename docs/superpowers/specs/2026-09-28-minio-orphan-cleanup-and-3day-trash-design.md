# MinIO 孤儿清理与项目库回收站 3 天设计

日期：2026-09-28
状态：待用户审阅
关联约束：`CLAUDE.md`（2026-09-21）——项目库是媒体真源，同时保留旧画布/旧 AI 资产兼容层。

## 1. 背景

项目库统一改造后，项目库成为原始素材、生成结果、画布资产与资产组的真源，但旧画布与旧 AI 资产仍在使用同一 MinIO 桶。用户提出三项要求：

1. 清理所有没有出现在项目库的 MinIO 图片/视频对象。
2. **旧画布中未迁入项目库的图片/视频不清理**（本要求推翻了"项目库是唯一保护来源"的假设）。
3. 项目库回收站保留时间改为 3 天，且资产进回收站满 3 天后自动进入清理队列。

## 2. 目标与非目标

### 目标

- 新增可重复执行的对象扫描，发现并清理 `images/`、`videos/` 前缀下的无用对象。
- 清理判定使用"多个引用来源的并集"，而不是只查项目库。
- 项目库回收站保留期由 7 天改为 3 天，并新增到期自动清理。
- 所有新逻辑必须幂等，可被重复调度而不重复删除或误删。

### 非目标

- 不清理 `audios/`、`files/` 及其他前缀对象。
- 不删除任何数据库记录，只删除 MinIO 对象。
- 不改变旧画布、旧 `/api/assets` 只读兼容的现有行为。
- 不迁移历史 `data:` URI，不重构画布资产写入链路。
- 不做与本次需求无关的清理任务表重构。

## 3. 已确认的代码事实

- asset-service 与主后端本地共用同一个 PostgreSQL：`jdbc:postgresql://localhost:5432/ai_storyboard`。
  asset-service 的 Flyway 默认 schema 为 `asset`；画布与旧 AI 资产业务在 `public`。
- 两侧共用同一个 MinIO 桶 `storyboard`，key 形如 `images/…`、`videos/…`、`audios/…`。
- `ObjectStorage` 目前只有 `put/get/exists/delete/...`，**没有列举对象的能力**。
- `StorageCleanupWorker` 每 30 秒消费 `asset.library_storage_cleanup_tasks`，删除前调用
  `LibraryRepository.objectKeyHasReferences` 二次确认。
- `objectKeyHasReferences` 目前只保护 `asset.library_media`、`library_group_items`、
  `library_group_snapshot_items`。
- `AssetCatalogServiceImpl.trash` 与 `bulkTrash` 硬编码 `plusDays(7)`；
  `LibraryRepository.trashAssetsInFolders` 硬编码 `interval '7 days'`。
- 目前**没有**任何任务在 `purge_after` 到期后自动清理 `TRASHED` 资产。
- `public.canvas_assets`（V6/V7）保存 `object_key`、`library_asset_id`、`library_sync_state`。
  画布资产可能处于 `pending`/`failed` 同步状态而尚未进入项目库。
- `LegacyAiAssetMigrationServiceImpl` 已在 asset-service 中直接跨 schema 只读 `public.assets`、
  `public.asset_images`，说明同库跨 schema 访问是既有且可行的模式。

## 4. 保护集合设计

对象的删除前提是：它属于清理范围，且**不属于**任何保护来源。

### 4.1 清理范围

- `images/` 前缀
- `videos/` 前缀

不在范围内的一律不扫描、不入队、不删除。

### 4.2 保护来源（并集）

1. **项目库引用**：现有 `asset.library_media`、`library_group_items`、
   `library_group_snapshot_items`，资产状态为 `ACTIVE` 或回收期内的 `TRASHED`
   （排除 `PURGE_PENDING`/`PURGED`）。回收期内资产因此天然受保护。
2. **画布资产**：`public.canvas_assets.object_key`。
   不区分 `status` 与 `library_sync_state`；`pending`、`failed`、未迁入项目库的画布资产一律保护。
3. **旧 AI 资产**：`public.asset_images.url` 解析出的 object key。
4. **旧分镜与生成审计**：`public.scene_assets`、`public.scene_reference_images.image_url`、
   `public.scenes.image_url`、`public.scenes.video_url`、`public.agent_assets.url`/`result_urls`。
5. **未过期上传会话**：上传会话状态为 `CREATED` 且 `expires_at > now()` 对应对象。
6. **最近写入宽限期**：对象 `lastModified` 晚于 `now() - gracePeriod` 时本轮跳过。

`public` 表不存在时按"该来源不存在"处理；但查询执行本身报错时，本轮扫描整体中止，不产生任何新清理任务。

### 4.3 Fail-safe 原则

- 保护集合查询异常 → 本轮不产生任何新清理任务，记录错误并等待下一轮。
- 扫描后入队，删除前由 `StorageCleanupWorker` 再次调用统一的引用判定做二次确认。
- 无法解析为 object key 的历史 URL 不做猜测，直接视为不可判定并保守跳过相关对象。

## 5. 方案设计

### 5.1 统一引用判定

在 asset-service 新增统一引用判定组件（暂定名 `ObjectReferenceGuard`），集中实现：

- `boolean isReferenced(String objectKey)`：供删除前二次确认使用。
- `Set<String> referencedObjectKeys(Collection<String> candidates)`：供批量扫描使用，按 key 批量查询，避免逐对象查询。

判定组件内部按"项目库 / 画布 / 旧 AI 资产 / 旧分镜 / 上传会话"分组查询，任一分组抛错则整批失败。
`public` 表能力探测（`to_regclass`）结果做短 TTL 缓存，避免每个对象重复探测。

`LibraryRepository.objectKeyHasReferences` 与新组件的职责合并，避免出现两套判定：
删除前的二次确认必须与扫描使用同一实现。

### 5.2 桶扫描

- `ObjectStorage` 新增分页列举能力（基于 S3 `listObjectsV2` + `continuationToken`），返回
  object key、大小、`lastModified`。
- 新增 `StorageOrphanScanner` 定时任务：
  1. 分页遍历 `images/`、`videos/`。
  2. 跳过宽限期内对象。
  3. 批量调用引用判定，命中的跳过。
  4. 未命中的调用 `repository.createCleanupTask(objectKey)` 入队。
- 入队与删除分离：扫描只负责发现，删除仍由现有 `StorageCleanupWorker` 执行，保持租约、重试、死信语义不变。
- 扫描频率与宽限期可配置，默认每 6 小时一轮。

### 5.3 回收站 3 天与自动清理

- `StorageProperties` 新增 `trashRetentionDays`（默认 3）与扫描相关配置项。
- 统一替换三处硬编码 7 天：
  - `AssetCatalogServiceImpl.trash`
  - `AssetCatalogServiceImpl.bulkTrash`
  - `LibraryRepository.trashAssetsInFolders`
- 新增到期清理任务：
  1. 分批查询 `status = 'TRASHED' AND purge_after <= now()`，使用 `FOR UPDATE SKIP LOCKED`。
  2. 抽取与手动 `purge` 相同的内部逻辑，避免两套实现分叉：
     `unlinkAllProjects` → `hasActiveReferences`（有引用则 `markPurgeBlocked`，保留对象）→
     `markPurgePending` → 对每个 media `createCleanupTask` → `markPurged`。
  3. 复用现有清理队列，不新增删除通道。
- 回收期内资产由保护集合第 1 条覆盖；到期后引用判定随资产状态变更而失效。

### 5.4 可观测性

记录并暴露每轮指标：扫描对象数、跳过（宽限期）数、受保护数、入队数、删除数、失败数。
保护集合查询失败必须有明确错误日志，便于快速定位误删风险。

## 6. 测试计划

采用测试先行：先写失败测试，再最小实现。

### 6.1 引用判定

- 项目库活跃引用 → 受保护。
- `public.canvas_assets` 中未同步（`pending`/`failed`）的画布资产 → 受保护。
- `public.asset_images.url` 旧引用 → 受保护。
- `public.scene_reference_images`、`public.scenes`、`public.agent_assets` 引用 → 受保护。
- 无任何引用 → 判定为可清理。
- 保护来源查询异常 → 扫描不产生任何清理任务（fail-safe）。

### 6.2 扫描与清理

- 宽限期内的新对象不入队。
- `audios/`、`files/` 前缀不被扫描。
- 同一 key 重复入队只保留一条未完成任务。
- 扫描入队后引用发生变化 → 删除前二次确认保留对象。

### 6.3 回收站 3 天与到期清理

- `trash`、`bulkTrash`、文件夹批量回收的 `purge_after` 均为 `now + 3 天`。
- 未到期的 `TRASHED` 资产不被清理。
- 到期且无活跃引用 → 入队并置为 `PURGED`。
- 到期但仍有活跃引用 → 置为 `PURGE_BLOCKED`，对象保留。

### 6.4 验证命令

- `mvn -f asset-service/pom.xml test`（最终以仓库实际模块路径为准）。
- 视改动范围补充 `XiaoYueCanvas` 相关测试或编译验证。

## 7. 发布顺序

1. 确认 `public.canvas_assets`（V6/V7）已在目标环境应用。
2. 发布统一引用判定与回收站 3 天配置（此时不启用扫描）。
3. 启用 `StorageOrphanScanner`，先观察日志统计，确认保护集合覆盖正常。
4. 确认无误后保留定时扫描，等待现有清理 Worker 自然消费队列。
5. 启用到期自动清理任务。

## 8. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 保护集合遗漏导致误删旧画布资产 | 并集判定 + fail-safe + 删除前二次确认 + 上线先观察日志 |
| asset-service 与主后端不在同一数据库 | 表能力探测；不在同一库时保护来源缺失，需在发布前确认部署拓扑 |
| 扫描把刚上传、引用尚未落库的对象当孤儿 | 可配置宽限期 + 二次确认 |
| 到期清理与手动 purge 逻辑分叉 | 抽取共享内部方法，二者走同一路径 |
| `agent_assets.result_urls` 为 JSON/数组 | 解析后逐个提取 key，无法解析时保守跳过 |


