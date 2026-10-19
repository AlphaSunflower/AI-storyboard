import client from './client';
import { BACKEND_URL } from '../config';
import { authorizedFetch } from '../lib/authSession';
import type { GatewayModelOption } from './ai';
import type { AgentMediaRef, CanvasEditContext } from '../lib/canvasEditMode';

export type AgentApprovalMode = 'PLAN_APPROVAL' | 'REQUEST_APPROVAL' | 'FULL_ACCESS';

export const DEFAULT_AGENT_APPROVAL_MODE: AgentApprovalMode = 'PLAN_APPROVAL';

export interface AgentConversation {
  id: string;
  userId: string;
  projectId: string;
  title: string;
  status: 'active' | 'archived' | 'temporary';
  canvasContext?: CanvasEditContext | null;
  orchestrationMode?: 'LEGACY' | 'UNIFIED_LOOP' | 'AGENTSCOPE';
  engineVersion?: string;
  debugComparisonEnabled?: boolean;
  approvalMode?: AgentApprovalMode;
  /** @ 引用菜单「对话」组展示用；后端尚未下发时为 undefined（前端按未知处理） */
  messageCount?: number;
  summaryStatus?: 'ready' | 'generating' | 'stale' | 'unavailable';
  summaryPreview?: string;
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

/** 项目维度：某个资产组被哪个对话绑定。 */
export interface ProjectAssetGroupBinding {
  groupId: string;
  conversationId: string;
  conversationTitle: string | null;
  snapshotId: string;
  groupVersion: number;
  target: string;
  boundAt: string | null;
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

/**
 * `@` 引用资料的类别（与后端 `AgentReferenceRequest.kind` 一一对应）。
 *
 * <p>正文里保留 `@名称` 原文（系统提示词据此约定「@ = 引用资料、在你的可读范围内、必须先去读」），
 * 这份结构化清单是同一批引用的机器可读版本：名字允许重名（同名文档/同名会话），
 * 只靠名字解析会让模型读到错的东西，所以每轮都必须带 id。
 */
export type AgentReferenceKind = 'library_file' | 'local_file' | 'scene' | 'conversation';

/** 本轮引用资料（见 docs/superpowers/specs/2026-10-16-mention-reference-materials-design.md §3.1） */
export interface AgentReference {
  kind: AgentReferenceKind;
  /** agent_assets.id / 项目库 assetId / sceneId / conversationId */
  id: string;
  /** 正文里的标记原文（含 `@`），必须与正文逐字一致 */
  token: string;
  /** 展示名（token 去掉 `@`） */
  name: string;
  assetId?: string;
  mediaId?: string;
  url?: string;
  mimeType?: string;
  sizeBytes?: number;
  sceneNumber?: number;
  conversationTitle?: string;
  messageCount?: number;
  /** 会话摘要状态：P2 的会话引用用，P1 分镜引用不涉及 */
  summaryStatus?: 'ready' | 'generating' | 'stale' | 'unavailable';
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

/** 手动压缩上下文结果（POST /conversations/{id}/compact） */
export interface CompactContextResult {
  compacted: boolean;
  status: 'COMPACTED' | 'SKIPPED' | 'UNAVAILABLE';
  messagesBefore: number;
  messagesAfter: number;
  tokensBefore: number;
  tokensAfter: number;
}

/** 清空上下文结果（POST /conversations/{id}/context/clear）：真删行数汇总 + 重置后的标题。 */
export interface ClearContextResult {
  deletedRows: number;
  title: string;
}

/** 分镜草稿的一次字段级改动（GET /agent/storyboards/runs/{id}/changes）。 */
export interface StoryboardChange {
  runId: string;
  shotId: string;
  shotOrdinal: number;
  /** INSERT / UPDATE / DELETE */
  kind: string;
  /** user / agent / system —— 谁改的 */
  source: string;
  /** 中文字段名：画面 / 地点 / 时间 / 角色 / 资产 */
  field: string;
  before: string;
  after: string;
  at: string;
}

/** 推理过程（后端 reasoning_delta / message_end 下发） */
export interface AgentReasoning {
  summary: string;
  details: string;
  /** 实时思考尾部片段：完整句子尚未形成时，用于持续展示当前进展。 */
  activity?: string;
  durationMs?: number;
  source: string;
}

/** 一条助手回复对应后端执行段的模型用量与耗时。 */
export interface AgentTurnMetrics {
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  totalTokens: number;
  /** 本轮末次模型调用的 input token：当前上下文窗口占用（后端权威值，旧后端可能缺失）。 */
  contextTokens?: number;
  /** 本轮所用模型的上下文窗口大小。 */
  contextWindowTokens?: number;
  durationMs: number;
  modelCalls: number;
  complete: boolean;
  /** 所有模型调用的推理耗时合计（毫秒，不含工具执行）；旧后端缺失。 */
  modelDurationMs?: number;
  /** 所有工具执行的耗时合计（毫秒）；旧后端缺失。 */
  toolDurationMs?: number;
  /** 当前上下文组成估算：系统提示词 token（描述组成，非累加值）。 */
  systemPromptTokens?: number;
  /** 当前上下文组成估算：工具定义 token（描述组成，非累加值）。 */
  toolDefinitionTokens?: number;
  /** 当前上下文组成估算：对话消息 token（= contextTokens − 系统 − 工具）。 */
  messageTokens?: number;
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

/** 可渲染意图的固定 kind 集合；未知 kind 走通用兜底渲染。 */
export const RENDER_INTENT_KINDS = [
  'table', 'list', 'gallery', 'progress', 'diff', 'form', 'preview',
] as const;

export type RenderIntentKind = (typeof RENDER_INTENT_KINDS)[number];

/** 工具结果 / 结果 metadata 上的渲染意图块：`{kind, id, status, items, version}`。 */
export interface ToolRenderIntent {
  /** 已知 kind 收敛为字面量；服务端新增的未知 kind 原样保留，交给兜底渲染。 */
  kind: RenderIntentKind | (string & {});
  id?: string;
  status?: string;
  items?: unknown[];
  version?: number;
  /** 未知 kind / 额外字段原样保留，便于兜底渲染展示。 */
  [key: string]: unknown;
}

/** 单个工具调用的流式草稿（generating.toolDrafts / metadata.toolDrafts）。 */
export interface AgentToolDraft {
  name: string;
  text: string;
  input: string;
  status: string;
  /** 工具结果携带的渲染意图（服务端开始下发后才有）。 */
  render?: ToolRenderIntent;
  /** 工具调用开始的时刻（毫秒），仅用于 UI 排序，不参与协议。 */
  startedAt?: number;
  seq?: number;
}

/** HITL 卡片信息（metadata.hitl） */
export interface AgentMessageHitl {
  formContent: string;
  actions: { id: string; title: string }[];
  formToken?: string;
  expirationTime?: number;
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
  /**
   * 卡片不可交互（只读回显）。
   * 典型场景：`formToken` 为空/空白的历史卡片 —— 提交必然失败，
   * 且空 token 会让「已提交回执」幂等判定失效，重放时会再次出现可点击卡片。
   */
  inactive?: boolean;
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
export type AgentCanvasRunStatus = 'queued' | 'planning' | 'running' | 'writing' | 'completed' | 'failed';

/** 生成结果确认信息（看图确认卡片：继续完善 / 满意完成） */
export interface ConfirmResultInfo {
  kind: 'script' | 'image' | 'video';
  url: string;
  assetId?: string;
  /** 多图结果（n>1 时后端下发全部 URL；未下发则回退单 url） */
  urls?: string[];
  assetIds?: string[];
  sceneCount?: number;
  actions: { id: string; title: string }[];
}

export interface AgentMessageMetadata {
  kind?: 'hitl' | 'assistant-reply' | 'video-plan' | 'storyboard-review' | 'canvas-run';
  /**
   * 这条助手回复是被用户打断/停止的：正文以「…」收尾，服务端也按同一文案落库。
   * 命中的气泡不再被后续 delta 续写。
   */
  interrupted?: boolean;
  /** 这条用户消息是在回合进行中插入的（steer），不是独立的新一轮。 */
  steer?: boolean;
  /** steer 的客户端幂等 ID，用于乐观气泡与服务端事件去重。 */
  clientMessageId?: string;
  canvasRunId?: string;
  canvasId?: string;
  turnId?: string;
  title?: string;
  status?: AgentCanvasRunStatus;
  progress?: number;
  nodeCount?: number;
  edgeCount?: number;
  revision?: number;
  /**
   * 画布增量补丁：服务端把每个画布命令的返回值合并累积到这条消息上，
   * 画布 iframe 据此本地增量应用，取代"每次 revision 变化就整图重载"。
   */
  canvasPatch?: Record<string, unknown>;
  /** 补丁超过服务端累积上限被截断：此时画布应回退到整图重载。 */
  canvasPatchOverflow?: boolean;
  error?: string;
  projectId?: string;
  conversationId?: string;
  messageInput?: {
    picUrls?: string[];
    referenceVideos?: string[];
    referenceAudios?: string[];
    attachments?: AgentAttachment[];
    /** 本轮 `@` 引用资料（后端落库后刷新仍可还原气泡里的蓝色标记） */
    references?: AgentReference[];
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
  sandboxAttachments?: AgentAttachment[];
  /**
   * 结果确认动作（继续完善 / 满意完成）。
   *
   * 挂在**产生结果的那条助手消息**上，不再是全局单槽——否则同一会话里两个结果
   * 会互相顶掉，而卡片一旦被收起，只存在于卡片里的图片就再也回不来。
   */
  confirmResult?: ConfirmResultInfo;
  /** 工具调用草稿快照：turn_completed.payload.metadata 回放后仍能渲染工具输出。 */
  toolDrafts?: Record<string, AgentToolDraft>;
  /** 本轮工具结果的渲染意图，按 id 去重后的稳定顺序。 */
  renders?: ToolRenderIntent[];
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
  /**
   * 工具卡归属：复合旅程父任务为自身 `toolCallId`，复合旅程子任务为父任务的
   * `toolCallId`。缺省/空串表示不属于任何工具卡（非旅程任务），前端按既有独立卡片渲染。
   */
  parentToolCallId?: string;
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

export interface AgentMessagePage {
  records: AgentMessage[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface AgentMessageSearchHit {
  messageId: string;
  conversationId: string;
  conversationTitle: string;
  role: string;
  content: string;
  createdAt: string;
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
  type: 'image' | 'video' | 'document' | 'reference';
  url: string;
  prompt: string | null;
  model: string | null;
  status: string;
  taskId: string | null;
  error: string | null;
  createdAt: string;
  originalName?: string | null;
  mimeType?: string | null;
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
  type: 'image' | 'video' | 'document' | 'reference';
  url: string | null;
  originalName?: string | null;
  mimeType?: string | null;
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
  documentTotal?: number;
  otherTotal?: number;
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
  | 'USER_MESSAGE'
  | 'SUBAGENT_STARTED'
  | 'SUBAGENT_COMPLETED'
  | 'agent_event'
  | 'turn_paused'
  | 'turn_completed'
  | 'turn_failed'
  | 'canvas_session_required'
  | 'canvas_run_started'
  | 'canvas_run_updated'
  | 'canvas_run_completed'
  | 'canvas_run_failed'
  | 'plan_updated'
  | 'constraint_flagged'
  | 'media_critique'
  | 'shot_progress'
  | 'assembly_progress'
  | 'AGENT_START'
  | 'AGENT_END'
  | 'AGENT_RESULT'
  | 'MODEL_CALL_START'
  | 'MODEL_CALL_END'
  | 'THINKING_BLOCK_START'
  | 'THINKING_BLOCK_DELTA'
  | 'THINKING_BLOCK_END'
  | 'DATA_BLOCK_START'
  | 'DATA_BLOCK_DELTA'
  | 'DATA_BLOCK_END'
  | 'TEXT_BLOCK_START'
  | 'TEXT_BLOCK_DELTA'
  | 'TEXT_BLOCK_END'
  | 'TOOL_CALL_START'
  | 'TOOL_CALL_DELTA'
  | 'TOOL_CALL_END'
  | 'TOOL_RESULT_START'
  | 'TOOL_RESULT_TEXT_DELTA'
  | 'TOOL_RESULT_DATA_DELTA'
  | 'TOOL_RESULT_END'
  | 'EXCEED_MAX_ITERS'
  | 'REQUIRE_USER_CONFIRM'
  | 'REQUIRE_EXTERNAL_EXECUTION'
  | 'EXTERNAL_EXECUTION_RESULT'
  | 'USER_CONFIRM_RESULT'
  | 'REQUEST_STOP'
  | 'SUBAGENT_EXPOSED'
  | 'HINT_BLOCK'
  | 'ALL_TOOLS_DENIED'
  | 'CUSTOM';

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
  'USER_MESSAGE',
  'SUBAGENT_STARTED',
  'SUBAGENT_COMPLETED',
  'agent_event',
  'turn_paused',
  'turn_completed',
  'turn_failed',
  'canvas_session_required',
  'canvas_run_started',
  'canvas_run_updated',
  'canvas_run_completed',
  'canvas_run_failed',
  'plan_updated',
  'constraint_flagged',
  'media_critique',
  'shot_progress',
  'assembly_progress',
  'AGENT_START',
  'AGENT_END',
  'AGENT_RESULT',
  'MODEL_CALL_START',
  'MODEL_CALL_END',
  'THINKING_BLOCK_START',
  'THINKING_BLOCK_DELTA',
  'THINKING_BLOCK_END',
  'DATA_BLOCK_START',
  'DATA_BLOCK_DELTA',
  'DATA_BLOCK_END',
  'TEXT_BLOCK_START',
  'TEXT_BLOCK_DELTA',
  'TEXT_BLOCK_END',
  'TOOL_CALL_START',
  'TOOL_CALL_DELTA',
  'TOOL_CALL_END',
  'TOOL_RESULT_START',
  'TOOL_RESULT_TEXT_DELTA',
  'TOOL_RESULT_DATA_DELTA',
  'TOOL_RESULT_END',
  'EXCEED_MAX_ITERS',
  'REQUIRE_USER_CONFIRM',
  'REQUIRE_EXTERNAL_EXECUTION',
  'EXTERNAL_EXECUTION_RESULT',
  'USER_CONFIRM_RESULT',
  'REQUEST_STOP',
  'SUBAGENT_EXPOSED',
  'HINT_BLOCK',
  'ALL_TOOLS_DENIED',
  'CUSTOM',
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

export interface SubagentRunView {
  id: string;
  graphId: string;
  conversationId: string;
  turnId?: string;
  toolCallId?: string;
  nodeId: string;
  role: string;
  status: string;
  instruction?: string;
  output?: string;
  error?: string;
  tokens?: number;
  durationMs?: number;
  createdAt?: string;
}

export const agentApi = {
  listConversations: (projectId: string) =>
    client.get<{ data: AgentConversation[] }>('/agent/conversations', { params: { projectId } }),

  getConversation: (conversationId: string) =>
    client.get<{ data: { conversation: AgentConversation; messages: AgentMessage[] } }>(
      `/agent/conversations/${conversationId}`,
    ),

  createSideChat: (sourceConversationId: string) =>
    client.post<{ data: AgentConversation }>(
      `/agent/conversations/${sourceConversationId}/side-chats`,
    ),

  closeSideChat: (conversationId: string, keepalive = false) => keepalive
    ? authorizedFetch(`${BACKEND_URL}/api/agent/side-chats/${conversationId}`, {
        method: 'DELETE',
        keepalive: true,
      }).then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
      })
    : client.delete(`/agent/side-chats/${conversationId}`),

  listConversationAssetGroups: (conversationId: string) =>
    client.get<{ data: ConversationAssetGroupBinding[] }>(
      `/agent/conversations/${conversationId}/asset-groups`),

  /** 项目维度：资产组被哪些对话使用（项目库展示用，只读绑定行）。 */
  listProjectAssetGroupBindings: (projectId: string) =>
    client.get<{ data: ProjectAssetGroupBinding[] }>(
      `/agent/projects/${projectId}/asset-group-bindings`),

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

  listSubagentRuns: (conversationId: string, graphId?: string, limit = 50) =>
    client.get<{ data: SubagentRunView[] }>('/agent/subagent-runs', {
      params: { conversationId, graphId: graphId || undefined, limit },
    }),

  retrySubagentRun: (runId: string, conversationId: string) =>
    client.post<{ data: { conversationId: string; turnId: string; prompt: string; tool: string } }>(
      `/agent/subagent-runs/${runId}/retry`,
      undefined,
      { params: { conversationId } },
    ),

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

  /**
   * 「/清空上下文」：后端真删除该会话的全部上下文（消息、轮次与直播事件、待确认卡片、
   * 画布会话与运行、文档索引等），并把标题重置为「新对话」——执行后等同于一个全新对话。
   * 产出素材（agent_assets）保留。
   */
  clearContext: (id: string) =>
    client.post<{ data: ClearContextResult }>(`/agent/conversations/${id}/context/clear`),

  listMessages: (id: string) =>
    client.get<{ data: AgentMessage[] }>(`/agent/conversations/${id}/messages`),

  listMessagesPage: (id: string, before?: string | null, limit = 50) =>
    client.get<{ data: AgentMessagePage }>(
      `/agent/conversations/${id}/messages/page`,
      { params: { before: before || undefined, limit } },
    ),

  searchMessages: (q: string, limit = 100) =>
    client.get<{ data: AgentMessageSearchHit[] }>(
      '/agent/messages/search',
      { params: { q, limit } },
    ),

  listAssets: (id: string, page = 1, size = 20) =>
    client.get<{ data: AgentPage<AgentAsset> }>(`/agent/conversations/${id}/assets`, { params: { page, size } }),

  listTurnEvents: (conversationId: string, turnId: string, afterSeq = 0) =>
    client.get<{ data: AgentTurnEventEnvelope[] }>(
      `/agent/conversations/${conversationId}/turns/${turnId}/events`,
      { params: { afterSeq } },
    ),

  /**
   * 删除资产。不带 resultIndex 删整条，带上则只删该条结果里的第 N 张；
   * 两种都是软删除：后端清 MinIO 对象并置 status=deleted，行保留给生成记录查。
   */
  deleteAsset: (assetId: string, resultIndex?: number) =>
    client.delete(`/agent/assets/${assetId}`, {
      params: resultIndex === undefined ? {} : { resultIndex },
    }),

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
      assetId: string;
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

  // 手动压缩会话上下文（/chat 斜杠命令「压缩上下文」）：只压缩运行时会话上下文，消息记录不变
  compactContext: (conversationId: string) =>
    client.post<{ data: CompactContextResult }>(`/agent/conversations/${conversationId}/compact`),

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

  /** 分镜草稿改动日志（字段级 before/after）：刷新、换设备、审批之后都能追溯。 */
  getStoryboardChanges: (runId: string) =>
    client.get<{ data: StoryboardChange[] }>(`/agent/storyboards/runs/${runId}/changes`),

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

  /** 会话级打断：turnId 还没下发（turn_started 未到）时的兜底入口。 */
  interruptConversation: (conversationId: string) =>
    client.post<{ data: boolean }>(`/agent/conversations/${conversationId}/interrupt`),

  /**
   * 回合中插入一条用户消息（steer）：不打断当前工具，在下一个模型步骤注入。
   * 返回 not_running 表示回合已结束，调用方应改走普通发送。
   */
  steerTurn: (conversationId: string, turnId: string, content: string, clientMessageId: string) =>
    client.post<{ data: { status: string; turnId: string; messageId: string | null } }>(
      `/agent/conversations/${conversationId}/turns/${encodeURIComponent(turnId)}/steer`,
      { content, clientMessageId },
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
    clientRequestId?: string;
    canvasContext?: CanvasEditContext;
    attachments?: AgentAttachment[];
    /** 本轮 `@` 引用资料（正文保留 `@名称`，这里给机器可读的 id 清单） */
    references?: AgentReference[];
    /** 生成偏好载荷（自动且不忽略方案时不带）；形状见 lib/generationPreferences */
    generationPreferences?: Record<string, unknown>;
  },
): Promise<void> {
  const res = await authorizedFetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/turns/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      content,
      clientRequestId: opts?.clientRequestId,
      picUrls: picUrls ?? [],
      assetIds: assetIds ?? [],
      assetGroupIds: assetGroupIds ?? [],
      assetGroupSnapshotIds: opts?.assetGroupSnapshotIds ?? [],
      referenceVideos: opts?.referenceVideos ?? [],
      referenceAudios: opts?.referenceAudios ?? [],
      attachments: opts?.attachments ?? [],
      references: opts?.references ?? [],
      routingHint: opts?.routingHint,
      cancelActiveTasks: opts?.cancelActiveTasks,
      editMessageId: opts?.editMessageId,
      canvasContext: opts?.canvasContext,
      generationPreferences: opts?.generationPreferences,
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
  const res = await authorizedFetch(
    `${BACKEND_URL}/api/agent/conversations/${conversationId}/turns/${encodeURIComponent(turnId)}/resume/stream`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
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
  const res = await authorizedFetch(
    `${BACKEND_URL}/api/agent/conversations/${conversationId}/canvas-sessions/${encodeURIComponent(sessionId)}/ready`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ canvasId: selectedCanvasId ?? '', createNew }),
      signal,
    },
  );
  await consumeSse(res, onEvent, signal);
}

/**
 * 续传某个正在运行的 turn：先补 `afterSeq` 之后的持久化事件，再持续接收直播事件直到终态。
 *
 * <p>用于页面刷新 / 新开标签页接上**正在运行**的 turn，替代"全量重放 + 1s 轮询"。
 * 与 `POST /turns/stream` 共用同一套 SSE 解析（`consumeSse`），因此事件形状、
 * 去重与缺口等待行为完全一致。
 *
 * <p>老后端没有该端点时会抛错（404），调用方据此回退到 `listTurnEvents` 轮询回放。
 */
export async function streamTurnResume(
  conversationId: string,
  turnId: string,
  afterSeq: number,
  onEvent: (e: AgentTurnEventEnvelope) => void,
  signal?: AbortSignal,
): Promise<void> {
  const cursor = Number.isFinite(afterSeq) ? Math.max(0, Math.floor(afterSeq)) : 0;
  const res = await authorizedFetch(
    `${BACKEND_URL}/api/agent/conversations/${encodeURIComponent(conversationId)}`
    + `/turns/${encodeURIComponent(turnId)}/stream?afterSeq=${cursor}`,
    {
      headers: { Accept: 'text/event-stream' },
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
  await consumeSseData<unknown>(res, (eventName, data) => {
    const envelope = parseAgentTurnEnvelope(data);
    if (envelope) {
      onEvent(envelope);
      return;
    }
    const record = isRecord(data) ? data : null;
    const type = typeof record?.type === 'string' ? record.type : eventName;
    if (type !== 'turn_failed') return;
    const payload = isRecord(record?.payload) ? record.payload : {};
    const message = typeof payload.message === 'string'
      ? payload.message
      : '对话处理失败，请重试';
    throw new Error(message);
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
        let data: T;
        try {
          data = JSON.parse(dataLine.slice(5).trim()) as T;
        } catch { continue; /* 忽略坏帧 */ }
        onEvent(eventName, data);
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
  const res = await authorizedFetch(`${BACKEND_URL}/api/agent/tasks/events`, {
    headers: {
      Accept: 'text/event-stream',
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
