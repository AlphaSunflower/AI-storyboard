import type { Connection, XYPosition } from '@xyflow/react';
import { useCanvasStore } from '../store/canvasStore';
import { createId } from '../utils/id';
import type { CanvasNode, MediaKind } from '../types';
import { getNodeDefByType } from '../domain/nodeRegistry';
import { failure } from './canvasErrors';
import { check, result } from './canvasTransaction';
import { computeGridLayout, type GridDirection, type LayoutPosition } from './layout/gridLayout';
import { computeDagLayout, type DagDirection } from './layout/dagLayout';
import { computeAlignment, computeDistribution, type AlignAxis, type DistributeAxis } from './layout/alignment';
import { deriveFromAsset } from './derivationService';
import { createAnnotatedImageAsset, createCroppedImageAsset, createPixelAdjustedAsset, createSplitImageAssets } from '../utils/mediaPipeline';
import type { CanvasCommandContext, CanvasCommandResult } from './canvasTypes';
import type { AnnotationDocument, AnnotationShape } from '../types';

export type LayoutMode = 'grid' | 'horizontal' | 'vertical' | 'dag';

interface LayoutParams {
  nodeIds?: string[];
  mode?: LayoutMode;
  gapX?: number;
  gapY?: number;
  columns?: number;
  anchor?: XYPosition;
}

interface LayoutApplyParams {
  nodeIds?: string[];
  gapX?: number;
  gapY?: number;
  columns?: number;
  anchor?: XYPosition;
}

const resolveSelection = (nodeIds?: string[]): CanvasNode[] => {
  const state = useCanvasStore.getState();
  const ids = nodeIds?.length ? new Set(nodeIds) : new Set(state.nodes.filter((node) => node.selected).map((node) => node.id));
  return state.nodes.filter((node) => ids.has(node.id));
};

const applyLayoutCommand = (context: CanvasCommandContext, params: { nodeIds?: string[] }, description: string, compute: (nodes: CanvasNode[]) => LayoutPosition[]): CanvasCommandResult<{ positions: LayoutPosition[] }> => {
  const invalid = check(context);
  if (invalid) return invalid as CanvasCommandResult<{ positions: LayoutPosition[] }>;
  const selected = resolveSelection(params.nodeIds);
  if (!selected.length) return failure('NODE_NOT_FOUND', '没有可排布的节点');
  const positions = compute(selected);
  return result(context, { movedNodeIds: positions.map((position) => position.nodeId), description }, { positions }, () => {
    useCanvasStore.getState().moveNodes(positions.map((position) => ({ nodeId: position.nodeId, position: position.position })));
  });
};

const gridDirectionForMode = (mode: LayoutMode): GridDirection => (mode === 'horizontal' ? 'horizontal' : mode === 'vertical' ? 'vertical' : 'grid');

export const canvasCommandService = {
  createNode(context: CanvasCommandContext, params: { kind: MediaKind; position?: XYPosition; label?: string; prompt?: string }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<string>;
    const position = params.position ?? { x: 300, y: 200 };
    const value = { kind: params.kind, position, label: params.label ?? params.kind };
    return result(context, { description: `创建 1 个${params.kind}节点` }, value, () => {
      useCanvasStore.getState().addNode(params.kind, position, { label: params.label, prompt: params.prompt });
    });
  },

  createNodes(context: CanvasCommandContext, params: { items: Array<{ kind: MediaKind; position: XYPosition; label?: string; prompt?: string }> }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<string[]>;
    if (!Array.isArray(params.items) || !params.items.length) return failure('INVALID_ARGUMENTS', 'items 不能为空');
    if (params.items.length > 100) return failure('LIMIT_EXCEEDED', '单次最多创建 100 个节点');
    const items = params.items.map((item) => ({ kind: item.kind, position: item.position, overrides: { label: item.label, prompt: item.prompt } }));
    return result(context, { description: `创建 ${items.length} 个节点` }, { count: items.length }, () => {
      useCanvasStore.getState().createNodes(items);
    });
  },

  deleteNodes(context: CanvasCommandContext, params: { nodeIds: string[] }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<number>;
    const ids = [...new Set(params.nodeIds ?? [])];
    const state = useCanvasStore.getState();
    const existing = ids.filter((id) => state.nodes.some((node) => node.id === id));
    if (!existing.length) return failure('NODE_NOT_FOUND', '没有找到可删除的节点', { nodeIds: ids });
    const changes = { deletedNodeIds: existing, description: `删除 ${existing.length} 个节点及其连线` };
    return result(context, changes, { count: existing.length }, () => {
      useCanvasStore.getState().deleteNodes(existing);
    });
  },

  duplicateNodes(context: CanvasCommandContext, params: { nodeIds: string[]; offset?: XYPosition }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<string[]>;
    const ids = [...new Set(params.nodeIds ?? [])];
    const state = useCanvasStore.getState();
    const existing = ids.filter((id) => state.nodes.some((node) => node.id === id));
    if (!existing.length) return failure('NODE_NOT_FOUND', '没有找到可复制的节点', { nodeIds: ids });
    const offset = params.offset ?? { x: 42, y: 42 };
    return result(context, { affectedNodeIds: existing, description: `复制 ${existing.length} 个节点` }, { count: existing.length, offset }, () => {
      useCanvasStore.getState().duplicateNodes(existing, offset);
    });
  },

  copyNodes(context: CanvasCommandContext, params: { nodeIds: string[] }): CanvasCommandResult<{ count: number }> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ count: number }>;
    const ids = [...new Set(params.nodeIds ?? [])];
    const state = useCanvasStore.getState();
    const existing = ids.filter((id) => state.nodes.some((node) => node.id === id));
    if (!existing.length) return failure('NODE_NOT_FOUND', '没有找到可复制的节点', { nodeIds: ids });
    // 复制到剪贴板不改动图，因此不进入撤销历史，也不产生事务 id。
    const count = useCanvasStore.getState().copyNodes(existing);
    return { ok: true, status: 'applied', revision: state.revision, result: { count }, changes: { affectedNodeIds: existing, description: `复制 ${count} 个节点到剪贴板` } };
  },

  pasteNodes(context: CanvasCommandContext, params: { position?: XYPosition }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<string[]>;
    const value = { position: params.position };
    return result(context, { description: '粘贴剪贴板节点' }, value, () => {
      useCanvasStore.getState().pasteClipboard(params.position);
    });
  },

  moveNodes(context: CanvasCommandContext, params: { positions: Array<{ nodeId: string; x: number; y: number }> }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<number>;
    const positions = (params.positions ?? []).filter((item) => Number.isFinite(item.x) && Number.isFinite(item.y));
    const state = useCanvasStore.getState();
    const existing = positions.filter((item) => state.nodes.some((node) => node.id === item.nodeId));
    if (!existing.length) return failure('NODE_NOT_FOUND', '没有找到可移动的节点');
    return result(context, { movedNodeIds: existing.map((item) => item.nodeId), description: `移动 ${existing.length} 个节点` }, { count: existing.length }, () => {
      useCanvasStore.getState().moveNodes(existing.map((item) => ({ nodeId: item.nodeId, position: { x: item.x, y: item.y } })));
    });
  },

  resizeNodes(context: CanvasCommandContext, params: { sizes: Array<{ nodeId: string; width: number; height: number }> }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<number>;
    const sizes = (params.sizes ?? []).filter((item) => Number.isFinite(item.width) && Number.isFinite(item.height) && item.width > 0 && item.height > 0);
    const state = useCanvasStore.getState();
    const existing = sizes.filter((item) => state.nodes.some((node) => node.id === item.nodeId));
    if (!existing.length) return failure('NODE_NOT_FOUND', '没有找到可调整尺寸的节点');
    return result(context, { resizedNodeIds: existing.map((item) => item.nodeId), description: `调整 ${existing.length} 个节点尺寸` }, { count: existing.length }, () => {
      useCanvasStore.getState().resizeNodes(existing.map((item) => ({ nodeId: item.nodeId, size: { width: item.width, height: item.height } })));
    });
  },

  pinNodes(context: CanvasCommandContext, params: { nodeIds: string[]; pinned: boolean }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<number>;
    const ids = [...new Set(params.nodeIds ?? [])];
    const state = useCanvasStore.getState();
    const existing = ids.filter((id) => state.nodes.some((node) => node.id === id));
    if (!existing.length) return failure('NODE_NOT_FOUND', '没有找到可固定的节点', { nodeIds: ids });
    return result(context, { affectedNodeIds: existing, description: `${params.pinned ? '固定' : '取消固定'} ${existing.length} 个节点` }, { count: existing.length }, () => {
      existing.forEach((id) => useCanvasStore.getState().setNodePinned(id, params.pinned));
    });
  },

  createEdge(context: CanvasCommandContext, params: { source: string; target: string; sourceHandle?: string; targetHandle?: string }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<string | null>;
    const state = useCanvasStore.getState();
    const sourceNode = state.nodes.find((n) => n.id === params.source);
    const targetNode = state.nodes.find((n) => n.id === params.target);
    if (!sourceNode || !targetNode) return failure('INVALID_ARGUMENTS', '源或目标节点不存在', { source: params.source, target: params.target });
    if (sourceNode.id === targetNode.id) return failure('INVALID_ARGUMENTS', '不能创建自环连线');
    // connectivity 校验：类型可接受 + 数量上限
    const sourceDef = getNodeDefByType(sourceNode.data.kind);
    const targetDef = getNodeDefByType(targetNode.data.kind);
    if (sourceDef && sourceDef.connectivity.maxOutputs > 0) {
      const outCount = state.edges.filter((e) => e.source === params.source).length;
      if (outCount >= sourceDef.connectivity.maxOutputs) return failure('INVALID_ARGUMENTS', `源节点输出已达上限 ${sourceDef.connectivity.maxOutputs}`);
    }
    if (targetDef && targetDef.connectivity.maxInputs > 0) {
      const inCount = state.edges.filter((e) => e.target === params.target).length;
      if (inCount >= targetDef.connectivity.maxInputs) return failure('INVALID_ARGUMENTS', `目标节点输入已达上限 ${targetDef.connectivity.maxInputs}`);
    }
    if (targetDef && targetDef.connectivity.acceptTypes.length > 0 && !targetDef.connectivity.acceptTypes.includes(sourceNode.data.kind)) {
      return failure('INVALID_ARGUMENTS', `目标节点不接受源类型 "${sourceNode.data.kind}"`);
    }
    const connection: Connection = { source: params.source, target: params.target, sourceHandle: params.sourceHandle ?? 'right', targetHandle: params.targetHandle ?? 'left' };
    return result(context, { description: '创建 1 条连线' }, { source: params.source, target: params.target }, () => {
      useCanvasStore.getState().createEdge(connection);
    });
  },

  deleteEdges(context: CanvasCommandContext, params: { edgeIds: string[] }) {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<number>;
    const ids = [...new Set(params.edgeIds ?? [])];
    const state = useCanvasStore.getState();
    const existing = ids.filter((id) => state.edges.some((edge) => edge.id === id));
    if (!existing.length) return failure('INVALID_ARGUMENTS', '没有找到可删除的连线', { edgeIds: ids });
    return result(context, { deletedEdgeIds: existing, description: `删除 ${existing.length} 条连线` }, { count: existing.length }, () => {
      existing.forEach((id) => useCanvasStore.getState().deleteEdge(id));
    });
  },

  undo(context: CanvasCommandContext): CanvasCommandResult {
    const invalid = check(context);
    if (invalid) return invalid;
    const description = { description: '撤销上一步画布事务' };
    if (context.dryRun) return { ok: true, status: 'preview', transactionId: `tx-${createId()}`, revision: useCanvasStore.getState().revision, changes: description };
    useCanvasStore.getState().undo();
    return { ok: true, status: 'applied', transactionId: `tx-${createId()}`, revision: useCanvasStore.getState().revision, changes: description };
  },

  redo(context: CanvasCommandContext): CanvasCommandResult {
    const invalid = check(context);
    if (invalid) return invalid;
    const description = { description: '重做上一步画布事务' };
    if (context.dryRun) return { ok: true, status: 'preview', transactionId: `tx-${createId()}`, revision: useCanvasStore.getState().revision, changes: description };
    useCanvasStore.getState().redo();
    return { ok: true, status: 'applied', transactionId: `tx-${createId()}`, revision: useCanvasStore.getState().revision, changes: description };
  },

  layout(context: CanvasCommandContext, params: LayoutParams) {
    const mode = params.mode ?? 'grid';
    if (mode === 'dag') {
      return applyLayoutCommand(context, params, `按 DAG 布局 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) => computeDagLayout(nodes, useCanvasStore.getState().edges, { gapX: params.gapX, gapY: params.gapY, anchor: params.anchor }));
    }
    return applyLayoutCommand(context, params, `按${mode}布局 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) => computeGridLayout(nodes, { gapX: params.gapX, gapY: params.gapY, columns: params.columns, anchor: params.anchor, direction: gridDirectionForMode(mode) }));
  },

  layoutGrid(context: CanvasCommandContext, params: LayoutApplyParams) {
    return applyLayoutCommand(context, params, `按网格布局 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) => computeGridLayout(nodes, { gapX: params.gapX, gapY: params.gapY, columns: params.columns, anchor: params.anchor }));
  },

  layoutDag(context: CanvasCommandContext, params: { nodeIds?: string[]; gapX?: number; gapY?: number; anchor?: XYPosition; direction?: DagDirection }) {
    return applyLayoutCommand(context, params, `按 DAG 布局 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) => computeDagLayout(nodes, useCanvasStore.getState().edges, { gapX: params.gapX, gapY: params.gapY, anchor: params.anchor, direction: params.direction }));
  },

  layoutAlign(context: CanvasCommandContext, params: { nodeIds?: string[]; axis: AlignAxis }) {
    return applyLayoutCommand(context, params, `按${params.axis}对齐 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) => computeAlignment(nodes, params.axis));
  },

  layoutDistribute(context: CanvasCommandContext, params: { nodeIds?: string[]; axis: DistributeAxis }) {
    return applyLayoutCommand(context, params, `按${params.axis === 'horizontal' ? '水平' : '垂直'}分布 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) => computeDistribution(nodes, params.axis));
  },

  // ── 语义操作（AI 驱动，落成因果链派生节点）──────────────────────────
  imageCrop(context: CanvasCommandContext, params: { nodeId: string; crop: { x: number; y: number; width: number; height: number } }) {
    return deriveFromAsset({ context, sourceNodeId: params.nodeId, operationType: 'crop', labelSuffix: '裁剪', process: (source) => createCroppedImageAsset(source, params.crop).then((asset) => [asset]) });
  },

  imageSplit(context: CanvasCommandContext, params: { nodeId: string; rows: number; columns: number }) {
    return deriveFromAsset({ context, sourceNodeId: params.nodeId, operationType: 'split', labelSuffix: '切分', process: (source) => createSplitImageAssets(source, params.rows, params.columns), layout: { columns: params.columns } });
  },

  imageAdjust(context: CanvasCommandContext, params: { nodeId: string; brightness: number; contrast: number; saturation: number }) {
    return deriveFromAsset({ context, sourceNodeId: params.nodeId, operationType: 'pixels', labelSuffix: '像素调整', process: (source) => createPixelAdjustedAsset(source, { brightness: params.brightness, contrast: params.contrast, saturation: params.saturation }).then((asset) => [asset]) });
  },

  imageAnnotate(context: CanvasCommandContext, params: { nodeId: string; shapes: AnnotationShape[] }) {
    return deriveFromAsset({
      context,
      sourceNodeId: params.nodeId,
      operationType: 'annotation',
      labelSuffix: '标注',
      process: (source) => {
        const document: AnnotationDocument = { version: 1, sourceAssetId: source.id, shapes: params.shapes };
        return createAnnotatedImageAsset(source, document).then((asset) => [asset]);
      },
    });
  },
};
