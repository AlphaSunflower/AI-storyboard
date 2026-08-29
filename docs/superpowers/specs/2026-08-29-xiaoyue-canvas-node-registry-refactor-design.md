# XiaoYueCanvas 画布重构：nodeRegistry 声明式架构（方案 B）

日期：2026-08-29
模块：`XiaoYueCanvasAgent`（前端 React Flow 画布）+ 后端 `XiaoYueCanvas`（无需改动，画布图 JSON 兼容）

## 1. 目标与范围

用户诉求（源：本轮 brainstorming）：
- **节点**：只加「剧本」「分镜」两类（不要角色/人物/音频/旁白等 25 种全套）
- **交互**：最致命——拖拽连线、节点菜单、连接即创建、右键、选中态不如参考画布顺滑
- **布局**：左侧栏/顶栏/面板排布按参考画布重排

范围锁定（用户明确选择）：**方案 B · nodeRegistry 声明式一次到位，剧本/分镜/图片/视频/文字 5 类全部迁入注册表，顺带修掉现有边校验**。不更换框架。

## 2. 现状诊断

当前画布（`XiaoYueCanvasAgent`）：
- 库：`@xyflow/react` 12.x（与参考工程 `xiakeman-ai-short-drama` 同一个库，版本几乎一致）
- `CanvasWorkspace.tsx`：`const nodeTypes = { media: MediaNode, video: VideoNode }` —— **只有两种 node type**
- `types.ts`：`CanvasNode = Node<MediaNodeData, 'media' | 'video'>` —— TS 类型收窄，加新 type 必须改这里
- 7 种 `kind`（text/image/video/script/note/prompt/sequence）全部挤在 `MediaNode` 里靠 `data.kind` 分支渲染；`script` 的生成分镜逻辑已存在（`submitScriptDrafting`），但没拆成独立节点组件
- `store/canvasStore.ts` 的 `connect()`/`createEdge()` 直接 `addEdge`，**无 `acceptTypes` 边校验**
- store 有完整基建：undo/redo、复制粘贴、框选、拖拽缩放、媒体预算、虚拟化、自动保存、小地图 —— **这些全部保留，不做推倒**

参考工程（`xiakeman`）已具备的声明式架构：
- `domain/canvasNodes.ts`：`CANVAS_NODE_TYPES` 常量 + 每类型一套 TS 数据接口（`ScriptFrame`、`StoryboardFrame`、`ScriptResultNodeData` 等）
- `domain/nodeRegistry.ts`：`CanvasNodeDefinition { type, menuLabelKey, menuIcon, menuCategory, visibleInMenu, capabilities, connectivity: { maxInputs, maxOutputs, acceptTypes }, createDefaultData() }`
- `ConnectionNodeMenu`（连接即创建）、`GroupedNodeMenu`（分组菜单）、`CanvasToolbar`（左侧悬浮工具栏）、`CanvasProjectBridgeBar`（顶栏）

## 3. 架构设计

### 3.1 新建 `src/domain/canvasNodes.ts`

```ts
export const CANVAS_NODE_TYPES = {
  media: 'media',        // 存量兜底（图片占位/导入图/文字）
  video: 'video',        // 存量兜底（视频）
  script: 'script',      // 新增：剧本
  storyboard: 'storyboard', // 新增：分镜结果
} as const;

export type CanvasNodeType = (typeof CANVAS_NODE_TYPES)[keyof typeof CANVAS_NODE_TYPES];
```

数据接口（只建剧本/分镜两类 + 复用现有字段）：
- `ScriptFrame`：`{ shotNumber, duration, sceneDescription, shotType, cameraAngle, cameraMovement, characterAction, emotion, dialogue, lighting, sceneTag, sound, character, characterDesc }`
- `ScriptNodeData`：`{ displayName, scriptText, frames: ScriptFrame[], isGenerating, providerId, model, width?, height? }`
- `StoryboardFrame`：`{ index, label?, description, notes?, imageUrl, previewImageUrl?, duration? }`
- `StoryboardNodeData`：`{ displayName, frames: StoryboardFrame[], rows, cols, overallAspectRatio, isGenerating, width?, height? }`

### 3.2 新建 `src/domain/nodeRegistry.ts`

照参考 `CanvasNodeDefinition` 结构，首批注册表：

| type | 归类 | 可见 | 输入 acceptTypes |
|------|------|------|------------------|
| media | 基础 | √ | `[]`（接受上游为空，下游不限） |
| video | 媒体 | √ | `[media]` |
| script | 剧本 | √ | `[]` |
| storyboard | 剧本 | √ | `[script]`（剧本 → 分镜） |

关键：`connectivity.acceptTypes` 是边校验与「连接即创建」菜单弹出的唯一数据源。`createDefaultData()` 返回每类型初始 data，替代现在 `makeNode` 里的硬编码。

### 3.3 `nodeTypes` 构建（`CanvasWorkspace.tsx`）

```ts
const nodeTypes: NodeTypes = {
  media: MediaNode,        // 存量，保留
  video: VideoNode,        // 存量，保留
  script: ScriptNode,      // 新增
  storyboard: StoryboardNode, // 新增
};
```

`types.ts` 里 `CanvasNode` 统一放宽：
```ts
export type CanvasNode = Node<MediaNodeData & Record<string, unknown>, CanvasNodeType>;
```

### 3.4 拆分独立节点组件

- `nodes/ScriptNode.tsx`：剧本输入（textarea）→ 「生成分镜」按钮 → 复用 `submitScriptDrafting(id)` → 产出落 `data.frames`，并在下游创建/更新 `StoryboardNode`
- `nodes/StoryboardNode.tsx`：分镜结果展示（网格帧卡片，每帧含镜号/画面描述/景别/对白，可点帧放大），向上取自已有 `submitScriptDrafting` 链路或 `frames` 数据
- `MediaNode` / `VideoNode` 维持现状不动（迁移期内零改动，降低风险）

## 4. 交互强化（痛点 #3 核心）

### 4.1 `ConnectionNodeMenu`（连接即创建，参考 `Canvas.tsx` + `ConnectionNodeMenu.tsx`）

- `Canvas.tsx` 的 `ReactFlow` 加 `onConnectStart` / `onConnectEnd`：记录拖线起点 node + handleType
- `onConnectEnd` 里若 `didConnect === false`（松手未落到节点）：`screenToFlowPosition` 把鼠标坐标转 flow 坐标，`window.dispatchEvent(new CustomEvent('connection-end', { detail: { clientX, clientY, flowX, flowY, sourceNodeId, sourceNodeType, handleType } }))`
- `ConnectionNodeMenu` 组件监听 `connection-end`：
  - `handleType === 'source'`（输出口拖）= 向后接：弹出「选择下游节点类型」→ 按 `acceptTypes` 过滤 → 选中后 `createConnectedNode(sourceId, key, flowPos)` 创建并连线
  - `handleType === 'target'`（输入口拖）= 向前插：弹出「选择上游节点类型」→ 选中后 `createNodeBefore(sourceId, key, flowPos)`
- `store` 增加 `createConnectedNode` / `createNodeBefore`（复用现有 `createNodes` + `createEdge` 逻辑）

### 4.2 边校验（修现有薄弱点）

`store.connect()` / `createEdge()` 加校验：源节点 `acceptTypes` 不包含目标节点 type 时拒绝并 toast。数据源 = `nodeRegistry[sourceType].connectivity.acceptTypes`。

### 4.3 `GroupedNodeMenu` + 分组添加节点

节点库 `/ 画布空白双击 / 右键菜单「添加节点」` 统一走 `GroupedNodeMenu`（按 category 折叠 + 最近使用）。替换现在 `CanvasContextMenu` 里写死的「图片/文字」两项。

## 5. 布局重排（痛点 #4）

现状：顶部 `.canvas-project-bar`（已有）+ 底部 `.canvas-tool-island` + 左侧 `.canvas-view-bar` + 右侧 400px `chat-workspace`（可收起）。
参照参考画布：**左侧悬浮玻璃工具栏（添加节点/素材库/AI 对话/设置）+ 顶部项目桥接栏**。

改造：
- 新增左侧竖排悬浮工具栏（`CanvasToolbar` 样式，参考 `CanvasToolbar.tsx`），承载「＋添加节点 / 素材库 / AI 对话 / 设置」
- 顶部 `.canvas-project-bar` 保留并强化为项目桥接（标题/画布切换/保存状态/导出）
- 右侧 `chat-workspace`（执行服务/节点属性）保留但默认可收起
- **视觉基调沿用当前 TapNow 中性层**（`--tn-*`，黑/白/蓝），不引入参考画布的荧光绿——参考画布观感精致来自「玻璃拟态 + 中性色卡片 + 圆角层次」，而非配色

> 视觉基调（定案）：**先保持现状**——画布中性深色层与设计 token 不动，仅借「玻璃拟态 + 卡片层次 + 圆角」对齐参考观感。配色是否调整**等首轮效果落地后再决定**（用户明确：先做功能/交互/布局，看到效果再评估是否切浅色）。避免在一开始就推翻既有主题、扩大改动面。

## 6. 数据迁移与兼容

- 存量画布 JSON：`node.type` 均为 `'media'` / `'video'` → `nodeRegistry` 保留这两项兜底映射，**旧画布打开即兼容，零迁移**
- 新增 `script` / `storyboard` 的 `createDefaultData()` 保证新创建节点数据完整
- `morrow_canvas_graphs` 表与 `saveCanvas`/`loadCanvas` 不动（图 JSON 结构兼容）

## 7. 分阶段实施

1. **Phase 1**：`domain/canvasNodes.ts` + `domain/nodeRegistry.ts`（纯声明，无 UI 改动）
2. **Phase 2**：拆 `ScriptNode` / `StoryboardNode` 组件，挂进 `nodeTypes`；`CanvasNode` 类型放宽
3. **Phase 3**：交互强化——`ConnectionNodeMenu` + `store` 增 `createConnectedNode`/`createNodeBefore` + 边校验
4. **Phase 4**：`GroupedNodeMenu` 替换裸的「添加图片/文字」；节点库、双击空白、右键菜单统一入口
5. **Phase 5**：布局重排——左侧悬浮工具栏 + 顶栏项目桥接强化；右侧面板可收起
6. **Phase 6**：测试验证——旧画布打开兼容、剧本→分镜全链路、连接即创建、边校验拒绝非法边

## 8. 测试

- 保留现有 `canvasStore.test.ts`，补 `createConnectedNode`/`createNodeBefore`/边校验用例
- 手动：打开旧画布（media/video）不白屏；新建剧本→生成分镜；拖线到空白弹出节点菜单；非法连接被拒
- 前端构建验证：`cd AIStoryboardClient && npx tsc -p tsconfig.app.json --noEmit && npm run build`（注意实际目录是 `XiaoYueCanvasAgent`，构建按其 package.json 脚本走）

## 9. 明确不做（YAGNI）

- 不引入 `character/scene/prop/audio/novel` 等其余参考节点——用户明确只要剧本/分镜
- 不为「以后加满 25 种」预建完整 capabilities 枚举——注册表结构支持，按需添项
- 不改后端 `XiaoYueCanvas` / 数据库——图 JSON 兼容，零后端改动
