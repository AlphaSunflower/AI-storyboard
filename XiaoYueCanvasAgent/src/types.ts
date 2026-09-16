import type { Edge, Node, Viewport } from '@xyflow/react';

export type NodeKind =
  | 'text'
  | 'image'
  | 'video'
  | 'videoGen'
  | 'script'
  | 'note'
  | 'prompt'
  | 'sequence'
  | 'storyboard'
  | 'storyboardGen'
  | 'imageGen'
  | 'storyboardPlanner'
  | 'shot'
  | 'textToImage'
  | 'imageToImage'
  | 'batchImageRefine'
  | 'textToVideo'
  | 'imageToVideo'
  | 'firstLastFrameToVideo'
  | 'referenceToVideo';
export type MediaKind = NodeKind;
export type NodeStatus = 'idle' | 'queued' | 'running' | 'success' | 'error' | 'cancelled';
export type SaveStatus = 'idle' | 'saving' | 'saved' | 'error';
export type MediaQuality = 'placeholder' | 'thumbnail' | 'preview';

export type StoryboardVideoStatus = 'idle' | 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'cancelling';

/** 单个分镜帧的视频生成参数配置（用户在行内可改）。 */
export interface StoryboardVideoConfig {
  model?: string;
  aspectRatio?: string;
  resolution?: string;
  size?: string;
  quality?: string;
  duration?: number;
}

/** 分镜节点中的一帧，支持行内上传参考图片/资产、修改提示词与参数、标记视频生成状态。 */
export interface StoryboardFrame {
  img?: string;
  label?: string;
  description?: string;
  imageUrl?: string;
  duration?: number;
  prompt?: string;
  config?: StoryboardVideoConfig;
  /** 上传到此帧的参考资产 id（图片/视频），生成视频时作为参考素材使用。 */
  assetIds?: string[];
  /** 上传到此帧的参考图片资产 id。 */
  imageAssetId?: string;
  /** 该帧生成的视频资产 id。 */
  videoAssetId?: string;
  /** 该帧生成的视频可展示 URL（object URL/data URL）。 */
  videoUrl?: string;
  videoStatus?: StoryboardVideoStatus;
  videoJobId?: string;
  /** 是否勾选（用于批量视频生成）。 */
  checked?: boolean;
  /** 该帧使用的视频生成模式（text2video/fullReference/image2video/…）。 */
  videoMode?: string;
}

export interface ImageOperation {
  type: 'import' | 'crop' | 'split' | 'pixels' | 'annotation' | 'flatten' | 'remote';
  params: Record<string, string | number | boolean>;
  at?: number;
}

export interface VideoGenerationParams {
  model: string;
  mode: string;
  aspectRatio: '16:9' | '9:16' | '1:1' | '4:3';
  resolution: '720p' | '1080p';
  durationSeconds: number;
  variations: number;
}

export interface VideoClip {
  id: string;
  assetId: string;
  startSeconds: number;
  endSeconds?: number;
}

export interface Point {
  x: number;
  y: number;
}

interface AnnotationBase {
  id: string;
  color: string;
  width: number;
}

export interface BrushShape extends AnnotationBase {
  type: 'brush';
  points: Point[];
}

export interface ArrowShape extends AnnotationBase {
  type: 'arrow';
  start: Point;
  end: Point;
}

export interface RectangleShape extends AnnotationBase {
  type: 'rectangle';
  start: Point;
  end: Point;
}

export interface TextShape extends AnnotationBase {
  type: 'text';
  point: Point;
  text: string;
  fontSize: number;
}

export type AnnotationShape = BrushShape | ArrowShape | RectangleShape | TextShape;

export interface AnnotationDocument {
  version: 1;
  sourceAssetId: string;
  shapes: AnnotationShape[];
}

export type RedrawMaskOperation = 'paint' | 'erase';

export interface RedrawBrushMark {
  id: string;
  type: 'brush';
  operation: RedrawMaskOperation;
  points: Point[];
  width: number;
}

export interface RedrawRectangleMark {
  id: string;
  type: 'rectangle';
  operation: RedrawMaskOperation;
  start: Point;
  end: Point;
}

export type RedrawMaskMark = RedrawBrushMark | RedrawRectangleMark;

export interface RedrawMaskDocument {
  version: 1;
  sourceAssetId: string;
  marks: RedrawMaskMark[];
}

export interface MediaNodeData extends Record<string, unknown> {
  kind: MediaKind;
  label: string;
  prompt: string;
  status: NodeStatus;
  accent: string;
  assetId?: string;
  mediaLoadState?: 'loading' | 'ready' | 'error';
  derivedFrom?: string;
  pinned?: boolean;
  operation?: ImageOperation;
  annotation?: AnnotationDocument;
  videoPrompt?: string;
  videoMode?: string;
  videoReferences?: string[];
  videoParams?: VideoGenerationParams;
  playlist?: VideoClip[];
  playlistEnabled?: boolean;
  starred?: boolean;
  // ── 新节点类型字段（script/note/prompt/sequence）──
  scriptText?: string;
  richText?: string;
  promptText?: string;
  children?: string[];
  // ── 分镜（storyboard）字段──
  frames?: StoryboardFrame[];
  shotId?: string;
  shotKey?: string;
  shotOrdinal?: number;
  imagePrompt?: string;
  negativePrompt?: string;
  sceneDescription?: string;
  shotType?: string;
  cameraMovement?: string;
  durationSeconds?: number;
  referenceHints?: string[];
  referenceAssetIds?: string[];
  useReferenceImages?: boolean;
  shots?: import('./canvas/storyboardPlan').StoryboardShotPlan[];
  // ── 分镜生成（storyboardGen）字段──
  rows?: number;
  cols?: number;
  styleType?: '2d-anime' | '3d-cg' | 'live-action';
  imagePreset?: string;
  model?: string;
  isGenerating?: boolean;
}

export interface PersistedAsset {
  id: string;
  kind: 'image' | 'video';
  name: string;
  mimeType: string;
  bytes: number;
  width: number;
  height: number;
  durationSeconds?: number;
  originalBlob: Blob;
  thumbnailBlob: Blob;
  previewBlob: Blob;
  derivedFrom?: string;
  operation?: ImageOperation;
  projectId?: string;
  canvasId?: string;
  libraryState: 'project' | 'global';
  createdAt: number;
}

export type AssetMetadata = Omit<PersistedAsset, 'originalBlob' | 'thumbnailBlob' | 'previewBlob'>;

// Runtime media URLs are acquired only while a media surface is visible.
export type MediaAsset = PersistedAsset;

export type CanvasNode = Node<MediaNodeData, 'media' | 'video' | import('./canvas/graphContracts').CanvasNodeKind>;

// 边携带简略的操作标签，用于在连线上展示因果链的"操作短名"。
export type CanvasEdgeData = {
  operation?: ImageOperation['type'];
  portOrder?: number;
  resultSlot?: boolean;
  resultSlotOrder?: number;
};

export type CanvasEdge = Edge<CanvasEdgeData>;

export interface GraphSnapshot {
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  generationRecords?: import('./canvas/graphContracts').GenerationRecord[];
  migrationReport?: import('./canvas/graphContracts').MigrationReport;
}

export type {
  AutomationPhase,
  AutomationState,
  CanvasEdgeV2,
  CanvasGraphV2,
  CanvasJobRequest,
  CanvasNodeKind,
  CanvasNodeV2,
  CanvasExecution,
  ConnectionMode,
  ConnectionValidation,
  ConnectionValidationCode,
  ExecutionNodeState,
  ExecutionStatus,
  GenerationRecord,
  GenerationQueueStatus,
  GenerationSource,
  GenerationStatus,
  MaterializationStatus,
  MigrationReport,
  NodeAction,
  NodeCategory,
  NodeDefinition,
  NodePort,
  NodeRunContext,
  PortMedia,
  ResultPolicy,
} from './canvas/graphContracts';

export interface ProjectDocumentV1 {
  schemaVersion: 1;
  id: string;
  title: string;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  viewport: Viewport;
  createdAt: number;
  updatedAt: number;
}

// 项目元数据（v2）：不含图，只记录画布 id 列表。
export interface ProjectDocumentV2 {
  schemaVersion: 2;
  id: string;
  title: string;
  canvases: string[];
  createdAt: number;
  updatedAt: number;
}

// 画布图：独立存储，按需加载。
export interface CanvasDocumentV1 {
  schemaVersion: 1;
  id: string;
  projectId: string;
  title: string;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  viewport: Viewport;
  /** 后端持久化版本（乐观锁 baseRevision）；加载后回写、保存时携带 */
  revision?: number;
  generationRecords?: import('./canvas/graphContracts').GenerationRecord[];
  executions?: import('./canvas/graphContracts').CanvasExecution[];
  migrationReport?: import('./canvas/graphContracts').MigrationReport;
  createdAt: number;
  updatedAt: number;
}

export interface CanvasSummary {
  id: string;
  projectId: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  nodeCount: number;
  assetCount: number;
}

export interface ProjectSummary {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  canvasCount: number;
  nodeCount: number;
  assetCount: number;
}

export interface NodePreset {
  kind: MediaKind;
  title: string;
  description: string;
  accent: string;
}

export interface CapabilityDescriptor {
  id: string;
  label: string;
  category: 'local' | 'remote';
  available: boolean;
  unavailableReason?: string;
}

export interface JobAdapterStatus {
  connected: boolean;
  label: string;
  endpoint?: string;
}

export interface JobContext {
  projectId: string;
  nodeIds: string[];
  selectedNodeId?: string;
}

export interface JobRequest {
  clientRequestId: string;
  conversationId: string;
  capabilityId: string;
  sourceAssetIds: string[];
  context: JobContext;
  params: Record<string, unknown>;
}

export interface AgentToolCall {
  id: string;
  name: string;
  arguments: Record<string, unknown>;
  requiresApproval?: boolean;
}

export interface AgentToolResult {
  toolCallId: string;
  name: string;
  ok: boolean;
  result?: unknown;
  error?: { code: string; message: string; details?: unknown };
  transactionId?: string;
  revision?: number;
  status?: 'preview' | 'applied' | 'accepted' | 'rejected' | 'failed';
}

export interface JobAssetDescriptor {
  id: string;
  name: string;
  mimeType: string;
  downloadUrl?: string;
}

export interface JobSnapshot {
  jobId: string;
  status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'interrupted';
  progress?: number;
  error?: string;
  assets?: JobAssetDescriptor[];
}

export type JobEvent = ({ jobId: string; eventId?: string } & (
  | { type: 'job.queued' }
  | { type: 'job.started' }
  | { type: 'job.progress'; progress: number; message?: string }
  | { type: 'agent.message'; message: string; messageId?: string; delta?: boolean }
  | { type: 'agent.tool_call'; toolCall: AgentToolCall }
  | { type: 'agent.tool_result'; toolResult: AgentToolResult }
  | { type: 'storyboard.created'; shots: import('./canvas/storyboardPlan').StoryboardShotPlan[]; planVersion: number }
  | { type: 'asset.created'; asset: JobAssetDescriptor }
  | { type: 'job.completed' }
  | { type: 'job.failed'; message: string }
  | { type: 'job.cancelled' }
));

export interface JobAdapter {
  getStatus(): Promise<JobAdapterStatus>;
  getCapabilities(signal?: AbortSignal): Promise<CapabilityDescriptor[]>;
  getJob?(jobId: string): Promise<JobSnapshot>;
  submit(request: JobRequest, signal?: AbortSignal): Promise<{ jobId: string }>;
  subscribe(jobId: string, listener: (event: JobEvent) => void): () => void;
  cancel(jobId: string): Promise<void>;
  fetchAsset(asset: JobAssetDescriptor): Promise<File>;
  sendToolResult?(jobId: string, result: AgentToolResult): Promise<void>;
  listConversations?(projectId: string): Promise<Array<{ id: string; title: string; status: string; updatedAt: string }>>;
  getConversation?(projectId: string): Promise<{ id: string; title: string } | null>;
  getConversationMessages?(conversationId: string): Promise<Array<{ role: string; content: string; createdAt: string }>>;
}

export interface LocalImageProcessor<TParams> {
  validate(asset: MediaAsset, params: TParams): { valid: true } | { valid: false; message: string };
  execute(asset: MediaAsset, params: TParams): Promise<MediaAsset[]>;
}
