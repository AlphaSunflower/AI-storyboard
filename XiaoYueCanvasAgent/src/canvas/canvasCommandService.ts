import type { Connection, XYPosition } from '@xyflow/react';
import { useCanvasStore } from '../store/canvasStore';
import { createId } from '../utils/id';
import type { CanvasEdge, CanvasNode, MediaKind } from '../types';
import {
  getMediaPlaceholderContract,
  getNodeDefinition,
  getNodeFlowRole,
  listNodeDefinitions,
  validateEdge,
  type CanvasGraphV2,
} from './graphContracts';
import { failure } from './canvasErrors';
import { check, result } from './canvasTransaction';
import { computeGridLayout, type GridDirection, type LayoutPosition } from './layout/gridLayout';
import type { DagDirection } from './layout/dagLayout';
import { computeReadableDagLayout } from './layout/readableDagLayout';
import { collectConnectedComponentNodeIds } from './layout/layoutScope';
import { computeAlignment, computeDistribution, type AlignAxis, type DistributeAxis } from './layout/alignment';
import { deriveFromAsset } from './derivationService';
import { createAnnotatedImageAsset, createCroppedImageAsset, createPixelAdjustedAsset, createSplitImageAssets } from '../utils/mediaPipeline';
import { getMediaNodeSize, getVideoNodeSize } from '../utils/mediaPipeline';
import { createPersistedCanvasAsset } from '../services/canvasMedia';
import { useMediaStore } from '../store/mediaStore';
import { createCanvas, listCanvases, loadCanvas, saveCanvas } from '../services/projectStorage';
import { resolveResultSlots, validateResultSlotOwnership } from './resultSlots';
import { isModelConfigNode, modelTypeForNode, MODEL_CONFIG_RESERVED_KEYS } from './modelConfig';
import type { CanvasCommandContext, CanvasCommandResult } from './canvasTypes';
import type { AnnotationDocument, AnnotationShape } from '../types';

export type LayoutMode = 'grid' | 'horizontal' | 'vertical' | 'dag';

// 未配置时：开发指向本地 Vite(5173)，生产返回空串由同源地址兜底
const configuredMoonChatBaseUrl = (import.meta.env.VITE_MOON_CHAT_URL as string | undefined)?.trim();
const moonChatBaseUrl = configuredMoonChatBaseUrl
  ? configuredMoonChatBaseUrl.replace(/\/$/, '')
  : (import.meta.env.DEV ? 'http://localhost:5173' : '');
const configuredMediaBaseUrl = (import.meta.env.VITE_FILE_BASE_URL as string | undefined)?.trim().replace(/\/$/, '');

const resolveMediaUrl = (url: string) => {
  try {
    const sameOrigin = typeof window !== 'undefined' && window.location?.origin;
    const base = configuredMediaBaseUrl || sameOrigin || moonChatBaseUrl;
    if (/^https?:\/\//i.test(url)) {
      const parsed = new URL(url);
      if (parsed.pathname.startsWith('/api/files/')) {
        return new URL(`${parsed.pathname}${parsed.search}`, base).toString();
      }
      return url;
    }
    return new URL(url, base).toString();
  } catch {
    return url;
  }
};

const waitForCanvasBridgeReady = (projectId: string, canvasId: string, timeoutMs = 120_000) => {
  if (typeof window === 'undefined') return Promise.resolve(true);
  return new Promise<boolean>((resolve) => {
    let timeout: number | null = null;
    const cleanup = () => {
      if (timeout !== null) window.clearTimeout(timeout);
      window.removeEventListener('morrow:canvas-bridge-ready', onReady);
    };
    function onReady(event: Event) {
      const detail = (event as CustomEvent<{ projectId?: string; canvasId?: string }>).detail;
      if (detail?.projectId !== projectId || detail.canvasId !== canvasId) return;
      cleanup();
      resolve(true);
    }
    timeout = window.setTimeout(() => {
      cleanup();
      resolve(false);
    }, timeoutMs);
    window.addEventListener('morrow:canvas-bridge-ready', onReady);
  });
};

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
  const selected = state.nodes.filter((node) => node.selected).map((node) => node.id);
  const ids = nodeIds !== undefined
    ? new Set(nodeIds)
    : new Set(selected.length ? selected : state.nodes.map((node) => node.id));
  return state.nodes.filter((node) => ids.has(node.id));
};

const layoutScopeSignature = (nodes: CanvasNode[], edges: CanvasEdge[]): string => {
  const nodePart = [...nodes]
    .sort((left, right) => left.id.localeCompare(right.id))
    .map((node) => [
      node.id,
      node.data.kind,
      node.position.x,
      node.position.y,
      node.width ?? node.measured?.width ?? '',
      node.height ?? node.measured?.height ?? '',
    ].join(':'))
    .join('|');
  const edgePart = [...edges]
    .sort((left, right) => left.id.localeCompare(right.id))
    .map((edge) => `${edge.id}:${edge.source}:${edge.sourceHandle ?? ''}:${edge.target}:${edge.targetHandle ?? ''}:${edge.data?.portOrder ?? ''}`)
    .join('|');
  return `${nodePart}::${edgePart}`;
};

const applyLayoutCommand = async (
  context: CanvasCommandContext,
  params: { nodeIds?: string[] },
  description: string,
  compute: (nodes: CanvasNode[], edges: CanvasEdge[]) => LayoutPosition[] | Promise<LayoutPosition[]>,
): Promise<CanvasCommandResult<{ positions: LayoutPosition[] }>> => {
  const invalid = check(context);
  if (invalid) return invalid as CanvasCommandResult<{ positions: LayoutPosition[] }>;
  const state = useCanvasStore.getState();
  const selected = resolveSelection(params.nodeIds);
  if (!selected.length) return failure('NODE_NOT_FOUND', '没有可排布的节点');
  const selectedIds = new Set(selected.map((node) => node.id));
  const scopedEdges = state.edges.filter((edge) => selectedIds.has(edge.source) && selectedIds.has(edge.target));
  const signatureBefore = layoutScopeSignature(selected, scopedEdges);
  const positions = await compute(selected, state.edges);
  const latest = useCanvasStore.getState();
  const latestSelected = latest.nodes.filter((node) => selectedIds.has(node.id));
  const latestEdges = latest.edges.filter((edge) => selectedIds.has(edge.source) && selectedIds.has(edge.target));
  if (
    latest.projectId !== state.projectId
    || latest.canvasId !== state.canvasId
    || layoutScopeSignature(latestSelected, latestEdges) !== signatureBefore
  ) {
    return failure('CANVAS_REVISION_CONFLICT', '画布在布局期间已发生变化，请重新执行', {
      currentRevision: latest.revision,
    });
  }
  const positionById = new Map(positions.map((position) => [position.nodeId, position.position]));
  const changed = positions.filter((position) => {
    const node = latest.nodes.find((candidate) => candidate.id === position.nodeId);
    return node && (Math.abs(node.position.x - position.position.x) > .5 || Math.abs(node.position.y - position.position.y) > .5);
  });
  return result(context, { movedNodeIds: changed.map((position) => position.nodeId), description }, { positions }, () => {
    useCanvasStore.getState().moveNodes(changed.map((position) => ({
      nodeId: position.nodeId,
      position: positionById.get(position.nodeId)!,
    })));
  });
};

const gridDirectionForMode = (mode: LayoutMode): GridDirection => (mode === 'horizontal' ? 'horizontal' : mode === 'vertical' ? 'vertical' : 'grid');

const nextFreePosition = (reserved = new Set<string>()): XYPosition => {
  const nodes = useCanvasStore.getState().nodes;
  const occupied = new Set(nodes.map((node) => `${Math.round(node.position.x)}:${Math.round(node.position.y)}`));
  const columns = 3;
  for (let index = 0; index < 10_000; index += 1) {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const candidate = { x: 300 + column * 480, y: 200 + row * 360 };
    const key = `${candidate.x}:${candidate.y}`;
    if (!occupied.has(key) && !reserved.has(key)) {
      reserved.add(key);
      return candidate;
    }
  }
  return { x: 300, y: 200 };
};

const resolveEdgeHandles = (sourceId: string, targetId: string, sourceHandle?: string, targetHandle?: string) => {
  const state = useCanvasStore.getState();
  const source = state.nodes.find((node) => node.id === sourceId);
  const target = state.nodes.find((node) => node.id === targetId);
  if (!source || !target) return null;
  const sourceDef = getNodeDefinition(source.data.kind);
  const targetDef = getNodeDefinition(target.data.kind);
  const output = sourceDef?.outputs.find((port) => port.id === sourceHandle) ?? sourceDef?.outputs[0];
  const input = targetDef?.inputs.find((port) => port.id === targetHandle)
    ?? targetDef?.inputs.find((port) => port.acceptsKinds.includes(source.data.kind as never))
    ?? targetDef?.inputs[0];
  if (!output || !input) return null;
  return { source, target, output, input };
};

export const canvasCommandService = {
  createNode(context: CanvasCommandContext, params: { kind: MediaKind; position?: XYPosition; label?: string; prompt?: string; data?: Record<string, unknown> }): CanvasCommandResult<{ nodeId: string }> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ nodeId: string }>;
    const reservedModelKeys = modelTypeForNode(params.kind)
      ? Object.keys(params.data ?? {}).filter((key) => MODEL_CONFIG_RESERVED_KEYS.has(key))
      : [];
    if (reservedModelKeys.length) {
      return failure(
        'MODEL_CONFIG_TOOL_REQUIRED',
        '模型和模型参数必须通过 canvas_set_node_model_config 修改',
        { kind: params.kind, keys: reservedModelKeys },
      );
    }
    const position = params.position ?? nextFreePosition();
    const overrides = {
      ...(params.data ?? {}),
      ...(params.label !== undefined ? { label: params.label } : {}),
      ...(params.prompt !== undefined ? { prompt: params.prompt } : {}),
    } as Partial<CanvasNode['data']>;
    const nodeId = context.dryRun ? `${params.kind}-preview` : useCanvasStore.getState().addNode(params.kind, position, overrides);
    const changes = { createdNodeIds: context.dryRun ? [] : [nodeId], description: `创建 1 个${params.kind}节点` };
    if (context.dryRun) {
      return { ok: true, status: 'preview', transactionId: `tx-${createId()}`, revision: useCanvasStore.getState().revision, changes, result: { nodeId } };
    }
    return {
      ok: true,
      status: 'applied' as const,
      transactionId: `tx-${createId()}`,
      revision: useCanvasStore.getState().revision,
      changes,
      result: { nodeId },
    };
  },

  async importMedia(context: CanvasCommandContext, params: {
    kind: 'image' | 'video';
    url: string;
    name?: string;
    mimeType?: string;
    label?: string;
    position?: XYPosition;
  }): Promise<CanvasCommandResult<{ nodeId: string; assetId: string }>> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ nodeId: string; assetId: string }>;
    if (!params.url) return failure('INVALID_ARGUMENTS', 'url 必须是非空字符串');
    if (context.dryRun) {
      return {
        ok: true,
        status: 'preview',
        transactionId: `tx-preview-${createId()}`,
        revision: useCanvasStore.getState().revision,
        changes: { description: `导入 1 个${params.kind}素材` },
        result: { nodeId: `${params.kind}-preview`, assetId: `${params.kind}-asset-preview` },
      };
    }
    let response: Response;
    try {
      const mediaUrl = resolveMediaUrl(params.url);
      response = context.signal
        ? await fetch(mediaUrl, { signal: context.signal })
        : await fetch(mediaUrl);
    } catch (error) {
      return failure('MEDIA_FETCH_FAILED', error instanceof Error ? error.message : '媒体下载失败');
    }
    if (!response.ok) return failure('MEDIA_FETCH_FAILED', `媒体下载失败：HTTP ${response.status}`);
    const blob = await response.blob();
    const mimeType = params.mimeType || blob.type || (params.kind === 'video' ? 'video/mp4' : 'image/png');
    const name = params.name || `${params.kind}-${Date.now()}${params.kind === 'video' ? '.mp4' : '.png'}`;
    const asset = await createPersistedCanvasAsset(new File([blob], name, { type: mimeType }), '');
    useMediaStore.getState().upsertAssets([asset]);
    const position = params.position ?? nextFreePosition();
    const item = {
      assetId: asset.id,
      label: params.label || name.replace(/\.[^.]+$/, ''),
      position,
      size: asset.kind === 'video'
        ? getVideoNodeSize(asset.width, asset.height)
        : getMediaNodeSize(asset.width, asset.height),
    };
    const nodeIds = asset.kind === 'video'
      ? useCanvasStore.getState().addImportedVideos([item])
      : useCanvasStore.getState().addImportedImages([item]);
    const nodeId = nodeIds[0];
    return {
      ok: true,
      status: 'applied',
      transactionId: `tx-${createId()}`,
      revision: useCanvasStore.getState().revision,
      changes: { createdNodeIds: [nodeId], description: `导入 1 个${asset.kind}素材` },
      result: { nodeId, assetId: asset.id },
    };
  },

  async createCanvas(context: CanvasCommandContext, params: {
    title?: string;
    activate?: boolean;
  } = {}): Promise<CanvasCommandResult<{ canvasId: string; title: string; activated: boolean }>> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ canvasId: string; title: string; activated: boolean }>;
    const projectId = context.projectId || useCanvasStore.getState().projectId;
    if (!projectId) return failure('PROJECT_NOT_OPEN', '当前没有打开的项目');
    if (context.dryRun) {
      return {
        ok: true,
        status: 'preview',
        transactionId: `tx-preview-${createId()}`,
        revision: useCanvasStore.getState().revision,
        changes: { description: '新建 1 个画布' },
        result: { canvasId: 'canvas-preview', title: params.title?.trim() || '新画布', activated: params.activate === true },
      };
    }
    try {
      const existing = await listCanvases(projectId);
      const title = params.title?.trim() || `画布 ${existing.length + 1}`;
      const created = await createCanvas(projectId, title);
      const detail = { projectId, canvasId: created.id, title: created.title, activate: params.activate === true };
      const bridgeReady = params.activate === true
        ? waitForCanvasBridgeReady(projectId, created.id)
        : Promise.resolve(true);
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('morrow:canvas-created', { detail }));
      }
      if (!(await bridgeReady)) {
        return failure('CANVAS_ACTIVATION_TIMEOUT', '新画布已创建，但浏览器未完成切换', {
          projectId,
          canvasId: created.id,
          title: created.title,
        });
      }
      return {
        ok: true,
        status: 'applied',
        transactionId: `tx-${createId()}`,
        revision: useCanvasStore.getState().revision,
        changes: { description: `新建画布「${created.title}」` },
        result: { canvasId: created.id, title: created.title, activated: detail.activate },
      };
    } catch (error) {
      return failure('CANVAS_CREATE_FAILED', error instanceof Error ? error.message : '新建画布失败');
    }
  },

  async renameCanvas(context: CanvasCommandContext, params: {
    canvasId?: string;
    title: string;
  }): Promise<CanvasCommandResult<{ canvasId: string; title: string }>> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ canvasId: string; title: string }>;
    const canvasId = params.canvasId || useCanvasStore.getState().canvasId;
    const title = params.title?.trim();
    if (!canvasId) return failure('INVALID_ARGUMENTS', '缺少 canvasId');
    if (!title) return failure('INVALID_ARGUMENTS', '画布名称不能为空');
    if (context.dryRun) {
      return {
        ok: true,
        status: 'preview',
        transactionId: `tx-preview-${createId()}`,
        revision: useCanvasStore.getState().revision,
        changes: { description: `重命名画布为「${title}」` },
        result: { canvasId, title },
      };
    }
    try {
      const target = await loadCanvas(canvasId);
      if (!target) return failure('CANVAS_NOT_FOUND', '画布不存在', { canvasId });
      if (context.projectId && target.projectId !== context.projectId) {
        return failure('PROJECT_NOT_OPEN', '目标画布不属于当前项目', { canvasId });
      }
      const saved = await saveCanvas({ ...target, title, updatedAt: Date.now() });
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('morrow:canvas-renamed', {
          detail: { projectId: saved.projectId, canvasId: saved.id, title: saved.title },
        }));
      }
      return {
        ok: true,
        status: 'applied',
        transactionId: `tx-${createId()}`,
        revision: useCanvasStore.getState().revision,
        changes: { description: `画布已重命名为「${saved.title}」` },
        result: { canvasId: saved.id, title: saved.title },
      };
    } catch (error) {
      return failure('CANVAS_RENAME_FAILED', error instanceof Error ? error.message : '画布重命名失败');
    }
  },

  createNodes(context: CanvasCommandContext, params: { items: Array<{ kind: MediaKind; position?: XYPosition; label?: string; prompt?: string; data?: Record<string, unknown> }> }): CanvasCommandResult<{ nodeIds: string[] }> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ nodeIds: string[] }>;
    if (!Array.isArray(params.items) || !params.items.length) return failure('INVALID_ARGUMENTS', 'items 不能为空');
    if (params.items.length > 100) return failure('LIMIT_EXCEEDED', '单次最多创建 100 个节点');
    const invalidModelItem = params.items.find((item) =>
      modelTypeForNode(item.kind)
      && Object.keys(item.data ?? {}).some((key) => MODEL_CONFIG_RESERVED_KEYS.has(key)));
    if (invalidModelItem) {
      return failure(
        'MODEL_CONFIG_TOOL_REQUIRED',
        '模型和模型参数必须通过 canvas_set_node_model_config 修改',
        {
          kind: invalidModelItem.kind,
          keys: Object.keys(invalidModelItem.data ?? {}).filter((key) => MODEL_CONFIG_RESERVED_KEYS.has(key)),
        },
      );
    }
    const reserved = new Set<string>();
    const items = params.items.map((item) => ({
      kind: item.kind,
      position: item.position ?? nextFreePosition(reserved),
      overrides: {
        ...(item.data ?? {}),
        ...(item.label !== undefined ? { label: item.label } : {}),
        ...(item.prompt !== undefined ? { prompt: item.prompt } : {}),
      } as Partial<CanvasNode['data']>,
    }));
    const nodeIds = context.dryRun ? items.map((item) => `${item.kind}-preview`) : useCanvasStore.getState().createNodes(items);
    const changes = { createdNodeIds: context.dryRun ? [] : nodeIds, description: `创建 ${items.length} 个节点` };
    return {
      ok: true,
      status: context.dryRun ? 'preview' as const : 'applied' as const,
      transactionId: `tx-${createId()}`,
      revision: useCanvasStore.getState().revision,
      changes,
      result: { nodeIds },
    };
  },

  async markResultSlots(context: CanvasCommandContext, params: {
    producerNodeId: string;
    nodeIds: string[];
    startOrder?: number;
  }): Promise<CanvasCommandResult<{ nodeIds: string[]; edgeIds: string[] }>> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ nodeIds: string[]; edgeIds: string[] }>;
    const state = useCanvasStore.getState();
    const producer = state.nodes.find((node) => node.id === params.producerNodeId);
    if (!producer) return failure('NODE_NOT_FOUND', '生产节点不存在', { nodeId: params.producerNodeId });
    const nodeIds = [...new Set(params.nodeIds ?? [])];
    if (!nodeIds.length) return failure('INVALID_ARGUMENTS', 'nodeIds 不能为空');
    const definitions = getNodeDefinition(producer.data.kind);
    const resolved: Array<{ nodeId: string; kind: 'image' | 'video'; outputId: string; order: number }> = [];
    for (const [index, nodeId] of nodeIds.entries()) {
      const target = state.nodes.find((node) => node.id === nodeId);
      if (!target) return failure('NODE_NOT_FOUND', `结果槽节点不存在：${nodeId}`);
      if (target.data.kind !== 'image' && target.data.kind !== 'video') {
        return failure('INVALID_ARGUMENTS', `节点 ${nodeId} 不是图片或视频占位节点`);
      }
      if (target.data.assetId) return failure('INVALID_ARGUMENTS', `节点 ${nodeId} 已有素材，不能作为空结果槽`);
      const output = definitions?.outputs.find((port) => port.media === target.data.kind);
      if (!output) return failure('INVALID_ARGUMENTS', `节点 ${nodeId} 的类型与生产节点输出不兼容`);
      resolved.push({ nodeId, kind: target.data.kind, outputId: output.id, order: (params.startOrder ?? 0) + index });
    }
    const slots = resolved.map(({ nodeId, kind, order }) => ({
      nodeId,
      kind,
      order,
      edgeId: state.edges.find((edge) => edge.source === producer.id && edge.target === nodeId)?.id ?? '',
    }));
    const graph: CanvasGraphV2 = {
      schemaVersion: 2,
      nodes: state.nodes as CanvasGraphV2['nodes'],
      edges: state.edges as CanvasGraphV2['edges'],
      viewport: { x: 0, y: 0, zoom: 1 },
      generationRecords: state.generationRecords,
      executions: state.executions,
    };
    const ownership = validateResultSlotOwnership(graph, producer.id, slots);
    if (!ownership.ok) return failure(ownership.code ?? 'RESULT_SLOT_CONFLICT', ownership.message ?? '结果槽冲突');
    if (context.dryRun) {
      return {
        ok: true,
        status: 'preview',
        transactionId: `tx-preview-${createId()}`,
        revision: state.revision,
        changes: { affectedNodeIds: nodeIds, description: `标记 ${nodeIds.length} 个结果槽` },
        result: { nodeIds, edgeIds: slots.map((slot) => slot.edgeId).filter(Boolean) },
      };
    }
    const edgeIds: string[] = [];
    for (const slot of resolved) {
      const existing = useCanvasStore.getState().edges.find((edge) => edge.source === producer.id && edge.target === slot.nodeId);
      if (existing) {
        useCanvasStore.getState().updateEdgeData(existing.id, {
          resultSlot: true,
          resultSlotOrder: slot.order,
        });
        edgeIds.push(existing.id);
      } else {
        const edgeId = useCanvasStore.getState().createEdge({
          source: producer.id,
          sourceHandle: slot.outputId,
          target: slot.nodeId,
          targetHandle: 'origin',
          data: { resultSlot: true, resultSlotOrder: slot.order },
        } as Connection);
        if (!edgeId) return failure('INVALID_ARGUMENTS', `无法连接结果槽 ${slot.nodeId}`);
        edgeIds.push(edgeId);
      }
    }
    return {
      ok: true,
      status: 'applied',
      transactionId: `tx-${createId()}`,
      revision: useCanvasStore.getState().revision,
      changes: { affectedNodeIds: nodeIds, description: `标记 ${nodeIds.length} 个结果槽` },
      result: { nodeIds, edgeIds },
    };
  },

  async createResultSlots(context: CanvasCommandContext, params: {
    producerNodeId: string;
    kind: 'image' | 'video';
    count: number;
    position?: XYPosition;
    gapY?: number;
  }): Promise<CanvasCommandResult<{ nodeIds: string[]; edgeIds: string[] }>> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ nodeIds: string[]; edgeIds: string[] }>;
    const count = Math.floor(params.count);
    if (!Number.isFinite(count) || count < 1 || count > 12) {
      return failure('INVALID_ARGUMENTS', 'count 必须是 1~12');
    }
    const state = useCanvasStore.getState();
    const producer = state.nodes.find((node) => node.id === params.producerNodeId);
    if (!producer) return failure('NODE_NOT_FOUND', '生产节点不存在', { nodeId: params.producerNodeId });
    const definition = getNodeDefinition(producer.data.kind);
    if (!definition?.outputs.some((port) => port.media === params.kind)) {
      return failure('INVALID_ARGUMENTS', `生产节点没有 ${params.kind} 输出`);
    }
    const size = getNodeDefinition(params.kind)?.defaultSize ?? { width: 250, height: 250 };
    const producerWidth = producer.width ?? producer.measured?.width ?? definition.defaultSize.width;
    const origin = params.position ?? {
      x: producer.position.x + producerWidth + 160,
      y: producer.position.y,
    };
    const gapY = params.gapY ?? size.height + 40;
    if (context.dryRun) {
      return {
        ok: true,
        status: 'preview',
        transactionId: `tx-preview-${createId()}`,
        revision: state.revision,
        changes: { description: `创建 ${count} 个${params.kind}结果槽` },
        result: {
          nodeIds: Array.from({ length: count }, (_, index) => `${params.kind}-slot-preview-${index}`),
          edgeIds: [],
        },
      };
    }
    const nodeIds = useCanvasStore.getState().createNodes(
      Array.from({ length: count }, (_, index) => ({
        kind: params.kind,
        position: { x: origin.x, y: origin.y + index * gapY },
        overrides: { label: `结果 ${index + 1}`, status: 'idle' as const },
        size,
      })),
    );
    return canvasCommandService.markResultSlots(context, {
      producerNodeId: params.producerNodeId,
      nodeIds,
      startOrder: 0,
    });
  },

  updateNode(context: CanvasCommandContext, params: { nodeId: string; patch: Record<string, unknown> }): CanvasCommandResult<{ nodeId: string }> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ nodeId: string }>;
    const node = useCanvasStore.getState().nodes.find((candidate) => candidate.id === params.nodeId);
    if (!node) return failure('NODE_NOT_FOUND', '节点不存在', { nodeId: params.nodeId });
    if (!params.patch || typeof params.patch !== 'object' || Array.isArray(params.patch)) {
      return failure('INVALID_ARGUMENTS', 'patch 必须是对象');
    }
    if (isModelConfigNode(node) && Object.keys(params.patch).some((key) => MODEL_CONFIG_RESERVED_KEYS.has(key))) {
      return failure(
        'MODEL_CONFIG_TOOL_REQUIRED',
        '模型和模型参数必须通过 canvas_set_node_model_config 修改',
        { nodeId: node.id, keys: Object.keys(params.patch).filter((key) => MODEL_CONFIG_RESERVED_KEYS.has(key)) },
      );
    }
    const patch = Object.fromEntries(Object.entries(params.patch).filter(([key]) => key !== 'kind')) as Partial<CanvasNode['data']>;
    const changes = { affectedNodeIds: [params.nodeId], description: '更新节点数据' };
    if (!context.dryRun) useCanvasStore.getState().updateNode(params.nodeId, patch);
    return {
      ok: true,
      status: context.dryRun ? 'preview' as const : 'applied' as const,
      transactionId: `tx-${createId()}`,
      revision: useCanvasStore.getState().revision,
      changes,
      result: { nodeId: params.nodeId },
    };
  },

  listNodeTypes(context: CanvasCommandContext): CanvasCommandResult<unknown[]> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<unknown[]>;
    const definitions = listNodeDefinitions().map((definition) => ({
      kind: definition.kind,
      title: definition.title,
      category: definition.category,
      flowRole: getNodeFlowRole(definition.kind),
      mediaPlaceholder: getMediaPlaceholderContract(definition.kind),
      defaultSize: definition.defaultSize,
      inputs: definition.inputs.map((port) => ({
        id: port.id,
        label: port.label,
        media: port.media,
        acceptsKinds: port.acceptsKinds,
        role: port.role,
        ordered: port.ordered,
        min: port.min,
        max: Number.isFinite(port.max) ? port.max : -1,
        required: port.required,
      })),
      outputs: definition.outputs.map((port) => ({
        id: port.id,
        label: port.label,
        media: port.media,
        role: port.role,
      })),
      actions: definition.actions.map((action) => ({
        id: action.id,
        label: action.label,
        capabilityId: action.capabilityId,
        resultPolicy: action.resultPolicy,
        startable: action.automation.startable,
      })),
    }));
    return { ok: true, status: 'applied' as const, revision: useCanvasStore.getState().revision, result: definitions };
  },

  createEdge(context: CanvasCommandContext, params: { source: string; target: string; sourceHandle?: string; targetHandle?: string }): CanvasCommandResult<{ edgeId: string }> {
    const invalid = check(context);
    if (invalid) return invalid as CanvasCommandResult<{ edgeId: string }>;
    const state = useCanvasStore.getState();
    if (params.source === params.target) return failure('INVALID_ARGUMENTS', '不能创建自环连线');
    const resolved = resolveEdgeHandles(params.source, params.target, params.sourceHandle, params.targetHandle);
    if (!resolved) return failure('INVALID_ARGUMENTS', '找不到兼容的输入输出端口', params);
    const graph: CanvasGraphV2 = {
      schemaVersion: 2,
      nodes: state.nodes as CanvasGraphV2['nodes'],
      edges: state.edges as CanvasGraphV2['edges'],
      viewport: { x: 0, y: 0, zoom: 1 },
      generationRecords: state.generationRecords,
      executions: state.executions,
    };
    const usedOrders = new Set(state.edges
      .filter((edge) => edge.target === params.target && edge.targetHandle === resolved.input.id)
      .map((edge) => (edge.data as { portOrder?: number } | undefined)?.portOrder)
      .filter((order): order is number => Number.isInteger(order)));
    let portOrder = 0;
    if (resolved.input.ordered) {
      while (usedOrders.has(portOrder)) portOrder += 1;
    }
    const edgeId = `edge-${createId()}`;
    const proposed = {
      id: edgeId,
      source: params.source,
      sourceHandle: resolved.output.id,
      target: params.target,
      targetHandle: resolved.input.id,
      type: 'canvas',
      data: resolved.input.ordered ? { portOrder } : undefined,
    };
    const validation = validateEdge(graph, proposed, 'agent');
    if (!validation.ok) return failure('INVALID_ARGUMENTS', validation.message ?? '非法连线');
    if (!context.dryRun) {
      const created = useCanvasStore.getState().createEdge({
        source: params.source,
        sourceHandle: resolved.output.id,
        target: params.target,
        targetHandle: resolved.input.id,
        data: proposed.data,
      } as Connection);
      if (!created) return failure('INVALID_ARGUMENTS', '创建连线失败');
      return {
        ok: true,
        status: 'applied' as const,
        transactionId: `tx-${createId()}`,
        revision: useCanvasStore.getState().revision,
        changes: { createdEdgeIds: [created], description: '创建 1 条连线' },
        result: { edgeId: created },
      };
    }
    return {
      ok: true,
      status: 'preview',
      transactionId: `tx-${createId()}`,
      revision: state.revision,
      changes: { description: '创建 1 条连线' },
      result: { edgeId },
    };
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

  async layout(context: CanvasCommandContext, params: LayoutParams) {
    const mode = params.mode ?? 'grid';
    if (mode === 'dag') {
      return applyLayoutCommand(context, params, `按依赖层级布局 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes, edges) =>
        computeReadableDagLayout(nodes, edges, { gapX: params.gapX, gapY: params.gapY, anchor: params.anchor }));
    }
    return applyLayoutCommand(context, params, `按${mode}布局 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) =>
      computeGridLayout(nodes, { gapX: params.gapX, gapY: params.gapY, columns: params.columns, anchor: params.anchor, direction: gridDirectionForMode(mode) }));
  },

  async layoutGrid(context: CanvasCommandContext, params: LayoutApplyParams) {
    return applyLayoutCommand(context, params, `按网格布局 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) => computeGridLayout(nodes, { gapX: params.gapX, gapY: params.gapY, columns: params.columns, anchor: params.anchor }));
  },

  async layoutDag(context: CanvasCommandContext, params: { nodeIds?: string[]; gapX?: number; gapY?: number; anchor?: XYPosition; direction?: DagDirection }) {
    return applyLayoutCommand(context, params, `按依赖层级布局 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes, edges) =>
      computeReadableDagLayout(nodes, edges, { gapX: params.gapX, gapY: params.gapY, anchor: params.anchor, direction: params.direction }));
  },

  async layoutConnectedComponent(context: CanvasCommandContext, params: { nodeIds: string[]; gapX?: number; gapY?: number; anchor?: XYPosition }) {
    const state = useCanvasStore.getState();
    const nodeIds = collectConnectedComponentNodeIds(state.nodes, state.edges, params.nodeIds);
    if (!nodeIds.length) return failure('NODE_NOT_FOUND', '没有可排布的节点');
    return applyLayoutCommand(context, { ...params, nodeIds }, `整理包含 ${nodeIds.length} 个节点的连通编排`, (nodes, edges) =>
      computeReadableDagLayout(nodes, edges, { gapX: params.gapX, gapY: params.gapY, anchor: params.anchor, direction: 'right' }));
  },

  async layoutAlign(context: CanvasCommandContext, params: { nodeIds?: string[]; axis: AlignAxis }) {
    return applyLayoutCommand(context, params, `按${params.axis}对齐 ${resolveSelection(params.nodeIds).length} 个节点`, (nodes) => computeAlignment(nodes, params.axis));
  },

  async layoutDistribute(context: CanvasCommandContext, params: { nodeIds?: string[]; axis: DistributeAxis }) {
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
