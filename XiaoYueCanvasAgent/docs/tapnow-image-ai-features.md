# TapNow 图片智能工具实现分析与复刻设计

> 分析日期：2026-08-06  
> 范围：图片节点的多角度、重新布光、自动抠图，以及可扩展的交互式对象选择。  
> 用途：为 Morrow Creative Canvas 的前后端实现提供设计依据，不直接调用或复制 TapNow 私有服务。

## 1. 结论摘要

这三项功能在交互上都表现为“选择源图片、设置参数、在右侧生成结果”，但其执行方式并不完全相同。

| 能力 | 浏览器职责 | TapNow 服务形态 | 已确认模型/服务标识 | 结果形态 |
| --- | --- | --- | --- | --- |
| 多角度 | 3D 方块预览、参数输入、创建派生节点 | 统一图片生成接口，异步任务 | `provider: fal`、`model: qwen-image-edit-plus` | 新视角图片 |
| 重新布光 | WebGL 灯位预览、参数输入、创建派生节点 | 专用 `/api/chat/v1/beta/relight` 接口，当前直接返回结果 | `provider: tapnow`、`model: relight` 仅用于能力/计价描述；实际底层模型未暴露 | 重新照明后的图片 |
| 自动抠图 | 发起任务、展示状态、创建派生节点 | 统一图片生成接口，异步任务 | `provider: bria`、`model: rmbg` | 带 Alpha 通道的透明 PNG |
| 交互式选择对象 | 点选、框选、正负提示、遮罩修正 | TapNow 当前抠图未提供；属于推荐扩展 | 推荐 SAM 2，可组合 Grounding DINO 和 Matting | 可编辑遮罩及透明 PNG |

共同的产品原则是：

1. 源图片保持不变。
2. 结果默认在源节点右侧生成。
3. 源节点与结果节点自动连线。
4. 只有真实结果返回后才完成结果节点。
5. 请求失败时删除空结果节点或保留明确的失败状态，不能伪造成功结果。

## 2. 证据等级与边界

本文使用三个证据等级：

- **已确认**：可从 TapNow 生产前端模块的请求封装、参数、状态或组件逻辑直接确认。
- **高置信度推断**：前端暴露了供应商或模型族，但没有暴露具体版本或服务端编排。
- **推荐设计**：为 Morrow 复刻项目设计，不代表 TapNow 当前内部实现。

能够从前端确认请求结构和浏览器行为，但不能仅凭前端确认：

- TapNow 服务端的队列、GPU、对象存储和缓存实现。
- Bria RMBG 的具体版本。
- 重新布光的具体底层模型、训练数据和推理参数。
- 多角度请求在 Fal 后面是否还有 TapNow 自定义提示词、前后处理或模型路由。

## 3. 统一画布业务闭环

### 3.1 默认派生模式

多角度、重新布光和画布上的抠图均采用非破坏式派生：

```text
源图片节点
  -> 打开参数面板
  -> 校验输入与使用权限
  -> 在右侧创建空结果节点
  -> 创建 source -> result 连线
  -> 提交远程任务
  -> 写入真实结果
  -> 保存任务和资产关系
```

建议用单个画布事务完成“结果节点 + 连线 + pending 状态”，避免节点存在而连线丢失：

```ts
interface DerivationTransaction {
  sourceNodeId: string;
  resultNodeId: string;
  edgeId: string;
  operation: 'multi-angle' | 'relight' | 'cutout';
  status: 'submitting' | 'pending' | 'processing' | 'completed' | 'failed';
  requestParams: Record<string, unknown>;
}
```

### 3.2 任务状态机

```text
idle
  -> submitting
  -> pending
  -> processing
  -> completed

submitting / pending / processing
  -> failed
  -> retrying
  -> pending

submitting / pending / processing
  -> cancelled
```

状态职责应严格分开：

- 临时动画进度不写入画布历史。
- `jobId` 和远程状态持久化，以便刷新后恢复。
- 只有服务端真实返回的资产才可触发 `asset.created`。
- 撤销结果节点时保留派生资产一段时间，保证可以重做。

## 4. 多角度

### 4.1 TapNow 前端交互

**已确认：** 图片节点点击“多角度”后进入节点内参数面板。面板包含：

- 一个可拖拽的 3D 方块/图片本体预览。
- 水平旋转。
- 垂直角度。
- 前后移动/画面缩放。
- 广角镜头开关。
- 重置与生成按钮。

默认参数：

```ts
const defaultMultiAngleParams = {
  rotate_right_left: -30,
  move_forward: 0,
  vertical_angle: 0.5,
  wide_angle_lens: false,
};
```

参数范围：

| 参数 | 范围 | UI 含义 |
| --- | --- | --- |
| `rotate_right_left` | `-90..90` | 水平旋转角度，单位为度 |
| `vertical_angle` | `-1..1` | 归一化垂直角，显示值为 `vertical_angle * 45°` |
| `move_forward` | `0..10` | 相机前移/主体画面尺度控制，不应等同于图片 CSS 缩放 |
| `wide_angle_lens` | 布尔值 | 是否应用广角镜头效果 |

预览指示器使用以下映射：

```ts
previewX = vertical_angle * 45;
previewY = -rotate_right_left;
```

这个符号关系很重要：屏幕拖拽方向、指示器旋转和最终请求参数必须经过同一个转换层，不能分别在组件中临时取反。

### 4.2 TapNow 请求

**已确认：** 请求发送到统一图片生成接口：

```http
POST /api/conversation/v1/generations/image
```

请求负载等价于：

```ts
{
  scene: 'image-edit',
  provider: 'fal',
  model: 'qwen-image-edit-plus',
  image_urls: [sourceImageUrl],
  rotate_right_left,
  move_forward,
  vertical_angle,
  wide_angle_lens,
  metadata: {
    node_id: resultNodeId,
    canvas_id: canvasId,
  },
}
```

服务返回任务 ID，前端注册到统一任务管理器。完成后从统一生成结果中取得图片 URL并写入结果节点。

### 4.3 画布事务

TapNow 的前端顺序为：

1. 校验源图和策略限制。
2. 在源节点右侧约 `200px` 位置创建标题为“多角度”的空图片节点。
3. 设置 `editSubType: 'multi_angle'` 和 `pending` 任务状态。
4. 创建连线并将视口平移到结果位置。
5. 提交任务并注册任务 ID。
6. 完成后填入结果图；提交失败或没有任务 ID时删除空节点。

### 4.4 后端能力判断

多角度不是把 2D 图片贴到浏览器 3D 盒子后截图。3D 盒子只是参数控制器，真实结果来自生成式图像编辑模型。

服务端很可能执行：

```text
源图标准化
  -> 主体/场景语义理解
  -> 视角与镜头参数转成模型条件
  -> Qwen 图像编辑推理
  -> 尺寸与安全检查
  -> 对象存储
  -> 任务结果事件
```

单张图片通常缺少背面信息，因此模型会生成不可见区域，而不是重建完全准确的 3D 物体。多角度结果应被定义为“视觉一致的新视图”，不能承诺几何精确。

### 4.5 Morrow 推荐实现

```ts
interface MultiAngleParams {
  horizontalDeg: number;  // -90..90
  verticalDeg: number;    // -45..45
  dolly: number;          // 0..10
  wideAngle: boolean;
}

function toMultiAngleRequest(params: MultiAngleParams) {
  return {
    rotate_right_left: params.horizontalDeg,
    vertical_angle: params.verticalDeg / 45,
    move_forward: params.dolly,
    wide_angle_lens: params.wideAngle,
  };
}
```

前端内部始终使用可读的角度单位，在适配器边界才转换为供应商字段，避免坐标系多次取反。

## 5. 重新布光

### 5.1 TapNow 前端交互

**已确认：** 重新布光面板由两部分构成：

- 左侧约 `256px` 的 Three.js/WebGL 交互预览。
- 右侧亮度、色温、主光源预设和轮廓光设置。

预览提供“透视”和“正面”两个观察模式：

```ts
const cameraViews = {
  perspective: { position: [11, 8, 10], lookAt: [0, 0, 0] },
  front: { position: [0, 1, 15], lookAt: [0, 0, 0] },
};
```

原图作为纹理贴在一个有厚度的目标平面正面。WebGL 场景还包含灯泡、光锥、球面/轴环和点光源等指示元素。它的作用是解释灯位，不是浏览器内的最终重打光算法。

### 5.2 坐标系

TapNow 使用方位角 `azimuthDeg` 和仰角 `elevationDeg`：

| 方向 | 方位角 | 仰角 |
| --- | ---: | ---: |
| 前方 | 0° | 0° |
| 右侧 | 90° | 0° |
| 后方 | 180° | 0° |
| 左侧 | 270° | 0° |
| 顶部 | 0° | 90° |
| 底部 | 0° | -90° |

主光的完整预设集合包含：

- 水平方向每 `45°` 一个位置。
- 仰角 `-45° / 0° / 45°` 下的组合位置。
- 独立顶部 `90°` 和底部 `-90°`。

拖动过程中灯位连续跟随；松手时 TapNow 会把主光吸附到最近的标准位置。其前端吸附集合为：

```ts
azimuth: [0, 45, 90, 135, 180, 225, 270, 315]
elevation: [-90, -45, 0, 45, 90]
```

快速按钮只显示前、后、左、右、顶、底六个方向，并不代表底层只有六个方向。

### 5.3 亮度、色温与轮廓光

**已确认默认值：**

```ts
const defaultRelightParams = {
  angle: { preset: 'front_0', azimuthDeg: 0, elevationDeg: 0 },
  brightnessLevel: 50,
  temperatureK: 5600,
  rimEnabled: true,
  rimPreset: 'back_0',
};
```

TapNow 当前前端提供的离散值：

```ts
brightness: [10, 50, 100]
temperatureK: [2000, 3000, 4000, 5600, 7000, 8000]
```

轮廓光只在主光处于受支持的正位或标准 `45°` 光位时可用。轮廓光方位固定在背面，只提供三种高度：

```ts
{
  back_0:      { azimuthDeg: 180, elevationDeg: 0 },
  top_back_45: { azimuthDeg: 180, elevationDeg: 45 },
  low_back_45: { azimuthDeg: 180, elevationDeg: -45 },
}
```

因此，轮廓光指示器不是可在整个球面自由移动的第二盏灯。

### 5.4 TapNow 请求

**已确认：** 当前实现调用专用接口，而不是通用任务轮询接口：

```http
POST /api/chat/v1/beta/relight
```

请求负载：

```ts
{
  fileId: sourceFileId,
  angle: { preset: selectedPreset },
  brightnessPercent: 50,
  temperatureK: 5600,
  rimEnabled: true,
  rimPreset: 'back_0',
}
```

能力/计价描述中还能看到：

```ts
{
  type: 'image',
  model: 'relight',
  provider: 'tapnow',
  imageParams: { scene: 'relight', images: [sourceImage] },
}
```

成功响应当前直接包含 `fileUrl`。前端创建一个本地任务标识用于结果记录，但没有像多角度和抠图那样把远程任务 ID 交给统一轮询器。

### 5.5 画布事务和错误

TapNow 会先创建标题为“打光”的派生节点和连线，再调用专用服务。成功后写入 `fileUrl` 并记录：

- `canvasId`
- 结果节点 ID
- 源节点 ID
- 结果 URL
- 本地任务 ID

已确认的错误分支包括：

- 无法确认图片尺寸。
- 输入图片超过 `30MB`。
- 输入内容违规。
- 余额不足（HTTP 402）。
- 服务繁忙（HTTP 502）。
- 通用生成失败。

失败时删除空派生节点。

### 5.6 Three.js 预览的性能边界

TapNow 的预览每帧只更新很小的独立 Three.js 场景：

- 相机、主灯和轮廓灯使用插值平滑。
- 纹理只在图片源变化时重新加载。
- 组件卸载时释放 renderer、geometry、material、texture 和动画帧。
- 预览原图保持原始纹理；灯光和明暗主要用于指示器及空间关系。

Morrow 复刻时必须：

1. 只在布光面板打开时启动 `requestAnimationFrame`。
2. 面板不可见或页面切到后台时停止循环。
3. 不在 React state 中写入每一帧的拖拽插值值。
4. 主图纹理使用缩略图或预览图，不上传原始大图到 GPU。
5. 关闭面板时显式释放 GPU 资源。

### 5.7 后端能力判断

**高置信度推断：** 服务端至少需要估计图像的材质、表面法线、深度或内在分解，再依据光源条件重合成画面。可能的业务链路为：

```text
输入图像
  -> 主体/场景分解
  -> 深度、法线、反照率或光照表征估计
  -> 主光与轮廓光条件编码
  -> 条件生成/扩散式重照明
  -> 身份和结构一致性约束
  -> 输出图片
```

TapNow 前端没有暴露底层模型，因此不能写死为某个公开模型。

## 6. 自动抠图

### 6.1 TapNow 当前行为

TapNow 当前的抠图是自动前景提取，不要求用户点选或框选对象。用户点击“抠图”后，模型自行判断主要前景并删除背景。

这意味着：

- 单主体商品、人像通常无需额外交互。
- 多人、多件商品或复杂遮挡时，模型可能保留错误主体。
- 当前入口没有表达“保留左边的人”或“排除后方物体”的提示数据。

### 6.2 TapNow 请求

**已确认：** 抠图同样调用统一图片生成接口：

```http
POST /api/conversation/v1/generations/image
```

请求封装会追加：

```ts
{
  image_url: sourceImageUrl,
  times: 1,
  provider: 'bria',
  model: 'rmbg',
  scene: 'cutout',
  metadata: {
    node_id: resultNodeId,
    canvas_id: canvasId,
  },
}
```

因此可以确认它不是 Canvas 本地像素处理、CSS `mask` 或棋盘格占位，而是远程背景移除模型。

**高置信度推断：** TapNow 使用 Bria RMBG 模型族。前端未暴露版本，不能断言是 RMBG 1.4 还是 2.0。

### 6.3 任务与结果

画布模式的执行流程：

1. 校验源图片。
2. 默认在右侧创建标题为“抠图”的空图片节点。
3. 设置 `editSubType: 'cutout'` 并创建连线。
4. 提交任务并取得任务 ID。
5. 注册统一任务管理器。
6. 完成后读取第一张 `generated_images[].image_url`。
7. 写入结果节点。
8. 提交失败、无任务 ID或请求异常时删除空节点并提示错误。

结果应为带多级 Alpha 的透明 PNG，而不是只有 0/1 两级的硬边遮罩。头发、绒毛、玻璃等边缘需要保留半透明过渡。

### 6.4 图片编辑器替换模式

除画布派生模式外，TapNow 图片编辑器内还支持替换当前 Fabric 图片对象：

1. 将旧对象透明度降到 `0.5`。
2. 无裁剪时复用原图片 URL。
3. 有裁剪或变换时先导出 PNG 并上传。
4. 提交抠图任务。
5. 加载透明结果。
6. 继承旧对象的 `top`、`left`、`angle`、`flipX`、`flipY` 和 `clipPath`。
7. 按原显示尺寸重算缩放。
8. 删除旧对象并插入新对象。

Morrow 第一阶段应优先实现画布派生模式；编辑器替换模式可作为后续能力，但必须保留几何和变换信息。

## 7. 交互式对象选择抠图

### 7.1 与 TapNow 当前能力的区别

TapNow 当前的自动抠图没有对象提示。若要让用户自己选择目标，需要增加独立的“选择对象”模式，不能只给 RMBG 请求增加一个前端按钮。

建议保留两个入口：

- **快速抠图**：自动识别主要前景，使用 RMBG 类模型。
- **选择对象**：点选、框选和画笔修正，使用可提示分割模型。

### 7.2 推荐模型链路

```text
自动主体：Bria RMBG
点击/框选：SAM 2 或同类可提示分割模型
文字选物：Grounding DINO + SAM 2
头发/透明边缘：Image Matting 精修
```

推荐交互：

- 单击：增加一个保留点。
- `Shift + 单击`：追加保留对象。
- `Alt + 单击`：增加排除点。
- 拖框：限定目标区域。
- 保留/排除画笔：局部修补遮罩。
- 遮罩覆盖层：实时预览选区。
- “应用”：生成透明 PNG 派生节点。

### 7.3 数据模型

```ts
type SegmentationPrompt =
  | {
      type: 'point';
      operation: 'include' | 'exclude';
      x: number;
      y: number;
    }
  | {
      type: 'box';
      operation: 'include' | 'exclude';
      x: number;
      y: number;
      width: number;
      height: number;
    }
  | {
      type: 'brush';
      operation: 'include' | 'exclude';
      points: Array<{ x: number; y: number }>;
      radius: number;
    }
  | {
      type: 'text';
      operation: 'include' | 'exclude';
      text: string;
    };

interface InteractiveCutoutDocument {
  version: 1;
  sourceAssetId: string;
  prompts: SegmentationPrompt[];
  maskAssetId?: string;
  featherPx: number;
  decontaminateEdges: boolean;
}
```

所有几何坐标使用相对原图的 `0..1` 归一化值，禁止保存屏幕像素坐标。

### 7.4 低延迟方案

用户每次点击时不应重新上传整张图片：

```text
首次打开
  -> 上传/引用源图
  -> 服务端编码图像特征
  -> 返回 segmentationSessionId

每次提示
  -> 只发送点、框或文字
  -> 服务端复用图像特征
  -> 返回低分辨率 mask 预览

最终应用
  -> 高分辨率 mask + matting
  -> 透明 PNG
  -> 持久化派生资产
```

遮罩预览可使用压缩灰度 WebP/PNG 或 RLE/二值块编码；最终 Alpha 结果必须由服务端使用原图分辨率合成。

## 8. Morrow 统一执行架构

### 8.1 能力注册

当前项目应继续将三项功能登记为远程能力；未配置服务时入口可见但禁用：

```ts
type RemoteImageCapability =
  | 'image.multi-angle'
  | 'image.relight'
  | 'image.cutout.auto'
  | 'image.cutout.interactive';
```

不可用状态应显示具体原因：

```text
未配置图像执行服务
```

不能用 CSS 效果、计时器或占位图片模拟完成。

### 8.2 适配器

```ts
interface ImageJobRequestMap {
  'image.multi-angle': {
    sourceAssetId: string;
    params: MultiAngleParams;
  };
  'image.relight': {
    sourceAssetId: string;
    params: RelightParams;
  };
  'image.cutout.auto': {
    sourceAssetId: string;
  };
  'image.cutout.interactive': {
    sourceAssetId: string;
    document: InteractiveCutoutDocument;
  };
}

interface RemoteImageJobAdapter {
  getCapabilities(): Promise<CapabilityDescriptor[]>;
  submit<K extends keyof ImageJobRequestMap>(
    capability: K,
    request: ImageJobRequestMap[K],
  ): Promise<{ jobId: string }>;
  subscribe(jobId: string, listener: (event: JobEvent) => void): () => void;
  cancel(jobId: string): Promise<void>;
}
```

重新布光即使后端当前同步返回，也应在 Morrow 内部包装成同一个 `JobEvent` 协议，使 UI 不依赖供应商的同步或异步差异。

### 8.3 推荐事件协议

```ts
type JobEvent =
  | { type: 'job.accepted'; jobId: string }
  | { type: 'job.progress'; jobId: string; stage: string; progress?: number }
  | { type: 'asset.created'; jobId: string; asset: RemoteAssetDescriptor }
  | { type: 'job.failed'; jobId: string; code: string; message: string }
  | { type: 'job.cancelled'; jobId: string };
```

只有 `asset.created` 能完成结果节点。`progress: 100`、HTTP 200 或轮询结束都不能单独被当作资产成功。

### 8.4 服务端职责

```text
浏览器
  -> 上传或引用源资产
  -> 提交规范化能力请求

API 网关
  -> 身份、限流、MIME、像素量和内容校验
  -> 创建幂等 job

任务调度器
  -> 选择供应商/自托管模型
  -> 重试和超时
  -> 记录供应商任务 ID

结果处理器
  -> 下载供应商结果
  -> 校验图片可解码、尺寸和 Alpha
  -> 生成缩略图/预览图
  -> 写入对象存储和资产数据库
  -> 发布 asset.created
```

浏览器不应长期直接依赖供应商 URL。服务端应把结果转存到自己控制的对象存储，避免 URL 过期、跨域失败或供应商删除文件。

## 9. 性能与网络设计

### 9.1 浏览器

- 节点默认加载缩略图，放大或选中后加载预览图。
- 原图只在下载、局部编辑或最终合成时读取。
- 远程任务共用一个任务订阅/轮询管理器，不允许每个节点独立建立定时器。
- Three.js 参数面板关闭时彻底释放纹理、材质、几何体和动画帧。
- 拖拽参数只更新局部 store 或 ref，不触发整个画布节点数组重渲染。
- 结果 URL 转 Blob 后由现有 `mediaStore` 管理对象 URL 生命周期。

### 9.2 网络

- 同一源资产只上传一次，后续任务传 `assetId` 或受控短期签名 URL。
- 参数请求通常只有几 KB；主要流量来自原图上传和结果图下载。
- 自动抠图结果 PNG 可能因 Alpha 和无损编码比 JPEG 原图更大。
- 交互分割每次仅发送提示坐标和低分辨率 mask，不重复传原图。
- 批量任务状态合并查询，或使用单条 SSE/WebSocket 事件流。

### 9.3 服务端

- 使用内容哈希避免同一资产重复上传和重复预处理。
- SAM 类交互分割缓存 image embedding，并设置会话 TTL。
- 多角度和重打光使用有界 GPU 队列、租户并发限制和超时。
- 结果转码、缩略图和安全扫描放在推理之后的独立 CPU Worker。
- `idempotencyKey = projectId + resultNodeId + operationRevision`，防止重复点击产生重复计费任务。

## 10. 错误与回滚

统一错误码建议：

| 错误码 | UI 行为 |
| --- | --- |
| `SERVICE_NOT_CONFIGURED` | 禁用入口并显示“未配置图像执行服务” |
| `SOURCE_ASSET_MISSING` | 不创建结果节点，提示重新导入 |
| `IMAGE_TOO_LARGE` | 不提交，显示实际限制 |
| `UNSUPPORTED_MIME` | 不提交，列出允许格式 |
| `CONTENT_REJECTED` | 删除空节点，保留可理解的安全提示 |
| `INSUFFICIENT_BALANCE` | 删除空节点，不伪造任务 |
| `PROVIDER_BUSY` | 结果节点显示可重试状态 |
| `PROVIDER_TIMEOUT` | 保留任务记录，允许继续查询或重试 |
| `RESULT_INVALID` | 不发布 `asset.created`，记录不可解码或 Alpha 缺失原因 |

是否删除失败节点应由事务策略统一决定：

- 提交前失败：不创建或回滚节点。
- 已有远程任务 ID：保留失败节点和“重试”入口更利于追踪。
- 用户主动取消：保留取消记录，但默认从画布移除占位节点。

## 11. 测试清单

### 11.1 多角度

- 水平角 `-90 / 0 / 90` 与预览、请求方向一致。
- 垂直角 `-45 / 0 / 45` 正确转换为 `-1 / 0 / 1`。
- 拖拽指示器和参数面板没有重复取反。
- 失败时不留下孤立连线或无任务空节点。

### 11.2 重新布光

- 前、右、后、左、顶、底与方位角/仰角表一致。
- 透视和正面模式只改变观察相机，不改变实际灯位参数。
- 主光拖动过程中连续，松手后按产品策略吸附；若 Morrow 选择不吸附，则适配器必须明确量化方式。
- 轮廓光只允许三个背部位置。
- 原图纹理不被前端伪亮度效果当作最终结果。
- 关闭面板后 WebGL context、纹理和动画帧得到释放。

### 11.3 抠图

- 结果图片可以解码且确实含 Alpha 通道。
- 源节点不被覆盖。
- 头发等边缘保留半透明值。
- 多主体图片能明确暴露自动模式的不确定性。
- 交互模式的点、框和画笔坐标在不同缩放下保持一致。
- 页面刷新后能恢复未完成的任务和已保存遮罩。

## 12. 实施顺序

1. 完成统一 `RemoteImageJobAdapter`、事件协议、幂等任务和派生节点事务。
2. 接入自动抠图，验证 Alpha 资产存储、透明预览和下载闭环。
3. 接入多角度，统一内部坐标并复用现有 3D 参数面板。
4. 接入重新布光，把专用同步接口包装成统一任务事件。
5. 增加交互式选择对象，先实现点选/框选，再补画笔和文字选择。
6. 完成任务恢复、失败重试、配额限制和批量任务性能验收。

## 13. 公开参考

- [Bria RMBG 2.0](https://github.com/Bria-AI/RMBG-2.0)
- [Bria Background Removal API](https://docs.bria.ai/image-editing/endpoints/background-remove)
- [Fal Bria Background Remove](https://fal.ai/models/fal-ai/bria/background/remove/api)
- [Segment Anything 2](https://github.com/facebookresearch/sam2)
- [Grounding DINO](https://github.com/IDEA-Research/GroundingDINO)

这些公开项目只能说明可行的复刻路线，不证明 TapNow 服务端采用了相同版本或完全相同的模型组合。
