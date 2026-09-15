import type { ComponentType } from 'react';
import type { XYPosition, Viewport } from '@xyflow/react';
import type { MediaNodeData, NodeStatus } from '../types';

export type CanvasNodeKind =
  | 'text'
  | 'script'
  | 'note'
  | 'image'
  | 'video'
  | 'storyboardPlanner'
  | 'shot'
  | 'textToImage'
  | 'imageToImage'
  | 'batchImageRefine'
  | 'textToVideo'
  | 'imageToVideo'
  | 'firstLastFrameToVideo'
  | 'referenceToVideo';

export type LegacyNodeKind =
  | 'videoGen'
  | 'prompt'
  | 'sequence'
  | 'storyboard'
  | 'storyboardGen'
  | 'imageGen';

export type NodeCategory = '输入' | '分镜' | '图像生成' | '视频生成' | '辅助';
export type PortMedia = 'text' | 'script' | 'storyboard' | 'shot' | 'image' | 'video';
export type ConnectionMode = 'user' | 'agent' | 'pipeline' | 'migration';
export type ResultPolicy = 'media-nodes' | 'inline-data' | 'none';

export interface NodePort {
  id: string;
  label: string;
  media: PortMedia;
  acceptsKinds: CanvasNodeKind[];
  role: string;
  order: number;
  ordered: boolean;
  min: number;
  max: number;
  required: boolean;
}

export interface CanvasJobRequest {
  capabilityId: string;
  sourceAssetIds: string[];
  params: Record<string, unknown>;
}

export interface NodeRunContext {
  projectId: string;
  nodeId: string;
  actionId: string;
  generationRecordId: string;
  attempt: number;
  inputBindings: Record<string, Array<{ nodeId: string; assetId?: string; role: string; order: number }>>;
  nodeData: MediaNodeData;
}

export interface NodeAction {
  id: string;
  label: string;
  capabilityId: string;
  resultPolicy: ResultPolicy;
  automation: { startable: boolean; pauseAfter: boolean };
  buildRequest: (context: NodeRunContext) => CanvasJobRequest;
}

export interface NodeDefinition {
  kind: CanvasNodeKind;
  title: string;
  category: NodeCategory;
  icon: string;
  defaultSize: { width: number; height: number };
  inputs: NodePort[];
  outputs: NodePort[];
  actions: NodeAction[];
  createDefaultData: () => MediaNodeData;
  renderer: ComponentType<unknown>;
}

export interface CanvasNodeV2 {
  id: string;
  type: CanvasNodeKind;
  position: XYPosition;
  width?: number;
  height?: number;
  selected?: boolean;
  data: MediaNodeData & {
    kind: CanvasNodeKind;
    automation?: AutomationState;
  };
}

export interface CanvasEdgeV2 {
  id: string;
  source: string;
  sourceHandle: string;
  target: string;
  targetHandle: string;
  type?: string;
  selected?: boolean;
  data?: {
    portOrder?: number;
    operation?: string;
    migrationStatus?: 'migration_invalid';
    migrationError?: string;
  };
}

export type MaterializationStatus = 'pending' | 'materializing' | 'completed' | 'failed';
export type GenerationStatus = 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'interrupted';
export type GenerationQueueStatus =
  | 'pending_submit'
  | 'queued'
  | 'running'
  | 'claimed'
  | 'terminal'
  | 'cancelled'
  | 'interrupted';

export interface GenerationSource {
  nodeId: string;
  assetId: string;
  role: string;
  order: number;
}

export interface GenerationRecord {
  id: string;
  batchId?: string;
  itemId?: string;
  retryOf?: string;
  producerNodeId: string;
  actionId: string;
  attempt: number;
  jobId?: string;
  lastEventSeq?: number;
  progress?: number;
  queueStatus?: GenerationQueueStatus;
  status: GenerationStatus;
  materializationStatus: MaterializationStatus;
  materializedAssetIds: string[];
  resultNodeIds: string[];
  materializationError?: string;
  sources: GenerationSource[];
  prompt: string;
  effectivePrompt: string;
  params: Record<string, unknown>;
  model?: string;
  error?: string;
  startedAt: number;
  completedAt?: number;
}

export interface MigrationReport {
  sourceSchemaVersion: number;
  migratedNodeIds: string[];
  invalidEdges: Array<{ edgeId: string; code: string; message: string }>;
  warnings: string[];
}

export interface CanvasGraphV2 {
  schemaVersion: 2;
  nodes: CanvasNodeV2[];
  edges: CanvasEdgeV2[];
  viewport: Viewport;
  generationRecords: GenerationRecord[];
  migrationReport?: MigrationReport;
}

export type AutomationPhase = 'idle' | 'ready' | 'queued' | 'running' | 'paused' | 'failed' | 'stale' | 'done';

export interface AutomationState {
  phase: AutomationPhase;
  configFingerprint?: string;
  runFingerprint?: string;
  paused?: boolean;
}

export type ConnectionValidationCode =
  | 'NODE_NOT_FOUND'
  | 'PORT_NOT_FOUND'
  | 'HANDLE_REQUIRED'
  | 'PORT_KIND_MISMATCH'
  | 'PORT_MEDIA_MISMATCH'
  | 'PORT_LIMIT_EXCEEDED'
  | 'PORT_ORDER_REQUIRED'
  | 'PORT_ORDER_CONFLICT'
  | 'DUPLICATE_EDGE'
  | 'SELF_LOOP'
  | 'CYCLE'
  | 'INPUT_REQUIRED'
  | 'ACTION_NOT_FOUND';

export interface ConnectionValidation {
  ok: boolean;
  code?: ConnectionValidationCode;
  message?: string;
}

const data = (kind: CanvasNodeKind, label: string, accent: string): MediaNodeData => ({
  kind,
  label,
  prompt: '',
  status: 'idle',
  accent,
});

const noRequest = (): CanvasJobRequest => ({ capabilityId: 'none', sourceAssetIds: [], params: {} });
const requestBuilder = (capabilityId: string): NodeAction['buildRequest'] => (context) => ({
  capabilityId,
  sourceAssetIds: Object.values(context.inputBindings).flat().flatMap((binding) => binding.assetId ? [binding.assetId] : []),
  params: {
    prompt: String(context.nodeData.prompt ?? ''),
    actionId: context.actionId,
  },
});

const action = (
  id: string,
  label: string,
  capabilityId: string,
  resultPolicy: ResultPolicy,
  startable = true,
): NodeAction => ({
  id,
  label,
  capabilityId,
  resultPolicy,
  automation: { startable, pauseAfter: resultPolicy === 'media-nodes' },
  buildRequest: requestBuilder(capabilityId),
});

const noneAction = (): NodeAction[] => [{
  id: 'none',
  label: '无操作',
  capabilityId: 'none',
  resultPolicy: 'none',
  automation: { startable: false, pauseAfter: false },
  buildRequest: noRequest,
}];

const port = (value: NodePort): NodePort => value;
const sourceImage = (id = 'image', order = 0): NodePort => port({
  id, label: '图片', media: 'image', acceptsKinds: ['image'], role: 'source', order,
  ordered: false, min: 1, max: 1, required: true,
});
const outputImage = (): NodePort => port({
  id: 'image', label: '图片结果', media: 'image', acceptsKinds: [], role: 'result', order: 0,
  ordered: false, min: 0, max: 1, required: false,
});
const outputVideo = (): NodePort => port({
  id: 'video', label: '视频结果', media: 'video', acceptsKinds: [], role: 'result', order: 0,
  ordered: false, min: 0, max: 1, required: false,
});

const renderer = (() => null) as ComponentType<unknown>;

const define = (definition: NodeDefinition): NodeDefinition => definition;

export const CANVAS_NODE_DEFINITIONS: Record<CanvasNodeKind, NodeDefinition> = {
  text: define({
    kind: 'text', title: '文字', category: '输入', icon: 'type', defaultSize: { width: 380, height: 280 },
    inputs: [], outputs: [port({ id: 'text', label: '文字', media: 'text', acceptsKinds: [], role: 'source', order: 0, ordered: false, min: 0, max: Infinity, required: false })],
    actions: noneAction(), createDefaultData: () => data('text', '文字', '#f4c95d'), renderer,
  }),
  script: define({
    kind: 'script', title: '剧本', category: '输入', icon: 'file-text', defaultSize: { width: 420, height: 320 },
    inputs: [], outputs: [port({ id: 'script', label: '剧本', media: 'script', acceptsKinds: [], role: 'source', order: 0, ordered: false, min: 0, max: Infinity, required: false })],
    actions: noneAction(), createDefaultData: () => ({ ...data('script', '剧本', '#a9c9f5'), scriptText: '' }), renderer,
  }),
  note: define({
    kind: 'note', title: '批注', category: '辅助', icon: 'message-square', defaultSize: { width: 250, height: 250 },
    inputs: [], outputs: [], actions: noneAction(),
    createDefaultData: () => ({ ...data('note', '批注', '#d7ff54'), richText: '' }), renderer,
  }),
  image: define({
    kind: 'image', title: '图片', category: '输入', icon: 'image', defaultSize: { width: 250, height: 250 },
    inputs: [port({ id: 'origin', label: '生成来源', media: 'image', acceptsKinds: ['textToImage', 'imageToImage', 'batchImageRefine', 'shot'], role: 'origin', order: 0, ordered: false, min: 0, max: 1, required: false })],
    outputs: [outputImage()], actions: noneAction(),
    createDefaultData: () => data('image', '图片', '#ff775f'), renderer,
  }),
  video: define({
    kind: 'video', title: '视频', category: '输入', icon: 'video', defaultSize: { width: 420, height: 236 },
    inputs: [port({ id: 'origin', label: '生成来源', media: 'video', acceptsKinds: ['textToVideo', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo', 'shot'], role: 'origin', order: 0, ordered: false, min: 0, max: 1, required: false })],
    outputs: [outputVideo()], actions: noneAction(),
    createDefaultData: () => data('video', '视频', '#8f9cff'), renderer,
  }),
  storyboardPlanner: define({
    kind: 'storyboardPlanner', title: '分镜拆解', category: '分镜', icon: 'clapperboard', defaultSize: { width: 520, height: 420 },
    inputs: [
      port({ id: 'script', label: '剧本', media: 'script', acceptsKinds: ['script'], role: 'source', order: 0, ordered: false, min: 1, max: 1, required: true }),
      port({ id: 'reference', label: '参考图', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 1, ordered: true, min: 0, max: 9, required: false }),
    ],
    outputs: [port({ id: 'shots', label: '镜头', media: 'shot', acceptsKinds: ['shot'], role: 'result', order: 0, ordered: true, min: 0, max: Infinity, required: false })],
    actions: [action('plan', '生成镜头', 'storyboard.plan', 'inline-data')],
    createDefaultData: () => ({ ...data('storyboardPlanner', '分镜拆解', '#ff8a3d'), shots: [], isGenerating: false }), renderer,
  }),
  shot: define({
    kind: 'shot', title: '镜头', category: '分镜', icon: 'film', defaultSize: { width: 360, height: 300 },
    inputs: [
      port({ id: 'origin', label: '分镜来源', media: 'shot', acceptsKinds: ['storyboardPlanner'], role: 'origin', order: 0, ordered: false, min: 0, max: 1, required: false }),
      port({ id: 'reference', label: '参考', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 1, ordered: true, min: 0, max: 9, required: false }),
    ],
    outputs: [outputImage(), outputVideo()],
    actions: [action('generateImage', '生成图片', 'image.generate', 'media-nodes'), action('generateVideo', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('shot', '镜头', '#ffb3a1'), shotId: '', shotKey: '', shotOrdinal: 0, imagePrompt: '', videoPrompt: '', negativePrompt: '' }), renderer,
  }),
  textToImage: define({
    kind: 'textToImage', title: '文生图', category: '图像生成', icon: 'sparkles', defaultSize: { width: 520, height: 420 },
    inputs: [port({ id: 'prompt', label: '提示词', media: 'text', acceptsKinds: ['text', 'script'], role: 'prompt', order: 0, ordered: false, min: 0, max: 1, required: false })],
    outputs: [outputImage()], actions: [action('generate', '生成图片', 'image.generate', 'media-nodes')],
    createDefaultData: () => data('textToImage', '文生图', '#b48cff'), renderer,
  }),
  imageToImage: define({
    kind: 'imageToImage', title: '图生图', category: '图像生成', icon: 'images', defaultSize: { width: 520, height: 420 },
    inputs: [sourceImage('source'), port({ id: 'reference', label: '参考图', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 1, ordered: true, min: 0, max: 5, required: false })],
    outputs: [outputImage()], actions: [action('generate', '生成图片', 'image.redraw', 'media-nodes')],
    createDefaultData: () => data('imageToImage', '图生图', '#b48cff'), renderer,
  }),
  batchImageRefine: define({
    kind: 'batchImageRefine', title: '批量完善图片', category: '图像生成', icon: 'layout-grid', defaultSize: { width: 560, height: 460 },
    inputs: [port({ id: 'images', label: '源图片', media: 'image', acceptsKinds: ['image'], role: 'source', order: 0, ordered: true, min: 1, max: 12, required: true })],
    outputs: [outputImage()], actions: [action('refineBatch', '批量完善', 'image.redraw', 'media-nodes')],
    createDefaultData: () => data('batchImageRefine', '批量完善图片', '#b48cff'), renderer,
  }),
  textToVideo: define({
    kind: 'textToVideo', title: '文生视频', category: '视频生成', icon: 'video', defaultSize: { width: 520, height: 420 },
    inputs: [port({ id: 'prompt', label: '提示词', media: 'text', acceptsKinds: ['text', 'script'], role: 'prompt', order: 0, ordered: false, min: 0, max: 1, required: false })],
    outputs: [outputVideo()], actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('textToVideo', '文生视频', '#8f9cff'), videoMode: 'text2video' }), renderer,
  }),
  imageToVideo: define({
    kind: 'imageToVideo', title: '图生视频', category: '视频生成', icon: 'video', defaultSize: { width: 520, height: 420 },
    inputs: [port({ id: 'first', label: '首帧', media: 'image', acceptsKinds: ['image'], role: 'first_frame', order: 0, ordered: false, min: 1, max: 1, required: true })],
    outputs: [outputVideo()], actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('imageToVideo', '图生视频', '#8f9cff'), videoMode: 'image2video' }), renderer,
  }),
  firstLastFrameToVideo: define({
    kind: 'firstLastFrameToVideo', title: '首尾帧生视频', category: '视频生成', icon: 'video', defaultSize: { width: 560, height: 420 },
    inputs: [
      port({ id: 'first', label: '首帧', media: 'image', acceptsKinds: ['image'], role: 'first_frame', order: 0, ordered: false, min: 1, max: 1, required: true }),
      port({ id: 'last', label: '尾帧', media: 'image', acceptsKinds: ['image'], role: 'last_frame', order: 1, ordered: false, min: 1, max: 1, required: true }),
    ],
    outputs: [outputVideo()], actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('firstLastFrameToVideo', '首尾帧生视频', '#8f9cff'), videoMode: 'firstLastFrame' }), renderer,
  }),
  referenceToVideo: define({
    kind: 'referenceToVideo', title: '参考物生视频', category: '视频生成', icon: 'scan-search', defaultSize: { width: 560, height: 460 },
    inputs: [
      port({ id: 'reference', label: '参考图', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 0, ordered: true, min: 1, max: 6, required: true }),
      port({ id: 'video', label: '参考视频', media: 'video', acceptsKinds: ['video'], role: 'video_reference', order: 1, ordered: false, min: 0, max: 1, required: false }),
    ],
    outputs: [outputVideo()], actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('referenceToVideo', '参考物生视频', '#8f9cff'), videoMode: 'fullReference' }), renderer,
  }),
};

export function getNodeDefinition(kind: string | undefined): NodeDefinition | undefined {
  if (!kind) return undefined;
  const alias: Record<string, CanvasNodeKind> = {
    prompt: 'text',
    imageGen: 'textToImage',
    videoGen: 'referenceToVideo',
    storyboard: 'storyboardPlanner',
    storyboardGen: 'storyboardPlanner',
  };
  const normalized = alias[kind] ?? kind;
  return CANVAS_NODE_DEFINITIONS[normalized as CanvasNodeKind];
}

export function listNodeDefinitions(): NodeDefinition[] {
  return Object.values(CANVAS_NODE_DEFINITIONS);
}

export function isCanvasNodeKind(kind: string): kind is CanvasNodeKind {
  return kind in CANVAS_NODE_DEFINITIONS;
}

const activeEdges = (graph: CanvasGraphV2) =>
  graph.edges.filter((edge) => edge.data?.migrationStatus !== 'migration_invalid');

const findPort = (ports: NodePort[], id: string | null | undefined) =>
  id ? ports.find((item) => item.id === id) : undefined;

const withOrderedPortFallback = (edges: CanvasEdgeV2[]) => {
  const used = new Set(edges.map((edge) => edge.data?.portOrder).filter((order): order is number => Number.isInteger(order)));
  let next = 0;
  return edges.map((edge) => {
    const explicit = edge.data?.portOrder;
    if (Number.isInteger(explicit)) return { edge, order: explicit! };
    while (used.has(next)) next += 1;
    const order = next++;
    used.add(order);
    return { edge, order };
  }).sort((left, right) => left.order - right.order);
};

const hasPath = (edges: CanvasEdgeV2[], from: string, to: string): boolean => {
  const adjacency = new Map<string, string[]>();
  for (const edge of edges) {
    const next = adjacency.get(edge.source) ?? [];
    next.push(edge.target);
    adjacency.set(edge.source, next);
  }
  const queue = [from];
  const visited = new Set<string>();
  while (queue.length) {
    const current = queue.shift()!;
    if (current === to) return true;
    if (visited.has(current)) continue;
    visited.add(current);
    queue.push(...(adjacency.get(current) ?? []));
  }
  return false;
};

export function validateEdge(
  graph: CanvasGraphV2,
  proposed: CanvasEdgeV2,
  _mode: ConnectionMode,
): ConnectionValidation {
  if (proposed.source === proposed.target) return { ok: false, code: 'SELF_LOOP', message: '节点不能连接自身' };
  const sourceNode = graph.nodes.find((node) => node.id === proposed.source);
  const targetNode = graph.nodes.find((node) => node.id === proposed.target);
  if (!sourceNode || !targetNode) return { ok: false, code: 'NODE_NOT_FOUND', message: '源节点或目标节点不存在' };
  if (!proposed.sourceHandle || !proposed.targetHandle) {
    return { ok: false, code: 'HANDLE_REQUIRED', message: 'V2 连线必须指定 sourceHandle 和 targetHandle' };
  }
  const sourceDef = getNodeDefinition(sourceNode.data.kind);
  const targetDef = getNodeDefinition(targetNode.data.kind);
  const sourcePort = findPort(sourceDef?.outputs ?? [], proposed.sourceHandle);
  const targetPort = findPort(targetDef?.inputs ?? [], proposed.targetHandle);
  if (!sourcePort || !targetPort) return { ok: false, code: 'PORT_NOT_FOUND', message: '找不到对应的输出端口或输入端口' };
  if (!targetPort.acceptsKinds.includes(sourceNode.data.kind)) {
    return { ok: false, code: 'PORT_KIND_MISMATCH', message: `${targetPort.label} 不接受 ${sourceDef?.title ?? sourceNode.data.kind}` };
  }
  if (sourcePort.media !== targetPort.media) {
    return { ok: false, code: 'PORT_MEDIA_MISMATCH', message: '输出端口与输入端口媒体类型不一致' };
  }
  const edges = activeEdges(graph);
  if (edges.some((edge) =>
    edge.source === proposed.source
    && edge.target === proposed.target
    && edge.sourceHandle === proposed.sourceHandle
    && edge.targetHandle === proposed.targetHandle)) {
    return { ok: false, code: 'DUPLICATE_EDGE', message: '该连线已存在' };
  }
  const targetEdges = edges.filter((edge) => edge.target === proposed.target && edge.targetHandle === proposed.targetHandle);
  if (Number.isFinite(targetPort.max) && targetEdges.length >= targetPort.max) {
    return { ok: false, code: 'PORT_LIMIT_EXCEEDED', message: `${targetPort.label} 最多连接 ${targetPort.max} 条` };
  }
  if (targetPort.ordered) {
    const order = proposed.data?.portOrder;
    if (!Number.isInteger(order) || order === undefined || order < 0) {
      return { ok: false, code: 'PORT_ORDER_REQUIRED', message: `${targetPort.label} 需要 portOrder` };
    }
    if (targetEdges.some((edge) => edge.data?.portOrder === order)) {
      return { ok: false, code: 'PORT_ORDER_CONFLICT', message: `${targetPort.label} 的顺序 ${order} 已被占用` };
    }
  }
  if (hasPath(edges, proposed.target, proposed.source)) {
    return { ok: false, code: 'CYCLE', message: '该连线会形成循环依赖' };
  }
  return { ok: true };
}

export function validateRunnable(
  graph: CanvasGraphV2,
  nodeId: string,
  actionId: string,
): ConnectionValidation {
  const node = graph.nodes.find((item) => item.id === nodeId);
  if (!node) return { ok: false, code: 'NODE_NOT_FOUND', message: '节点不存在' };
  const definition = getNodeDefinition(node.data.kind);
  if (!definition?.actions.some((item) => item.id === actionId)) {
    return { ok: false, code: 'ACTION_NOT_FOUND', message: '节点动作不存在' };
  }
  const edges = activeEdges(graph);
  const imageToImage = node.data.kind === 'imageToImage';
  if (imageToImage && !edges.some((edge) =>
    edge.target === nodeId && (edge.targetHandle === 'source' || edge.targetHandle === 'reference'))) {
    return { ok: false, code: 'INPUT_REQUIRED', message: '图片至少需要 1 个输入' };
  }
  for (const input of definition.inputs) {
    if (imageToImage && input.id === 'source') continue;
    const bound = edges.filter((edge) => edge.target === nodeId && edge.targetHandle === input.id);
    if (bound.length < input.min) {
      return { ok: false, code: 'INPUT_REQUIRED', message: `${input.label} 至少需要 ${input.min} 个输入` };
    }
    if (input.ordered) {
      const orders = withOrderedPortFallback(bound).map((item) => item.order);
      if (orders.some((order, index) => order !== index)) {
        return { ok: false, code: 'PORT_ORDER_REQUIRED', message: `${input.label} 的顺序必须从 0 连续排列` };
      }
    }
  }
  return { ok: true };
}

export function getInputBindings(
  graph: CanvasGraphV2,
  nodeId: string,
  actionId: string,
): NodeRunContext['inputBindings'] {
  const node = graph.nodes.find((item) => item.id === nodeId);
  const definition = getNodeDefinition(node?.data.kind);
  if (!definition?.actions.some((item) => item.id === actionId)) return {};
  const result: NodeRunContext['inputBindings'] = {};
  for (const input of definition.inputs) {
    const bound = activeEdges(graph).filter((edge) => edge.target === nodeId && edge.targetHandle === input.id);
    const ordered = input.ordered ? withOrderedPortFallback(bound) : bound.map((edge, order) => ({ edge, order }));
    result[input.id] = ordered
      .sort((a, b) => a.order - b.order)
      .map(({ edge, order }) => {
        const source = graph.nodes.find((item) => item.id === edge.source);
        return {
          nodeId: edge.source,
          assetId: typeof source?.data.assetId === 'string' ? source.data.assetId : undefined,
          role: input.role,
          order,
        };
      });
  }
  return result;
}

export const NODE_STATUS_VALUES: NodeStatus[] = ['idle', 'queued', 'running', 'success', 'error', 'cancelled'];
