# Morrow Creative Canvas

一个可离线运行的“图片 + 文字 + 视频”设计画布，用于学习 TapNow 一类空间型创作工具的前端实现。项目使用独立的 Morrow 视觉，不调用 TapNow 私有接口，也不模拟模型、credits、进度或生成结果。

## 运行

```bash
npm install
npm run dev
```

完整验证：

```bash
npm run verify
npm run test:e2e
```

- `verify`：TypeScript、Vitest、生产构建。
- `test:e2e`：使用本机 Edge 执行真实浏览器闭环。

## 画布命令信箱（服务端投递 → 浏览器执行）

外部进程不直接访问 Zustand、React 或 IndexedDB：命令先投递到 canvas 服务的信箱端点，浏览器打开画布后每 1s 轮询领取，在页面内执行并回传结果。信箱在 canvas 服务里（`/api/canvas/bridge/**`），不再有独立的 Bridge 进程、8787 端口或 `X-Bridge-Token`。

- 端点：`POST /register`、`GET /poll`、`POST /result`、`POST /unregister`、`GET /health`，同源调用，身份走正常登录态（网关验签后注入 `X-User-Id`）。
- 归属：标签按「用户 + 项目 + 画布」登记，同画布多标签时以最近注册的为活跃标签，活跃标签注销后自动回退。
- 命令侧：Vue/React 之外的服务端 MCP 工具 `canvas_execute_node` / `canvas_execute_graph` / `canvas_get_execution` / `canvas_cancel_execution` 由 canvas 服务 `/api/canvas/mcp` 提供，MoonAgent 通过 `spring.ai.mcp.client.streamable-http.connections.canvas-server` 连接。
- 时限：命令超时 11 分钟；浏览器心跳 30 秒（超过即视为未响应）；标签 15 分钟未轮询即失活。
- 安全：删除类与执行类命令由服务端强制预览，调用方无法用 `dryRun=false` 绕过；未打开项目返回 `PROJECT_NOT_OPEN`，目标画布未打开返回 `CANVAS_NOT_OPEN`。

```bash
npm run dev      # 启动 Vite，打开项目后浏览器自动注册到画布服务
```

模型参数通过 `canvas_get_node_model_config` 与 `canvas_set_node_model_config` 管理：读取当前模型、网关兼容模型和递归参数 Schema；写入时严格校验枚举、范围和嵌套对象。模型及模型参数禁止通过 `canvas_update_node` 绕过校验。

## 当前能力

- IndexedDB 本地项目中心，新项目默认空画布。
- 图片、文字和视频节点；图片支持 PNG、JPEG、WebP，视频支持 MP4、WebM、MOV。
- 图片导入处理 EXIF 朝向，保留原图 Blob，生成 480px 缩略图和 1200px 预览图。
- 裁剪、像素调整、矢量标注和快速切分都生成右侧派生节点，不覆盖源图。
- 快速切分支持 `2×2 / 3×3 / 4×4` 和以左上为基准的 `8×8` 选择矩阵。
- 标注支持选择、移动、画笔、箭头、矩形、文字、颜色、线宽、独立撤销、SVG 和合成 PNG。
- 图片处理运行在模块 Worker；画布继续响应拖拽与缩放。
- 节点、连线、视口和标题防抖自动保存；刷新通过 URL 项目标识恢复当前画布。
- 跨项目本地素材库保存真实资产记录。
- 下载当前图片、批量切片 ZIP 和包含真实素材的 `.morrow` 项目包。
- 画布事务支持导入、派生、切分、模板、删除、节点拖动的撤销/重做。
- 大于 180 个节点时启用可见节点渲染；缩放使用占位、缩略图、预览图三级策略。

## 真实边界

本地可用能力由 `src/services/capabilities.ts` 注册：

- 裁剪
- 快速切分
- 像素调整
- 标注

扩图、擦除、重绘、增强、抠图、多角度和重新布光保留入口顺序，但在没有 `JobAdapter` 执行服务时禁用并显示原因。右侧设计助理已经具备真实请求、项目会话、增量回复、任务进度、取消、重试和多结果接入 UI；未配置服务时发送请求只会返回明确连接错误。

未来接入远程服务时，只有真实 `asset.created` 事件可以创建结果节点。

## 执行服务接入

在 `.env.local` 配置执行服务地址：

```text
VITE_JOB_API_URL=http://127.0.0.1:8787
```

前端通过 HTTP 提交任务、通过 SSE 接收事件，并在 `asset.created` 后下载真实素材、重新生成本地缩略图与预览图，再写入 IndexedDB 和画布。完整协议见 `docs/agent-service-contract.md`。不要把模型密钥写入 `VITE_*` 环境变量；模型凭据必须保留在服务端。

## 数据模型

`src/types.ts` 定义版本化项目、资产和标注契约。IndexedDB 位于 `src/services/projectStorage.ts`，包含：

```text
projects        项目图、视口和时间戳
assets          原图、缩略图、预览图与派生信息
libraryEntries  跨项目素材库引用
settings        本地设置
```

运行时 `blob:` URL 由 `src/store/mediaStore.ts` 管理，不写入项目数据；项目切换或资产替换时会释放。资产回收扫描项目、素材库、撤销/重做历史和派生祖先，避免过早删除重做所需 Blob。

## `.morrow` 项目包

项目包由 `fflate` 生成 ZIP：

```text
manifest.json
assets/<asset-id>/original
assets/<asset-id>/thumbnail.webp
assets/<asset-id>/preview.webp
```

清单保存图结构、版本、MIME、尺寸、派生关系和 SHA-256 校验。导入会检查路径、文件数、压缩包与解压体积、MIME 魔数、素材引用和校验值，然后在同一个 IndexedDB 事务中写入新 ID 的项目副本，不覆盖原项目。

## 阅读顺序

1. `src/types.ts`：项目、资产、图和能力边界。
2. `src/store/canvasStore.ts`：原子画布事务与撤销/重做。
3. `src/store/mediaStore.ts`：Blob 对应的运行时 URL 生命周期和分级加载。
4. `src/services/projectStorage.ts`：IndexedDB、素材库与引用回收。
5. `src/workers/imageVariantWorker.ts`：图片解码、裁剪、切分、像素和标注合成。
6. `src/components/CanvasWorkspace.tsx`：React Flow 视口、可见区域和导入调度。
7. `src/components/ImageNodeTools.tsx`：反向缩放浮层和派生节点业务事务。

## 值得学习的画布细节

图片卡属于世界坐标，应随画布缩放；工具栏属于屏幕操作 UI，应保持固定的可读尺寸。`ImageNodeTools.tsx` 读取 React Flow zoom，对浮层使用 `1 / zoom` 反向缩放，并把垂直偏移也按 zoom 反算，避免低缩放时面板与主工具栏重叠。

切分、裁剪和标注的流程统一为：

```text
参数预览 → Worker 输入校验与处理 → 编码真实 Blob
→ IndexedDB 持久化 → 单次画布事务创建节点与连线
```

Worker 进度和临时 UI 不进入画布历史。撤销移除派生节点但保留资产供重做使用，超出引用范围后再由回收流程删除。

## 测试覆盖

Vitest / Testing Library：

- 非整除尺寸切分无像素丢失、重叠或顺序错误。
- 标注归一化坐标和 SVG 转义。
- 批量导入与派生节点的原子撤销/重做。
- 项目引用、派生祖先、素材库引用与孤儿回收。
- `.morrow` 图和 Blob 往返及损坏校验。
- 首次进入项目中心没有演示入口。

Playwright + Edge：

- 新数据库显示空项目中心。
- 新项目为空画布。
- 导入真实 PNG 后出现图片节点与本地工具栏。
- 远程 AI 工具禁用且不会创建节点。
- `.morrow` 触发真实下载。

## 快捷键

| 操作 | 快捷键 |
| --- | --- |
| 全选节点 | `Ctrl/Cmd + A` |
| 删除选中节点 | `Ctrl/Cmd + D` / `Delete` / `Backspace` |
| 复制选中节点 | `Ctrl/Cmd + C` |
| 粘贴节点 | `Ctrl/Cmd + V` |
| 撤销 | `Ctrl/Cmd + Z` |
| 重做 | `Ctrl/Cmd + Shift + Z` |
| 添加文字节点 | 双击画布空白处 |

## 尚未接入

- 远程图像模型与账户服务
- 分享、支付、社区和 credits
- 音频和 3D 节点
- 服务端协作、版本冲突与多人编辑

这些入口不会以假数据或假成功状态出现。
