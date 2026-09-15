# 画布资产 MinIO 持久化与数据库映射设计

## 目标

画布中的生成图片、生成视频以及用户导入的参考媒体必须满足以下约束：

1. 二进制对象写入 MinIO，不再依赖 `data:` URI 作为长期资产载体。
2. `canvas_assets` 表是画布资产与 MinIO 对象的唯一映射。
3. 画布节点中的 `assetId` 使用 `canvas_assets.id`。
4. IndexedDB 只作为浏览器 Blob 缓存，不再作为跨设备资产源。
5. 生图、生视频提交只传短 URL 或画布资产 ID，不传 base64 图片或视频。
6. 作业重启、SSE 断线、页面刷新后，均可通过数据库恢复资产和生成状态。

## 非目标

- 不改造分镜场景的 `scene_assets` 关系。
- 不替换现有 `agent_assets` 生成审计记录。
- 不迁移历史画布中的全部 Blob，只在新资产写入或再次引用时惰性迁移。
- 不改变模型路由、提示词或 MiniMax 的生成参数语义。

## 现状问题

当前画布前端用 IndexedDB 保存本地 Blob。提交图生视频时，前端把 Blob 转成
`data:image/...;base64,...`，再写入 `canvas_jobs.params.referenceImages`。
后端 `VideoGenerationServiceImpl.resolveReferenceUrl` 对 `data:` 直接透传，
导致视频创建请求仍携带内联数据。结果是：

- 生成请求可能卡在网关或上游创建阶段，尚未取得 `taskId`。
- 资产没有画布级数据库映射，刷新后只能依赖本地浏览器状态。
- 多设备、服务重启和恢复轮询缺少稳定的资产身份。
- 生成记录与画布节点之间只能依赖本地 `generation-record-id` 约定。

## 架构

引入三层资产模型：

1. **对象层**：MinIO，保存实际图片、视频、音频文件。
2. **映射层**：`canvas_assets`，保存画布、节点、生成记录与 MinIO 地址的关系。
3. **缓存层**：IndexedDB，按 `canvas_assets.id` 缓存 Blob，提升画布渲染性能。

`agent_assets` 继续保存一次生成的审计信息，包括模型、提示词、任务 ID、
状态、生成参数和结果 URL。`canvas_assets` 不复制生成审计信息，只建立
“生成记录结果”到“画布节点资产”的持久映射。

## 数据库设计

新增迁移：

`XiaoYueCanvas/src/main/sql/V6__canvas_assets.sql`

```sql
CREATE TABLE IF NOT EXISTS public.canvas_assets (
    id                    text PRIMARY KEY,
    canvas_id             text NOT NULL REFERENCES public.morrow_canvases(id) ON DELETE CASCADE,
    project_id            text,
    user_id               text NOT NULL,
    type                  varchar(16) NOT NULL,
    mime_type             varchar(128) NOT NULL,
    object_url            text NOT NULL,
    object_key            text NOT NULL,
    generation_record_id  text,
    producer_node_id      text,
    status                varchar(24) NOT NULL DEFAULT 'ready',
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT canvas_assets_type_check CHECK (type IN ('image', 'video', 'audio')),
    CONSTRAINT canvas_assets_status_check CHECK (status IN ('pending', 'ready', 'failed'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_canvas_assets_object
    ON public.canvas_assets(canvas_id, object_url);

CREATE UNIQUE INDEX IF NOT EXISTS uq_canvas_assets_generation
    ON public.canvas_assets(generation_record_id, object_url)
    WHERE generation_record_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_canvas_assets_canvas
    ON public.canvas_assets(canvas_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_canvas_assets_node
    ON public.canvas_assets(canvas_id, producer_node_id);
```

`id` 由后端生成，不再由前端本地生成。

## 后端接口

### 上传本地资产

`POST /api/canvas/assets/upload`

请求：`multipart/form-data`

- `canvasId`: 画布 ID
- `producerNodeId`: 可选，产生或承载该资产的节点
- `type`: `image`、`video` 或 `audio`
- `file`: 文件

处理：

1. 校验用户拥有该画布。
2. 通过 `FileStorageService.saveUploadedReference(type, file)` 写入 MinIO。
3. 从返回的 `/api/files/...` 地址解析 `object_key`。
4. 插入 `canvas_assets`。
5. 返回 `{ id, type, mimeType, url }`。

### 映射生成结果

`POST /api/canvas/assets/link-generation`

请求 JSON：

```json
{
  "canvasId": "canvas-id",
  "producerNodeId": "node-id",
  "generationRecordId": "agent-asset-id"
}
```

处理：

1. 校验用户拥有画布。
2. 查询 `agent_assets`，要求状态为 `completed`。
3. 读取 `result_urls`；为空时回退 `url`。
4. 对每个结果地址解析对象类型、MIME 和 object key。
5. 按唯一索引幂等插入或返回已有 `canvas_assets`。
6. 返回资产描述数组。

### 查询资产

`GET /api/canvas/assets/{assetId}`

- 改为根据 `canvas_assets.id` 校验画布归属。
- 图片、视频、音频统一从 MinIO 读取。
- 响应包含正确 MIME 和文件长度。

### 获取画布资产

`GET /api/canvas/{canvasId}/assets`

- 返回该画布全部 `canvas_assets` 描述。
- 用于页面刷新后修复节点引用和 IndexedDB 缓存。

## 前端数据流

### 用户导入

1. 读取文件 Blob。
2. 调用上传接口，等待 `{ id, url }`。
3. IndexedDB 以数据库 `id` 保存 Blob 缓存。
4. 节点 `assetId` 写入数据库 `id`。
5. 画布图保存到 `morrow_canvas_graphs`。

### 生成图片或视频

1. 作业完成时，前端取得 `generationRecordId`。
2. 调用映射接口，后端把结果登记为 `canvas_assets`。
3. 前端按返回 ID 下载结果并写入 IndexedDB 缓存。
4. 结果节点写入数据库 `id`。
5. 画布图保存。

### 再次作为参考素材

1. 从节点 `assetId` 取得 `canvas_assets.id`。
2. 作业参数通过 `referenceAssetIds` 传画布资产 ID，不传 data URI。
3. 画布后端从 `canvas_assets` 读取 `object_url`，转换成 `/api/files/**` 短地址。
4. agent-core 下载 `/api/files/**`，再通过网关文件代理换成 `mm_file://`。

### 遗留 data URI

提交作业前检测 `data:`：

1. 解析 MIME 和二进制。
2. 上传 MinIO。
3. 插入 `canvas_assets`。
4. 用新资产 ID 替换原参数。

该路径只用于兼容历史数据，不再作为新资产默认路径。

## agent-core 规则

`VideoGenerationServiceImpl.resolveReferenceUrl` 调整为：

1. `mm_file://` 原样透传。
2. `http(s)` 和 `/api/files/**` 先取得二进制，再上传网关文件代理并返回 `mm_file://`。
3. `data:` 必须解码后上传网关文件代理，返回 `mm_file://`。
4. 不再把小文件转换成 data URI。
5. 对无法解析的 data URI 直接抛业务错误，禁止退回内联模式。
6. 画布内部 `/api/canvas/assets/**` 不进入 agent-core，由画布后端先解析为
   `/api/files/**`。

图片生成和视频生成两条路径必须使用同一参考素材解析规则。

## 恢复与一致性

- 作业终态仍以 `canvas_jobs.status` 为准。
- `provider_task_id` 存在时，视频作业必须继续轮询。
- `generationRecordId` 存在但 `canvas_assets` 缺失时，恢复逻辑补建映射。
- 前端每 15 秒调用一次作业快照对账。
- 页面打开时加载画布资产列表，修复节点引用和 Blob 缓存。
- 插入映射使用幂等唯一索引，重复恢复不会产生重复资产。

## 错误处理

- MinIO 写入失败：不写 `canvas_assets`，接口返回 `50201`。
- `agent_assets` 不是 `completed`：不建立结果映射。
- 结果 URL 缺失或不可解析：返回 `50201` 并保留生成记录供排查。
- 上游创建失败：作业写 `failed`，不得保持 `running`。
- 已拿到 `taskId` 后失败：先保存 `provider_task_id`，后续可恢复轮询。

## 测试

### 后端

- 上传接口把对象写入 MinIO 并插入 `canvas_assets`。
- 相同生成结果重复映射只产生一条记录。
- 非画布属主无法上传、映射或读取资产。
- 状态接口返回完成结果资产描述。
- `LinkageError` 等致命异常也把作业结算为 `failed`。
- 有 `provider_task_id` 的过期视频作业可恢复轮询。
- data URI 不会进入视频创建请求体。

### 前端

- 导入图片后节点使用数据库资产 ID。
- 生成图片和视频完成后自动建立映射并缓存 Blob。
- 页面刷新后通过数据库映射恢复节点媒体。
- SSE 断开后快照对账能把任务移出“生成中”。
- 参考图提交参数不包含 `data:`。

### 验证

- `mvn -pl XiaoYueCanvas -am test`
- `mvn -pl agent-core -am test`
- `npm run verify`
- 实际图生视频创建请求中确认无语义级 base64 data URI。

## 发布顺序

1. 执行 `canvas_assets` 迁移。
2. 发布后端上传、映射和查询接口。
3. 发布 agent-core data URI 拒绝与 `mm_file://` 转换。
4. 发布前端资产 ID 与上传链路。
5. 终止当前未创建上游任务且无 `provider_task_id` 的假运行作业。
6. 重新进行一次图生视频验证。
