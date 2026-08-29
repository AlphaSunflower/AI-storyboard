# 画布节点类型丰富设计（XiaoYueCanvasAgent）

- 日期：2026-08-29
- 状态：待评审
- 关联：参考仓库 `xiakeman-ai-short-drama/src/features/canvas`（24 种语义节点 + nodeRegistry 声明式注册）

## 目标

把画布从"3 种媒体节点（text/image/video）+ operation 派生"扩展到**更丰富的节点类型**，兼顾三态编排（Agent 工具面 / 人工微调 / 纯人工）。参考画布真正值得借鉴的是它的**声明式注册模式**（nodeRegistry），不照搬它 24 个具体节点（很多依赖其 Tauri 本地文件能力）。

## 现状 vs 目标

| | 现状 | 参考画布 | 目标 |
|---|---|---|---|
| 节点模型 | `MediaKind` = text/image/video，节点 `data` 是 `MediaNodeData` | `CanvasNodeType` 24 种，每类一种 React 组件 | 多类型节点，但保留统一数据面 |
| 注册 | `NODE_PRESETS` 硬编码 3 项 | `nodeRegistry: Record<key, NodeDef>` 声明式 | 声明式 registry，新增类型只加一条 |
| 渲染 | `nodeTypes = { media, video }` | 24 个组件 + `connectivity` 约束 | 按 `node.kind` 分发渲染 |
| 连线约束 | 无（createEdge 只去重） | `connectivity.maxInputs/maxOutputs/acceptTypes` | 类型级校验 |

## 设计原则

1. **不破坏现有 media+operation 派生链**——它是 Agent 工具面的底座（crop/split/adjust/annotate）。
2. **新增的是"节点种类"，不是"新的运维模式"**。每个新类型仍是 React Flow 的一个 `node.type`，数据仍放 `node.data`（Record<string,unknown>），持久化方式不变（graph_json 整幅入库）。
3. **声明式注册**：参考 `nodeRegistry` 模式——`type/menuLabel/menuIcon/category/capabilities/connectivity/createDefaultData` 一个对象定义一类节点，UI（节点库、菜单）由 registry 驱动，新增类型零 UI 改动。

## 节点类型提议（第一批，不贪多）

在保留现有 3 种 media 基础上，新增 4 种高价值、纯 Web 可实现的：

| type | 用途 | 说明 |
|------|------|------|
| `script` | 剧本/文本输入 | 用户贴分镜脚本，后续可派生 |
| `note` | 设计说明/批注 | 现有 text 的增强，带富文本 |
| `prompt` | AI 提示词节点 | 专用于生图/生视频的 prompt 容器 |
| `sequence` | 序列编排节点 | 组合多个子节点（类似于参考画布 Group/视频合成）|

**不引入**（依赖 Tauri 本地文件，纯 Web 跑不了）：audio/videoFrame/panorama360/director3d/novel。这些等后续接后端文件服务再评估。

## 数据模型改动

`types.ts`：
```ts
// MediaKind 扩充为 NodeKind：新增 4 种
export type NodeKind = 'text' | 'image' | 'video' | 'script' | 'note' | 'prompt' | 'sequence';
// CanvasNodeData 增加可选字段（保持 Record<string,unknown> 兼容）
export interface MediaNodeData extends Record<string, unknown> {
  kind: NodeKind;
  // ... 现有字段
  scriptText?: string;   // script
  richText?: string;     // note
  promptText?: string;   // prompt
  children?: string[];   // sequence
}
```

`data/presets.ts`：`NODE_PRESETS` 扩充为 7 项，每项带 `kind/title/description/accent`。

新增 `domain/nodeRegistry.ts`（仿参考画布）：
```ts
export interface NodeDef {
  type: NodeKind;
  menuLabel: string;
  menuIcon: string;
  category: '基础' | '场景' | '媒体' | '文本';
  capabilities: { hasImage: boolean; hasPrompt: boolean; hasExport: boolean; hasTools: boolean };
  connectivity: { maxInputs: number; maxOutputs: number; acceptTypes: NodeKind[] };
  createDefaultData: () => Record<string, unknown>;
}
export const nodeRegistry: Record<string, NodeDef> = { ... };
export function getNodeDefByType(t: string): NodeDef | undefined { ... }
```

## 渲染层

`CanvasWorkspace.tsx` 的 `nodeTypes` 从 `{ media, video }` 扩到按 registry 分发。新类型组件首先复用 `MediaNode`（文本类），`script/prompt` 等用轻量新组件。核心：`kind` 驱动渲染，不在组件里写死。

## 连线约束（connectivity 校验）

`toolRegistry` 的 `canvas.edge.create` 增加类型校验：从 `nodeRegistry[source.type].connectivity.maxOutputs/acceptTypes` 判断是否能连,匹配参考画布。这是"复杂但值得"的增强——让 Agent 和不熟悉模型的人都不会连出错。

## 范围控制（ponytail）

- **本步只做**：NodeKind 扩充 + NODE_PRESETS + nodeRegistry + 节点库 UI 驱动 + 基础渲染分发。约 4-6 文件。
- **暂不做**：script 的 AI 分镜派生逻辑、sequence 的嵌套组合运行时、connectivity 全量校验（先做 edge 校验这一个）。这些放第二步，等第一批跑通。
- **不碰**：agent-core / 后端（节点类型是前端 UI 概念，持久化仍是 graph_json 整幅）。

## 验证

- `npx tsc -p tsconfig.app.json --noEmit` 通过
- 节点库能列出 7 类节点，点击落节点
- 旧 3 类 media 操作（crop/split 派生）不回归

## 待用户确认

1. 第一批这 4 种类型（script/note/prompt/sequence）是否合适，还是你要先上别的？
2. connectivity 边校验是否本轮一并做，还是先出类型再补校验？
