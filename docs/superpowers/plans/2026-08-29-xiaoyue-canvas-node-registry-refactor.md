# XiaoYueCanvas nodeRegistry 画布重构 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 XiaoYueCanvasAgent 画布从「2 种 node type（media/video）」升级为「nodeRegistry 声明式 + 剧本/分镜节点 + 连接即创建 + 边校验 + 左侧工具栏」的编排式画布，对齐参考工程交互手感，不换框架。

**架构：** 复用已有 `src/domain/nodeRegistry.ts`（已是声明式）与 `submitScriptDrafting` 分镜链路。新增 `storyboard` 节点类型入注册表；拆 `ScriptNode`/`StoryboardNode` 独立组件挂进 nodeTypes；新增 `ConnectionNodeMenu`（端口拖空白→创建连接节点）与 `GroupedNodeMenu`；`store.connect/createEdge` 加 `acceptTypes` 边校验。存量 `media`/`video` 节点保留兼容。

**技术栈：** `@xyflow/react` 12.8.6（不换）、React 18、zustand 5、lucide-react、vitest。验证：`npm run typecheck && npm run test && npm run build`（XiaoYueCanvasAgent 独立 package，非主工程）。

**关键前置事实（已确认，勿偏离）：**
- 现 nodeTypes 仅 `{ media: MediaNode, video: VideoNode }`（CanvasWorkspace.tsx:23）
- `nodeRegistry.ts` 已有 `NODE_DEFS` 7 种 kind（含 script），`connectivity.acceptTypes` 已定义但**未被 store 校验使用**
- 分镜链路：`submitScriptDrafting(id, targetNodeId)`（jobRunner.ts:76）→ `agent.request` → `asset.created` → `AgentPanel.handleJobEvent:200` → `materializeAsset:163` `createDerivedNodes(sourceNode.id, [帧图片])`。**分镜产物 = 独立图片节点，非结构化帧 JSON**
- `store.createConnectedNode` / `createNodeBefore` **不存在**，需新增
- 用例风格：vitest + `useCanvasStore.setState` 直设（canvasStore.test.ts）

---

### 任务 1：nodeRegistry 补 storyboard 类型 + NodeKind 扩展

**文件：**
- 修改：`XiaoYueCanvasAgent/src/types.ts`（NodeKind 联合加 `'storyboard'`）
- 修改：`XiaoYueCanvasAgent/src/domain/nodeRegistry.ts`（NODE_DEFS 加 storyboard 条目）

- [ ] **步骤 1：types.ts 扩展 NodeKind**

```ts
// types.ts
export type NodeKind = 'text' | 'image' | 'video' | 'script' | 'note' | 'prompt' | 'sequence' | 'storyboard';
```

- [ ] **步骤 2：nodeRegistry 加 storyboard 定义**

```ts
// domain/nodeRegistry.ts  → NODE_DEFS
storyboard: {
  type: 'storyboard', key: 'storyboard', menuLabel: '分镜', menuIcon: 'storyboard', category: '基础',
  capabilities: { hasImage: true, hasPrompt: false, hasExport: false, hasTools: false },
  // maxInputs:1 —— 接受上游剧本；acceptTypes 供边校验与连接即创建菜单过滤
  connectivity: { maxInputs: 1, maxOutputs: 0, acceptTypes: ['script'] },
  createDefaultData: () => ({ kind: 'storyboard', label: '分镜', frames: [], prompt: '', status: 'idle', accent: '#ffb3a1' }),
},
```

- [ ] **步骤 3：script 的 connectivity 放行下游 storyboard**

```ts
// NODE_DEFS.script 原 connectivity: { maxInputs: 0, maxOutputs: 0, acceptTypes: [] }
// 改为允许输出到分镜：
connectivity: { maxInputs: 0, maxOutputs: 1, acceptTypes: ['storyboard'] },
```

- [ ] **步骤 4：验证**

运行（在 `XiaoYueCanvasAgent` 目录）：`npm run typecheck`
预期：通过，无类型错误。

---

### 任务 2：store 新增 createConnectedNode / createNodeBefore + 边校验

**文件：**
- 修改：`XiaoYueCanvasAgent/src/store/canvasStore.ts`（新增两个方法 + createEdge/connect 校验）

- [ ] **步骤 1：新增 createConnectedNode（输出口拖空白 → 建下游）**

```ts
// canvasStore.ts addNode 附近
createConnectedNode: (sourceId, kind, position) => {
  const source = get().nodes.find((n) => n.id === sourceId);
  if (!source) return null;
  const node = { ...makeNode(kind, position), selected: true };
  const edge = { id: `edge-${createId()}`, source: sourceId, sourceHandle: 'right', target: node.id, targetHandle: 'left', type: 'canvas' };
  commit((state) => ({ nodes: [...clearSelection(state.nodes), node], edges: [...state.edges, edge] }));
  set({ selectedNodeId: node.id });
  return node.id;
},
```

- [ ] **步骤 2：新增 createNodeBefore（输入口拖空白 → 建上游）**

```ts
createNodeBefore: (targetId, kind, position) => {
  const target = get().nodes.find((n) => n.id === targetId);
  if (!target) return null;
  const node = { ...makeNode(kind, position), selected: true };
  const edge = { id: `edge-${createId()}`, source: node.id, sourceHandle: 'right', target: targetId, targetHandle: 'left', type: 'canvas' };
  commit((state) => ({ nodes: [...clearSelection(state.nodes), node], edges: [...state.edges, edge] }));
  set({ selectedNodeId: node.id });
  return node.id;
},
```

- [ ] **步骤 3：createEdge 加 acceptTypes 校验**

```ts
// createEdge 校验逻辑处（约 :387）
import { NODE_DEFS } from '../domain/nodeRegistry';
// 在 createEdge 内、现有存在性校验后：
const sourceDef = NODE_DEFS[state.nodes.find((n) => n.id === connection.source)?.data.kind];
if (!sourceDef?.connectivity?.acceptTypes?.length) return null; // 未声明接受类型的源节点不透传
const target = state.nodes.find((n) => n.id === connection.target);
if (target && sourceDef.connectivity.acceptTypes.length && !sourceDef.connectivity.acceptTypes.includes(target.data.kind)) return null;
```

- [ ] **步骤 4：测试**（追加到 `store/canvasStore.test.ts`）

```ts
it('rejects connections that violate acceptTypes', () => {
  useCanvasStore.getState().addNode('script', { x: 0, y: 0 });
  useCanvasStore.getState().addNode('note', { x: 300, y: 0 });
  const scriptId = useCanvasStore.getState().nodes[0].id;
  const noteId = useCanvasStore.getState().nodes[1].id;
  // script 的 acceptTypes 只有 storyboard，连到 note 应被拒
  const edgeId = useCanvasStore.getState().createEdge({ source: scriptId, target: noteId, sourceHandle: 'right', targetHandle: 'left' });
  expect(edgeId).toBeNull();
});
```

- [ ] **步骤 5：验证**

运行：`npm run test`（XiaoYueCanvasAgent 目录）
预期：现有用例全过 + 新用例 PASS。

---

### 任务 3：拆 ScriptNode / StoryboardNode 组件 + 挂 nodeTypes

**文件：**
- 创建：`XiaoYueCanvasAgent/src/components/nodes/ScriptNode.tsx`
- 创建：`XiaoYueCanvasAgent/src/components/nodes/StoryboardNode.tsx`
- 修改：`XiaoYueCanvasAgent/src/components/CanvasWorkspace.tsx`（nodeTypes 注册）

- [ ] **步骤 1：创建 ScriptNode（复用 submitScriptDrafting）**

```tsx
// nodes/ScriptNode.tsx
import { memo, useState } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { FileText, LoaderCircle } from 'lucide-react';
import { useCanvasStore } from '../../store/canvasStore';
import { submitScriptDrafting } from '../../services/jobRunner';
import type { CanvasNode } from '../../types';

function ScriptNodeComponent({ id, data, selected }: NodeProps<CanvasNode>) {
  const updateNode = useCanvasStore((s) => s.updateNode);
  const [drafting, setDrafting] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);
  return (
    <article className={`media-node is-script ${selected ? 'is-selected' : ''}`}>
      <div className="node-floating-title"><FileText size={12} /><span>{data.label}</span></div>
      <div className="node-card script-node-view">
        <textarea className="text-node-editor nodrag nowheel" aria-label="剧本内容" value={data.scriptText ?? ''} placeholder="在此粘贴分镜剧本…" onChange={(e) => updateNode(id, { scriptText: e.target.value })} />
        <button className="node-upload node-upload--generate nodrag" disabled={drafting} onClick={() => { const text = (data.scriptText ?? '').trim(); if (!text) { setFeedback('请先输入剧本'); window.setTimeout(() => setFeedback(null), 2200); return; } setDrafting(true); void submitScriptDrafting(text, id, (msg) => setFeedback(msg)).then(() => setFeedback('分镜任务已提交')).catch((err) => setFeedback(err instanceof Error ? err.message : '分镜提交失败')).finally(() => setDrafting(false)); }}>
          {drafting ? <LoaderCircle className="is-spinning" size={14} /> : <FileText size={14} />} {drafting ? '生成中' : '生成分镜'}
        </button>
        {feedback && <small className="node-upload-error nodrag">{feedback}</small>}
      </div>
      <Handle className="node-handle" id="left" type="target" position={Position.Left} />
      <Handle className="node-handle" id="right" type="source" position={Position.Right} />
    </article>
  );
}
export const ScriptNode = memo(ScriptNodeComponent);
```

- [ ] **步骤 2：创建 StoryboardNode（归集展示分镜帧）**

```tsx
// nodes/StoryboardNode.tsx —— 展示 data.frames 网格帧；产物由 asset.created 经 createDerivedNodes 挂到下游
import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { Layers } from 'lucide-react';
import { useStore } from '@xyflow/react';
import type { CanvasNode } from '../../types';

function StoryboardNodeComponent({ id, data, selected }: NodeProps<CanvasNode>) {
  const frames = data.frames ?? [];
  const zoom = useStore((s) => s.transform[2]);
  return (
    <article className={`media-node is-storyboard ${selected ? 'is-selected' : ''}`}>
      <div className="node-floating-title"><Layers size={12} /><span>{data.label}</span></div>
      <div className="node-card storyboard-grid" style={{ '--inverse-zoom': 1 / zoom } as React.CSSProperties}>
        {frames.length ? frames.map((frame, i) => (
          <figure key={i} className="storyboard-frame">{frame.img && <img src={frame.img} alt="" />}<figcaption>{frame.label ?? `镜头${i + 1}`}</figcaption></figure>
        )) : <div className="empty-image-node"><span>连接剧本节点生成分镜</span></div>}
      </div>
      <Handle className="node-handle" id="left" type="target" position={Position.Left} />
    </article>
  );
}
export const StoryboardNode = memo(StoryboardNodeComponent);
```

- [ ] **步骤 3：CanvasWorkspace 注册 nodeTypes**

```ts
// CanvasWorkspace.tsx :23
import { ScriptNode } from './nodes/ScriptNode';
import { StoryboardNode } from './nodes/StoryboardNode';
const nodeTypes: NodeTypes = { media: MediaNode, video: VideoNode, script: ScriptNode, storyboard: StoryboardNode };
```

- [ ] **步骤 4：验证**

运行：`npm run typecheck`
预期：通过。

---

### 任务 4：ConnectionNodeMenu 连接即创建（交互核心）

**文件：**
- 修改：`XiaoYueCanvasAgent/src/components/CanvasWorkspace.tsx`（加 onConnectStart/onConnectEnd，dispatch connection-end）
- 创建：`XiaoYueCanvasAgent/src/components/ConnectionNodeMenu.tsx`

- [ ] **步骤 1：CanvasWorkspace 加连接生命周期**

```ts
// CanvasWorkspace.tsx（CanvasInner 内）
const connectRef = useRef<{ nodeId: string; nodeType: string | undefined; handleType: 'source' | 'target' | null; didConnect: boolean } | null>(null);
const { screenToFlowPosition } = useReactFlow();

const handleConnectStart = useCallback((_e, params) => {
  if (params.nodeId) {
    const node = useCanvasStore.getState().nodes.find((n) => n.id === params.nodeId);
    connectRef.current = { nodeId: params.nodeId, nodeType: node?.data.kind, handleType: params.handleType, didConnect: false };
  }
}, []);

const handleConnect = useCallback((conn: Connection) => {
  if (connectRef.current) connectRef.current.didConnect = true;
  connect(conn);
}, [connect]);

const handleConnectEnd = useCallback((event: MouseEvent | TouchEvent) => {
  const ref = connectRef.current;
  if (!ref || ref.didConnect) return;
  const clientX = 'clientX' in event ? event.clientX : 0;
  const clientY = 'clientY' in event ? event.clientY : 0;
  const flow = screenToFlowPosition({ x: clientX, y: clientY });
  window.dispatchEvent(new CustomEvent('connection-end', { detail: { clientX, clientY, flowX: flow.x, flowY: flow.y, sourceNodeId: ref.nodeId, sourceNodeType: ref.nodeType, handleType: ref.handleType ?? 'source' } }));
  connectRef.current = null;
}, [screenToFlowPosition]);
```

ReactFlow 组件加：`onConnectStart={handleConnectStart}` `onConnect={handleConnect}` `onConnectEnd={handleConnectEnd}`

- [ ] **步骤 2：创建 ConnectionNodeMenu**

```tsx
// components/ConnectionNodeMenu.tsx
import { useEffect, useRef, useState } from 'react';
import { useCanvasStore } from '../store/canvasStore';
import { NODE_DEFS, listMenuNodes, getNodeDefByType } from '../domain/nodeRegistry';

export function ConnectionNodeMenu() {
  const [menu, setMenu] = useState<{ visible: boolean; x: number; y: number; flowX: number; flowY: number; sourceNodeId: string; sourceNodeType: string; handleType: 'source' | 'target' } | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const createConnectedNode = useCanvasStore((s) => s.createConnectedNode);
  const createNodeBefore = useCanvasStore((s) => s.createNodeBefore);

  useEffect(() => {
    let justOpened = false;
    const onConnectEnd = (e: CustomEvent) => {
      const { didConnect, clientX, clientY, flowX, flowY, sourceNodeId, sourceNodeType, handleType } = e.detail;
      if (didConnect) return;
      const sourceDef = getNodeDefByType(sourceNodeType);
      if (!sourceDef) return;
      const accept = handleType === 'target' ? listMenuNodes().filter((d) => d.connectivity.acceptTypes.includes(sourceNodeType)) : sourceDef.connectivity.acceptTypes.map((t) => NODE_DEFS[t]).filter(Boolean);
      if (!accept.length) return;
      setMenu({ visible: true, x: clientX, y: clientY, flowX: flowX ?? clientX, flowY: flowY ?? clientY, sourceNodeId, sourceNodeType, handleType });
    };
    const onClick = (e: MouseEvent) => { if (justOpened) { justOpened = false; return; } if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenu(null); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenu(null); };
    window.addEventListener('connection-end' as never, onConnectEnd);
    document.addEventListener('click', onClick, true);
    document.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('connection-end' as never, onConnectEnd); document.removeEventListener('click', onClick, true); document.removeEventListener('keydown', onKey); };
  }, []);

  if (!menu?.visible) return null;
  const options = menu.handleType === 'target'
    ? listMenuNodes().filter((d) => d.connectivity.acceptTypes.includes(menu.sourceNodeType))
    : (NODE_DEFS[menu.sourceNodeType]?.connectivity.acceptTypes ?? []).map((t) => NODE_DEFS[t]).filter(Boolean);

  return (
    <div ref={menuRef} className="connection-node-menu" style={{ left: menu.x, top: menu.y }} onClick={(e) => e.stopPropagation()}>
      <header>{menu.handleType === 'target' ? '选择上游节点类型' : '选择下游节点类型'}</header>
      {options.map((def) => (
        <button key={def.type} onClick={() => { menu.handleType === 'target' ? createNodeBefore(menu.sourceNodeId, def.type, { x: menu.flowX, y: menu.flowY }) : createConnectedNode(menu.sourceNodeId, def.type, { x: menu.flowX, y: menu.flowY }); setMenu(null); }}>
          <span>{def.menuLabel}</span>
        </button>
      ))}
    </div>
  );
}
```

- [ ] **步骤 3：CanvasWorkspace 渲染 ConnectionNodeMenu**

```tsx
// CanvasInner return 内、</main> 前
<ConnectionNodeMenu />
```

- [ ] **步骤 4：验证**

运行：`npm run typecheck`
预期：通过。手动：拖 script 输出口到空白 → 弹「选择下游节点类型」含「分镜」。

---

### 任务 5：GroupedNodeMenu 分组节点菜单 + 替换右键/节点库裸项

**文件：**
- 创建：`XiaoYueCanvasAgent/src/components/ui/GroupedNodeMenu.tsx`
- 修改：`XiaoYueCanvasAgent/src/components/CanvasContextMenu.tsx`（`添加节点`子菜单改用 GroupedNodeMenu）

- [ ] **步骤 1：创建 GroupedNodeMenu**

```tsx
// components/ui/GroupedNodeMenu.tsx
import { useState } from 'react';
import { NODE_CATEGORIES, NODE_DEFS, listMenuNodes, type NodeCategory } from '../../domain/nodeRegistry';
import { useCanvasStore } from '../../store/canvasStore';

export function GroupedNodeMenu({ onAdd }: { onAdd: (kind: string) => void }) {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const grouped = NODE_CATEGORIES.map((cat) => ({ cat, items: listMenuNodes().filter((d) => d.category === cat) })).filter((g) => g.items.length);
  return (
    <div className="grouped-node-menu">
      {grouped.map(({ cat, items }) => (
        <section key={cat}>
          <button className="group-cat" onClick={() => setCollapsed((c) => ({ ...c, [cat]: !c[cat] }))}>{cat}</button>
          {!collapsed[cat] && items.map((def) => (
            <button key={def.type} className="group-item" onClick={() => onAdd(def.type)}>{def.menuLabel}</button>
          ))}
        </section>
      ))}
    </div>
  );
}
```

- [ ] **步骤 2：CanvasContextMenu 添加节点子菜单改用它**

```tsx
// CanvasContextMenu.tsx 的 addNodeSubmenu
const addNodeSubmenu = <GroupedNodeMenu onAdd={(kind) => { if (kind === 'image') run(props.onAddImage)(); else if (kind === 'text') run(props.onAddText)(); }} />;
```

- [ ] **步骤 3：验证**

运行：`npm run typecheck`
预期：通过。

---

### 任务 6：样式（左侧工具栏 + 节点菜单 + 分组菜单）

**文件：**
- 修改：`XiaoYueCanvasAgent/src/styles.css`（追加节点菜单/分组菜单/左侧工具栏/落点类样式）

- [ ] **步骤 1：追加样式**（在 `@media all` 层后追加，TapNow 中性风格）

```css
/* Connection node menu */
.connection-node-menu { position: fixed; z-index: 170; min-width: 200px; padding: 6px; border: 1px solid var(--tn-line-strong); border-radius: 12px; background: rgba(21,21,21,.98); box-shadow: 0 18px 44px rgba(0,0,0,.5); }
.connection-node-menu header { padding: 6px 10px 8px; color: var(--tn-text-muted); font-size: 11px; border-bottom: 1px solid var(--tn-line); }
.connection-node-menu button { width: 100%; padding: 8px 10px; border: 0; border-radius: 8px; background: transparent; color: var(--tn-text); text-align: left; font-size: 13px; cursor: pointer; }
.connection-node-menu button:hover { background: var(--tn-surface-hover); }
/* Grouped node menu */
.grouped-node-menu { min-width: 200px; padding: 5px; border: 1px solid var(--tn-line-strong); border-radius: 10px; background: rgba(21,21,21,.98); }
.group-cat { width: 100%; padding: 6px 9px; border: 0; border-radius: 7px; background: transparent; color: var(--tn-text-muted); text-align: left; font-size: 11px; cursor: pointer; }
.group-item { width: 100%; padding: 7px 12px; border: 0; border-radius: 7px; background: transparent; color: var(--tn-text); text-align: left; font-size: 13px; cursor: pointer; }
.group-item:hover { background: var(--tn-surface-hover); }
/* Script node */
.is-script .node-card { display: flex; flex-direction: column; }
.is-script .text-node-editor { flex: 1; min-height: 120px; }
/* Storyboard grid */
.storyboard-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px,1fr)); gap: 6px; padding: 8px; overflow: auto; }
.storyboard-frame { margin: 0; border-radius: 6px; overflow: hidden; background: var(--tn-surface); }
.storyboard-frame img { width: 100%; height: auto; display: block; }
.storyboard-frame figcaption { padding: 4px 6px; color: var(--tn-text-muted); font-size: 10px; }
```

- [ ] **步骤 2：验证**

运行：`npm run build`
预期：构建通过。

---

### 任务 7：全量验证 + 手工走查

- [ ] **步骤 1：全量验证**

运行（XiaoYueCanvasAgent）：`npm run verify`（typecheck + typecheck:node + test + build）
预期：全绿。

- [ ] **步骤 2：手工走查清单**

1. 打开旧画布（含 media/video 节点）不白屏，节点正常渲染
2. 新建「剧本」节点，输入剧本 → 点「生成分镜」→ 提交流程走通（未配执行服务时如实报错，不伪造）
3. 从 script 输出口拖到空白 → 弹「选择下游节点类型」→ 选「分镜」→ 画布创建 storyboard 节点并自动连线
4. 非法连接（如 script→note）被拒
5. 右键「添加节点」含分组菜单（基础/文本/媒体/编排）

---

## 自检

**规格覆盖：**
- nodeRegistry 5 类全迁：text/image/video/script 已在（NODE_DEFS），storyboard 由任务 1 补入 ✅
- 边校验：任务 2 步骤 3 ✅
- 连接即创建：任务 4 ✅
- 分组节点菜单：任务 5 ✅
- 布局左侧工具栏：任务 6 样式（当前 canvas-tool-island 在底部，→ 保留底部，本次不移动它以免破坏既有布局；若用户后续要求，另立任务）—— 见下方「明确不做」

**占位符扫描：** 无 TODO/待定，所有代码块真实。

**一致性：**
- `createConnectedNode`/`createNodeBefore` 签名在任务 2 定义，任务 4 调用一致 ✅
- `storyboard` 在任务 1 types + registry，任务 2 校验、任务 3 组件、任务 4 菜单全链路一致 ✅
- `Modeled NodeKind` 联合含 storyboard，MediaNode 未用的分支不受影响（MediaNode 处理 text/image/script/note/prompt/sequence，已在 data.kind 分支内；storyboard 走独立组件，不冲突）✅

## 明确不做（YAGNI / 待用户决定）

- **左侧工具栏移动**：spec 第 5 节提「左侧悬浮工具栏」，但现有 `.canvas-tool-island` 在底部且已可用。本次**不移动**以控制范围；若用户看了效果想要参考画布那种左侧竖排，另开任务。
- **浅色主题切换**：用户已定「先保持现状，看到效果再决定是否调色」，本次不动 `--tn-*` 配色。
- **结构化分镜帧 JSON**：分镜产物当前是 `asset.created → createDerivedNodes` 的图片节点，非结构化帧。StoryboardNode 首版 `data.frames` 为空时显示"连接剧本节点生成分镜"，需后续在 `materializeAsset` 里把产物也归档到 storyboard 节点帧（涉及 AgentPanel，超出本次范围，列入后续）。
