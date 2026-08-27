# 设计助理执行服务契约

前端通过 `VITE_JOB_API_URL` 连接执行服务。模型密钥、供应商凭据和系统提示词必须保留在服务端，不得写入任何 `VITE_*` 变量。

## HTTP 端点

### `GET /health`

```json
{ "label": "Design Agent" }
```

返回 `2xx` 表示服务可接收请求。

### `GET /capabilities`

只返回远程能力；本地能力由浏览器注册。

```json
[
  { "id": "agent.request", "label": "设计助理请求", "category": "remote", "available": true },
  { "id": "image.redraw", "label": "重绘", "category": "remote", "available": true }
]
```

### `POST /jobs`

```json
{
  "clientRequestId": "uuid",
  "conversationId": "uuid",
  "capabilityId": "agent.request",
  "sourceAssetIds": ["local-asset-id"],
  "context": {
    "projectId": "project-id",
    "nodeIds": ["node-id"],
    "selectedNodeId": "node-id"
  },
  "params": { "prompt": "统一当前版式的视觉层级" }
}
```

```json
{ "jobId": "job-id" }
```

`clientRequestId` 应作为幂等键。服务端需要把浏览器素材 ID 映射为已上传的服务端素材；正式接入时应在任务提交前增加独立素材上传接口，而不是把 Blob 塞进 JSON。

### `GET /jobs/:jobId/events`

响应类型为 `text/event-stream`。可以使用默认 `message` 事件并在 JSON 中携带 `type`，也可以使用同名 SSE 事件。

```text
id: event-1
event: job.started
data: {"jobId":"job-id"}

id: event-2
event: job.progress
data: {"jobId":"job-id","progress":0.42,"message":"正在分析版式"}

id: event-3
event: agent.message
data: {"jobId":"job-id","messageId":"answer-1","message":"先统一","delta":true}

id: event-4
event: asset.created
data: {"jobId":"job-id","asset":{"id":"remote-asset-id","name":"result.png","mimeType":"image/png"}}

id: event-5
event: job.completed
data: {"jobId":"job-id"}
```

支持的事件：

- `job.queued`
- `job.started`
- `job.progress`
- `agent.message`
- `asset.created`
- `job.completed`
- `job.failed`
- `job.cancelled`

`asset.created` 不是终止事件，一个任务可以返回多个素材。只有 `completed / failed / cancelled` 会关闭前端订阅。SSE `id` 或 JSON `eventId` 用于重连去重。

### `POST /jobs/:jobId/cancel`

接受取消后返回 `2xx`，最终状态通过 `job.cancelled` 事件确认。

### `GET /assets/:assetId`

返回 PNG、JPEG、WebP、MP4、WebM 或 MOV。单个文件上限 256 MB。当前前端要求素材地址与执行服务同源；对象存储签名地址应由该端点代理，避免向任意第三方地址携带浏览器凭据。

## 前端结果事务

收到 `asset.created` 后，前端按以下顺序执行：

```text
下载真实文件 -> 校验 MIME 和体积 -> 浏览器重新解码
-> 生成 480px 缩略图和 1200px 预览图
-> IndexedDB 保存资产 -> 创建派生节点和连线
```

下载、解码或持久化失败不会创建节点，并在会话中显示明确错误。选中节点存在时结果作为其派生节点；画布级请求的结果放到当前节点区域右侧。

## 尚需服务端补充

当前浏览器会话在同一项目内切换面板时保留，刷新后不会伪造恢复正在运行的任务。生产服务应增加：

- 会话历史查询与分页
- 活跃任务恢复及 SSE `Last-Event-ID` 重放
- 浏览器素材上传、去重和引用授权
- 用户认证、项目权限和速率限制
- 任务审批、费用上限和审计日志
