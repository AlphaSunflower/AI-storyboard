import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type EdgeChange,
  type NodeChange,
  type XYPosition,
} from '@xyflow/react';
import { create } from 'zustand';
import { NODE_PRESETS } from '../data/presets';
import type { CanvasDocumentV1, CanvasEdge, CanvasNode, GraphSnapshot, MediaKind, MediaOperation } from '../types';
import { createId } from '../utils/id';
import {
  getNodeDefinition,
  isCanvasNodeKind,
  validateEdge,
  type CanvasExecution,
  type CanvasGraphPatch,
  type CanvasGraphPatchEntry,
  type CanvasGraphV2,
  type GenerationRecord,
  type NodePort,
} from '../canvas/graphContracts';
import { reconcileStoryboardShots, type StoryboardShotPlan } from '../canvas/storyboardPlan';
import { nextGroupTitle } from '../canvas/groups';
import { relabelGeneratedResultNodes } from '../canvas/generationResultLabel';
import { withResultSlotData } from '../canvas/resultSlots';

// Canvas updates are immutable, so history can share untouched graph objects.
const graphSnapshot = (nodes: CanvasNode[], edges: CanvasEdge[]): GraphSnapshot => ({
  nodes,
  edges,
});
const clearSelection = (nodes: CanvasNode[]) => nodes.map((node) => node.selected ? { ...node, selected: false } : node);
const indexDerivedAssets = (nodes: CanvasNode[]) => {
  const index: Record<string, string[]> = {};
  nodes.forEach((node) => {
    if (!node.data.derivedFrom || node.data.operation?.type !== 'split' || !node.data.assetId) return;
    (index[node.data.derivedFrom] ??= []).push(node.data.assetId);
  });
  return index;
};

const makeNode = (
  kind: MediaKind,
  position: XYPosition,
  overrides: Partial<CanvasNode['data']> = {},
  size?: { width: number; height: number },
): CanvasNode => {
  const modern = getNodeDefinition(kind);
  if (modern) {
    return {
      id: `${kind}-${createId()}`,
      type: modern.kind,
      position,
      width: size?.width ?? modern.defaultSize.width,
      height: size?.height ?? modern.defaultSize.height,
      data: {
        ...modern.createDefaultData(),
        ...overrides,
        kind: modern.kind,
      },
    };
  }
  const preset = NODE_PRESETS.find((item) => item.kind === kind) ?? NODE_PRESETS[0];
  // 生成类节点参考尺寸（对齐参考 ImageEditNode 520×400），text/video 保持；其余 250×250
  const defaultSize = kind === 'text' ? { width: 380, height: 280 }
    : kind === 'video' ? { width: 420, height: 236 }
    : (kind === 'imageGen' || kind === 'videoGen' || kind === 'storyboardGen') ? { width: 520, height: 420 }
    : kind === 'storyboard' ? { width: 520, height: 380 }
    : { width: 250, height: 250 };
  return {
    id: `${kind}-${createId()}`,
    type: isCanvasNodeKind(kind) ? kind : 'media',
    position,
    width: size?.width ?? defaultSize.width,
    height: size?.height ?? defaultSize.height,
    data: {
      kind,
      label: preset.title,
      prompt: '',
      status: 'idle',
      accent: preset.accent,
      ...overrides,
    },
  };
};

interface DerivedOutput {
  data: Partial<CanvasNode['data']>;
  size?: { width: number; height: number };
  edgeData?: Partial<CanvasEdge['data']>;
}

interface CanvasClipboard {
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  copiedAt: number;
}

interface CanvasState {
  projectId: string | null;
  canvasId: string | null;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  generationRecords: GenerationRecord[];
  executions: CanvasExecution[];
  materializeResultNode: (request: {
    resultNodeId: string;
    producerNodeId: string;
    assetId: string;
    kind: 'image' | 'video';
    label: string;
    size: { width: number; height: number };
  }) => string;
  past: GraphSnapshot[];
  future: GraphSnapshot[];
  selectedNodeId: string | null;
  dragStartSnapshot: GraphSnapshot | null;
  clipboard: CanvasClipboard | null;
  clipboardPasteCount: number;
  revision: number;
  derivedAssetIdsBySource: Record<string, string[]>;
  onNodesChange: (changes: NodeChange<CanvasNode>[]) => void;
  onEdgesChange: (changes: EdgeChange<CanvasEdge>[]) => void;
  beginNodeDrag: () => void;
  endNodeDrag: () => void;
  connect: (connection: Connection) => void;
  loadProjectGraph: (canvas: CanvasDocumentV1) => void;
  addNode: (kind: MediaKind, position?: XYPosition, overrides?: Partial<CanvasNode['data']>) => string;
  createNodes: (items: Array<{ kind: MediaKind; position: XYPosition; overrides?: Partial<CanvasNode['data']>; size?: { width: number; height: number } }>) => string[];
  addImportedImages: (items: Array<{ assetId: string; label: string; position: XYPosition; size: { width: number; height: number }; operation?: CanvasNode['data']['operation'] }>) => string[];
  addImportedVideos: (items: Array<{ assetId: string; label: string; position: XYPosition; size: { width: number; height: number }; operation?: CanvasNode['data']['operation'] }>) => string[];
  attachAssetToNode: (nodeId: string, item: { assetId: string; label: string; size: { width: number; height: number }; operation?: MediaOperation }) => void;
  createDerivedNodes: (sourceId: string, outputs: DerivedOutput[], layout?: { columns?: number }) => string[];
  extendNode: (sourceId: string, kind?: MediaKind, direction?: 'left' | 'right') => void;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
  updateEdgeData: (edgeId: string, patch: Partial<CanvasEdge['data']>) => void;
  reorderOrderedInputs: (nodeId: string, handleId: string, orderedEdgeIds: string[]) => void;
  setNodePinned: (nodeId: string, pinned: boolean) => void;
  /** 把一组节点合并成一个画布分组（frame）；少于 2 个节点返回 null。 */
  groupNodes: (nodeIds: string[]) => { id: string; title: string } | null;
  /** 解散分组：只清掉成员节点上的分组字段，不删除节点。 */
  ungroupNodes: (groupId: string) => void;
  /** 重命名分组：写回全部成员节点，保持同一分组标题一致。 */
  renameGroup: (groupId: string, title: string) => void;
  /** 折叠/展开分组：折叠时成员节点从画布隐藏，但仍在图里可执行；不写撤销栈。 */
  toggleGroupCollapsed: (groupId: string, collapsed?: boolean) => void;
  /** 把分组挂到另一个分组下；parentId=null 表示提升为顶层。返回 false 表示非法（成环/目标不存在）。 */
  nestGroup: (groupId: string, parentId: string | null) => boolean;
  /** 按 id 精确设置选中集合（分组标题点击用），不写历史、不碰 revision。 */
  selectNodes: (nodeIds: string[]) => void;
  /** 分组拖动中使用：只改坐标、不写历史；结束时由 beginNodeDrag/endNodeDrag 收口。 */
  setNodePositions: (positions: Record<string, XYPosition>) => void;
  updateNodeTransient: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
  updateNodeGeometry: (nodeId: string, size: { width: number; height: number }) => void;
  copySelected: () => number;
  copyNodes: (nodeIds: string[]) => number;
  pasteClipboard: (position?: XYPosition) => string[];
  duplicateSelected: () => string[];
  duplicateNodes: (nodeIds: string[], offset?: XYPosition) => string[];
  deleteSelected: () => void;
  deleteNodes: (nodeIds: string[]) => number;
  moveNodes: (positions: Array<{ nodeId: string; position: XYPosition }>) => number;
  resizeNodes: (sizes: Array<{ nodeId: string; size: { width: number; height: number } }>) => number;
  createEdge: (connection: Connection) => string | null;
  // 创建节点并自动连线（新节点连到 sourceId 的右边）
  createConnectedNode: (sourceId: string, kind: MediaKind, position: XYPosition, sourceHandle?: string, overrides?: Partial<CanvasNode['data']>) => string | null;
  // 创建节点并自动连线（新节点排在 targetId 的左边，作为其前置）
  createNodeBefore: (targetId: string, kind: MediaKind, position: XYPosition, targetHandle?: string) => string | null;
  deleteEdge: (edgeId: string) => void;
  selectNode: (nodeId: string | null, preserveSelection?: boolean) => void;
  selectAll: () => number;
  undo: () => void;
  redo: () => void;
  clearCanvas: () => void;
  appendGenerationRecord: (record: GenerationRecord) => void;
  updateGenerationRecord: (record: GenerationRecord) => void;
  appendExecution: (execution: CanvasExecution) => void;
  updateExecution: (execution: CanvasExecution) => void;
  applyStoryboardPlan: (producerNodeId: string, plans: StoryboardShotPlan[]) => {
    created: string[];
    updated: string[];
    stale: string[];
  };
  /**
   * 应用服务端增量补丁（远端改动）。
   *
   * <p>按 id upsert + removed*Ids 删除，**幂等**（同一份补丁重复应用结果一致）；
   * 不写 past/future（远端改动不进用户撤销栈），也不动 store `revision`
   * （避免触发 550ms 自动保存把刚收到的服务端状态再回写一遍）。
   * 结构不合法时抛错，由调用方退回整图重载。
   */
  applyRemotePatch: (patch: CanvasGraphPatch) => RemotePatchSummary;
}

/** applyRemotePatch 的应用摘要，供调用方决定要不要补资产元数据。 */
export interface RemotePatchSummary {
  addedNodeIds: string[];
  updatedNodeIds: string[];
  removedNodeIds: string[];
  addedEdgeIds: string[];
  updatedEdgeIds: string[];
  removedEdgeIds: string[];
}

const MAX_HISTORY = 40;

const asGraphV2 = (state: Pick<CanvasState, 'nodes' | 'edges' | 'generationRecords' | 'executions'>): CanvasGraphV2 => ({
  schemaVersion: 2,
  nodes: state.nodes as CanvasGraphV2['nodes'],
  edges: state.edges as CanvasGraphV2['edges'],
  viewport: { x: 0, y: 0, zoom: 1 },
  generationRecords: state.generationRecords,
  executions: state.executions,
});

const compatibleTargetPort = (sourceKind: MediaKind, targetKind: MediaKind): NodePort | undefined => {
  const sourceDef = getNodeDefinition(sourceKind);
  const targetDef = getNodeDefinition(targetKind);
  const output = sourceDef?.outputs[0];
  if (!output) return undefined;
  return targetDef?.inputs.find((port) => port.media === output.media && port.acceptsKinds.includes(sourceKind as never));
};

const canConnect = (sourceKind: MediaKind, targetKind: MediaKind): boolean => {
  const sourceDef = getNodeDefinition(sourceKind);
  const targetPort = compatibleTargetPort(sourceKind, targetKind);
  return Boolean(sourceDef?.outputs[0] && targetPort);
};

export const canConnectFromPort = (sourceKind: MediaKind, sourceHandle: string, targetKind: MediaKind): boolean => {
  const sourcePort = getNodeDefinition(sourceKind)?.outputs.find((port) => port.id === sourceHandle);
  if (!sourcePort) return false;
  return Boolean(getNodeDefinition(targetKind)?.inputs.some((port) =>
    port.media === sourcePort.media && port.acceptsKinds.includes(sourceKind as never)));
};

export const canConnectToPort = (targetKind: MediaKind, targetHandle: string, sourceKind: MediaKind): boolean => {
  const targetPort = getNodeDefinition(targetKind)?.inputs.find((port) => port.id === targetHandle);
  if (!targetPort) return false;
  return Boolean(getNodeDefinition(sourceKind)?.outputs.some((port) =>
    port.media === targetPort.media
    && targetPort.acceptsKinds.includes(sourceKind as never)));
};

/**
 * 单侧只保留一个可见连接点后，拖拽/菜单给到的端口未必是对侧真正能接的那个
 * （例如「视频生成」左侧可见点挂在 prompt 上，但拖过来的是图片节点）。
 * 给定端口兼容就用它，否则按媒体在该侧找第一个可用端口；都找不到才返回 undefined。
 */
const resolveOutputPort = (sourceKind: MediaKind, targetKind: MediaKind, preferred?: string): NodePort | undefined => {
  const outputs = getNodeDefinition(sourceKind)?.outputs ?? [];
  const named = preferred ? outputs.find((port) => port.id === preferred) : undefined;
  if (named && canConnectFromPort(sourceKind, named.id, targetKind)) return named;
  return outputs.find((port) => canConnectFromPort(sourceKind, port.id, targetKind));
};

const resolveInputPort = (sourceKind: MediaKind, targetKind: MediaKind, preferred?: string): NodePort | undefined => {
  const inputs = getNodeDefinition(targetKind)?.inputs ?? [];
  const named = preferred ? inputs.find((port) => port.id === preferred) : undefined;
  if (named && canConnectToPort(targetKind, named.id, sourceKind)) return named;
  return inputs.find((port) => canConnectToPort(targetKind, port.id, sourceKind));
};

const normalizeHandle = (
  handle: string | null | undefined,
  side: 'source' | 'target',
  sourceKind: MediaKind,
  targetKind: MediaKind,
) => {
  const preferred = handle && handle !== 'right' && handle !== 'left' ? handle : undefined;
  if (side === 'source') return resolveOutputPort(sourceKind, targetKind, preferred)?.id;
  return resolveInputPort(sourceKind, targetKind, preferred)?.id;
};

const nextPortOrder = (edges: CanvasEdge[], targetId: string, targetHandle: string): number => {
  const used = new Set(edges
    .filter((edge) => edge.target === targetId && edge.targetHandle === targetHandle)
    .map((edge) => (edge.data as { portOrder?: number } | undefined)?.portOrder)
    .filter((order): order is number => Number.isInteger(order)));
  let order = 0;
  while (used.has(order)) order += 1;
  return order;
};

const compactOrderedEdges = (
  edges: CanvasEdge[],
  targetId: string,
  targetHandle: string,
  preferredOrder: string[] = [],
): CanvasEdge[] => {
  const scoped = edges.filter((edge) => edge.target === targetId && edge.targetHandle === targetHandle);
  if (!scoped.length) return edges;
  const preferred = new Set(preferredOrder);
  const orderedIds = [
    ...preferredOrder.filter((edgeId) => scoped.some((edge) => edge.id === edgeId)),
    ...scoped
      .filter((edge) => !preferred.has(edge.id))
      .sort((left, right) =>
        (left.data?.portOrder ?? Number.MAX_SAFE_INTEGER) - (right.data?.portOrder ?? Number.MAX_SAFE_INTEGER))
      .map((edge) => edge.id),
  ];
  const orderById = new Map(orderedIds.map((edgeId, index) => [edgeId, index]));
  return edges.map((edge) => {
    if (edge.target !== targetId || edge.targetHandle !== targetHandle) return edge;
    const portOrder = orderById.get(edge.id) ?? 0;
    if (edge.data?.portOrder === portOrder) return edge;
    return { ...edge, data: { ...(edge.data ?? {}), portOrder } };
  });
};

/** 导出给连线菜单（ConnectionNodeMenu）用：判断源能否连到目标，口径与 store 一致。 */
export const canConnectNodes = canConnect;

const selectedNodes = (state: Pick<CanvasState, 'nodes' | 'selectedNodeId'>) => {
  const selected = state.nodes.filter((node) => node.selected);
  if (selected.length) return selected;
  const fallback = state.nodes.find((node) => node.id === state.selectedNodeId);
  return fallback ? [fallback] : [];
};

/** 补丁节点结构校验：guard 只保证 id，这里补 position{x,y} 与 data（缺了就没法渲染）。 */
const requireRemotePatchNode = (entry: CanvasGraphPatchEntry): CanvasNode => {
  const position = entry.position as { x?: unknown; y?: unknown } | undefined;
  const x = position?.x;
  const y = position?.y;
  if (typeof x !== 'number' || !Number.isFinite(x)
      || typeof y !== 'number' || !Number.isFinite(y)) {
    throw new Error(`补丁节点 ${entry.id} 缺少合法 position`);
  }
  const data = entry.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error(`补丁节点 ${entry.id} 缺少合法 data`);
  }
  return entry as unknown as CanvasNode;
};

/** 补丁连线结构校验：source/target 必须是节点 id（端点不存在的边会被拒绝并退回整图重载）。 */
const requireRemotePatchEdge = (entry: CanvasGraphPatchEntry): CanvasEdge => {
  const source = entry.source;
  const target = entry.target;
  if (typeof source !== 'string' || !source
      || typeof target !== 'string' || !target) {
    throw new Error(`补丁连线 ${entry.id} 缺少 source/target`);
  }
  return entry as unknown as CanvasEdge;
};

/** 删除节点后把指向已消失分组的 parentId 清掉，避免出现悬空的嵌套分组。 */
const sanitizeGroupParents = (nodes: CanvasNode[]): CanvasNode[] => {
  const groupIds = new Set(
    nodes.map((node) => (typeof node.data.groupId === 'string' ? node.data.groupId : '')).filter(Boolean),
  );
  let changed = false;
  const next = nodes.map((node) => {
    const parent = typeof node.data.groupParentId === 'string' ? node.data.groupParentId : '';
    if (!parent || groupIds.has(parent)) return node;
    changed = true;
    const data = { ...node.data };
    delete data.groupParentId;
    return { ...node, data };
  });
  return changed ? next : nodes;
};

const cloneSelection = (nodes: CanvasNode[], edges: CanvasEdge[], offset: XYPosition) => {
  const idMap = new Map<string, string>();
  const clonedNodes = nodes.map((node) => {
    const id = `${node.data.kind}-${createId()}`;
    idMap.set(node.id, id);
    const clone = structuredClone(node);
    // 副本不继承分组：粘贴到别处却把原分组框撑大，是比“没分组”更糟的体验。
    const data = { ...clone.data, label: `${clone.data.label} 副本` };
    delete data.groupId;
    delete data.groupTitle;
    delete data.groupCreatedAt;
    delete data.groupCollapsed;
    delete data.groupParentId;
    return {
      ...clone,
      id,
      position: { x: node.position.x + offset.x, y: node.position.y + offset.y },
      selected: true,
      dragging: false,
      measured: undefined,
      data,
    } satisfies CanvasNode;
  });
  const clonedEdges = edges.flatMap((edge) => {
    const source = idMap.get(edge.source);
    const target = idMap.get(edge.target);
    if (!source || !target) return [];
    return [{ ...structuredClone(edge), id: `edge-${createId()}`, source, target, selected: false } satisfies CanvasEdge];
  });
  return { nodes: clonedNodes, edges: clonedEdges };
};

export const useCanvasStore = create<CanvasState>((set, get) => {
  const commit = (recipe: (state: CanvasState) => Pick<CanvasState, 'nodes' | 'edges'> & Partial<Pick<CanvasState, 'generationRecords'>>) => {
    const current = get();
    const next = recipe(current);
    set({
      ...next,
      derivedAssetIdsBySource: indexDerivedAssets(next.nodes),
      past: [...current.past, graphSnapshot(current.nodes, current.edges)].slice(-MAX_HISTORY),
      future: [],
      revision: current.revision + 1,
    });
  };

  return {
    projectId: null, canvasId: null, nodes: [], edges: [], generationRecords: [], executions: [], past: [], future: [], selectedNodeId: null, dragStartSnapshot: null,
    clipboard: null, clipboardPasteCount: 0, revision: 0, derivedAssetIdsBySource: {},

    loadProjectGraph: (canvas) => {
      const generationRecords = structuredClone((canvas as CanvasDocumentV1 & { generationRecords?: GenerationRecord[] }).generationRecords ?? []);
      const nodes = relabelGeneratedResultNodes(
        structuredClone(canvas.nodes).map((node) => ({ ...node, selected: false })),
        generationRecords,
        canvas.edges,
      );
      set({
      projectId: canvas.projectId,
      canvasId: canvas.id,
      nodes,
      edges: structuredClone(canvas.edges),
      generationRecords,
      executions: structuredClone(canvas.executions ?? []),
      past: [], future: [], selectedNodeId: null, dragStartSnapshot: null,
      clipboard: null, clipboardPasteCount: 0, revision: 0,
      derivedAssetIdsBySource: indexDerivedAssets(nodes),
    });
    },

    onNodesChange: (changes) => {
      const permanent = changes.some((change) => change.type === 'remove');
      if (permanent) commit((state) => ({ nodes: sanitizeGroupParents(applyNodeChanges(changes, state.nodes)), edges: state.edges }));
      else set((state) => ({ nodes: applyNodeChanges(changes, state.nodes) }));
    },
    onEdgesChange: (changes) => {
      const permanent = changes.some((change) => change.type === 'remove');
      if (permanent) commit((state) => ({ nodes: state.nodes, edges: applyEdgeChanges(changes, state.edges) }));
      else set((state) => ({ edges: applyEdgeChanges(changes, state.edges) }));
    },
    beginNodeDrag: () => {
      const state = get();
      if (!state.dragStartSnapshot) set({ dragStartSnapshot: graphSnapshot(state.nodes, state.edges) });
    },
    endNodeDrag: () => {
      const state = get(); const snapshot = state.dragStartSnapshot;
      if (!snapshot) return;
      const changed = snapshot.nodes.some((node, index) => node.position.x !== state.nodes[index]?.position.x || node.position.y !== state.nodes[index]?.position.y);
      set(changed ? {
        dragStartSnapshot: null,
        past: [...state.past, snapshot].slice(-MAX_HISTORY), future: [], revision: state.revision + 1,
      } : { dragStartSnapshot: null });
    },
    connect: (connection) => {
      const state = get();
      if (!connection.source || !connection.target) return;
      const sourceNode = state.nodes.find((node) => node.id === connection.source);
      const targetNode = state.nodes.find((node) => node.id === connection.target);
      if (!sourceNode || !targetNode) return;
      const sourceHandle = normalizeHandle(connection.sourceHandle, 'source', sourceNode.data.kind, targetNode.data.kind);
      const targetHandle = normalizeHandle(connection.targetHandle, 'target', sourceNode.data.kind, targetNode.data.kind);
      if (!sourceHandle || !targetHandle) return;
      const targetPort = getNodeDefinition(targetNode.data.kind)?.inputs.find((port) => port.id === targetHandle);
      const baseData = targetPort?.ordered
        ? { ...(connection as Connection & { data?: Record<string, unknown> }).data, portOrder: nextPortOrder(state.edges, targetNode.id, targetHandle) }
        : (connection as Connection & { data?: Record<string, unknown> }).data;
      const data = withResultSlotData(sourceNode, targetNode, state.edges, baseData);
      const proposed = {
        id: `edge-${createId()}`,
        source: connection.source,
        sourceHandle,
        target: connection.target,
        targetHandle,
        type: 'canvas',
        data,
      };
      if (!validateEdge(asGraphV2(state), proposed, 'user').ok) return;
      const edge = proposed as CanvasEdge;
      commit((state) => ({ nodes: state.nodes, edges: addEdge(edge, state.edges), generationRecords: state.generationRecords }));
    },
    addNode: (kind, position = { x: 300, y: 200 }, overrides = {}) => {
      const node = { ...makeNode(kind, position, overrides), selected: true };
      commit((state) => ({ nodes: [...clearSelection(state.nodes), node], edges: state.edges, generationRecords: state.generationRecords }));
      set({ selectedNodeId: node.id });
      return node.id;
    },
    createNodes: (items) => {
      if (!items.length) return [];
      const created = items.map((item, index) => ({ ...makeNode(item.kind, item.position, item.overrides, item.size), selected: index === items.length - 1 }));
      commit((state) => ({ nodes: [...clearSelection(state.nodes), ...created], edges: state.edges, generationRecords: state.generationRecords }));
      set({ selectedNodeId: created.at(-1)?.id ?? null });
      return created.map((node) => node.id);
    },
    addImportedImages: (items) => {
      const created = items.map((item, index) => ({ ...makeNode('image', item.position, {
        assetId: item.assetId, label: item.label, mediaLoadState: 'ready', status: 'success',
        operation: item.operation ?? { type: 'import', params: {} },
      }, item.size), selected: index === items.length - 1 }));
      commit((state) => ({ nodes: [...clearSelection(state.nodes), ...created], edges: state.edges, generationRecords: state.generationRecords }));
      set({ selectedNodeId: created.at(-1)?.id ?? null });
      return created.map((node) => node.id);
    },
    addImportedVideos: (items) => {
      const created = items.map((item, index) => ({ ...makeNode('video', item.position, {
        assetId: item.assetId, label: item.label, mediaLoadState: 'ready', status: 'success',
        operation: item.operation, videoPrompt: '', videoReferences: [], videoParams: {
          model: 'Seedance 2.0 Mini', mode: '全能参考', aspectRatio: '16:9', resolution: '1080p', durationSeconds: 12, variations: 1,
        },
      }, item.size), selected: index === items.length - 1 }));
      commit((state) => ({ nodes: [...clearSelection(state.nodes), ...created], edges: state.edges, generationRecords: state.generationRecords }));
      set({ selectedNodeId: created.at(-1)?.id ?? null });
      return created.map((node) => node.id);
    },
    attachAssetToNode: (nodeId, item) => commit((state) => ({
      nodes: state.nodes.map((node) => node.id === nodeId ? {
        ...node,
        width: item.size.width,
        height: item.size.height,
        data: {
          ...node.data,
          assetId: item.assetId,
          label: item.label,
          mediaLoadState: 'ready',
          status: 'success',
          operation: item.operation ?? (node.data.kind === 'image' ? { type: 'import', params: {} } : undefined),
          automation: {
            ...(typeof node.data.automation === 'object' && node.data.automation ? node.data.automation : {}),
            phase: 'done',
          },
        },
      } : node),
      edges: state.edges,
    })),
    createDerivedNodes: (sourceId, outputs, layout) => {
      const source = get().nodes.find((node) => node.id === sourceId);
      if (!source || !outputs.length) return [];
      const sourceWidth = source.width ?? source.measured?.width ?? 250;
      const sourceHeight = source.height ?? source.measured?.height ?? 250;
      const columns = Math.max(1, Math.min(outputs.length, Math.round(layout?.columns ?? (outputs.length > 4 ? 3 : Math.min(outputs.length, 2)))));
      const columnWidths = Array.from({ length: columns }, (_, column) => Math.max(...outputs.filter((_, index) => index % columns === column).map((output) => output.size?.width ?? Math.min(300, sourceWidth)), 180));
      const created = outputs.map((output, index) => {
        const column = index % columns;
        const row = Math.floor(index / columns);
        const xOffset = columnWidths.slice(0, column).reduce((sum, width) => sum + width + 54, 0);
        const previousRowHeights = Array.from({ length: row }, (_, previousRow) => Math.max(...outputs.slice(previousRow * columns, previousRow * columns + columns).map((item) => item.size?.height ?? Math.min(340, sourceHeight)), 180));
        const yOffset = previousRowHeights.reduce((sum, height) => sum + height + 54, 0);
        const kind = output.data.kind ?? 'image';
        return { ...makeNode(kind, { x: source.position.x + sourceWidth + 150 + xOffset, y: source.position.y + yOffset }, {
          prompt: source.data.prompt, accent: source.data.accent, derivedFrom: sourceId, status: 'success', ...output.data,
        }, output.size ?? { width: Math.min(300, sourceWidth), height: Math.min(340, sourceHeight) }), selected: index === outputs.length - 1 };
      });
      // 连线必须用注册表里的真实端口 id（'left'/'right' 匹配不到 Handle，边不会被渲染）
      const portOf = (node: CanvasNode, side: 'inputs' | 'outputs') => getNodeDefinition(node.data.kind)?.[side][0]?.id ?? (side === 'outputs' ? 'right' : 'left');
      const connections: CanvasEdge[] = created.map((node, index) => ({
        id: `edge-${createId()}`,
        source: sourceId,
        sourceHandle: portOf(source, 'outputs'),
        target: node.id,
        targetHandle: portOf(node, 'inputs'),
        type: 'canvas',
        data: {
          operation: outputs[index]?.data?.operation?.type,
          ...(outputs[index]?.edgeData ?? {}),
        },
      }));
      commit((state) => ({ nodes: [...clearSelection(state.nodes), ...created], edges: [...state.edges, ...connections] }));
      set({ selectedNodeId: created.at(-1)?.id ?? sourceId });
      return created.map((node) => node.id);
    },
    extendNode: (sourceId, kind = 'image', direction = 'right') => {
      const source = get().nodes.find((node) => node.id === sourceId); if (!source) return;
      const node = { ...makeNode(kind, { x: source.position.x + (direction === 'right' ? 350 : -350), y: source.position.y + 24 }), selected: true };
      const edgeSource = direction === 'right' ? sourceId : node.id; const edgeTarget = direction === 'right' ? node.id : sourceId;
      commit((state) => ({ nodes: [...clearSelection(state.nodes), node], edges: [...state.edges, { id: `edge-${createId()}`, source: edgeSource, sourceHandle: 'right', target: edgeTarget, targetHandle: 'left', type: 'canvas' }] }));
      set({ selectedNodeId: node.id });
    },
    updateNode: (nodeId, patch) => commit((state) => ({ nodes: state.nodes.map((node) => node.id === nodeId ? { ...node, data: { ...node.data, ...patch } } : node), edges: state.edges })),
    updateEdgeData: (edgeId, patch) => commit((state) => ({
      nodes: state.nodes,
      edges: state.edges.map((edge) => edge.id === edgeId ? { ...edge, data: { ...(edge.data ?? {}), ...patch } } : edge),
    })),
    reorderOrderedInputs: (nodeId, handleId, orderedEdgeIds) => commit((state) => ({
      nodes: state.nodes,
      edges: compactOrderedEdges(state.edges, nodeId, handleId, orderedEdgeIds),
    })),
    setNodePinned: (nodeId, pinned) => commit((state) => ({ nodes: state.nodes.map((node) => node.id === nodeId ? { ...node, draggable: !pinned, data: { ...node.data, pinned } } : node), edges: state.edges })),
    groupNodes: (nodeIds) => {
      const state = get();
      const ids = new Set(nodeIds);
      const members = state.nodes.filter((node) => ids.has(node.id));
      if (members.length < 2) return null;
      const id = `group-${createId()}`;
      const title = nextGroupTitle(state.nodes);
      const createdAt = Date.now();
      // 选中的节点原本都在同一个父分组里 → 新分组继续挂在该父分组下；否则成为顶层分组。
      const parentIds = new Set(members
        .map((node) => typeof node.data.groupParentId === 'string' ? node.data.groupParentId : '')
        .filter(Boolean));
      const inheritedParent = parentIds.size === 1 ? [...parentIds][0] : '';
      commit((current) => ({
        nodes: current.nodes.map((node) => ids.has(node.id)
          ? {
            ...node,
            data: {
              ...node.data,
              groupId: id,
              groupTitle: title,
              groupCreatedAt: createdAt,
              groupCollapsed: false,
              groupParentId: inheritedParent || undefined,
            },
          }
          : node),
        edges: current.edges,
      }));
      return { id, title };
    },
    ungroupNodes: (groupId) => {
      if (!groupId) return;
      commit((state) => ({
        nodes: state.nodes.map((node) => {
          if (node.data.groupId === groupId) {
            const data = { ...node.data };
            delete data.groupId;
            delete data.groupTitle;
            delete data.groupCreatedAt;
            delete data.groupCollapsed;
            delete data.groupParentId;
            return { ...node, data };
          }
          // 子分组：父分组被解散后提升为顶层，不能留下指向不存在分组的 parentId。
          if (node.data.groupParentId === groupId) {
            const data = { ...node.data };
            delete data.groupParentId;
            return { ...node, data };
          }
          return node;
        }),
        edges: state.edges,
      }));
    },
    renameGroup: (groupId, title) => {
      const next = title.trim();
      if (!groupId || !next) return;
      commit((state) => ({
        nodes: state.nodes.map((node) => node.data.groupId === groupId
          ? { ...node, data: { ...node.data, groupTitle: next } }
          : node),
        edges: state.edges,
      }));
    },
    toggleGroupCollapsed: (groupId, collapsed) => {
      if (!groupId) return;
      const state = get();
      const members = state.nodes.filter((node) => node.data.groupId === groupId);
      if (!members.length) return;
      const next = collapsed ?? !members.some((node) => node.data.groupCollapsed === true);
      set((current) => ({
        nodes: current.nodes.map((node) => node.data.groupId === groupId
          ? { ...node, selected: next ? false : node.selected, data: { ...node.data, groupCollapsed: next } }
          : node),
        selectedNodeId: next && current.selectedNodeId
          && current.nodes.find((node) => node.id === current.selectedNodeId)?.data.groupId === groupId
          ? null
          : current.selectedNodeId,
        revision: current.revision + 1,
      }));
    },
    nestGroup: (groupId, parentId) => {
      const state = get();
      const members = state.nodes.filter((node) => node.data.groupId === groupId);
      if (!members.length) return false;
      if (parentId !== null) {
        if (!parentId || parentId === groupId) return false;
        if (!state.nodes.some((node) => node.data.groupId === parentId)) return false;
        // 沿 parentId 往上走：目标不能是自己的后代，否则会形成环。
        const parentOf = new Map<string, string>();
        for (const node of state.nodes) {
          const gid = typeof node.data.groupId === 'string' ? node.data.groupId : '';
          const pid = typeof node.data.groupParentId === 'string' ? node.data.groupParentId : '';
          if (gid && pid && !parentOf.has(gid)) parentOf.set(gid, pid);
        }
        let cursor: string | undefined = parentId;
        const seen = new Set<string>();
        while (cursor) {
          if (cursor === groupId) return false;
          if (seen.has(cursor)) break;
          seen.add(cursor);
          cursor = parentOf.get(cursor);
        }
      }
      commit((current) => ({
        nodes: current.nodes.map((node) => {
          if (node.data.groupId !== groupId) return node;
          const data = { ...node.data };
          if (parentId) data.groupParentId = parentId;
          else delete data.groupParentId;
          return { ...node, data };
        }),
        edges: current.edges,
      }));
      return true;
    },
    selectNodes: (nodeIds) => set((state) => {
      const ids = new Set(nodeIds);
      return {
        selectedNodeId: nodeIds.length ? nodeIds[nodeIds.length - 1] : null,
        nodes: state.nodes.map((node) => Boolean(node.selected) === ids.has(node.id) ? node : { ...node, selected: ids.has(node.id) }),
        edges: state.edges.map((edge) => edge.selected ? { ...edge, selected: false } : edge),
      };
    }),
    setNodePositions: (positions) => set((state) => ({
      nodes: state.nodes.map((node) => positions[node.id] ? { ...node, position: { ...positions[node.id] } } : node),
    })),
    updateNodeTransient: (nodeId, patch) => set((state) => ({ nodes: state.nodes.map((node) => node.id === nodeId ? { ...node, data: { ...node.data, ...patch } } : node) })),
    updateNodeGeometry: (nodeId, size) => set((state) => ({ nodes: state.nodes.map((node) => node.id === nodeId ? { ...node, width: size.width, height: size.height } : node) })),
    copySelected: () => {
      const state = get();
      const nodes = selectedNodes(state);
      if (!nodes.length) return 0;
      const ids = new Set(nodes.map((node) => node.id));
      const edges = state.edges.filter((edge) => ids.has(edge.source) && ids.has(edge.target));
      set({
        clipboard: { nodes: structuredClone(nodes), edges: structuredClone(edges), copiedAt: Date.now() },
        clipboardPasteCount: 0,
      });
      return nodes.length;
    },
    copyNodes: (nodeIds) => {
      const state = get();
      const ids = new Set(nodeIds);
      const nodes = state.nodes.filter((node) => ids.has(node.id));
      if (!nodes.length) return 0;
      const edges = state.edges.filter((edge) => ids.has(edge.source) && ids.has(edge.target));
      set({
        clipboard: { nodes: structuredClone(nodes), edges: structuredClone(edges), copiedAt: Date.now() },
        clipboardPasteCount: 0,
      });
      return nodes.length;
    },
    pasteClipboard: (position) => {
      const state = get();
      const clipboard = state.clipboard;
      if (!clipboard?.nodes.length) return [];
      const minX = Math.min(...clipboard.nodes.map((node) => node.position.x));
      const minY = Math.min(...clipboard.nodes.map((node) => node.position.y));
      const step = state.clipboardPasteCount + 1;
      const offset = position
        ? { x: position.x - minX, y: position.y - minY }
        : { x: 42 * step, y: 42 * step };
      const cloned = cloneSelection(clipboard.nodes, clipboard.edges, offset);
      commit((current) => ({
        nodes: [...clearSelection(current.nodes), ...cloned.nodes],
        edges: [...current.edges.map((edge) => edge.selected ? { ...edge, selected: false } : edge), ...cloned.edges],
      }));
      set({ selectedNodeId: cloned.nodes.at(-1)?.id ?? null, clipboardPasteCount: step });
      return cloned.nodes.map((node) => node.id);
    },
    duplicateSelected: () => {
      const state = get();
      const nodes = selectedNodes(state);
      if (!nodes.length) return [];
      const ids = new Set(nodes.map((node) => node.id));
      const cloned = cloneSelection(nodes, state.edges.filter((edge) => ids.has(edge.source) && ids.has(edge.target)), { x: 42, y: 42 });
      commit((current) => ({
        nodes: [...clearSelection(current.nodes), ...cloned.nodes],
        edges: [...current.edges.map((edge) => edge.selected ? { ...edge, selected: false } : edge), ...cloned.edges],
      }));
      set({ selectedNodeId: cloned.nodes.at(-1)?.id ?? null });
      return cloned.nodes.map((node) => node.id);
    },
    duplicateNodes: (nodeIds, offset = { x: 42, y: 42 }) => {
      const state = get();
      const ids = new Set(nodeIds);
      const nodes = state.nodes.filter((node) => ids.has(node.id));
      if (!nodes.length) return [];
      const cloned = cloneSelection(nodes, state.edges.filter((edge) => ids.has(edge.source) && ids.has(edge.target)), offset);
      commit((current) => ({
        nodes: [...clearSelection(current.nodes), ...cloned.nodes],
        edges: [...current.edges.map((edge) => edge.selected ? { ...edge, selected: false } : edge), ...cloned.edges],
      }));
      set({ selectedNodeId: cloned.nodes.at(-1)?.id ?? null });
      return cloned.nodes.map((node) => node.id);
    },
    deleteSelected: () => {
      const state = get();
      const ids = new Set(state.nodes.filter((node) => node.selected || node.id === state.selectedNodeId).map((node) => node.id));
      const selectedEdgeIds = new Set(state.edges.filter((edge) => edge.selected).map((edge) => edge.id));
      if (!ids.size && !selectedEdgeIds.size) return;
      commit((current) => ({
        nodes: sanitizeGroupParents(current.nodes.filter((node) => !ids.has(node.id))),
        edges: current.edges.filter((edge) => !selectedEdgeIds.has(edge.id) && !ids.has(edge.source) && !ids.has(edge.target)),
      }));
      set({ selectedNodeId: null });
    },
    deleteNodes: (nodeIds) => {
      const ids = new Set(nodeIds);
      const state = get();
      const count = state.nodes.filter((node) => ids.has(node.id)).length;
      if (!count) return 0;
      commit((current) => ({
        nodes: sanitizeGroupParents(current.nodes.filter((node) => !ids.has(node.id))),
        edges: current.edges.filter((edge) => !ids.has(edge.source) && !ids.has(edge.target)),
      }));
      set((current) => ({ selectedNodeId: current.selectedNodeId && ids.has(current.selectedNodeId) ? null : current.selectedNodeId }));
      return count;
    },
    moveNodes: (positions) => {
      if (!positions.length) return 0;
      const changes = new Map(positions.map((item) => [item.nodeId, item.position]));
      const state = get();
      const count = state.nodes.filter((node) => changes.has(node.id)).length;
      if (!count) return 0;
      commit((current) => ({ nodes: current.nodes.map((node) => changes.has(node.id) ? { ...node, position: { ...changes.get(node.id)! } } : node), edges: current.edges }));
      return count;
    },
    resizeNodes: (sizes) => {
      if (!sizes.length) return 0;
      const changes = new Map(sizes.map((item) => [item.nodeId, item.size]));
      const state = get();
      const count = state.nodes.filter((node) => changes.has(node.id)).length;
      if (!count) return 0;
      commit((current) => ({ nodes: current.nodes.map((node) => changes.has(node.id) ? { ...node, width: Math.max(40, changes.get(node.id)!.width), height: Math.max(40, changes.get(node.id)!.height) } : node), edges: current.edges }));
      return count;
    },
    createEdge: (connection) => {
      const state = get();
      if (!connection.source || !connection.target || !connection.sourceHandle || !connection.targetHandle) return null;
      const sourceNode = state.nodes.find((node) => node.id === connection.source);
      const targetNode = state.nodes.find((node) => node.id === connection.target);
      if (!sourceNode || !targetNode) return null;
      const sourceHandle = normalizeHandle(connection.sourceHandle, 'source', sourceNode.data.kind, targetNode.data.kind);
      const targetHandle = normalizeHandle(connection.targetHandle, 'target', sourceNode.data.kind, targetNode.data.kind);
      if (!sourceHandle || !targetHandle) return null;
      const targetPort = getNodeDefinition(targetNode.data.kind)?.inputs.find((port) => port.id === targetHandle);
      const baseData = targetPort?.ordered
        ? { ...(connection as Connection & { data?: Record<string, unknown> }).data, portOrder: nextPortOrder(state.edges, targetNode.id, targetHandle) }
        : (connection as Connection & { data?: Record<string, unknown> }).data;
      const proposed = {
        ...connection,
        id: `edge-${createId()}`,
        sourceHandle,
        targetHandle,
        type: 'canvas',
        data: withResultSlotData(sourceNode, targetNode, state.edges, baseData),
      };
      if (!validateEdge(asGraphV2(state), proposed, 'user').ok) return null;
      const edge = proposed as CanvasEdge;
      commit((current) => ({ nodes: current.nodes, edges: [...current.edges, edge], generationRecords: current.generationRecords }));
      return edge.id;
    },
    // 创建节点并自动连线（新节点连到 sourceId 的右边）
    createConnectedNode: (sourceId, kind, position, sourceHandle, overrides) => {
      const source = get().nodes.find((n) => n.id === sourceId);
      if (!source) return null;
      const sourcePort = resolveOutputPort(source.data.kind, kind, sourceHandle);
      const targetPort = sourcePort
        ? getNodeDefinition(kind)?.inputs.find((port) =>
          port.media === sourcePort.media && port.acceptsKinds.includes(source.data.kind as never))
        : undefined;
      if (!sourcePort || !targetPort) return null;
      const node = { ...makeNode(kind, position, overrides), selected: true };
      const baseData = targetPort.ordered
        ? { portOrder: nextPortOrder(get().edges, node.id, targetPort.id) }
        : undefined;
      const edge = {
        id: `edge-${createId()}`,
        source: sourceId,
        sourceHandle: sourcePort.id,
        target: node.id,
        targetHandle: targetPort.id,
        type: 'canvas',
        data: withResultSlotData(source, node as CanvasNode, get().edges, baseData),
      };
      if (!validateEdge(asGraphV2({ ...get(), nodes: [...get().nodes, node] }), edge, 'user').ok) return null;
      commit((state) => ({ nodes: [...clearSelection(state.nodes), node], edges: [...state.edges, edge], generationRecords: state.generationRecords }));
      set({ selectedNodeId: node.id });
      return node.id;
    },
    // 创建节点并自动连线（新节点排在 targetId 的左边，作为其前置）
    createNodeBefore: (targetId, kind, position, targetHandle) => {
      const target = get().nodes.find((n) => n.id === targetId);
      if (!target) return null;
      const targetPort = resolveInputPort(kind, target.data.kind, targetHandle);
      const sourcePort = targetPort
        ? getNodeDefinition(kind)?.outputs.find((port) =>
          port.media === targetPort.media && targetPort.acceptsKinds.includes(kind as never))
        : undefined;
      if (!sourcePort || !targetPort) return null;
      const node = { ...makeNode(kind, position), selected: true };
      const edge = {
        id: `edge-${createId()}`,
        source: node.id,
        sourceHandle: sourcePort.id,
        target: targetId,
        targetHandle: targetPort.id,
        type: 'canvas',
        data: targetPort.ordered
          ? { portOrder: nextPortOrder(get().edges, targetId, targetPort.id) }
          : undefined,
      };
      if (!validateEdge(asGraphV2({ ...get(), nodes: [...get().nodes, node] }), edge, 'user').ok) return null;
      commit((state) => ({ nodes: [...clearSelection(state.nodes), node], edges: [...state.edges, edge], generationRecords: state.generationRecords }));
      set({ selectedNodeId: node.id });
      return node.id;
    },
    deleteEdge: (edgeId) => {
      const removed = get().edges.find((edge) => edge.id === edgeId);
      if (!removed) return;
      commit((state) => ({
        nodes: state.nodes,
        edges: compactOrderedEdges(
          state.edges.filter((edge) => edge.id !== edgeId),
          removed.target,
          removed.targetHandle ?? '',
        ),
      }));
    },
    selectNode: (nodeId, preserveSelection = false) => set((state) => ({
      selectedNodeId: nodeId,
      nodes: preserveSelection && nodeId
        ? state.nodes.map((node) => node.id === nodeId && !node.selected ? { ...node, selected: true } : node)
        : state.nodes.map((node) => Boolean(node.selected) === (node.id === nodeId) ? node : { ...node, selected: node.id === nodeId }),
      edges: preserveSelection ? state.edges : state.edges.map((edge) => edge.selected ? { ...edge, selected: false } : edge),
    })),
    selectAll: () => {
      const state = get();
      set({
        selectedNodeId: state.nodes.at(-1)?.id ?? null,
        nodes: state.nodes.map((node) => node.selected ? node : { ...node, selected: true }),
        edges: state.edges.map((edge) => edge.selected ? { ...edge, selected: false } : edge),
      });
      return state.nodes.length;
    },
    undo: () => {
      const state = get(); const previous = state.past.at(-1); if (!previous) return;
      set({ nodes: previous.nodes, edges: previous.edges, past: state.past.slice(0, -1), future: [graphSnapshot(state.nodes, state.edges), ...state.future].slice(0, MAX_HISTORY), selectedNodeId: null, revision: state.revision + 1, derivedAssetIdsBySource: indexDerivedAssets(previous.nodes) });
    },
    redo: () => {
      const state = get(); const next = state.future[0]; if (!next) return;
      set({ nodes: next.nodes, edges: next.edges, past: [...state.past, graphSnapshot(state.nodes, state.edges)].slice(-MAX_HISTORY), future: state.future.slice(1), selectedNodeId: null, revision: state.revision + 1, derivedAssetIdsBySource: indexDerivedAssets(next.nodes) });
    },
    clearCanvas: () => commit(() => ({ nodes: [], edges: [] })),
    appendGenerationRecord: (record) => set((state) => ({
      generationRecords: [...state.generationRecords.filter((item) => item.id !== record.id), record],
      revision: state.revision + 1,
    })),
    updateGenerationRecord: (record) => set((state) => ({
      generationRecords: state.generationRecords.map((item) => item.id === record.id ? record : item),
      revision: state.revision + 1,
    })),
    appendExecution: (execution) => set((state) => ({
      executions: [...state.executions.filter((item) => item.id !== execution.id), execution].slice(-20),
    })),
    updateExecution: (execution) => set((state) => ({
      executions: state.executions.some((item) => item.id === execution.id)
        ? state.executions.map((item) => item.id === execution.id ? execution : item)
        : [...state.executions, execution].slice(-20),
    })),
    materializeResultNode: (request) => {
      const state = get();
      if (state.nodes.some((node) => node.id === request.resultNodeId)) return request.resultNodeId;
      const producer = state.nodes.find((node) => node.id === request.producerNodeId);
      const existingResults = state.nodes.filter((node) =>
        node.id.startsWith('result:') && node.data.derivedFrom === producer?.id);
      const resultIndex = existingResults.length;
      const stepY = request.size.height + 32;
      const result = {
        id: request.resultNodeId,
        type: request.kind,
        position: {
          x: producer
            ? producer.position.x + (producer.width ?? 250) + 160
            : 300,
          y: (producer?.position.y ?? 200) + resultIndex * stepY,
        },
        width: request.size.width,
        height: request.size.height,
        data: {
          kind: request.kind,
          label: request.label,
          prompt: '',
          status: 'success' as const,
          accent: request.kind === 'video' ? '#a0a0a0' : '#929292',
          assetId: request.assetId,
          mediaLoadState: 'ready' as const,
          derivedFrom: producer?.id,
          operation: { type: 'remote' as const, params: {} },
        },
      } satisfies CanvasNode;
      const sourcePort = getNodeDefinition(producer?.data.kind)?.outputs.find((candidate) => candidate.media === request.kind);
      const targetPort = getNodeDefinition(request.kind)?.inputs.find((candidate) => candidate.id === 'origin');
      const proposedEdge = producer && sourcePort && targetPort ? {
        id: `edge-result-${request.resultNodeId}`,
        source: producer.id,
        sourceHandle: sourcePort.id,
        target: result.id,
        targetHandle: targetPort.id,
        type: 'canvas',
      } : null;
      const nextGraph = asGraphV2({ ...state, nodes: [...state.nodes, result] });
      const validEdge = proposedEdge && validateEdge(nextGraph, proposedEdge, 'pipeline').ok ? proposedEdge as CanvasEdge : null;
      commit((current) => ({
        nodes: [...clearSelection(current.nodes), result],
        edges: validEdge ? [...current.edges, validEdge] : current.edges,
        generationRecords: current.generationRecords,
      }));
      set({ selectedNodeId: result.id });
      return result.id;
    },
    applyStoryboardPlan: (producerNodeId, plans) => {
      const state = get();
      const scopedShotIds = new Set(state.edges
        .filter((edge) => edge.source === producerNodeId && edge.targetHandle === 'origin')
        .map((edge) => edge.target));
      const existingShots = state.nodes.filter((node) =>
        node.data.kind === 'shot' && scopedShotIds.has(node.id)) as CanvasGraphV2['nodes'];
      const reconciledRaw = reconcileStoryboardShots(existingShots, plans);
      const idRemap = new Map(reconciledRaw.created.map((node) => [node.id, `${producerNodeId}:${node.id}`]));
      const reconciled = {
        ...reconciledRaw,
        shots: reconciledRaw.shots.map((node) => idRemap.has(node.id) ? { ...node, id: idRemap.get(node.id)! } : node),
        created: reconciledRaw.created.map((node) => ({ ...node, id: idRemap.get(node.id)! })),
      };
      const shotById = new Map(reconciled.shots.map((node) => [node.id, node]));
      const nextNodes = state.nodes.map((node) => shotById.get(node.id) ?? node);
      const knownShotIds = new Set(nextNodes.map((node) => node.id));
      for (const shot of reconciled.shots) {
        if (!knownShotIds.has(shot.id)) nextNodes.push(shot);
      }
      const createdEdges = reconciled.created.flatMap((shot) => {
        const proposed = {
          id: `edge-${producerNodeId}-${shot.id}`,
          source: producerNodeId,
          sourceHandle: 'shots',
          target: shot.id,
          targetHandle: 'origin',
          type: 'canvas',
        };
        return validateEdge(asGraphV2({ ...state, nodes: nextNodes as CanvasNode[] }), proposed, 'pipeline').ok
          ? [proposed as CanvasEdge]
          : [];
      });
      const existingReferenceEdges = new Set(state.edges
        .filter((edge) => edge.targetHandle === 'reference')
        .map((edge) => `${edge.source}:${edge.target}`));
      const createdReferenceEdges: CanvasEdge[] = [];
      const referenceEdgesForValidation = [...state.edges, ...createdEdges];
      for (const shot of [...reconciled.created, ...reconciled.updated]) {
        const referenceAssetIds = Array.isArray(shot.data.referenceAssetIds) ? shot.data.referenceAssetIds : [];
        for (const assetId of referenceAssetIds) {
          const source = nextNodes.find((node) => node.data.assetId === assetId);
          if (!source || existingReferenceEdges.has(`${source.id}:${shot.id}`)) continue;
          const usedOrders = new Set(referenceEdgesForValidation
            .filter((edge) => edge.target === shot.id && edge.targetHandle === 'reference')
            .map((edge) => (edge.data as { portOrder?: number } | undefined)?.portOrder)
            .filter((order): order is number => Number.isInteger(order)));
          let order = 0;
          while (usedOrders.has(order)) order += 1;
          const proposed = {
            id: `edge-ref-${source.id}-${shot.id}`,
            source: source.id,
            sourceHandle: 'image',
            target: shot.id,
            targetHandle: 'reference',
            type: 'canvas',
            data: { portOrder: order },
          };
          if (!validateEdge(asGraphV2({
            ...state,
            nodes: nextNodes as CanvasNode[],
            edges: referenceEdgesForValidation,
          }), proposed, 'pipeline').ok) continue;
          existingReferenceEdges.add(`${source.id}:${shot.id}`);
          createdReferenceEdges.push(proposed as CanvasEdge);
          referenceEdgesForValidation.push(proposed as CanvasEdge);
        }
      }
      commit((current) => ({
        nodes: nextNodes as CanvasNode[],
        edges: [...current.edges, ...createdEdges, ...createdReferenceEdges],
        generationRecords: current.generationRecords,
      }));
      return {
        created: reconciled.created.map((node) => node.id),
        updated: reconciled.updated.map((node) => node.id),
        stale: reconciled.stale.map((node) => node.id),
      };
    },
    applyRemotePatch: (patch) => {
      const state = get();
      const incomingNodes = (patch.nodes ?? []).map(requireRemotePatchNode);
      const incomingEdges = (patch.edges ?? []).map(requireRemotePatchEdge);
      const removedNodeIds = new Set(patch.removedNodeIds ?? []);
      const removedEdgeIds = new Set(patch.removedEdgeIds ?? []);

      const addedNodeIds: string[] = [];
      const updatedNodeIds: string[] = [];
      const nodeById = new Map(state.nodes.map((node) => [node.id, node]));
      for (const id of removedNodeIds) nodeById.delete(id);
      for (const node of incomingNodes) {
        const existing = nodeById.get(node.id);
        if (existing) {
          updatedNodeIds.push(node.id);
          // 同 id 覆盖，但保留本地交互态（选中/拖拽）与本地 UI 标记（isGenerating / progress 等）。
          // 正在拖的节点**不能**被远端位置覆盖：否则鼠标还没松手，节点会突然弹回原位。
          const dragging = existing.dragging === true;
          nodeById.set(node.id, {
            ...existing,
            ...node,
            position: dragging ? existing.position : (node.position ?? existing.position),
            data: { ...existing.data, ...node.data },
            selected: existing.selected,
            dragging,
          });
        } else {
          addedNodeIds.push(node.id);
          nodeById.set(node.id, { ...node, selected: false, dragging: false });
        }
      }
      const survivingNodeIds = new Set(nodeById.keys());

      const addedEdgeIds: string[] = [];
      const updatedEdgeIds: string[] = [];
      const edgeById = new Map(state.edges.map((edge) => [edge.id, edge]));
      for (const id of removedEdgeIds) edgeById.delete(id);
      for (const edge of incomingEdges) {
        // 端点不在本地 → 说明中间漏了补丁；抛错让调用方整图重载，绝不留下悬挂边。
        if (!survivingNodeIds.has(edge.source) || !survivingNodeIds.has(edge.target)) {
          throw new Error(`补丁连线 ${edge.id} 的端点不在本地图中`);
        }
        if (edgeById.has(edge.id)) updatedEdgeIds.push(edge.id);
        else addedEdgeIds.push(edge.id);
        edgeById.set(edge.id, edge);
      }
      // 节点被删时服务端会一并下发 removedEdgeIds；这里再兜一层，防止渲染悬挂边。
      const prunedEdgeIds: string[] = [];
      for (const [id, edge] of [...edgeById]) {
        if (survivingNodeIds.has(edge.source) && survivingNodeIds.has(edge.target)) continue;
        edgeById.delete(id);
        if (!removedEdgeIds.has(id)) prunedEdgeIds.push(id);
      }

      const nodes = sanitizeGroupParents([...nodeById.values()]);
      set({
        nodes,
        edges: [...edgeById.values()],
        // 远端删掉了当前选中节点 → 清选中，避免选中一个不存在的节点。
        selectedNodeId: state.selectedNodeId && !survivingNodeIds.has(state.selectedNodeId)
          ? null
          : state.selectedNodeId,
        derivedAssetIdsBySource: indexDerivedAssets(nodes),
        // 远端补丁不写 past/future，也不动 store revision（见 CanvasState.applyRemotePatch 注释）。
      });
      return {
        addedNodeIds,
        updatedNodeIds,
        removedNodeIds: [...removedNodeIds].filter((id) => !nodeById.has(id)),
        addedEdgeIds,
        updatedEdgeIds,
        removedEdgeIds: [...removedEdgeIds, ...prunedEdgeIds],
      };
    },
  };
});
