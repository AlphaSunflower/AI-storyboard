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
import type { CanvasDocumentV1, CanvasEdge, CanvasNode, GraphSnapshot, MediaKind } from '../types';
import { createId } from '../utils/id';
import {
  getNodeDefinition,
  isCanvasNodeKind,
  validateEdge,
  type CanvasExecution,
  type CanvasGraphV2,
  type GenerationRecord,
  type NodePort,
} from '../canvas/graphContracts';
import { reconcileStoryboardShots, type StoryboardShotPlan } from '../canvas/storyboardPlan';
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
  attachAssetToNode: (nodeId: string, item: { assetId: string; label: string; size: { width: number; height: number } }) => void;
  createDerivedNodes: (sourceId: string, outputs: DerivedOutput[], layout?: { columns?: number }) => string[];
  extendNode: (sourceId: string, kind?: MediaKind, direction?: 'left' | 'right') => void;
  updateNode: (nodeId: string, patch: Partial<CanvasNode['data']>) => void;
  updateEdgeData: (edgeId: string, patch: Partial<CanvasEdge['data']>) => void;
  setNodePinned: (nodeId: string, pinned: boolean) => void;
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
  createConnectedNode: (sourceId: string, kind: MediaKind, position: XYPosition, sourceHandle?: string) => string | null;
  // 创建节点并自动连线（新节点排在 targetId 的左边，作为其前置）
  createNodeBefore: (targetId: string, kind: MediaKind, position: XYPosition, targetHandle?: string) => string | null;
  deleteEdge: (edgeId: string) => void;
  selectNode: (nodeId: string | null, preserveSelection?: boolean) => void;
  selectAll: () => number;
  undo: () => void;
  redo: () => void;
  loadTemplate: (templateId: string) => void;
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

const normalizeHandle = (
  handle: string | null | undefined,
  side: 'source' | 'target',
  sourceKind: MediaKind,
  targetKind: MediaKind,
) => {
  if (handle && handle !== 'right' && handle !== 'left') return handle;
  if (side === 'source') return getNodeDefinition(sourceKind)?.outputs[0]?.id;
  return compatibleTargetPort(sourceKind, targetKind)?.id;
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

/** 导出给连线菜单（ConnectionNodeMenu）用：判断源能否连到目标，口径与 store 一致。 */
export const canConnectNodes = canConnect;

const selectedNodes = (state: Pick<CanvasState, 'nodes' | 'selectedNodeId'>) => {
  const selected = state.nodes.filter((node) => node.selected);
  if (selected.length) return selected;
  const fallback = state.nodes.find((node) => node.id === state.selectedNodeId);
  return fallback ? [fallback] : [];
};

const cloneSelection = (nodes: CanvasNode[], edges: CanvasEdge[], offset: XYPosition) => {
  const idMap = new Map<string, string>();
  const clonedNodes = nodes.map((node) => {
    const id = `${node.data.kind}-${createId()}`;
    idMap.set(node.id, id);
    const clone = structuredClone(node);
    return {
      ...clone,
      id,
      position: { x: node.position.x + offset.x, y: node.position.y + offset.y },
      selected: true,
      dragging: false,
      measured: undefined,
      data: { ...clone.data, label: `${clone.data.label} 副本` },
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
      if (permanent) commit((state) => ({ nodes: applyNodeChanges(changes, state.nodes), edges: state.edges }));
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
          operation: node.data.kind === 'image' ? { type: 'import', params: {} } : undefined,
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
      const connections: CanvasEdge[] = created.map((node, index) => ({ id: `edge-${createId()}`, source: sourceId, sourceHandle: 'right', target: node.id, targetHandle: 'left', type: 'canvas', data: { operation: outputs[index]?.data?.operation?.type } }));
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
    setNodePinned: (nodeId, pinned) => commit((state) => ({ nodes: state.nodes.map((node) => node.id === nodeId ? { ...node, draggable: !pinned, data: { ...node.data, pinned } } : node), edges: state.edges })),
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
        nodes: current.nodes.filter((node) => !ids.has(node.id)),
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
        nodes: current.nodes.filter((node) => !ids.has(node.id)),
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
    createConnectedNode: (sourceId, kind, position, sourceHandle) => {
      const source = get().nodes.find((n) => n.id === sourceId);
      if (!source) return null;
      const sourcePort = sourceHandle
        ? getNodeDefinition(source.data.kind)?.outputs.find((port) => port.id === sourceHandle)
        : getNodeDefinition(source.data.kind)?.outputs[0];
      const targetPort = sourcePort
        ? getNodeDefinition(kind)?.inputs.find((port) =>
          port.media === sourcePort.media && port.acceptsKinds.includes(source.data.kind as never))
        : undefined;
      if (!sourcePort || !targetPort) return null;
      const node = { ...makeNode(kind, position), selected: true };
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
      const targetPort = targetHandle
        ? getNodeDefinition(target.data.kind)?.inputs.find((port) => port.id === targetHandle)
        : compatibleTargetPort(kind, target.data.kind);
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
      if (!get().edges.some((edge) => edge.id === edgeId)) return;
      commit((state) => ({ nodes: state.nodes, edges: state.edges.filter((edge) => edge.id !== edgeId) }));
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
    loadTemplate: (templateId) => {
      const x = 180 + get().nodes.length * 18;
      const source = makeNode('image', { x, y: 180 }, { label: '上传源图片' });
      const note = makeNode('text', { x: x + 360, y: 150 }, { label: '设计说明', prompt: '记录目标、约束和版本判断。' });
      const nodes = templateId === 'variant-study'
        ? [source, note, makeNode('image', { x: x + 360, y: 480 }, { label: '版本 A' }), makeNode('image', { x: x + 700, y: 480 }, { label: '版本 B' })]
        : [source, note, makeNode('image', { x: x + 720, y: 180 }, { label: '修改版本' })];
      const edges: CanvasEdge[] = nodes.slice(1).map((node) => ({ id: `edge-${createId()}`, source: source.id, sourceHandle: 'right', target: node.id, targetHandle: 'left', type: 'canvas' }));
      commit((state) => ({ nodes: [...state.nodes, ...nodes], edges: [...state.edges, ...edges] }));
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
          accent: request.kind === 'video' ? '#8f9cff' : '#ff775f',
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
  };
});
