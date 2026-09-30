import client from './client';
import { BACKEND_URL } from '../config';
import type { GatewayModelOption } from './ai';
import type { AgentMediaRef, CanvasEditContext } from '../lib/canvasEditMode';

export type AgentApprovalMode = 'PLAN_APPROVAL' | 'REQUEST_APPROVAL' | 'FULL_ACCESS';

export const DEFAULT_AGENT_APPROVAL_MODE: AgentApprovalMode = 'PLAN_APPROVAL';

export interface AgentConversation {
  id: string;
  userId: string;
  projectId: string;
  title: string;
  status: 'active' | 'archived';
  canvasContext?: CanvasEditContext | null;
  orchestrationMode?: 'LEGACY' | 'UNIFIED_LOOP' | 'AGENTSCOPE';
  engineVersion?: string;
  debugComparisonEnabled?: boolean;
  approvalMode?: AgentApprovalMode;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationAssetGroupBinding {
  groupId: string;
  snapshotId: string;
  groupVersion: number;
  contentHash: string;
  target: string;
  stale: boolean;
  media: Array<{ type: 'IMAGE' | 'VIDEO' | 'AUDIO'; role: string; contentUrl: string }>;
}

export type AgentAttachmentKind = 'image' | 'video' | 'audio' | 'file';

export interface AgentAttachment {
  url: string;
  name: string;
  mimeType: string;
  sizeBytes?: number;
  kind: AgentAttachmentKind;
  source?: 'upload' | 'library';
  assetId?: string;
  mediaId?: string;
}

export interface AgentActiveTurn {
  turnId: string;
  status: 'running' | 'waiting_user';
  lastSequence: number;
  inputText?: string;
  startedAt?: string;
}

export interface AgentRuntimeStatus {
  conversationId: string;
  locked?: boolean;
  activeTurn?: AgentActiveTurn;
}

/** 推理过程（后端 reasoning_delta / message_end 下发） */
export interface AgentReasoning {
  summary: string;
  details: string;
  durationMs?: number;
  source: string;
}

/** 一条助手回复对应后端执行段的模型用量与耗时。 */
export interface AgentTurnMetrics {
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  totalTokens: number;
  durationMs: number;
  modelCalls: number;
  complete: boolean;
}

/** 智能体交接轨迹事件（agent_trace / message_end 下发） */
export interface AgentTraceEvent {
  id: string;
  phase: string;
  agent: string;
  specialist?: string;
  status: string;
  title: string;
  summary: string;
  currentIntent?: string;
  targetIntent?: string;
  startedAt: string;
  durationMs?: number;
}

/** SSE 轨迹事件（与 AgentTraceEvent 同构） */
export type SseTraceEvent = AgentTraceEvent;

/** 建议动作按钮（message_end 下发）：点击后以 routingHint 重新发送 */
export interface SuggestedAction {
  id: string;
  title: string;
  routingHint: string;
}

/** HITL 卡片信息（metadata.hitl） */
export interface AgentMessageHitl {
  formContent: string;
  actions: { id: string; title: string }[];
  pending?: boolean;
  selectedActionId?: string;
  selectedActionTitle?: string;
  customText?: string;
  selectedCustomText?: string;
  params?: Record<string, string>;
  selectedParams?: Record<string, string>;
  assetIds?: string[];
  selectedAssetIds?: string[];
  messageId?: string;
}

/** 图生视频方案卡片（metadata.videoPlan；刷新后恢复 VideoPlanCard） */
export interface AgentMessageVideoPlan {
  message: string;
  duration: number;
  picUrl?: string;
  actions: { id: string; title: string }[];
  formToken?: string;
  messageId?: string;
  models?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
}

/** 消息元数据 */
export interface AgentMessageMetadata {
  kind?: 'hitl' | 'assistant-reply' | 'video-plan' | 'storyboard-review';
  messageInput?: {
    picUrls?: string[];
    referenceVideos?: string[];
    referenceAudios?: string[];
    attachments?: AgentAttachment[];
    assetIds?: string[];
    routingHint?: string;
  };
  hitl?: AgentMessageHitl;
  videoPlan?: AgentMessageVideoPlan;
  storyboardReview?: StoryboardReviewRef;
  taskCards?: AgentTaskCardRef[];
  pending?: boolean;
  reasoning?: AgentReasoning;
  metrics?: AgentTurnMetrics;
  agentTrace?: AgentTraceEvent[];
  suggestedActions?: SuggestedAction[];
  orchestrationMode?: 'LEGACY' | 'UNIFIED_LOOP' | 'AGENTSCOPE';
  engineVersion?: string;
  checkpointOwner?: string;
  loopRunId?: string;
  traceId?: string;
}

export interface ToolInvocationView {
  id: string;
  traceId?: string | null;
  loopRunId?: string | null;
  loopStepId?: string | null;
  taskId?: string | null;
  conversationId?: string | null;
  projectId?: string | null;
  toolCallId?: string | null;
  toolName: string;
  toolKind?: string | null;
  source: string;
  status: string;
  attempt?: number | null;
  inputHash?: string | null;
  inputSummary?: string | null;
  outputHash?: string | null;
  outputSummary?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  externalActionKey?: string | null;
  providerTaskId?: string | null;
  resultAssetIds: string[];
  expectedReferenceCount?: number | null;
  resolvedReferenceCount?: number | null;
  appliedReferenceCount?: number | null;
  skillVersion?: string | null;
  model?: string | null;
  engineVersion?: string | null;
  startedAt: string;
  completedAt?: string | null;
  durationMs?: number | null;
}

export interface ToolInvocationPage {
  records: ToolInvocationView[];
  nextCursor: string | null;
  limit: number;
}

export interface ToolAuditConfig {
  enabled: boolean;
  uiEnabled: boolean;
  retentionDays: number;
}

export interface AgentTaskCardRef {
  taskId: string;
  title: string;
  kind: string;
}

export interface StoryboardPseudoShot {
  shotId: string;
  segmentId: string;
  shotOrdinal: number;
  narrativePurpose: string;
  characters: string[];
  location: string;
  time: string;
  conflict: string;
  action: string;
  emotion: string;
  continuity: string[];
  sourceExcerpt: string[];
  referenceAssetIds: string[];
}

export interface StoryboardFinalShot {
  shotId: string;
  segmentId: string;
  shotOrdinal: number;
  sceneDescription: string;
  shotType: string;
  cameraMovement: string;
  durationSeconds: number;
  imagePrompt: string;
  videoPrompt: string;
  negativePrompt: string;
  referenceHints: string[];
}

export interface StoryboardReviewRef {
  runId: string;
  stage: 'PLOT_REVIEW' | 'PSEUDO_REVIEW' | 'FINALIZING' | 'FINAL_REVIEW' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  version: number;
  pending?: boolean;
}

export interface AgentStoryboardRun {
  runId: string;
  conversationId: string;
  stage: StoryboardReviewRef['stage'];
  version: number;
  originalScript: string;
  selectedScript: string;
  expansionScript: string;
  pseudoShots: StoryboardPseudoShot[];
  finalShots: StoryboardFinalShot[];
  error: string;
}

export interface AgentTaskSlotView {
  index: number;
  label: string;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled';
  prompt: string;
  url: string;
  assetId: string;
  error: string;
  completedAt?: string | null;
}

export interface AgentTaskView {
  taskId: string;
  conversationId: string;
  projectId: string;
  title: string;
  toolName: string;
  kind: string;
  status: 'waiting_parent' | 'queued' | 'running' | 'cancelling' | 'cancelled' | 'completed' | 'partial' | 'failed';
  stage: string;
  progress: number | null;
  resultSummary: string;
  error: string;
  parentTaskId: string;
  anchorMessageId: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
  assetIds: string[];
  resultUrls: string[];
  slots: AgentTaskSlotView[];
  result: string;
  liveText: string;
  liveThinking: string;
  liveStage: string;
  liveUpdatedAt?: string | null;
  canCancel: boolean;
  canRetry: boolean;
  canFollowUp: boolean;
}

export interface AgentTaskPage {
  records: AgentTaskView[];
  total: number;
  page: number;
  size: number;
}

export interface AgentWakeupMessage {
  taskId: string;
  conversationId: string;
  messageId: string;
}

export type AgentRealtimeEvent =
  | { type: 'task'; task: AgentTaskView }
  | { type: 'wakeup_message'; message: AgentWakeupMessage }
  | {
      type: 'task_stream';
      taskId: string;
      seq: number;
      stage: string;
      text: string;
      thinking: string;
      done: boolean;
      updatedAt: string;
    };

export interface AgentMessage {
  id: string;
  conversationId: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
  /** HITL 人工介入卡片信息（选择后保存到对话记录） */
  hitlCard?: {
    formContent: string;
    actions: { id: string; title: string }[];
    selectedActionId: string;
    selectedActionTitle: string;
  };
  /** 消息元数据（reasoning / agentTrace / suggestedActions / hitl） */
  metadata?: AgentMessageMetadata;
}

export interface CanvasSessionInfo {
  sessionId: string;
  conversationId: string;
  turnId: string;
  runtimeToolCallId?: string;
  projectId: string;
  canvasId?: string;
  canvasTitle?: string;
  intent: string;
  status: 'pending_selection' | 'pending_open' | 'opening' | 'ready' | 'resumed' | 'cancelled' | 'expired' | 'failed';
  choices: Array<{ canvasId: string; title: string }>;
  mediaRefs?: AgentMediaRef[];
  expiresAt?: string;
}

export interface AgentAsset {
  id: string;
  conversationId: string | null;
  type: 'image' | 'video' | 'reference';
  url: string;
  prompt: string | null;
  model: string | null;
  status: string;
  taskId: string | null;
  error: string | null;
  createdAt: string;
  // 生成记录扩展（产出素材面板按 resultUrls 展示多图）
  genType?: string | null;
  params?: string | null;
  referenceUrls?: string | null;
  resultUrls?: string | null;
  durationMs?: number | null;
  sceneId?: string | null;
}

/** 生成记录（agent_assets 扩展版）：一次生成尝试一行；params/referenceUrls/resultUrls 为 JSON 字符串 */
export interface GenerationRecord {
  id: string;
  conversationId: string | null;
  userId?: string | null;
  projectId?: string | null;
  type: 'image' | 'video' | 'reference';
  url: string | null;
  prompt: string | null;
  model: string | null;
  status: string;
  taskId: string | null;
  error: string | null;
  createdAt: string;
  genType: string | null;
  params: string | null;
  referenceUrls: string | null;
  resultUrls: string | null;
  durationMs: number | null;
  sceneId: string | null;
}

export interface GenerationRecordPage {
  records: GenerationRecord[];
  total: number;
  page: number;
  size: number;
}

export interface AgentPage<T> {
  records: T[];
  total: number;
  imageTotal?: number;
  videoTotal?: number;
  mediaTotal?: number;
  page: number;
  size: number;
}

/** 项目产出素材：一次生成可包含多张图片。 */
export interface ProjectOutputMaterialItem {
  id: string;
  type: 'image' | 'video';
  urls: string[];
  sceneId?: string | null;
  createdAt: string;
}

/** 项目产出素材按对话分组，当前对话由后端排在第一组。 */
export interface ProjectOutputMaterialGroup {
  conversationId: string;
  title: string | null;
  updatedAt: string;
  items: ProjectOutputMaterialItem[];
}

/** 资产选择卡片的资产项（human_input 事件 assets 字段） */
export interface AssetOption {
  id: string;
  name: string;
  type: string; // character / prop / scene
  image?: string; // 主图路径（/api/files/...）
  imageUrls?: string[];
  // ── 分镜选项扩展：结构化分镜项 ──
  sceneNumber?: number;
  scriptContent?: string;
  hasImage?: boolean;
  hasVideo?: boolean;
  videoStatus?: string;
  videoPrompt?: string;
  imagePrompt?: string;
  imageModel?: string;
  imageSize?: string;
  imageQuality?: string;
  imageN?: number;
  videoModel?: string;
  videoAspectRatio?: string;
  videoResolution?: string;
  duration?: number;
  negativePrompt?: string;
}

export type AgentTurnEventType =
  | 'turn_started'
  | 'agent_event'
  | 'turn_paused'
  | 'turn_completed'
  | 'turn_failed'
  | 'canvas_session_required'
  | 'plan_updated'
  | 'constraint_flagged'
  | 'media_critique'
  | 'shot_progress'
  | 'assembly_progress';

/** Native AgentScope event carried inside an `agent_event` payload. */
export interface AgentScopeEvent {
  eventType: string;
  data: Record<string, unknown>;
}

/** Sequenced transport unit for one AgentScope turn. */
export interface AgentTurnEventEnvelope {
  turnId: string;
  seq: number;
  type: AgentTurnEventType;
  payload: Record<string, unknown>;
}

export interface ConstraintItem {
  id: string;
  statement: string;
  severity: 'HARD' | 'SOFT';
  source: string;
  verifier: string;
  value: string;
}

export interface ProjectCreativeContext {
  projectId: string;
  objective: string;
  creativeDirection: string;
  visualStyle: string;
  references: Array<{ assetId: string; role: string; url: string; note: string }>;
  hardConstraints: ConstraintItem[];
  softConstraints: ConstraintItem[];
  approvedAssetIds: string[];
  rejectedDirections: string[];
  feedback: string[];
  modelPreferences: Record<string, string>;
  version: number;
  updatedAt: string;
}

export interface MediaCritique {
  id: string;
  turnId: string;
  itemId: string;
  mediaType: string;
  assetUrl: string;
  decision: string;
  hardConstraintPass: boolean;
  scores: Record<string, number>;
  violations: string[];
  reasons: string[];
  repairAttempt: number;
  createdAt: string;
}

const AGENT_TURN_EVENT_TYPES = new Set<AgentTurnEventType>([
  'turn_started',
  'agent_event',
  'turn_paused',
  'turn_completed',
  'turn_failed',
  'canvas_session_required',
  'plan_updated',
  'constraint_flagged',
  'media_critique',
  'shot_progress',
  'assembly_progress',
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/** Parse and validate one SSE JSON payload as an AgentScope turn envelope. */
export function parseAgentTurnEnvelope(raw: unknown): AgentTurnEventEnvelope | null {
  if (!isRecord(raw)) return null;
  const turnId = typeof raw.turnId === 'string' ? raw.turnId : '';
  const seq = typeof raw.seq === 'number' ? raw.seq : Number.NaN;
  const type = typeof raw.type === 'string' ? raw.type : '';
  if (!turnId || !Number.isInteger(seq) || seq < 1 || !AGENT_TURN_EVENT_TYPES.has(type as AgentTurnEventType)) {
    return null;
  }
  return {
    turnId,
    seq,
    type: type as AgentTurnEventType,
    payload: isRecord(raw.payload) ? raw.payload : {},
  };
}

/** 视频异步任务状态（GET /api/agent/tasks/{taskId} 轮询结果） */
export interface VideoTaskStatus {
  taskId: string;
  kind?: 'video' | 'image-batch' | 'long-tool';
  toolName?: string;
  assetId?: string;
  assetIds?: string[];
  status: 'queued' | 'running' | 'completed' | 'failed' | 'unknown';
  url: string;
  resultUrls?: string[];
  result?: string;
  followUpTaskId?: string;
  messageId?: string;
  error: string;
}

/** 分镜视频批量生成队列条目（GET /api/agent/storyboards/video/queue） */
export interface StoryboardVideoQueueItem {
  id: string;
  sceneId: string;
  projectId: string | null;
  conversationId: string | null;
  status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled';
  prompt: string | null;
  model: string | null;
  resolution: string | null;
  size: string | null;
  aspectRatio: string | null;
  duration: number | null;
  taskId: string | null;
  progress: number | null;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

/** 单个分镜入队载荷 */
export interface StoryboardVideoEnqueueItem {
  sceneId: string;
  prompt?: string;
  model?: string;
  resolution?: string;
  size?: string;
  aspectRatio?: string;
  duration?: number;
  negativePrompt?: string;
  sourceImageUrl?: string;
  refImageUrls?: string[];
  refAssetIds?: string[];
}

/** 分镜「完善图片」队列条目（GET /api/agent/storyboards/image/queue） */
export interface StoryboardImageQueueItem {
  id: string;
  sceneId: string;
  projectId: string | null;
  conversationId: string | null;
  status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled';
  prompt: string | null;
  model: string | null;
  size: string | null;
  quality: string | null;
  n: number | null;
  mode: string | null;
  sourceImageUrl: string | null;
  taskId: string | null;
  progress: number | null;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

/** 单个分镜「完善图片」入队载荷 */
export interface StoryboardImageEnqueueItem {
  sceneId: string;
  prompt?: string;
  model?: string;
  size?: string;
  quality?: string;
  n?: number;
  sourceImageUrl?: string;
  refImageUrls?: string[];
  mode?: 'edit' | 'generate';
}

// ── 会话 ──────────────────────────────────────────────

export const agentApi = {
  listConversations: (projectId: string) =>
    client.get<{ data: AgentConversation[] }>('/agent/conversations', { params: { projectId } }),

  getConversation: (conversationId: string) =>
    client.get<{ data: { conversation: AgentConversation; messages: AgentMessage[] } }>(
      `/agent/conversations/${conversationId}`,
    ),

  listConversationAssetGroups: (conversationId: string) =>
    client.get<{ data: ConversationAssetGroupBinding[] }>(
      `/agent/conversations/${conversationId}/asset-groups`),

  attachConversationAssetGroup: (conversationId: string, groupId: string) =>
    client.post<{ data: ConversationAssetGroupBinding }>(
      `/agent/conversations/${conversationId}/asset-groups`, { groupId }),

  removeConversationAssetGroup: (conversationId: string, groupId: string) =>
    client.delete(`/agent/conversations/${conversationId}/asset-groups/${groupId}`),

  getCreativeContext: (projectId: string) =>
    client.get<{ data: ProjectCreativeContext }>(
      `/agent/projects/${projectId}/creative-context`),

  updateCreativeContext: (
    projectId: string,
    data: Partial<Omit<ProjectCreativeContext, 'projectId' | 'version' | 'updatedAt'>>,
  ) =>
    client.put<{ data: ProjectCreativeContext }>(
      `/agent/projects/${projectId}/creative-context`, data),

  getRunPlan: (runId: string) =>
    client.get<{ data: Record<string, unknown> }>(`/agent/runs/${runId}/plan`),

  getRunCritiques: (runId: string) =>
    client.get<{ data: MediaCritique[] }>(`/agent/runs/${runId}/critique`),

  createConversation: (
    projectId: string,
    title?: string,
    approvalMode?: AgentApprovalMode,
  ) =>
    client.post<{ data: AgentConversation }>('/agent/conversations', {
      projectId,
      title,
      ...(approvalMode ? { approvalMode } : {}),
    }),

  updateConversation: (id: string, data: { title?: string; status?: string }) =>
    client.patch<{ data: AgentConversation }>(`/agent/conversations/${id}`, data),

  updateApprovalMode: (id: string, approvalMode: AgentApprovalMode) =>
    client.patch<{ data: AgentConversation }>(
      `/agent/conversations/${id}/approval-mode`,
      { approvalMode },
    ),

  deleteConversation: (id: string) =>
    client.delete(`/agent/conversations/${id}`),

  // 清空会话聊天记录（删消息 + 重置 AI 上下文；会话与资产保留）
  clearMessages: (id: string) =>
    client.delete(`/agent/conversations/${id}/messages`),

  listMessages: (id: string) =>
    client.get<{ data: AgentMessage[] }>(`/agent/conversations/${id}/messages`),

  listAssets: (id: string, page = 1, size = 20) =>
    client.get<{ data: AgentPage<AgentAsset> }>(`/agent/conversations/${id}/assets`, { params: { page, size } }),

  listTurnEvents: (conversationId: string, turnId: string, afterSeq = 0) =>
    client.get<{ data: AgentTurnEventEnvelope[] }>(
      `/agent/conversations/${conversationId}/turns/${turnId}/events`,
      { params: { afterSeq } },
    ),

  deleteAsset: (assetId: string) =>
    client.delete(`/agent/assets/${assetId}`),

  // ── 生成记录 ──────────────────────────────
  listGenerationRecords: (params: {
    genType?: string;
    status?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  }) =>
    client.get<{ data: GenerationRecordPage }>('/agent/generation-records', { params }),

  listProjectOutputMaterials: (projectId: string, currentConversationId?: string | null) =>
    client.get<{ data: ProjectOutputMaterialGroup[] }>(`/agent/projects/${projectId}/output-materials`, {
      params: { currentConversationId: currentConversationId ?? undefined },
    }),

  getGenerationRecord: (id: string) =>
    client.get<{ data: GenerationRecord }>(`/agent/generation-records/${id}`),

  refetchGenerationRecord: (id: string) =>
    client.post<{ data: GenerationRecord }>(`/agent/generation-records/${id}/refetch`),

  retryGenerationRecord: (id: string) =>
    client.post<{ data: { id: string; taskId: string } }>(`/agent/generation-records/${id}/retry`),

  uploadImage: (file: File, conversationId?: string, purpose?: string, kind?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (conversationId) form.append('conversationId', conversationId);
    if (purpose?.trim()) form.append('purpose', purpose.trim());
    if (kind) form.append('kind', kind);
    return client.post<{ data: {
      url: string;
      name?: string;
      mimeType?: string;
      sizeBytes?: number;
    } }>('/agent/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  getRuntimeStatus: (conversationId: string) =>
    client.get<{ data: AgentRuntimeStatus }>(`/agent/conversations/${conversationId}/runtime`),

  // 满意完成：清空会话图片上下文（生成结果确认卡片「满意完成」按钮）
  confirmDone: (conversationId: string) =>
    client.post<{ data: boolean }>(`/agent/conversations/${conversationId}/confirm-done`),

  // 视频异步任务状态（task_accepted 后前端 5s 轮询，取 status/url/error）
  getVideoTaskStatus: (taskId: string) =>
    client.get<{ data: VideoTaskStatus }>(`/agent/tasks/${taskId}`),

  listTasks: (params?: {
    conversationId?: string;
    projectId?: string;
    status?: 'active' | 'terminal' | 'all' | AgentTaskView['status'];
    page?: number;
    size?: number;
  }) => client.get<{ data: AgentTaskPage }>('/agent/tasks', { params }),

  getTask: (taskId: string) =>
    client.get<{ data: AgentTaskView }>(`/agent/tasks/${taskId}`),

  getStoryboardRun: (runId: string) =>
    client.get<{ data: AgentStoryboardRun }>(`/agent/storyboards/runs/${runId}`),

  saveStoryboardDraft: (
    runId: string,
    data: {
      expectedVersion: number;
      pseudoShots: StoryboardPseudoShot[];
      finalShots: StoryboardFinalShot[];
    },
  ) =>
    client.put<{ data: AgentStoryboardRun }>(`/agent/storyboards/runs/${runId}/draft`, data),

  approveStoryboard: (runId: string, data: { stage: string; decision?: string }) =>
    client.post<{ data: { taskId: string; stage: string } }>(
      `/agent/storyboards/runs/${runId}/approve`,
      data,
    ),

  getToolAuditConfig: () =>
    client.get<{ data: ToolAuditConfig }>('/agent/tool-audit/config'),

  listToolInvocations: (params: {
    taskId?: string;
    conversationId?: string;
    cursor?: string;
    limit?: number;
  }) => client.get<{ data: ToolInvocationPage }>('/agent/tool-invocations', { params }),

  cancelTask: (taskId: string) =>
    client.post<{ data: AgentTaskView }>(`/agent/tasks/${taskId}/cancel`),

  retryTask: (taskId: string) =>
    client.post<{ data: AgentTaskView }>(`/agent/tasks/${taskId}/retry`),

  followUpTask: (taskId: string, instruction: string) =>
    client.post<{ data: AgentTaskView }>(`/agent/tasks/${taskId}/followups`, { instruction }),

  // 分镜视频批量生成队列
  enqueueStoryboardVideos: (data: { conversationId?: string; projectId?: string; items: StoryboardVideoEnqueueItem[] }) =>
    client.post<{ data: StoryboardVideoQueueItem[] }>('/agent/storyboards/video/queue', data),

  listStoryboardVideoQueue: (projectId: string, status?: string) =>
    client.get<{ data: StoryboardVideoQueueItem[] }>('/agent/storyboards/video/queue', { params: { projectId, status } }),

  cancelStoryboardVideoQueue: (queueId: string) =>
    client.delete(`/agent/storyboards/video/queue/${queueId}`),

  // 分镜「完善图片」队列
  enqueueStoryboardImageRefine: (data: { conversationId?: string; projectId?: string; items: StoryboardImageEnqueueItem[] }) =>
    client.post<{ data: StoryboardImageQueueItem[] }>('/agent/storyboards/image/queue', data),
  listStoryboardImageQueue: (projectId: string, status?: string) =>
    client.get<{ data: StoryboardImageQueueItem[] }>('/agent/storyboards/image/queue', { params: { projectId, status } }),
  cancelStoryboardImageQueue: (queueId: string) =>
    client.delete(`/agent/storyboards/image/queue/${queueId}`),

  // 获取会话的待处理 checkpoint（页面刷新恢复 HITL 卡片用）
  getPendingCheckpoint: (conversationId: string) =>
    client.get<{ data: Record<string, unknown> | null }>(`/agent/conversations/${conversationId}/pending-checkpoint`),

  getPendingCanvasSession: (conversationId: string) =>
    client.get<{ data: CanvasSessionInfo | Record<string, never> }>(
      `/agent/conversations/${conversationId}/canvas-sessions/pending`),

  cancelCanvasSession: (conversationId: string, sessionId: string) =>
    client.post<{ data: CanvasSessionInfo }>(
      `/agent/conversations/${conversationId}/canvas-sessions/${sessionId}/cancel`),

  // 跳过/作废当前会话待处理 HITL checkpoint（「换个话题」按钮用）
  skipPendingCheckpoint: (conversationId: string) =>
    client.post<{ data: null }>(`/agent/conversations/${conversationId}/checkpoint/skip`),

  exitCanvasMode: (conversationId: string) =>
    client.post<{ data: null }>(`/agent/conversations/${conversationId}/canvas-mode/exit`),

  // 打断当前会话的生成（「停止生成」按钮触发）：后端置位会话级 interrupt 标志 → 编排协作式取消
  interruptTurn: (conversationId: string, turnId: string) =>
    client.post<{ data: boolean }>(
      `/agent/conversations/${conversationId}/turns/${encodeURIComponent(turnId)}/interrupt`,
    ),
};

/**
 * 流式语音识别：录音中实时推 PCM 裸流（16kHz mono 16bit）到 /agent/stt/stream，
 * SSE 收 partial（识别中文本，实时打字机）/ final（最终文本）。
 * 返回 { push, close, cancel }：push 推 PCM 块；close 关流结束识别；cancel 中断。
 */
export function sttStream(onPartial: (text: string) => void, onFinal: (text: string) => void) {
  // 前端直连 vosk-server（本机开发：ws://localhost:2700；生产部署时应走后端代理并加鉴权）
  // 不用 HTTP 流式上传：Chrome 对流式 body（fetch/XHR）强制 HTTP/2，对 HTTP/1.1 的
  // localhost（Vite/Tomcat）会 ALPN 协商失败（ERR_ALPN_NEGOTIATION_FAILED）。
  // WebSocket 握手是 HTTP/1.1 Upgrade，推流用 WS 帧，无此限制。
  const VOSK_WS_URL = (import.meta as { env?: Record<string, string> }).env?.VITE_VOSK_WS_URL ?? 'ws://localhost:2700';
  const ws = new WebSocket(VOSK_WS_URL);
  let opened = false;
  let closed = false;
  const pending: Int16Array[] = []; // 连接建立前缓冲 PCM

  ws.onopen = () => {
    opened = true;
    // 显式下发 config 更稳（vosk 默认即 16k）
    ws.send(JSON.stringify({ config: { sample_rate: 16000 } }));
    // 补发连接建立前的缓冲
    for (const p of pending) ws.send(p.buffer as ArrayBuffer);
    pending.length = 0;
  };

  // 从 vosk 消息中提取 partial/text 字段值
  const extractText = (msg: string): string => {
    const m = msg.match(/"partial"\s*:\s*"([^"]*)"|"text"\s*:\s*"([^"]*)"/);
    return m ? (m[1] ?? m[2] ?? '') : '';
  };

  ws.onmessage = (e) => {
    const msg = String(e.data);
    if (msg.includes('"partial"')) {
      onPartial(extractText(msg));
    } else if (msg.includes('"text"')) {
      onFinal(extractText(msg));
    }
  };
  ws.onerror = () => { /* 连接错误：静默，录音中无提示 */ };
  ws.onclose = () => { /* 关闭：正常结束 */ };

  return {
    /** 推送 PCM 块（16kHz mono 16bit Int16Array） */
    push: (pcm: Int16Array) => {
      if (closed) return;
      if (!opened) { pending.push(pcm); return; }
      try { ws.send(pcm.buffer as ArrayBuffer); } catch { /* 连接已关 */ }
    },
    /** 停止录音：发 eof 结束识别（vosk 回 final 后自行关闭） */
    close: () => {
      if (closed) return;
      closed = true;
      if (opened) {
        // 注意空格：vosk-server 精确字符串匹配 '{"eof" : 1}'，无空格会当音频数据
        try { ws.send('{"eof" : 1}'); } catch { /* ignore */ }
      } else {
        try { ws.close(); } catch { /* ignore */ }
      }
    },
    /** 中断：直接关闭连接 */
    cancel: () => {
      if (closed) return;
      closed = true;
      try { ws.close(); } catch { /* ignore */ }
    },
  };
}

/** Stream one AgentScope turn as sequenced envelopes. */
export async function streamChat(
  conversationId: string,
  content: string,
  picUrls: string[],
  onEvent: (e: AgentTurnEventEnvelope) => void,
  assetIds?: string[],
  assetGroupIds?: string[],
  signal?: AbortSignal,
  opts?: {
    assetGroupSnapshotIds?: string[];
    referenceVideos?: string[];
    referenceAudios?: string[];
    routingHint?: string;
    cancelActiveTasks?: boolean;
    editMessageId?: string;
    canvasContext?: CanvasEditContext;
    attachments?: AgentAttachment[];
  },
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/turns/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      content,
      picUrls: picUrls ?? [],
      assetIds: assetIds ?? [],
      assetGroupIds: assetGroupIds ?? [],
      assetGroupSnapshotIds: opts?.assetGroupSnapshotIds ?? [],
      referenceVideos: opts?.referenceVideos ?? [],
      referenceAudios: opts?.referenceAudios ?? [],
      attachments: opts?.attachments ?? [],
      routingHint: opts?.routingHint,
      cancelActiveTasks: opts?.cancelActiveTasks,
      editMessageId: opts?.editMessageId,
      canvasContext: opts?.canvasContext,
    }),
    signal,
  });
  await consumeSse(res, onEvent, signal);
}

/** HITL 表单提交并续流 */
export async function submitForm(
  conversationId: string,
  turnId: string,
  formToken: string,
  taskId: string,
  action: string,
  onEvent: (e: AgentTurnEventEnvelope) => void,
  customText?: string,
  params?: Record<string, string>,
  assetIds?: string[],
  signal?: AbortSignal,
  opts?: { canvasContext?: CanvasEditContext },
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(
    `${BACKEND_URL}/api/agent/conversations/${conversationId}/turns/${encodeURIComponent(turnId)}/resume/stream`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({
        formToken,
        taskId,
        action,
        content: customText ?? '',
        params: params ?? {},
        assetIds: assetIds ?? [],
        canvasContext: opts?.canvasContext,
      }),
      signal,
    },
  );
  await consumeSse(res, onEvent, signal);
}

/** Mark a canvas iframe ready and continue the paused AgentScope turn. */
export async function streamCanvasSessionReady(
  conversationId: string,
  sessionId: string,
  onEvent: (e: AgentTurnEventEnvelope) => void,
  selectedCanvasId?: string,
  createNew = false,
  signal?: AbortSignal,
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(
    `${BACKEND_URL}/api/agent/conversations/${conversationId}/canvas-sessions/${encodeURIComponent(sessionId)}/ready`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ canvasId: selectedCanvasId ?? '', createNew }),
      signal,
    },
  );
  await consumeSse(res, onEvent, signal);
}

/** Read typed turn-envelope payloads; the SSE event name is transport metadata. */
async function consumeSse(
  res: Response,
  onEvent: (e: AgentTurnEventEnvelope) => void,
  signal?: AbortSignal,
): Promise<void> {
  await consumeSseData<unknown>(res, (_eventName, data) => {
    const envelope = parseAgentTurnEnvelope(data);
    if (envelope) onEvent(envelope);
  }, signal);
}

async function consumeSseData<T>(
  res: Response,
  onEvent: (eventName: string, data: T) => void,
  signal?: AbortSignal,
): Promise<void> {
  if (!res.ok) {
    let detail = '';
    try { detail = (await res.json()).message ?? ''; } catch { /* ignore */ }
    throw new Error(detail || `请求失败 (${res.status})`);
  }
  if (!res.body) throw new Error('响应无数据流');
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    for (;;) {
      if (signal?.aborted) break;
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // 兼容 \n\n 与 \r\n\r\n 两种帧分隔（代理可能改写行尾）
      const parts = buffer.split(/\r?\n\r?\n/);
      buffer = parts.pop() ?? '';
      for (const part of parts) {
        const lines = part.split(/\r?\n/);
        const eventLine = lines.find((l) => l.startsWith('event:'));
        const dataLine = lines.find((l) => l.startsWith('data:'));
        if (!dataLine) continue;
        const eventName = eventLine ? eventLine.slice(6).trim() : '';
        try {
          const data = JSON.parse(dataLine.slice(5).trim()) as T;
          onEvent(eventName, data);
        } catch { /* 忽略坏帧 */ }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export async function streamTaskEvents(
  onEvent: (event: AgentRealtimeEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/tasks/events`, {
    headers: {
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    },
    signal,
  });
  await consumeSseData<
    { task?: AgentTaskView } & AgentWakeupMessage
      & {
        taskId?: string;
        seq?: number;
        stage?: string;
        text?: string;
        thinking?: string;
        done?: boolean;
        updatedAt?: string;
      }
  >(
    res,
    (eventName, data) => {
      if (eventName === 'snapshot' && data?.task) {
        onEvent({ type: 'task', task: data.task });
      } else if (eventName === 'wakeup_message' && data?.conversationId && data?.messageId) {
        onEvent({
          type: 'wakeup_message',
          message: {
            taskId: data.taskId,
            conversationId: data.conversationId,
            messageId: data.messageId,
          },
        });
      } else if (eventName === 'task_stream' && data?.taskId) {
        onEvent({
          type: 'task_stream',
          taskId: data.taskId,
          seq: data.seq ?? 0,
          stage: data.stage ?? '',
          text: data.text ?? '',
          thinking: data.thinking ?? '',
          done: data.done ?? false,
          updatedAt: data.updatedAt ?? '',
        });
      }
  }, signal);
}
