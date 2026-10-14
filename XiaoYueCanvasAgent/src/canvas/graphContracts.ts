import type { ComponentType } from 'react';
import type { ResultSlotBinding } from './resultSlots';
import type { XYPosition, Viewport } from '@xyflow/react';
import type { MediaNodeData, NodeStatus } from '../types';
import {
  DEFAULT_IMAGE_GENERATION_MODE,
  DEFAULT_VIDEO_GENERATION_MODE,
  GENERATION_KIND_ALIASES,
  IMAGE_GENERATION_KIND,
  LEGACY_GENERATION_PORT_ALIASES,
  LEGACY_GENERATION_PORT_ORDER,
  MERGED_GENERATION_SOURCE_KINDS,
  VIDEO_GENERATION_KIND,
  generationModeInputRule,
  mergedGenerationKind,
  resolveNodeMode,
} from './generationModes';

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
  | 'referenceToVideo'
  | 'imageGeneration'
  | 'videoGeneration'
  | 'videoMerge';

export type LegacyNodeKind =
  | 'videoGen'
  | 'prompt'
  | 'sequence'
  | 'storyboard'
  | 'storyboardGen'
  | 'imageGen';

export type NodeCategory = '输入' | '分镜' | '图像生成' | '视频生成' | '视频处理' | '辅助';
export type PortMedia = 'text' | 'script' | 'storyboard' | 'shot' | 'image' | 'video';
export type ConnectionMode = 'user' | 'agent' | 'pipeline' | 'migration';
export type ResultPolicy = 'media-nodes' | 'inline-data' | 'none';
export type MediaFlowRole =
  | 'input'
  | 'producer'
  | 'mediaPlaceholder'
  | 'storyboardPlaceholder'
  | 'processor'
  | 'utility';

export interface MediaPlaceholderContract {
  media: 'image' | 'video';
  inputPort: 'origin';
  outputPort: 'image' | 'video';
}

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

export interface MissingNodeInput {
  portId: string;
  label: string;
  media: PortMedia;
  required: boolean;
  min: number;
  bound: number;
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
    resultSlot?: boolean;
    resultSlotOrder?: number;
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
  resultSlots?: ResultSlotBinding[];
  resultBindings?: Record<string, string>;
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
  executions?: CanvasExecution[];
}

export type AutomationPhase = 'idle' | 'ready' | 'queued' | 'running' | 'paused' | 'failed' | 'blocked' | 'stale' | 'done';

export interface AutomationState {
  phase: AutomationPhase;
  configFingerprint?: string;
  runFingerprint?: string;
  paused?: boolean;
  error?: string;
}

export type ExecutionStatus =
  | 'pending'
  | 'ready'
  | 'running'
  | 'succeeded'
  | 'failed'
  | 'blocked'
  | 'cancelled'
  | 'interrupted';

export interface ExecutionNodeState {
  nodeId: string;
  actionId: string;
  status: ExecutionStatus;
  jobId?: string;
  generationRecordId?: string;
  error?: string;
  startedAt?: number;
  completedAt?: number;
}

export interface CanvasExecution {
  id: string;
  projectId: string;
  canvasId: string;
  nodeIds: string[];
  status: ExecutionStatus;
  maxParallel: number;
  nodes: ExecutionNodeState[];
  createdAt: number;
  updatedAt: number;
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
    actions: noneAction(), createDefaultData: () => data('text', '文字', '#cacaca'), renderer,
  }),
  script: define({
    kind: 'script', title: '剧本', category: '输入', icon: 'file-text', defaultSize: { width: 420, height: 320 },
    // 输出与文字同媒体：文字即剧本，存量剧本节点仍能接分镜拆解
    inputs: [], outputs: [port({ id: 'script', label: '剧本', media: 'text', acceptsKinds: [], role: 'source', order: 0, ordered: false, min: 0, max: Infinity, required: false })],
    actions: noneAction(), createDefaultData: () => ({ ...data('script', '剧本', '#c5c5c5'), scriptText: '' }), renderer,
  }),
  note: define({
    kind: 'note', title: '批注', category: '辅助', icon: 'message-square', defaultSize: { width: 250, height: 250 },
    inputs: [], outputs: [], actions: noneAction(),
    createDefaultData: () => ({ ...data('note', '批注', '#e7e7e7'), richText: '' }), renderer,
  }),
  image: define({
    kind: 'image', title: '图片', category: '输入', icon: 'image', defaultSize: { width: 250, height: 250 },
    inputs: [port({ id: 'origin', label: '生成来源', media: 'image', acceptsKinds: ['imageGeneration', 'textToImage', 'imageToImage', 'batchImageRefine', 'shot'], role: 'origin', order: 0, ordered: false, min: 0, max: 1, required: false })],
    outputs: [outputImage()], actions: noneAction(),
    createDefaultData: () => data('image', '图片', '#929292'), renderer,
  }),
  video: define({
    kind: 'video', title: '视频', category: '输入', icon: 'video', defaultSize: { width: 420, height: 236 },
    inputs: [port({ id: 'origin', label: '生成来源', media: 'video', acceptsKinds: ['videoGeneration', 'textToVideo', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo', 'shot'], role: 'origin', order: 0, ordered: false, min: 0, max: 1, required: false })],
    outputs: [outputVideo()], actions: noneAction(),
    createDefaultData: () => data('video', '视频', '#a0a0a0'), renderer,
  }),
  storyboardPlanner: define({
    kind: 'storyboardPlanner', title: '分镜拆解', category: '分镜', icon: 'clapperboard', defaultSize: { width: 520, height: 420 },
    inputs: [
      // 入口改收文字：剧本节点已下架，文字节点直接可接
      port({ id: 'script', label: '文字', media: 'text', acceptsKinds: ['text', 'script'], role: 'source', order: 0, ordered: false, min: 1, max: 1, required: true }),
      port({ id: 'reference', label: '参考图', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 1, ordered: true, min: 0, max: 9, required: false }),
    ],
    outputs: [port({ id: 'shots', label: '镜头', media: 'shot', acceptsKinds: ['shot'], role: 'result', order: 0, ordered: true, min: 0, max: Infinity, required: false })],
    actions: [action('plan', '生成镜头', 'storyboard.plan', 'inline-data')],
    createDefaultData: () => ({ ...data('storyboardPlanner', '分镜拆解', '#9d9d9d'), shots: [], isGenerating: false }), renderer,
  }),
  shot: define({
    kind: 'shot', title: '镜头', category: '分镜', icon: 'film', defaultSize: { width: 360, height: 300 },
    inputs: [
      port({ id: 'origin', label: '分镜来源', media: 'shot', acceptsKinds: ['storyboardPlanner'], role: 'origin', order: 0, ordered: false, min: 0, max: 1, required: false }),
      port({ id: 'reference', label: '参考', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 1, ordered: true, min: 0, max: 9, required: false }),
    ],
    outputs: [outputImage(), outputVideo()],
    actions: [action('generateImage', '生成图片', 'image.generate', 'media-nodes'), action('generateVideo', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('shot', '镜头', '#c2c2c2'), shotId: '', shotKey: '', shotOrdinal: 0, imagePrompt: '', videoPrompt: '', negativePrompt: '' }), renderer,
  }),
  textToImage: define({
    kind: 'textToImage', title: '文生图', category: '图像生成', icon: 'sparkles', defaultSize: { width: 520, height: 420 },
    inputs: [port({ id: 'prompt', label: '提示词', media: 'text', acceptsKinds: ['text', 'script'], role: 'prompt', order: 0, ordered: false, min: 0, max: 1, required: false })],
    outputs: [outputImage()], actions: [action('generate', '生成图片', 'image.generate', 'media-nodes')],
    createDefaultData: () => data('textToImage', '文生图', '#9d9d9d'), renderer,
  }),
  imageToImage: define({
    kind: 'imageToImage', title: '图生图', category: '图像生成', icon: 'images', defaultSize: { width: 520, height: 420 },
    inputs: [sourceImage('source'), port({ id: 'reference', label: '参考图', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 1, ordered: true, min: 0, max: 5, required: false })],
    outputs: [outputImage()], actions: [action('generate', '生成图片', 'image.redraw', 'media-nodes')],
    createDefaultData: () => data('imageToImage', '图生图', '#9d9d9d'), renderer,
  }),
  batchImageRefine: define({
    kind: 'batchImageRefine', title: '批量完善图片', category: '图像生成', icon: 'layout-grid', defaultSize: { width: 560, height: 460 },
    inputs: [port({ id: 'images', label: '源图片', media: 'image', acceptsKinds: ['image'], role: 'source', order: 0, ordered: true, min: 1, max: 12, required: true })],
    outputs: [outputImage()], actions: [action('refineBatch', '批量完善', 'image.redraw', 'media-nodes')],
    createDefaultData: () => data('batchImageRefine', '批量完善图片', '#9d9d9d'), renderer,
  }),
  textToVideo: define({
    kind: 'textToVideo', title: '文生视频', category: '视频生成', icon: 'video', defaultSize: { width: 520, height: 420 },
    inputs: [port({ id: 'prompt', label: '提示词', media: 'text', acceptsKinds: ['text', 'script'], role: 'prompt', order: 0, ordered: false, min: 0, max: 1, required: false })],
    outputs: [outputVideo()], actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('textToVideo', '文生视频', '#a0a0a0'), videoMode: 'text2video' }), renderer,
  }),
  imageToVideo: define({
    kind: 'imageToVideo', title: '图生视频', category: '视频生成', icon: 'video', defaultSize: { width: 520, height: 420 },
    inputs: [port({ id: 'first', label: '首帧', media: 'image', acceptsKinds: ['image'], role: 'first_frame', order: 0, ordered: false, min: 1, max: 1, required: true })],
    outputs: [outputVideo()], actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('imageToVideo', '图生视频', '#a0a0a0'), videoMode: 'image2video' }), renderer,
  }),
  firstLastFrameToVideo: define({
    kind: 'firstLastFrameToVideo', title: '首尾帧生视频', category: '视频生成', icon: 'video', defaultSize: { width: 560, height: 420 },
    inputs: [
      port({ id: 'first', label: '首帧', media: 'image', acceptsKinds: ['image'], role: 'first_frame', order: 0, ordered: false, min: 1, max: 1, required: true }),
      port({ id: 'last', label: '尾帧', media: 'image', acceptsKinds: ['image'], role: 'last_frame', order: 1, ordered: false, min: 1, max: 1, required: true }),
    ],
    outputs: [outputVideo()], actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('firstLastFrameToVideo', '首尾帧生视频', '#a0a0a0'), videoMode: 'firstLastFrame' }), renderer,
  }),
  referenceToVideo: define({
    kind: 'referenceToVideo', title: '参考物生视频', category: '视频生成', icon: 'scan-search', defaultSize: { width: 560, height: 460 },
    inputs: [
      port({ id: 'reference', label: '参考图', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 0, ordered: true, min: 1, max: 6, required: true }),
      port({ id: 'video', label: '参考视频', media: 'video', acceptsKinds: ['video'], role: 'video_reference', order: 1, ordered: false, min: 0, max: 1, required: false }),
    ],
    outputs: [outputVideo()], actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data('referenceToVideo', '参考物生视频', '#a0a0a0'), videoMode: 'fullReference' }), renderer,
  }),
  // ── 合并后的生成节点（节点库只展示这两个；旧 kind 走 getNodeDefinition 别名归一）──
  // 端口刻意取宽（文/图/视频都能连），真正的必填与上限由 generationModes 的「模式规则」决定，
  // 见 validateRunnable / getMissingRequiredInputs。
  videoGeneration: define({
    kind: 'videoGeneration', title: '视频生成', category: '视频生成', icon: 'video', defaultSize: { width: 480, height: 520 },
    inputs: [
      port({ id: 'prompt', label: '提示词', media: 'text', acceptsKinds: ['text', 'script'], role: 'prompt', order: 0, ordered: false, min: 0, max: 1, required: false }),
      port({ id: 'reference', label: '参考图', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 1, ordered: true, min: 0, max: 9, required: false }),
      port({ id: 'video', label: '参考视频', media: 'video', acceptsKinds: ['video'], role: 'video_reference', order: 2, ordered: false, min: 0, max: 1, required: false }),
    ],
    outputs: [outputVideo()],
    actions: [action('generate', '生成视频', 'video.generate', 'media-nodes')],
    createDefaultData: () => ({ ...data(VIDEO_GENERATION_KIND, '视频生成', '#a0a0a0'), mode: DEFAULT_VIDEO_GENERATION_MODE }),
    renderer,
  }),
  imageGeneration: define({
    kind: 'imageGeneration', title: '图片生成', category: '图像生成', icon: 'sparkles', defaultSize: { width: 480, height: 560 },
    inputs: [
      port({ id: 'prompt', label: '提示词', media: 'text', acceptsKinds: ['text', 'script'], role: 'prompt', order: 0, ordered: false, min: 0, max: 1, required: false }),
      port({ id: 'reference', label: '参考图', media: 'image', acceptsKinds: ['image'], role: 'reference', order: 1, ordered: true, min: 0, max: 14, required: false }),
    ],
    outputs: [outputImage()],
    actions: [
      action('generate', '生成图片', 'image.generate', 'media-nodes'),
      action('redraw', '图生图', 'image.redraw', 'media-nodes'),
    ],
    createDefaultData: () => ({ ...data(IMAGE_GENERATION_KIND, '图片生成', '#9d9d9d'), mode: DEFAULT_IMAGE_GENERATION_MODE }),
    renderer,
  }),
  videoMerge: define({
    kind: 'videoMerge', title: '视频合并', category: '视频处理', icon: 'list-video', defaultSize: { width: 560, height: 440 },
    inputs: [port({
      id: 'clips',
      label: '视频片段',
      media: 'video',
      acceptsKinds: ['video', 'videoGeneration', 'textToVideo', 'imageToVideo', 'firstLastFrameToVideo', 'referenceToVideo', 'shot', 'videoMerge'],
      role: 'source',
      order: 0,
      ordered: true,
      min: 2,
      max: 12,
      required: true,
    })],
    outputs: [outputVideo()],
    actions: [action('mergeVideo', '合并视频', 'video.merge', 'media-nodes', false)],
    createDefaultData: () => data('videoMerge', '视频合并', '#a0a0a0'),
    renderer,
  }),
};

export function getNodeDefinition(kind: string | undefined): NodeDefinition | undefined {
  if (!kind) return undefined;
  const alias: Record<string, CanvasNodeKind> = {
    prompt: 'text',
    storyboard: 'storyboardPlanner',
    storyboardGen: 'storyboardPlanner',
    // 生成的四个视频节点 / 三个图片节点已合并（见 generationModes）
    ...GENERATION_KIND_ALIASES,
  };
  const normalized = alias[kind] ?? kind;
  return CANVAS_NODE_DEFINITIONS[normalized as CanvasNodeKind];
}

/**
 * 旧图里的端口 id 归一到合并后的端口（first/last/source/images → reference）。
 * 只有生成节点的目标端口需要归一；其余节点原样返回。
 */
export function normalizeGenerationPortId(
  targetKind: string | undefined,
  handleId: string | null | undefined,
): string | undefined {
  if (!handleId) return undefined;
  if (!mergedGenerationKind(targetKind)) return handleId;
  return LEGACY_GENERATION_PORT_ALIASES[handleId] ?? handleId;
}

export function getNodeFlowRole(kind: string | undefined): MediaFlowRole | undefined {
  const normalized = getNodeDefinition(kind)?.kind;
  if (!normalized) return undefined;
  if (normalized === 'text' || normalized === 'script') return 'input';
  if (normalized === 'image' || normalized === 'video') return 'mediaPlaceholder';
  if (normalized === 'shot') return 'storyboardPlaceholder';
  if (normalized === 'note') return 'utility';
  if (normalized === 'videoMerge') return 'producer';
  if (normalized === 'storyboardPlanner') return 'producer';
  if (getNodeDefinition(normalized)?.actions.some((item) => item.automation.startable)) return 'producer';
  return 'processor';
}

export function getMediaPlaceholderContract(kind: string | undefined): MediaPlaceholderContract | undefined {
  const normalized = getNodeDefinition(kind)?.kind;
  if (normalized === 'image') {
    return { media: 'image', inputPort: 'origin', outputPort: 'image' };
  }
  if (normalized === 'video') {
    return { media: 'video', inputPort: 'origin', outputPort: 'video' };
  }
  return undefined;
}

/** 不再出现在节点库 / 右键菜单的 kind（定义保留，存量画布照旧渲染）。 */
export const MENU_HIDDEN_NODE_KINDS: ReadonlySet<string> = new Set(['script', 'note']);

/**
 * 正文就地编辑的纯文字 kind。这些节点默认渲染静态预览面（整块可拖动），
 * 双击才切进 textarea 输入态，失焦或 Esc 退出。
 * 集中在这里是为了让「谁渲染成文字节点」（MediaNode）和
 * 「双击是否让位给编辑」（CanvasWorkspace）用同一份判断，不再各写各的。
 */
export const TEXT_EDIT_NODE_KINDS: ReadonlySet<string> = new Set(['text', 'note', 'prompt']);

export function listNodeDefinitions(): NodeDefinition[] {
  // 已合并的旧生成节点、以及「剧本」都仍然保留定义（存量数据可渲染），但不再出现在节点库/右键菜单里。
  // 剧本下架原因：文字即剧本，分镜拆解改收文字节点。
  // 批注下架原因：既不参与连线也不能生成，画布上没有它能做的事。
  return Object.values(CANVAS_NODE_DEFINITIONS).filter((definition) =>
    !MERGED_GENERATION_SOURCE_KINDS.has(definition.kind) && !MENU_HIDDEN_NODE_KINDS.has(definition.kind));
}

export function isCanvasNodeKind(kind: string): kind is CanvasNodeKind {
  return kind in CANVAS_NODE_DEFINITIONS;
}

export function isMergedGenerationKind(kind: string | undefined): boolean {
  return Boolean(mergedGenerationKind(kind));
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

/** 某个输入端口在当前节点模式下的最小连线数（生成节点按模式取值，其余沿用端口声明）。 */
const requiredCountForPort = (kind: string | undefined, data: Record<string, unknown>, port: NodePort): number => {
  const rule = generationModeInputRule(kind, data);
  if (!rule) return port.min;
  if (port.id === 'reference') return rule.referenceMin;
  if (port.id === 'video') return rule.videoMin;
  return port.min;
};

const missingInputMessage = (input: NodePort, required: number): string =>
  input.id === 'reference' && required > 1
    ? `需要至少 ${required} 张图片`
    : `${input.label} 至少需要 ${required} 个输入`;

/** 目标节点上该端口当前的连线（兼容旧端口 id）。 */
const edgesIntoPort = (edges: CanvasEdgeV2[], nodeId: string, targetKind: string | undefined, portId: string) =>
  edges.filter((edge) => edge.target === nodeId && normalizeGenerationPortId(targetKind, edge.targetHandle) === portId);

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
  const targetHandle = normalizeGenerationPortId(targetNode.data.kind, proposed.targetHandle);
  if (!proposed.sourceHandle || !proposed.targetHandle || !targetHandle) {
    return { ok: false, code: 'HANDLE_REQUIRED', message: 'V2 连线必须指定 sourceHandle 和 targetHandle' };
  }
  const sourceDef = getNodeDefinition(sourceNode.data.kind);
  const targetDef = getNodeDefinition(targetNode.data.kind);
  const sourcePort = findPort(sourceDef?.outputs ?? [], proposed.sourceHandle);
  const targetPort = findPort(targetDef?.inputs ?? [], targetHandle);
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
    && normalizeGenerationPortId(targetNode.data.kind, edge.targetHandle) === targetHandle)) {
    return { ok: false, code: 'DUPLICATE_EDGE', message: '该连线已存在' };
  }
  const targetEdges = edges.filter((edge) =>
    edge.target === proposed.target && normalizeGenerationPortId(targetNode.data.kind, edge.targetHandle) === targetHandle);
  const modeRule = generationModeInputRule(targetNode.data.kind, targetNode.data);
  const modeLimit = modeRule
    ? targetPort.id === 'reference'
      ? modeRule.referenceMax
      : targetPort.id === 'video'
        ? modeRule.videoMax
        : undefined
    : undefined;
  const maxEdges = modeLimit === undefined ? targetPort.max : Math.min(targetPort.max, modeLimit);
  if (Number.isFinite(maxEdges) && targetEdges.length >= maxEdges) {
    return { ok: false, code: 'PORT_LIMIT_EXCEEDED', message: `${targetPort.label} 最多连接 ${maxEdges} 条` };
  }
  if (targetPort.ordered) {
    // 旧端口自带顺序（first → 0 / last → 1），存量连线没有 portOrder 也按它判定
    const impliedOrder = mergedGenerationKind(targetNode.data.kind)
      ? LEGACY_GENERATION_PORT_ORDER[proposed.targetHandle]
      : undefined;
    const order = proposed.data?.portOrder ?? impliedOrder;
    if (!Number.isInteger(order) || order === undefined || order < 0) {
      return { ok: false, code: 'PORT_ORDER_REQUIRED', message: `${targetPort.label} 需要 portOrder` };
    }
    if (targetEdges.some((edge) => (edge.data?.portOrder
      ?? (mergedGenerationKind(targetNode.data.kind) ? LEGACY_GENERATION_PORT_ORDER[edge.targetHandle ?? ''] : undefined)) === order)) {
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
  for (const input of definition.inputs) {
    const bound = edgesIntoPort(edges, nodeId, node.data.kind, input.id);
    const required = requiredCountForPort(node.data.kind, node.data, input);
    if (bound.length < required) {
      return { ok: false, code: 'INPUT_REQUIRED', message: missingInputMessage(input, required) };
    }
    const rule = generationModeInputRule(node.data.kind, node.data);
    if (rule && input.id === 'reference' && bound.length > rule.referenceMax) {
      return {
        ok: false,
        code: 'PORT_LIMIT_EXCEEDED',
        message: resolveNodeMode(node.data.kind, node.data) === 'firstLastFrame'
          ? '首尾帧只支持首帧与尾帧两张图片'
          : `参考图最多 ${rule.referenceMax} 张`,
      };
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

export function getMissingRequiredInputs(
  graph: CanvasGraphV2,
  nodeId: string,
): MissingNodeInput[] {
  const node = graph.nodes.find((item) => item.id === nodeId);
  const definition = getNodeDefinition(node?.data.kind);
  if (!definition) return [];
  const edges = activeEdges(graph);
  const missing: MissingNodeInput[] = [];

  for (const input of definition.inputs) {
    const bound = edgesIntoPort(edges, nodeId, node?.data.kind, input.id).length;
    const required = requiredCountForPort(node?.data.kind, node?.data ?? {}, input);
    if (bound >= required) continue;
    missing.push({
      portId: input.id,
      label: input.label,
      media: input.media,
      // 生成节点的必填由模式决定：端口本身是可选，但当前模式要求它时必须如实上报
      required: input.required || required > 0,
      min: required,
      bound,
    });
  }
  return missing;
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
    const bound = edgesIntoPort(activeEdges(graph), nodeId, node?.data.kind, input.id);
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
