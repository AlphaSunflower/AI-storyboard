import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm, streamCanvasSessionReady, streamTaskEvents,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type AgentTurnEventEnvelope, type AgentScopeEvent,
  type AgentTurnMetrics, type VideoTaskStatus, type AssetOption,
  type AgentApprovalMode,
  type AgentTaskView,
  type AgentWakeupMessage,
  type StoryboardVideoQueueItem, type StoryboardVideoEnqueueItem,
  type StoryboardImageQueueItem, type StoryboardImageEnqueueItem,
  type AgentMessageMetadata, type AgentReasoning, type AgentTraceEvent,
  type AgentMessagePage,
  type AgentCanvasRunStatus,
  type AgentAttachment, type AgentAttachmentKind, type AgentActiveTurn,
  type CanvasSessionInfo,
  DEFAULT_AGENT_APPROVAL_MODE,
} from '../api/agent';
import { useProjectStore } from './projectStore';
import { rememberDocumentSource } from '../lib/documentPreview';
import { useDocumentPreviewStore } from './documentPreviewStore';
import { useSideChatSelectionStore } from './sideChatSelectionStore';
import { assetUrl, VIDEO_POLL_INTERVAL } from '../config';
import type { GatewayModelOption } from '../api/ai';
import { libraryApi, type LibraryMedia } from '../api/library';
import {
  refreshLibraryMediaAttachments,
  refreshLibraryMediaMessages,
} from '../lib/libraryMedia';
import {
  buildCanvasRequestContext,
  clearCanvasEditContext,
  filterCanvasReferenceUrls,
  mediaUrlKey,
  normalizeCanvasEditContext,
  persistCanvasEditContext,
  withoutCanvasMediaRef,
  type AgentMediaRef,
  type CanvasBinding,
  type CanvasEditContext,
} from '../lib/canvasEditMode';

let conversationLoadEpoch = 0;
let conversationListEpoch = 0;
let sideChatRequestSeq = 0;

/** Newlines separate stage snapshots; token deltas concatenate directly. */
function appendReasoningDetails(
  prev: AgentReasoning | undefined,
  event: { details?: string; source?: string },
): string {
  const previous = prev?.details ?? '';
  const delta = event.details ?? '';
  if (!previous || !delta) return previous + delta;
  return (prev?.source === 'stage' || event.source === 'stage')
    ? `${previous}\n${delta}`
    : previous + delta;
}

/**
 * 实时摘要只取「已经写完的最后一句话」。
 * 直接取最后一个换行片段会把流式半句（如“…video. This maps”）当成摘要显示在标题上。
 */
function reasoningSummaryFrom(details: string, fallback?: string): string {
  const sentences = (details ?? '').match(/[^。．.!?！？\n]+[。．.!?！？]/g);
  const last = sentences && sentences.length > 0 ? sentences[sentences.length - 1].trim() : '';
  return last || fallback || '';
}

function reasoningActivityFrom(details: string): string {
  const normalized = (details ?? '').replace(/\s+/g, ' ').trim();
  if (!normalized) return '';
  return normalized.length > 72 ? normalized.slice(-72) : normalized;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : value == null ? fallback : String(value);
}

function asNumber(value: unknown, fallback = 0): number {
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item) => typeof item === 'string' && item.length > 0)
    : [];
}

const PENDING_ATTACHMENTS_KEY = 'moon.agent.pendingAttachments';

function pendingAttachmentsKey(conversationId?: string | null) {
  return `${PENDING_ATTACHMENTS_KEY}.${conversationId || 'draft'}`;
}

function isAgentAttachment(value: unknown): value is AgentAttachment {
  const item = asRecord(value);
  return !!item
    && typeof item.url === 'string'
    && typeof item.name === 'string'
    && ['image', 'video', 'audio', 'file'].includes(String(item.kind));
}

function readPendingAttachments(conversationId?: string | null): AgentAttachment[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(pendingAttachmentsKey(conversationId));
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter(isAgentAttachment) : [];
  } catch {
    return [];
  }
}

function persistPendingAttachments(conversationId: string | null | undefined, attachments: AgentAttachment[]) {
  if (typeof window === 'undefined') return;
  try {
    const key = pendingAttachmentsKey(conversationId);
    if (attachments.length === 0) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, JSON.stringify(attachments));
  } catch {
    // Storage can be unavailable in private mode; the in-memory state still works.
  }
}

function attachmentsFromMessages(messages: AgentMessage[]): AgentAttachment[] {
  for (let index = messages.length - 1; index >= 0; index--) {
    const attachments = messages[index].metadata?.messageInput?.attachments;
    if (attachments?.length) return attachments.filter(isAgentAttachment);
  }
  return [];
}

function pendingMediaFromAttachments(attachments: AgentAttachment[]) {
  return {
    refImageUrls: attachments.filter((item) => item.kind === 'image').map((item) => item.url),
    pickedReferenceVideos: attachments.filter((item) => item.kind === 'video').map((item) => item.url),
    pickedReferenceAudios: attachments.filter((item) => item.kind === 'audio').map((item) => item.url),
  };
}

function attachmentKind(file: File): AgentAttachmentKind {
  if (file.type.startsWith('image/')) return 'image';
  if (file.type.startsWith('video/') || file.type.includes('quicktime')) return 'video';
  if (file.type.startsWith('audio/')) return 'audio';
  return 'file';
}

function deepFindRecordWith(value: unknown, predicate: (record: Record<string, unknown>) => boolean): Record<string, unknown> | null {
  const record = asRecord(value);
  if (!record) return null;
  if (predicate(record)) return record;
  for (const child of Object.values(record)) {
    const found = deepFindRecordWith(child, predicate);
    if (found) return found;
  }
  return null;
}

function parseJsonRecord(value: unknown): Record<string, unknown> | null {
  const record = asRecord(value);
  if (record) return record;
  if (typeof value !== 'string' || !value.trim()) return null;
  try {
    return asRecord(JSON.parse(value));
  } catch {
    return null;
  }
}

/** Ordered per-turn envelope sequencer; gaps are held until replay or the missing event arrives. */
export class AgentTurnEventSequencer {
  private lastSeqByTurn = new Map<string, number>();
  private pendingByTurn = new Map<string, Map<number, AgentTurnEventEnvelope>>();

  accept(envelope: AgentTurnEventEnvelope): AgentTurnEventEnvelope[] {
    if (!envelope.turnId || !Number.isInteger(envelope.seq) || envelope.seq < 1) return [];
    const last = this.lastSeqByTurn.get(envelope.turnId) ?? 0;
    if (envelope.seq <= last) return [];
    const pending = this.pendingByTurn.get(envelope.turnId) ?? new Map<number, AgentTurnEventEnvelope>();
    pending.set(envelope.seq, envelope);
    const ready: AgentTurnEventEnvelope[] = [];
    let next = last + 1;
    for (;;) {
      const candidate = pending.get(next);
      if (!candidate) break;
      pending.delete(next);
      ready.push(candidate);
      this.lastSeqByTurn.set(envelope.turnId, next);
      next += 1;
    }
    if (pending.size > 0) this.pendingByTurn.set(envelope.turnId, pending);
    else this.pendingByTurn.delete(envelope.turnId);
    return ready;
  }

  lastSeq(turnId: string): number {
    return this.lastSeqByTurn.get(turnId) ?? 0;
  }

  reset(turnId: string) {
    this.lastSeqByTurn.delete(turnId);
    this.pendingByTurn.delete(turnId);
  }
}

export interface AgentScopeTimelineEntry {
  id: string;
  turnId: string;
  seq: number;
  kind: 'plan' | 'subagent' | 'artifact';
  title: string;
  status: string;
  summary?: string;
  agent?: string;
}

const turnEventSequencer = new AgentTurnEventSequencer();

type GenerationSnapshot = {
  hint: string;
  turnId?: string;
  assistantId?: string;
  assistantContent?: string;
  reasoningDraft?: AgentReasoning;
  traceDraft?: AgentTraceEvent[];
  usageDraft?: AgentTurnMetrics;
  toolDrafts?: Record<string, { name: string; text: string; input: string; status: string }>;
  assistantMessageMetadata?: AgentMessageMetadata;
  /** 本轮模型上下文窗口（turn_started 提前下发，不等 usage 事件）。 */
  contextWindowTokens?: number;
  /** message_end 已完成正文回复；异步视频任务可随后重新置回 false */
  finished?: boolean;
};

type StreamSettlement = {
  promise: Promise<void>;
  settle: () => void;
};

export type AgentTaskNotice = {
  taskId: string;
  title: string;
  conversationId: string;
  createdAt: string;
  storyboardRunId?: string;
};

type TaskUpsertOptions = {
  notifyIfTerminal?: boolean;
};

const streamSettlements = new Map<string, StreamSettlement>();
const titleRefreshTimers = new Map<string, ReturnType<typeof setTimeout>>();

const TITLE_REFRESH_DELAYS_MS = [1_200, 3_000, 7_000, 15_000, 30_000];

function cancelConversationTitleRefresh(conversationId: string) {
  const timer = titleRefreshTimers.get(conversationId);
  if (timer) clearTimeout(timer);
  titleRefreshTimers.delete(conversationId);
}

/**
 * 标题可能晚于 message_end 落库：先出现首句兜底，再由 AI 条件替换。
 * 首条消息没随 SSE 带回标题时，在 30 秒窗口内短暂拉取会话详情，覆盖两次更新后自动停止。
 */
function scheduleConversationTitleRefresh(conversationId: string) {
  cancelConversationTitleRefresh(conversationId);
  let attempt = 0;
  const poll = async () => {
    try {
      const res = await agentApi.getConversation(conversationId);
      const conversation = res.data.data?.conversation;
      if (conversation?.title && conversation.title !== '新对话') {
        useAgentStore.setState((s) => ({
          conversations: s.conversations.map((c) =>
            c.id === conversationId && c.title !== conversation.title ? conversation : c),
        }));
      }
    } catch {
      // 标题补齐是锦上添花；失败时允许下一次计划重试。
    }
    attempt += 1;
    if (attempt >= TITLE_REFRESH_DELAYS_MS.length) {
      titleRefreshTimers.delete(conversationId);
      return;
    }
    titleRefreshTimers.set(
      conversationId,
      setTimeout(() => { void poll(); }, TITLE_REFRESH_DELAYS_MS[attempt]),
    );
  };
  titleRefreshTimers.set(
    conversationId,
    setTimeout(() => { void poll(); }, TITLE_REFRESH_DELAYS_MS[0]),
  );
}

/** 同一会话 SSE 串行收尾：UI 可在 message_end 立即解锁，同时避免后端会话锁未释放时提交撞 40901。 */
async function reserveStreamSlot(conversationId: string): Promise<() => void> {
  const previous = streamSettlements.get(conversationId)?.promise;
  let settle!: () => void;
  const promise = new Promise<void>((resolve) => {
    settle = resolve;
  });
  const current: StreamSettlement = { promise, settle };
  streamSettlements.set(conversationId, current);
  if (previous) await previous;

  let released = false;
  return () => {
    if (released) return;
    released = true;
    if (streamSettlements.get(conversationId) === current) {
      streamSettlements.delete(conversationId);
    }
    settle();
    flushPendingMessageReconcile(conversationId);
  };
}

/**
 * 本轮 releaseStream 之后 streaming 标志才落定，所以对账延到下一个宏任务；
 * 若此时仍在流式（视频等后续轮询），refreshConversationMessages 会再次挂起，不做覆盖。
 */
function flushPendingMessageReconcile(conversationId: string) {
  if (!pendingMessageReconcile.has(conversationId)) return;
  window.setTimeout(() => {
    if (!pendingMessageReconcile.delete(conversationId)) return;
    void refreshConversationMessages(conversationId).catch(() => undefined);
  }, 0);
}

function generationInProgress(generation?: GenerationSnapshot): boolean {
  return !!generation && !generation.finished;
}

function generationHint(generation?: GenerationSnapshot): string {
  return generationInProgress(generation) ? generation?.hint ?? '' : '';
}

/** 完整正文保存到属主 generating；仅当前正在查看该会话时同步可见消息。 */
function storeAssistantContent(conversationId: string, assistantId: string, content: string) {
  useAgentStore.setState((state) => {
    const current = state.generating[conversationId] ?? { hint: '' };
    const generating = {
      ...state.generating,
      [conversationId]: { ...current, assistantId, assistantContent: content },
    };
    if (state.activeConversationId !== conversationId) return { generating };
    return {
      generating,
      messages: state.messages.map((message) =>
        message.id === assistantId ? { ...message, content } : message),
    };
  });
}

/** message_end 收到完整正文后立即结束对话中状态，不再等待持久化和 SSE EOF。 */
function finishAssistantReply(conversationId: string, assistantId: string) {
  useAgentStore.setState((state) => {
    const current = state.generating[conversationId];
    if (!current || current.assistantId !== assistantId) return {};

    const generating = {
      ...state.generating,
      [conversationId]: {
        ...current,
        hint: '',
        finished: true,
        reasoningDraft: undefined,
        traceDraft: undefined,
      },
    };
    const view = state.activeConversationId ? generating[state.activeConversationId] : undefined;
    return {
      generating,
      streaming: generationInProgress(view),
      workflowHint: generationHint(view),
      // 没在看这个会话时完成回复 → 侧边栏标记「新」，点进会话再清
      ...(state.activeConversationId !== conversationId
        ? { replyUnread: { ...state.replyUnread, [conversationId]: true } }
        : {}),
      ...(state.pendingAssistantId === assistantId ? { pendingAssistantId: null } : {}),
    };
  });
}

/** 视频任务已受理：正文即使已结束，也保持生成状态直到轮询到 completed/failed。 */
function resumeGenerationForTask(conversationId: string, assistantId: string, hint = '') {
  useAgentStore.setState((state) => {
    const current = state.generating[conversationId] ?? { hint };
    const currentMessage = state.messages.find((message) => message.id === assistantId);
    const fallbackContent = current.assistantContent?.trim()
      || currentMessage?.content?.trim()
      || '任务已受理，正在后台生成…';
    const generating = {
      ...state.generating,
      [conversationId]: {
        ...current,
        assistantId,
        assistantContent: fallbackContent,
        hint,
        finished: false,
      },
    };
    const view = state.activeConversationId ? generating[state.activeConversationId] : undefined;
    return {
      generating,
      streaming: generationInProgress(view),
      workflowHint: generationHint(view),
      messages: state.activeConversationId === conversationId && !currentMessage?.content?.trim()
        ? state.messages.map((message) =>
            message.id === assistantId ? { ...message, content: fallbackContent } : message)
        : state.messages,
    };
  });
}

export interface HumanInputInfo {
  turnId?: string;
  formToken: string;
  taskId: string;
  formContent: string;
  actions: { id: string; title: string }[];
  expirationTime: number;
  /** HITL 卡片对应的后端消息 id（human_input 事件 messageId；提交后写入 metadata.hitl） */
  messageId?: string;
  /** checkpoint 分类/事件名，用于刷新后区分 human_input 与 video_plan */
  kind?: 'human-input' | 'video-plan';
  hitlType?: string;
  /** video_plan checkpoint 恢复字段 */
  message?: string;
  duration?: number;
  picUrl?: string;
  // 卡片模型/参数选项 + LLM 推荐（后端 human_input 事件下发；无配置时为空数组/空对象）
  models?: GatewayModelOption[];
  imageModels?: GatewayModelOption[];
  videoModels?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
  // 资产选择卡片：可勾选的资产清单（后端 human_input 事件下发；缺失时不渲染勾选）
  assets?: AssetOption[];
}

export interface SideChatUiState {
  open: boolean;
  loading: boolean;
  focusToken: number;
  sourceConversationId: string | null;
  sourceTitle: string;
  projectId: string | null;
  conversationId: string | null;
  error: string | null;
}

/** 生成完成后的看图确认卡片（后端 confirm_result 事件） */
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

/** 图生视频方案确认卡片（后端 video_plan 事件）：视觉模型看图设计的方案，确认后生成 */
export interface VideoPlanInfo {
  turnId?: string;
  planToken: string;
  message: string;
  duration: number;
  picUrl: string;
  actions: { id: string; title: string }[];
  /** video_plan 事件携带的后端消息 id（human_input/video_plan 均带 messageId） */
  messageId?: string;
  // 卡片模型/参数选项 + LLM 推荐（后端 video_plan 事件下发；无配置时为空数组/空对象）
  models?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
}

interface AgentState {
  // 画布编辑模式：同一画布窗口的所有消息和 HITL 续流持续携带，直到用户显式离开。
  canvasEditContext: CanvasEditContext | null;
  canvasBinding: CanvasBinding | null;
  canvasBindingStatus: string;
  canvasExitPending: boolean;
  canvasSession: CanvasSessionInfo | null;
  canvasSessionStatus: string;
  canvasSessionSelection: { canvasId?: string; createNew: boolean };
  setCanvasEditContext: (context: CanvasEditContext | null) => void;
  startCanvasBinding: (binding: CanvasBinding) => void;
  setCanvasBindingStatus: (status: string) => void;
  cancelCanvasBinding: () => void;
  resumeCanvasBinding: (context: CanvasEditContext) => Promise<void>;
  beginCanvasSessionOpen: (canvasId?: string, createNew?: boolean) => void;
  readyCanvasSession: () => Promise<void>;
  cancelCanvasSession: () => Promise<void>;
  addCanvasMediaRefs: (refs: AgentMediaRef[]) => void;
  removeCanvasMediaRef: (url?: string) => void;
  moveCanvasMediaRef: (url: string, direction: -1 | 1) => void;
  restoreCanvasMode: (conversationId: string) => Promise<void>;
  leaveCanvasEditMode: () => void;
  exitCanvasModeNow: () => void;

  // 窗口
  // 临时侧边：仅保存在内存，刷新不恢复；实际聊天由独立 iframe 承载。
  sideChat: SideChatUiState;
  openSideChat: (sourceConversationId?: string, sourceTitle?: string) => Promise<void>;
  closeSideChat: (options?: { keepalive?: boolean }) => Promise<void>;

  // 会话
  conversations: AgentConversation[];
  activeConversationId: string | null;
  /** 新会话草稿模式；已有会话以 AgentConversation.approvalMode 为准。 */
  pendingApprovalMode: AgentApprovalMode;
  loadingConversations: boolean;
  loadConversations: () => Promise<void>;
  /** 新建对话：仅重置本地为空白草稿（不落库、不进列表）。真正建会话在首次发送消息时由 sendMessage 自动完成。 */
  newConversation: () => void;
  /** 真正创建并落库一个会话（sendMessage 首次发送时自动调用）：POST 后端 + 插到列表头 + 选中。 */
  createConversation: () => Promise<void>;
  setApprovalMode: (
    mode: AgentApprovalMode,
    options?: { confirmedFullAccess?: boolean },
  ) => Promise<void>;
  renameConversation: (id: string, title: string) => Promise<void>;
  setConversationStatus: (id: string, status: 'active' | 'archived') => Promise<void>;
  deleteConversation: (id: string) => Promise<void>;
  // 清空当前会话聊天记录（删消息 + 重置 AI 上下文；会话与资产保留，其他会话不受影响）
  clearMessages: () => Promise<void>;
  selectConversation: (id: string) => Promise<void>;

  // 消息
  messages: AgentMessage[];
  messageNextCursor: string | null;
  messageHasMore: boolean;
  loadOlderMessages: () => Promise<void>;
  streaming: boolean;
  /** AgentScope plan/subagent/artifact timeline, scoped by conversation. */
  timelineByConversation: Record<string, AgentScopeTimelineEntry[]>;
  /** Replay durable turn events after a known sequence, then reduce them in order. */
  replayTurnEvents: (conversationId: string, turnId: string, afterSeq?: number) => Promise<void>;
  /** 会话级进行中生成状态（key=conversationId）：切走保留、切回恢复；streaming/workflowHint 由此派生 */
  generating: Record<string, GenerationSnapshot>;
  waitingHumanInput: HumanInputInfo | null;
  streamError: string | null;
  confirmResult: ConfirmResultInfo | null;
  // 当前运行阶段提示（workflow 事件标题 / 视频异步生成进度；渲染在聊天窗口「正在生成」行）
  workflowHint: string;
  setWorkflowHint: (v: string) => void;
  // 智能体视频异步任务（进行中；completed/failed 后清空）——任务中心悬浮球展示用
  agentVideoTask: { taskId: string; status: 'queued' | 'running'; waitingSec: number } | null;
  // 统一智能体后台任务：key=taskId，version 保证 SSE/轮询乱序时不回退。
  tasks: Record<string, AgentTaskView>;
  taskNotices: Record<string, AgentTaskNotice[]>;
  taskUnread: Record<string, number>;
  /** 非当前查看的会话完成一轮回复后置位；点进该会话即清空（侧边栏「新」标记）。 */
  replyUnread: Record<string, boolean>;
  taskDrawerId: string | null;
  taskFocusId: string | null;
  taskStreamConnected: boolean;
  /** 后台任务运行期的实时输出（key=taskId），由 task_stream 事件推送。 */
  taskStream: Record<string, string>;
  /** 后台任务运行期的思考链（key=taskId），由 task_stream 事件推送。 */
  taskThinking: Record<string, string>;
  /** 每个任务实时流的最后序号，防止 SSE 重连后旧帧覆盖新帧。 */
  taskStreamSeq: Record<string, number>;
  startTaskStream: () => void;
  stopTaskStream: () => void;
  loadTasks: (conversationId?: string) => Promise<void>;
  upsertTask: (task: AgentTaskView, options?: TaskUpsertOptions) => void;
  openTaskDrawer: (taskId: string) => void;
  closeTaskDrawer: () => void;
  focusTask: (taskId: string | null) => void;
  dismissTaskNotice: (conversationId: string, taskId: string) => void;
  cancelTask: (taskId: string) => Promise<void>;
  retryTask: (taskId: string) => Promise<void>;
  followUpTask: (taskId: string, instruction: string) => Promise<void>;
  // I2：当前流式轮次的 assistant 占位 id（HITL 续流时复用同一气泡追加）
  pendingAssistantId: string | null;
  editAndResend: (content: string, messageId?: string) => Promise<void>;
  interruptAndSend: (content: string) => Promise<void>;

  // 输入
  refImageUrls: string[];
  setRefImageUrls: (v: string[]) => void;
  addRefImageUrls: (urls: string[]) => void;
  removeRefImageUrl: (index: number) => void;
  pendingAttachments: AgentAttachment[];
  setPendingAttachments: (attachments: AgentAttachment[]) => void;
  addPendingAttachments: (attachments: AgentAttachment[]) => void;
  removePendingAttachment: (url: string) => void;
  clearPendingAttachments: () => void;
  uploadRefImage: (file: File, purpose?: string, kind?: 'reference' | 'asset') => Promise<void>;
  uploadRefImages: (files: File[], purposes?: string[], kinds?: Array<'reference' | 'asset'>) => Promise<void>;
  addLocalUserMessage: (content: string) => void;

  // 本次携带的资产ID（持久化到发送时消费，不随渲染丢失）
  pickedAssetIds: string[];
  addPickedAssetId: (id: string) => void;
  removePickedAssetId: (id: string) => void;
  clearPickedAssetIds: () => void;
  setPickedAssetIds: (ids: string[]) => void;

  pickedReferenceVideos: string[];
  pickedReferenceAudios: string[];
  setPickedReferenceMedia: (videos: string[], audios: string[]) => void;
  clearPickedReferenceMedia: () => void;

  // 互斥标志（会话级，刷新即恢复）
  agentGeneratedScenes: boolean;
  setAgentGeneratedScenes: (v: boolean) => void;

  // 资产
  assets: AgentPage<AgentAsset> | null;
  loadAssets: (page?: number) => Promise<void>;
  deleteAsset: (id: string) => Promise<void>;

  // 发送
  sendMessage: (content: string, opts?: {
    picUrls?: string[];
    assetIds?: string[];
    assetGroupIds?: string[];
    assetGroupSnapshotIds?: string[];
    referenceVideos?: string[];
    referenceAudios?: string[];
    routingHint?: string;
    cancelActiveTasks?: boolean;
    editMessageId?: string;
    clientRequestId?: string;
    interruptCurrent?: boolean;
    attachments?: AgentAttachment[];
  }) => Promise<void>;
  submitHumanInput: (
    actionId: string,
    customText?: string,
    params?: Record<string, string>,
    assetIds?: string[],
    opts?: { hiddenCard?: boolean; formToken?: string; turnId?: string; canvasContext?: CanvasEditContext },
  ) => Promise<void>;
  // 图生视频方案确认卡片（video_plan 事件）：开始生成视频 → 后端生成；继续完善 → 本地保留参考图
  waitingVideoPlan: VideoPlanInfo | null;
  /** 会话级视频方案确认卡片（发起时存；切回恢复；提交/跳过清） */
  hitlVideoPlan: Record<string, VideoPlanInfo>;
  submitVideoPlan: (actionId: string, params?: Record<string, string>, customText?: string) => Promise<void>;
  // 跳过当前 HITL 卡片（用户想切换话题时使用，checkpoint 30 分钟后自动过期）
  skipCurrentHITL: () => void;
  /** 流结束后恢复已落库但未送达的待处理 HITL 卡片（SSE 被断开/超时后无需刷新即可补显）。 */
  recoverPendingCheckpoint: (conversationId: string) => Promise<void>;
  // ── 分镜视频批量生成队列（右上角任务队列） ──
  storyboardVideoQueue: StoryboardVideoQueueItem[];
  enqueueStoryboardVideoQueue: (items: StoryboardVideoEnqueueItem[], projectId?: string) => Promise<string[]>;
  refetchStoryboardVideoQueue: (projectId?: string) => Promise<void>;
  cancelStoryboardVideoQueue: (queueId: string) => Promise<void>;
  // ── 分镜「完善图片」队列 ──
  storyboardImageQueue: StoryboardImageQueueItem[];
  enqueueStoryboardImageRefine: (items: StoryboardImageEnqueueItem[], projectId?: string) => Promise<string[]>;
  refetchStoryboardImageQueue: (projectId?: string) => Promise<void>;
  cancelStoryboardImageQueue: (queueId: string) => Promise<void>;
  // 停止当前会话生成（「停止生成」按钮）：中断 SSE 流 + 后端协作式取消 + 复位视图
  stopGeneration: () => void;
  // 看图确认卡片（confirm_result 事件）：继续完善 / 满意完成
  refineAsset: () => void;
  dismissConfirm: () => void;
  // 继续完善参考图：点"继续完善"后不自动发送，暂存 PicUrls 等待用户输入完善需求（随下一条消息发送并消费）
  pendingPicUrls: string[];
  cancelRefine: () => void;
  resetChatState: () => void;

  // 页面级状态
  activeModal: 'storyboard' | 'assets' | 'project' | 'settings' | null;
  setActiveModal: (modal: AgentState['activeModal']) => void;
  historyExpanded: boolean;
  setHistoryExpanded: (v: boolean) => void;
}

let initialSceneCount = 0;

  // 当前流式会话的 AbortController + 用户手动停止标志：点「停止生成」时中断 SSE fetch（typewriter 停）并委托后端协作式取消
let streamAbort: AbortController | null = null;
let manualStop = false;
// 视频异步任务轮询句柄 + 代次令牌：新一轮发送/停止时作废旧轮询，防止过期轮询结果污染新流
let videoPollTimer: ReturnType<typeof setInterval> | null = null;
let videoPollToken = 0;
let taskStreamAbort: AbortController | null = null;
let taskStreamRunning = false;
let taskFallbackTimer: ReturnType<typeof setTimeout> | null = null;
// 流式期间被拦下的消息对账：等本轮结束再补拉，避免刷新整表覆盖正在生成的气泡。
const pendingMessageReconcile = new Set<string>();

const ACTIVE_TASK_STATUSES = new Set<AgentTaskView['status']>([
  'waiting_parent', 'queued', 'running', 'cancelling',
]);

function isDetachedAsyncTask(taskKind: string | null | undefined): boolean {
  return taskKind === 'long-tool' || taskKind === 'image-batch';
}

export function isActiveTask(task: AgentTaskView | undefined | null): boolean {
  return !!task && ACTIVE_TASK_STATUSES.has(task.status);
}

/** 维护任务实时输出：text 为空即移除该任务的条目。 */
function withTaskStream(
  stream: Record<string, string>,
  taskId: string,
  text: string,
): Record<string, string> {
  if (!taskId) return stream;
  if (!text) {
    if (!stream[taskId]) return stream;
    const next = { ...stream };
    delete next[taskId];
    return next;
  }
  return { ...stream, [taskId]: text };
}

export function taskStatusLabel(task: AgentTaskView): string {
  if (task.status === 'waiting_parent') return '等待前置任务';
  if (task.status === 'queued') return '排队中';
  if (task.status === 'cancelling') return '取消中';
  if (task.status === 'cancelled') return '已取消';
  if (task.status === 'failed') return '失败';
  if (task.status === 'completed') return task.stage === 'waiting_review' ? '待确认' : '已完成';
  if (task.status === 'partial') return '部分完成';
  if (task.stage === 'plot_diagnosis') return '剧情诊断中';
  if (task.stage === 'script_optimization') return '脚本优化中';
  if (task.stage === 'pseudo_planning') return '伪分镜生成中';
  if (task.stage === 'final_generation') return '正式镜头生成中';
  if (task.stage === 'generating_images') return '图片生成中';
  return '处理中';
}

function mergeTask(
  current: Record<string, AgentTaskView>,
  incoming: AgentTaskView,
  options?: TaskUpsertOptions,
): { tasks: Record<string, AgentTaskView>; completedNotice: boolean } {
  const previous = current[incoming.taskId];
  if (previous && previous.version >= incoming.version) {
    return { tasks: current, completedNotice: false };
  }
  const terminalWithoutReview = !isActiveTask(incoming)
    && incoming.stage !== 'waiting_review';
  const completedNotice = terminalWithoutReview && (
    (!!previous && isActiveTask(previous))
    || !!options?.notifyIfTerminal
  );
  return {
    tasks: { ...current, [incoming.taskId]: incoming },
    completedNotice: completedNotice && incoming.status !== 'cancelled',
  };
}

export function storyboardRunIdFromTask(task: AgentTaskView): string {
  const text = `${task.result ?? ''}\n${task.resultSummary ?? ''}`;
  const match = text.match(/(?:run[_ ]?id|runid)\s*[:：]\s*`?([0-9a-fA-F-]{32,36})`?/);
  return match?.[1] ?? '';
}

async function refreshAfterWakeup(message: AgentWakeupMessage) {
  const conversationId = message.conversationId;
  try {
    const taskResponse = await agentApi.getTask(message.taskId);
    useAgentStore.getState().upsertTask(taskResponse.data.data, {
      notifyIfTerminal: true,
    });
  } catch {
    // Message refresh below remains the fallback when task detail is temporarily unavailable.
  }
  if (useAgentStore.getState().activeConversationId === conversationId) {
    await refreshConversationMessages(conversationId);
    return;
  }
  useAgentStore.setState((state) => ({
    taskUnread: {
      ...state.taskUnread,
      [conversationId]: (state.taskUnread[conversationId] ?? 0) + 1,
    },
  }));
}

const libraryMediaRefreshCache = new Map<string, { media: LibraryMedia; freshUntil: number }>();

async function resolveLibraryMedia(
  conversationId: string,
  mediaId: string,
): Promise<LibraryMedia | null> {
  const cacheKey = `${conversationId}:${mediaId}`;
  const cached = libraryMediaRefreshCache.get(cacheKey);
  if (cached && cached.freshUntil > Date.now()) return cached.media;
  const response = await libraryApi.getMedia(mediaId);
  const media = response.data.data;
  if (!media) return null;
  libraryMediaRefreshCache.set(cacheKey, {
    media,
    freshUntil: Date.now() + 10 * 60 * 1000,
  });
  return media;
}

async function refreshConversationMessages(conversationId: string) {
  const before = useAgentStore.getState();
  if (before.activeConversationId !== conversationId) return;
  if (before.streaming) {
    pendingMessageReconcile.add(conversationId);
    return;
  }
  const page = await loadMessagePage(conversationId);
  const state = useAgentStore.getState();
  if (state.activeConversationId !== conversationId) return;
  if (state.streaming) {
    pendingMessageReconcile.add(conversationId);
    return;
  }
  const messages = await refreshLibraryMediaMessages(
    page.records,
    (mediaId) => resolveLibraryMedia(conversationId, mediaId),
  );
  useAgentStore.setState({
    messages,
    messageNextCursor: page.nextCursor,
    messageHasMore: page.hasMore,
  });
}

async function loadMessagePage(conversationId: string, before?: string | null): Promise<AgentMessagePage> {
  try {
    const response = await agentApi.listMessagesPage(conversationId, before);
    const page = response.data.data ?? { records: [], nextCursor: null, hasMore: false };
    if (before || page.records.length > 0) return page;
    // 兼容未部署 /messages/page 或旧数据返回空的运行实例。
    const legacy = await agentApi.listMessages(conversationId);
    const records = legacy.data.data ?? [];
    return records.length ? { records, nextCursor: null, hasMore: false } : page;
  } catch (error) {
    if (before) throw error;
    const legacy = await agentApi.listMessages(conversationId);
    return {
      records: legacy.data.data ?? [],
      nextCursor: null,
      hasMore: false,
    };
  }
}

function taskKindHint(kind: VideoTaskStatus['kind'], status: string, waitingSec: number) {
  const waiting = `已等待约 ${waitingSec} 秒`;
  if (kind === 'image-batch') {
    return status === 'queued' ? `批量生图排队中…（${waiting}）` : `批量生图中…（${waiting}）`;
  }
  if (kind === 'long-tool') {
    return status === 'queued' ? `后台任务排队中…（${waiting}）` : `后台任务处理中…（${waiting}）`;
  }
  return status === 'queued' ? `视频排队中…（${waiting}）` : `视频生成中…（${waiting}）`;
}

function taskCompletedContent(task: VideoTaskStatus) {
  if (task.result) return task.result;
  const urls = task.resultUrls?.length ? task.resultUrls : [task.url];
  if (task.kind === 'image-batch' || task.kind === 'long-tool') {
    return urls.filter(Boolean).map((url) => `![生成图片](${url})`).join('\n\n');
  }
  return task.url || '';
}

function appendTaskResult(existing: string | undefined, result: string) {
  const current = existing?.trim() ?? '';
  const addition = result?.trim() ?? '';
  if (!addition) return current;
  if (!current) return addition;
  if (current.includes(addition)) return current;
  return `${current}\n\n${addition}`;
}

function appendTerminalTaskResult(
  messages: AgentMessage[],
  task: AgentTaskView,
): AgentMessage[] {
  if (isActiveTask(task) || !task.result?.trim()) return messages;
  return messages.map((message) => {
    const linkedByCard = message.metadata?.taskCards
      ?.some((card) => card.taskId === task.taskId) ?? false;
    const linkedByAnchor = !!task.anchorMessageId && message.id === task.anchorMessageId;
    if (message.role !== 'assistant' || (!linkedByCard && !linkedByAnchor)) return message;
    const startMarker = `[//]: # (async-task:${task.taskId}:start)`;
    const endMarker = `[//]: # (async-task:${task.taskId}:end)`;
    const block = `${startMarker}\n${task.result.trim()}\n${endMarker}`;
    const start = message.content.indexOf(startMarker);
    const end = message.content.indexOf(endMarker, Math.max(0, start));
    const content = start >= 0 && end >= start
      ? `${message.content.slice(0, start)}${block}${message.content.slice(end + endMarker.length)}`
      : appendTaskResult(message.content, block);
    return content === message.content ? message : { ...message, content };
  });
}

function attachTaskCard(
  conversationId: string,
  assistantId: string,
  task: Pick<AgentTaskView, 'taskId' | 'title' | 'kind'>,
) {
  useAgentStore.setState((state) => {
    if (state.activeConversationId !== conversationId) return {};
    return {
      messages: state.messages.map((message) => {
        if (message.id !== assistantId) return message;
        const existing = message.metadata?.taskCards ?? [];
        const taskCards = [
          ...existing.filter((item) => item.taskId !== task.taskId),
          { taskId: task.taskId, title: task.title, kind: task.kind },
        ];
        return {
          ...message,
          metadata: { ...message.metadata, taskCards },
        };
      }),
    };
  });
}

/** UnifiedLoop 异步任务：收到 task_accepted 后独立轮询到终态。 */
function beginUnifiedVideoPolling(
  taskId: string,
  conversationId: string,
  assistantId: string,
  taskKind?: VideoTaskStatus['kind'],
) {
  if (!taskId) return;
  resumeGenerationForTask(conversationId, assistantId,
    taskKind === 'image-batch' ? '批量生图中...' : taskKind === 'long-tool' ? '后台任务处理中...' : '视频生成中...');
  const myToken = ++videoPollToken;
  let waitingSec = 0;
  useAgentStore.setState({ agentVideoTask: { taskId, status: 'queued', waitingSec: 0 } });
  void agentApi.getTask(taskId).then((response) => {
    useAgentStore.getState().upsertTask(response.data.data, { notifyIfTerminal: true });
    attachTaskCard(conversationId, assistantId, response.data.data);
  }).catch(() => undefined);
  const timer = setInterval(async () => {
    if (videoPollToken !== myToken || useAgentStore.getState().agentVideoTask?.taskId !== taskId) {
      clearInterval(timer);
      if (videoPollTimer === timer) videoPollTimer = null;
      return;
    }
    waitingSec += 5;
    try {
      const response = await agentApi.getVideoTaskStatus(taskId);
      const task = response.data.data;
      if (task.status === 'completed') {
        void agentApi.getTask(taskId).then((detail) => {
          useAgentStore.getState().upsertTask(detail.data.data, { notifyIfTerminal: true });
        }).catch(() => undefined);
        clearInterval(timer);
        if (videoPollTimer === timer) videoPollTimer = null;
        useAgentStore.setState((state) => {
          const generating = { ...state.generating };
          if (generating[conversationId]?.assistantId === assistantId) delete generating[conversationId];
        const currentGenerating = state.activeConversationId ? generating[state.activeConversationId] : undefined;
        const active = state.activeConversationId === conversationId;
        const completedContent = appendTaskResult(
          state.generating[conversationId]?.assistantContent
            ?? state.messages.find((message) => message.id === assistantId)?.content,
          taskCompletedContent(task));
        return {
            generating,
          streaming: generationInProgress(currentGenerating),
          workflowHint: generationHint(currentGenerating),
            pendingAssistantId: null,
            agentVideoTask: null,
            messages: active ? state.messages.map((message) =>
              message.id === assistantId ? { ...message, content: completedContent } : message) : state.messages,
            confirmResult: active && task.kind !== 'long-tool' ? {
              kind: task.kind === 'image-batch' ? 'image' : 'video',
              url: task.url || '',
              urls: task.resultUrls,
              assetId: task.assetId || task.assetIds?.[0],
              sceneCount: 0,
              actions: [{ id: 'refine', title: '继续完善' }, { id: 'done', title: '满意完成' }],
            } : state.confirmResult,
          };
        });
        void useAgentStore.getState().loadAssets();
        if (task.kind === 'image-batch' && task.followUpTaskId) {
          beginUnifiedVideoPolling(task.followUpTaskId, conversationId, assistantId, 'video');
        }
        return;
      }
      if (task.status === 'failed') {
        clearInterval(timer);
        if (videoPollTimer === timer) videoPollTimer = null;
        useAgentStore.setState((state) => {
          const generating = { ...state.generating };
          if (generating[conversationId]?.assistantId === assistantId) delete generating[conversationId];
        const currentGenerating = state.activeConversationId ? generating[state.activeConversationId] : undefined;
        const active = state.activeConversationId === conversationId;
        return {
            generating,
            streaming: generationInProgress(currentGenerating),
            workflowHint: generationHint(currentGenerating),
            agentVideoTask: null,
            streamError: active ? (task.error || '任务生成失败，请重试') : state.streamError,
          };
        });
        return;
      }
      useAgentStore.setState((state) => {
        const status = task.status === 'queued' ? 'queued' : 'running';
        const hint = taskKindHint(task.kind ?? taskKind, status, waitingSec);
        return {
          agentVideoTask: { taskId, status, waitingSec },
          workflowHint: state.activeConversationId === conversationId ? hint : state.workflowHint,
          generating: {
            ...state.generating,
            [conversationId]: { ...(state.generating[conversationId] ?? { hint: '' }), assistantId, hint },
          },
        };
      });
    } catch {
      // 单次轮询失败继续，下一 tick 自愈。
    }
  }, VIDEO_POLL_INTERVAL);
  videoPollTimer = timer;
}

/** 从会话 generating 记录恢复 assistant 占位气泡的消息元数据（切会话后重建占位用） */
function buildGeneratingMetadata(g?: { reasoningDraft?: AgentReasoning; traceDraft?: AgentTraceEvent[]; assistantMessageMetadata?: AgentMessageMetadata }): AgentMessageMetadata | undefined {
  if (g?.assistantMessageMetadata) return g.assistantMessageMetadata;
  if (g?.reasoningDraft || (g?.traceDraft && g.traceDraft.length > 0)) {
    return { kind: 'assistant-reply', reasoning: g.reasoningDraft, agentTrace: g.traceDraft };
  }
  return undefined;
}

/** 从消息元数据反解出 HITL 卡片信息（刷新后从 AgentMessage.metadata.hitl 恢复） */
export function hitlFromMetadata(metadata?: AgentMessageMetadata): HumanInputInfo | null {
  const h = metadata?.hitl;
  if (!h) return null;
  return {
    formToken: '',
    taskId: '',
    formContent: h.formContent ?? '',
    actions: h.actions ?? [],
    expirationTime: 0,
    messageId: h.messageId,
  };
}

/** 刷新当前项目的视频/图片任务队列：分镜审查触发的生成（全自动视频/完善图片）入队后即时可见，无需刷新页面。 */
function refreshStoryboardQueues() {
  const projectId = useProjectStore.getState().currentProject?.id;
  if (!projectId) return;
  void useAgentStore.getState().refetchStoryboardVideoQueue(projectId);
  void useAgentStore.getState().refetchStoryboardImageQueue(projectId);
}

async function restorePendingCanvasSession(conversationId: string) {
  try {
    const response = await agentApi.getPendingCanvasSession(conversationId);
    const value = response.data.data;
    if (value && typeof value === 'object' && 'sessionId' in value) {
      const session = value as CanvasSessionInfo;
      useAgentStore.setState({
        canvasSession: session,
        canvasSessionStatus: '',
        canvasSessionSelection: { createNew: false },
      });
      if (session.status === 'pending_open' && session.canvasId) {
        useAgentStore.getState().beginCanvasSessionOpen(session.canvasId, false);
      }
    }
  } catch {
    // Canvas Session is optional; keep the rest of conversation restoration intact.
  }
}

const canvasSessionResumeLocks = new Set<string>();

async function reconnectTurnThenRecover(conversationId: string, turnIdOverride?: string) {
  const state = useAgentStore.getState();
  const turnId = turnIdOverride || state.generating[conversationId]?.turnId;
  if (turnId) {
    try {
      await state.replayTurnEvents(conversationId, turnId);
    } catch {
      // Fall through to checkpoint recovery; replay is best-effort during disconnects.
    }
  }
  await useAgentStore.getState().recoverPendingCheckpoint(conversationId);
  await refreshConversationMessages(conversationId);
}

function reconnectDelay(ms: number) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, ms));
}

/**
 * A page refresh drops the browser SSE connection, but the backend turn keeps
 * running and records every sequenced event. Rebuild the generating bubble from
 * the persisted turn and poll event replay until a terminal envelope arrives.
 */
async function restorePersistedTurn(conversationId: string) {
  let active: AgentActiveTurn | undefined;
  try {
    const response = await agentApi.getRuntimeStatus(conversationId);
    active = response.data.data.activeTurn;
  } catch {
    return;
  }
  if (!active || active.status !== 'running') return;
  if (useAgentStore.getState().activeConversationId !== conversationId) return;
  if (useAgentStore.getState().generating[conversationId]?.finished === false) return;

  const assistantId = `restored-assistant-${active.turnId}`;
  useAgentStore.setState((state) => {
    if (state.activeConversationId !== conversationId) return state;
    const messages = state.messages.filter((message) => message.id !== assistantId);
    return {
      streaming: true,
      pendingAssistantId: assistantId,
      workflowHint: 'MoonAgent 正在处理',
      generating: {
        ...state.generating,
        [conversationId]: {
          hint: 'MoonAgent 正在处理',
          turnId: active.turnId,
          assistantId,
          assistantContent: '',
          finished: false,
        },
      },
      messages: [...messages, {
        id: assistantId,
        conversationId,
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
    };
  });

  let afterSeq = 0;
  let terminal: AgentTurnEventEnvelope['type'] | null = null;
  let pollCount = 0;
  while (useAgentStore.getState().activeConversationId === conversationId) {
    let events: AgentTurnEventEnvelope[] = [];
    try {
      const response = await agentApi.listTurnEvents(conversationId, active.turnId, afterSeq);
      events = response.data.data ?? [];
    } catch {
      await reconnectDelay(1500);
      continue;
    }
    for (const envelope of events) {
      applyAgentTurnEnvelope(envelope, { conversationId, assistantId });
      afterSeq = Math.max(afterSeq, envelope.seq);
      if (envelope.type === 'turn_completed'
          || envelope.type === 'turn_failed'
          || envelope.type === 'turn_paused') {
        terminal = envelope.type;
      }
    }
    if (terminal) break;
    pollCount += 1;
    if (pollCount % 5 === 0) {
      try {
        const latest = await agentApi.getRuntimeStatus(conversationId);
        const status = latest.data.data.activeTurn?.status;
        if (!status || status === 'waiting_user') break;
      } catch {
        // Keep replaying; a transient status failure should not abort recovery.
      }
    }
    await reconnectDelay(1000);
  }

  if (terminal === 'turn_completed') finishAssistantReply(conversationId, assistantId);
  useAgentStore.setState((state) => {
    const generating = { ...state.generating };
    if (generating[conversationId]?.assistantId === assistantId) delete generating[conversationId];
    const view = state.activeConversationId ? generating[state.activeConversationId] : undefined;
    return {
      generating,
      streaming: generationInProgress(view),
      workflowHint: generationHint(view),
      ...(state.pendingAssistantId === assistantId ? { pendingAssistantId: null } : {}),
    };
  });
  if (terminal === 'turn_completed'
      && useAgentStore.getState().activeConversationId === conversationId) {
    await refreshConversationMessages(conversationId);
  }
}

type AgentScopeTaskAccepted = {
  taskId: string;
  taskKind: 'video' | 'image-batch' | 'long-tool';
  message: string;
};

export type AgentTurnEnvelopeEffects = {
  started: boolean;
  paused: boolean;
  completed: boolean;
  failed: boolean;
  canvasModeRequired: Record<string, unknown> | null;
  canvasSessionRequired: CanvasSessionInfo | null;
  taskAccepted: AgentScopeTaskAccepted | null;
  confirmResult: ConfirmResultInfo | null;
};

type TurnReductionContext = {
  conversationId: string;
  assistantId: string;
};

function emptyTurnEffects(): AgentTurnEnvelopeEffects {
  return {
    started: false,
    paused: false,
    completed: false,
    failed: false,
    canvasModeRequired: null,
    canvasSessionRequired: null,
    taskAccepted: null,
    confirmResult: null,
  };
}

function upsertTimelineEntry(
  conversationId: string,
  entry: AgentScopeTimelineEntry,
) {
  useAgentStore.setState((state) => {
    const current = state.timelineByConversation[conversationId] ?? [];
    const index = current.findIndex((item) => item.id === entry.id);
    const next = index >= 0
      ? current.map((item) => item.id === entry.id ? { ...item, ...entry } : item)
      : [...current, entry];
    return {
      timelineByConversation: {
        ...state.timelineByConversation,
        [conversationId]: next,
      },
    };
  });
}

function setLiveReasoning(context: TurnReductionContext, reasoning: AgentReasoning) {
  useAgentStore.setState((state) => {
    const generating = {
      ...state.generating,
      [context.conversationId]: {
        ...(state.generating[context.conversationId] ?? { hint: '' }),
        reasoningDraft: reasoning,
      },
    };
    if (state.activeConversationId !== context.conversationId) return { generating };
    return {
      generating,
      workflowHint: reasoning.activity
        ? `正在思考：${reasoning.activity}`
        : 'MoonAgent 正在处理',
      messages: state.messages.map((message) =>
        message.id === context.assistantId
          ? { ...message, metadata: { ...(message.metadata ?? {}), kind: message.metadata?.kind ?? 'assistant-reply', reasoning } }
          : message),
    };
  });
}

function setLiveTrace(context: TurnReductionContext, traceEvent: AgentTraceEvent) {
  useAgentStore.setState((state) => {
    const generation = state.generating[context.conversationId] ?? { hint: '' };
    const current = generation.traceDraft ?? [];
    const existing = current.findIndex((item) => item.id === traceEvent.id);
    const traceDraft = existing >= 0
      ? current.map((item) => item.id === traceEvent.id ? { ...item, ...traceEvent } : item)
      : [...current, traceEvent];
    const generating = {
      ...state.generating,
      [context.conversationId]: { ...generation, traceDraft },
    };
    if (state.activeConversationId !== context.conversationId) return { generating };
    return {
      generating,
      messages: state.messages.map((message) =>
        message.id === context.assistantId
          ? { ...message, metadata: { ...(message.metadata ?? {}), kind: message.metadata?.kind ?? 'assistant-reply', agentTrace: traceDraft } }
          : message),
    };
  });
}

function mergeUsage(
  context: TurnReductionContext,
  data: Record<string, unknown>,
): AgentTurnMetrics | null {
  const usage = asRecord(data.usage) ?? data;
  const inputTokens = asNumber(usage.inputTokens ?? usage.input_tokens);
  const outputTokens = asNumber(usage.outputTokens ?? usage.output_tokens);
  const cachedInputTokens = asNumber(usage.cachedTokens ?? usage.cached_input_tokens);
  const totalTokens = asNumber(usage.totalTokens ?? usage.total_tokens, inputTokens + outputTokens);
  // 上下文占用由后端权威上报：末次调用 input（取最大，不累加）；窗口取最新值。
  const contextTokens = asNumber(usage.contextTokens ?? usage.context_tokens);
  const contextWindowTokens = asNumber(usage.contextWindowTokens ?? usage.context_window_tokens);
  if (!inputTokens && !outputTokens && !cachedInputTokens && !totalTokens) return null;
  let next: AgentTurnMetrics | null = null;
  useAgentStore.setState((state) => {
    const generation = state.generating[context.conversationId] ?? { hint: '' };
    const previous = generation.usageDraft;
    next = {
      inputTokens: (previous?.inputTokens ?? 0) + inputTokens,
      outputTokens: (previous?.outputTokens ?? 0) + outputTokens,
      cachedInputTokens: (previous?.cachedInputTokens ?? 0) + cachedInputTokens,
      totalTokens: (previous?.totalTokens ?? 0) + totalTokens,
      contextTokens: Math.max(previous?.contextTokens ?? 0, contextTokens),
      contextWindowTokens: contextWindowTokens || previous?.contextWindowTokens,
      durationMs: previous?.durationMs ?? asNumber(data.durationMs),
      modelCalls: (previous?.modelCalls ?? 0) + 1,
      complete: false,
    };
    const generating = {
      ...state.generating,
      [context.conversationId]: { ...generation, usageDraft: next },
    };
    if (state.activeConversationId !== context.conversationId) return { generating };
    return {
      generating,
      messages: state.messages.map((message) =>
        message.id === context.assistantId
          ? { ...message, metadata: { ...(message.metadata ?? {}), metrics: next ?? undefined } }
          : message),
    };
  });
  return next;
}

function toolStatus(eventType: string, data: Record<string, unknown>): string {
  if (eventType === 'TOOL_CALL_START') return 'node_started';
  if (eventType === 'TOOL_RESULT_START') return 'node_running';
  if (eventType === 'TOOL_RESULT_END') {
    const state = asString(data.state).toUpperCase();
    return state.includes('FAIL') || state.includes('DENIED') ? 'node_failed' : 'node_completed';
  }
  return 'node_running';
}

function toolTitle(eventType: string, toolName: string): string {
  if (eventType === 'TOOL_CALL_START') return `调用工具：${toolName}`;
  if (eventType === 'TOOL_RESULT_START') return `工具执行：${toolName}`;
  if (eventType === 'TOOL_RESULT_END') return `工具完成：${toolName}`;
  return `工具流式输出：${toolName}`;
}

function timelineKindForTool(toolName: string): AgentScopeTimelineEntry['kind'] | null {
  if (toolName === 'plan_enter' || toolName === 'plan_write' || toolName === 'plan_exit' || toolName === 'todo_write') {
    return 'plan';
  }
  if (toolName === 'deliver_artifact') return 'artifact';
  return null;
}

function timelineTitleForTool(toolName: string, draft?: { input: string }) {
  if (toolName === 'plan_enter') return '进入规划模式';
  if (toolName === 'plan_write') {
    const parsed = parseJsonRecord(draft?.input);
    const content = asString(parsed?.content);
    if (content) return content.split('\n').find((line) => line.trim())?.replace(/^#+\s*/, '') || '更新执行计划';
    return '更新执行计划';
  }
  if (toolName === 'plan_exit') return '等待计划确认';
  if (toolName === 'todo_write') return '更新任务清单';
  return '交付产物';
}

function taskAcceptedFromText(value: string): AgentScopeTaskAccepted | null {
  const parsed = parseJsonRecord(value);
  const record = parsed ? deepFindRecordWith(parsed, (candidate) => !!asString(candidate.taskId)) : null;
  const taskId = asString(record?.taskId);
  if (!taskId) return null;
  const rawKind = asString(record?.taskKind ?? record?.kind);
  const taskKind = rawKind === 'image-batch' || rawKind === 'long-tool' ? rawKind : 'video';
  return {
    taskId,
    taskKind,
    message: asString(record?.message) || (taskKind === 'image-batch'
      ? '批量生图任务已受理'
      : taskKind === 'long-tool' ? '后台任务已受理' : '视频任务已受理'),
  };
}

function confirmResultFromText(value: string): ConfirmResultInfo | null {
  const parsed = parseJsonRecord(value);
  const record = parsed ? deepFindRecordWith(parsed, (candidate) =>
    Array.isArray(candidate.imageUrls) || Array.isArray(candidate.urls) || !!asString(candidate.url)) : null;
  if (!record) return null;
  const urls = asStringArray(record.urls ?? record.imageUrls);
  const url = asString(record.url) || urls[0] || '';
  if (!url && urls.length === 0) return null;
  const mediaType = asString(record.mediaType ?? record.type).toLowerCase();
  return {
    kind: mediaType.includes('video') ? 'video' : mediaType.includes('script') ? 'script' : 'image',
    url,
    urls,
    assetId: asString(record.assetId) || undefined,
    assetIds: asStringArray(record.assetIds),
    sceneCount: asNumber(record.sceneCount, 0),
    actions: [{ id: 'refine', title: '继续完善' }, { id: 'done', title: '满意完成' }],
  };
}

function turnMetadata(payload: Record<string, unknown>): AgentMessageMetadata | undefined {
  const raw = asRecord(payload.metadata);
  if (!raw) return undefined;
  const metadata = { ...raw } as AgentMessageMetadata;
  if (raw.reasoning && typeof raw.reasoning === 'object') {
    metadata.reasoning = raw.reasoning as AgentReasoning;
  }
  if (Array.isArray(raw.agentTrace)) {
    metadata.agentTrace = raw.agentTrace as AgentTraceEvent[];
  }
  if (raw.metrics && typeof raw.metrics === 'object') {
    metadata.metrics = raw.metrics as AgentTurnMetrics;
  }
  return metadata;
}

function optionalString(value: unknown): string | undefined {
  const text = asString(value);
  return text || undefined;
}

function optionalNumber(value: unknown): number | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function canvasRunStatus(
  value: unknown,
  fallback: AgentCanvasRunStatus,
): AgentCanvasRunStatus {
  const status = asString(value) as AgentCanvasRunStatus;
  return status === 'queued' || status === 'planning' || status === 'running' || status === 'writing'
    || status === 'completed' || status === 'failed'
    ? status
    : fallback;
}

function canvasRunMetadata(
  payload: Record<string, unknown>,
  turnId: string,
  eventType: AgentTurnEventEnvelope['type'],
  previous?: AgentMessageMetadata,
): AgentMessageMetadata | null {
  const canvasRunId = optionalString(payload.canvasRunId ?? payload.runId)
    ?? previous?.canvasRunId;
  const canvasId = optionalString(payload.canvasId) ?? previous?.canvasId;
  if (!canvasRunId && !canvasId) return null;
  const fallbackStatus: AgentCanvasRunStatus = eventType === 'canvas_run_completed'
    ? 'completed'
    : eventType === 'canvas_run_failed'
      ? 'failed'
      : eventType === 'canvas_run_started'
        ? 'queued'
        : previous?.status ?? 'writing';
  return {
    ...previous,
    kind: 'canvas-run',
    canvasRunId,
    canvasId,
    turnId: optionalString(payload.turnId) ?? turnId,
    title: optionalString(payload.title) ?? previous?.title,
    status: canvasRunStatus(payload.status, fallbackStatus),
    progress: optionalNumber(payload.progress) ?? previous?.progress,
    nodeCount: optionalNumber(payload.nodeCount) ?? previous?.nodeCount,
    edgeCount: optionalNumber(payload.edgeCount) ?? previous?.edgeCount,
    revision: optionalNumber(payload.revision) ?? previous?.revision,
    error: optionalString(payload.error) ?? (eventType === 'canvas_run_failed' ? previous?.error : undefined),
    projectId: optionalString(payload.projectId) ?? previous?.projectId,
    conversationId: optionalString(payload.conversationId) ?? previous?.conversationId,
  };
}

function upsertCanvasRunMessage(
  conversationId: string,
  assistantId: string,
  metadata: AgentMessageMetadata,
) {
  const runId = metadata.canvasRunId;
  useAgentStore.setState((state) => {
    if (state.activeConversationId !== conversationId) return {};
    const index = state.messages.findIndex((message) =>
      message.id === assistantId
      || (!!runId && message.metadata?.canvasRunId === runId));
    const messages = index >= 0
      ? state.messages.map((message, messageIndex) => messageIndex === index
        ? { ...message, metadata: { ...message.metadata, ...metadata } }
        : message)
      : [
          ...state.messages,
          {
            id: assistantId || `canvas-run-${runId ?? Date.now()}`,
            conversationId,
            role: 'assistant' as const,
            content: '',
            metadata,
            createdAt: new Date().toISOString(),
          },
        ];
    return { messages };
  });
}

function applyOneEnvelope(
  envelope: AgentTurnEventEnvelope,
  context: TurnReductionContext,
  effects: AgentTurnEnvelopeEffects,
) {
  const payload = envelope.payload;
  if (envelope.type === 'canvas_run_started'
    || envelope.type === 'canvas_run_updated'
    || envelope.type === 'canvas_run_completed'
    || envelope.type === 'canvas_run_failed') {
    const previous = useAgentStore.getState().messages.find((message) =>
      message.id === context.assistantId
      || message.metadata?.canvasRunId === optionalString(payload.canvasRunId ?? payload.runId))?.metadata;
    const metadata = canvasRunMetadata(payload, envelope.turnId, envelope.type, previous);
    if (metadata) upsertCanvasRunMessage(context.conversationId, context.assistantId, metadata);
    return;
  }
  if (envelope.type === 'canvas_session_required') {
    effects.canvasSessionRequired = payload as unknown as CanvasSessionInfo;
    return;
  }
  if (envelope.type === 'turn_started') {
    effects.started = true;
    const startedContextWindow = asNumber(payload.contextWindowTokens ?? payload.context_window_tokens);
    useAgentStore.setState((state) => {
      const generating = {
        ...state.generating,
        [context.conversationId]: {
          ...(state.generating[context.conversationId] ?? { hint: '' }),
          turnId: envelope.turnId,
          hint: 'MoonAgent 正在处理',
          ...(startedContextWindow > 0 ? { contextWindowTokens: startedContextWindow } : {}),
        },
      };
      return {
        generating,
        ...(state.activeConversationId === context.conversationId
          ? { workflowHint: 'MoonAgent 正在处理', streaming: true }
          : {}),
      };
    });
    return;
  }

  if (envelope.type === 'turn_paused') {
    effects.paused = true;
    const metadata = asRecord(payload.metadata) ?? {};
    const hitl = asRecord(payload.hitl) ?? asRecord(metadata.hitl) ?? {};
    const source = { ...payload, ...metadata, ...hitl };
    const formToken = asString(source.formToken);
    const messageId = asString(source.messageId) || context.assistantId;
    const assistantContent = asString(payload.content);
    if (asString(source.kind) === 'canvas-bind') {
      effects.canvasModeRequired = {
        ...source,
        conversationId: context.conversationId,
        turnId: envelope.turnId,
        formToken,
      };
      return;
    }
    const actions = Array.isArray(source.actions)
      ? source.actions.filter(asRecord).map((action) => ({
          id: asString(action?.id),
          title: asString(action?.title),
        })).filter((action) => action.id && action.title)
      : [];
    const info: HumanInputInfo = {
      turnId: envelope.turnId,
      formToken,
      taskId: asString(source.taskId),
      formContent: asString(source.formContent ?? source.content),
      actions,
      expirationTime: asNumber(source.expirationTime),
      messageId,
      kind: asString(source.kind) === 'video-plan' ? 'video-plan' : 'human-input',
      hitlType: asString(source.hitlType),
      models: source.models as GatewayModelOption[] | undefined,
      imageModels: source.imageModels as GatewayModelOption[] | undefined,
      videoModels: source.videoModels as GatewayModelOption[] | undefined,
      recommended: asRecord(source.recommended) as Record<string, string> | undefined,
      reasons: asRecord(source.reasons) as Record<string, string> | undefined,
      assets: source.assets as AssetOption[] | undefined,
      message: asString(source.message),
      duration: asNumber(source.duration, 8),
      picUrl: asString(source.picUrl),
    };
    useAgentStore.setState((state) => {
      const videoPlan = info.kind === 'video-plan' || info.hitlType === 'generate_video';
      const videoInfo: VideoPlanInfo | null = videoPlan ? {
        turnId: envelope.turnId,
        planToken: formToken,
        message: info.message || info.formContent,
        duration: info.duration ?? 8,
        picUrl: info.picUrl ?? '',
        actions,
        messageId,
        models: info.models,
        recommended: info.recommended,
        reasons: info.reasons,
      } : null;
      const messages = state.messages.map((message) => message.id === context.assistantId
        ? {
            ...message,
            id: messageId || message.id,
            content: message.content.trim()
              ? message.content
              : (assistantContent || info.formContent || videoInfo?.message || ''),
            metadata: {
              ...(message.metadata ?? {}),
              kind: videoPlan ? 'video-plan' as const : 'hitl' as const,
              hitl: {
                formContent: info.formContent || videoInfo?.message || '',
                actions,
                pending: true,
                messageId,
              },
              ...(videoInfo ? { videoPlan: {
                message: videoInfo.message,
                duration: videoInfo.duration,
                picUrl: videoInfo.picUrl,
                actions,
                formToken,
                messageId,
                models: videoInfo.models,
                recommended: videoInfo.recommended,
                reasons: videoInfo.reasons,
              } } : {}),
            },
          }
        : message);
      return {
        workflowHint: '',
        waitingHumanInput: videoPlan ? null : info,
        waitingVideoPlan: videoInfo,
        hitlVideoPlan: videoInfo
          ? { ...state.hitlVideoPlan, [context.conversationId]: videoInfo }
          : state.hitlVideoPlan,
        messages: state.activeConversationId === context.conversationId ? messages : state.messages,
      };
    });
    return;
  }

  if (envelope.type === 'turn_completed') {
    effects.completed = true;
    const content = asString(payload.content);
    const metadata = turnMetadata(payload);
    const generation = useAgentStore.getState().generating[context.conversationId];
    const liveSandboxAttachments = useAgentStore.getState().messages
      .find((message) => message.id === context.assistantId)?.metadata?.sandboxAttachments;
    const finalMetadata: AgentMessageMetadata | undefined = (
      metadata || generation?.reasoningDraft || generation?.traceDraft?.length
      || generation?.usageDraft || generation?.assistantMessageMetadata
    ) ? {
        ...(generation?.assistantMessageMetadata ?? {}),
        ...(metadata ?? {}),
        kind: metadata?.kind ?? generation?.assistantMessageMetadata?.kind ?? 'assistant-reply',
        reasoning: metadata?.reasoning ?? generation?.reasoningDraft,
        agentTrace: metadata?.agentTrace ?? generation?.traceDraft,
        sandboxAttachments: metadata?.sandboxAttachments ?? liveSandboxAttachments,
        metrics: metadata?.metrics ?? generation?.usageDraft ?? generation?.assistantMessageMetadata?.metrics,
      } : undefined;
    if (content) storeAssistantContent(context.conversationId, context.assistantId, content);
    if (finalMetadata) {
      useAgentStore.setState((state) => {
        const generating = {
          ...state.generating,
          [context.conversationId]: {
            ...(state.generating[context.conversationId] ?? { hint: '' }),
            assistantMessageMetadata: finalMetadata,
            usageDraft: undefined,
          },
        };
        return {
          generating,
          ...(state.activeConversationId === context.conversationId
            ? {
                messages: state.messages.map((message) => {
                  if (message.id !== context.assistantId) return message;
                  const merged = { ...(message.metadata ?? {}), ...finalMetadata };
                  if (message.metadata?.kind === 'canvas-run' && finalMetadata.kind === 'assistant-reply') {
                    merged.kind = 'canvas-run';
                  }
                  return { ...message, metadata: merged };
                }),
              }
            : {}),
        };
      });
    }
    return;
  }

  if (envelope.type === 'turn_failed') {
    effects.failed = true;
    const message = asString(payload.message, 'MoonAgent 执行失败');
    useAgentStore.setState((state) => ({
      ...(state.activeConversationId === context.conversationId ? { streamError: message } : {}),
    }));
    return;
  }

  const native = asRecord(payload) as AgentScopeEvent | null;
  const eventType = asString(native?.eventType);
  const data = asRecord(native?.data) ?? {};
  if (!eventType) return;

  if (eventType === 'TEXT_BLOCK_DELTA') {
    const delta = asString(data.delta);
    if (!delta) return;
    useAgentStore.setState((state) => {
      const generation = state.generating[context.conversationId] ?? { hint: '' };
      const content = `${generation.assistantContent ?? ''}${delta}`;
      const generating = {
        ...state.generating,
        [context.conversationId]: { ...generation, assistantId: context.assistantId, assistantContent: content },
      };
      if (state.activeConversationId !== context.conversationId) return { generating };
      return {
        generating,
        messages: state.messages.map((message) =>
          message.id === context.assistantId ? { ...message, content: message.content + delta } : message),
      };
    });
    return;
  }

  if (eventType === 'AGENT_RESULT') {
    const result = asString(data.result ?? data.content);
    if (result) storeAssistantContent(context.conversationId, context.assistantId, result);
    return;
  }

  if (eventType === 'THINKING_BLOCK_DELTA') {
    const delta = asString(data.delta);
    if (!delta) return;
    const previous = useAgentStore.getState().generating[context.conversationId]?.reasoningDraft;
    const details = appendReasoningDetails(previous, { details: delta, source: 'agentscope' });
    setLiveReasoning(context, {
      summary: reasoningSummaryFrom(details, previous?.summary),
      details,
      activity: reasoningActivityFrom(details),
      durationMs: previous?.durationMs,
      source: 'agentscope',
    });
    return;
  }

  if (eventType === 'AGENT_TRACE') {
    const event = asRecord(data.traceEvent);
    if (!event) return;
    const id = asString(event.id);
    if (!id) return;
    setLiveTrace(context, {
      id,
      phase: asString(event.phase),
      agent: asString(event.agent, 'moon'),
      specialist: asString(event.specialist) || undefined,
      status: asString(event.status),
      title: asString(event.title),
      summary: asString(event.summary),
      currentIntent: asString(event.currentIntent) || undefined,
      targetIntent: asString(event.targetIntent) || undefined,
      startedAt: asString(event.startedAt),
      durationMs: typeof event.durationMs === 'number' ? event.durationMs : undefined,
    });
    return;
  }

  if (eventType === 'MODEL_CALL_END') {
    mergeUsage(context, data);
    return;
  }

  if (eventType.startsWith('TOOL_')) {
    const toolName = asString(data.toolName, 'tool');
    const toolCallId = asString(data.toolCallId, `tool-${envelope.seq}`);
    const status = toolStatus(eventType, data);
    setLiveTrace(context, {
      id: `${toolCallId}-call`,
      phase: 'tool',
      agent: 'agentscope',
      specialist: toolName,
      status,
      title: toolTitle(eventType, toolName),
      summary: asString(data.delta ?? data.state),
      startedAt: asString(data.createdAt, new Date().toISOString()),
    });

    const kind = timelineKindForTool(toolName);
    if (kind) {
      upsertTimelineEntry(context.conversationId, {
        id: `${envelope.turnId}:${toolCallId}:${kind}`,
        turnId: envelope.turnId,
        seq: envelope.seq,
        kind,
        title: timelineTitleForTool(toolName),
        status,
        summary: asString(data.delta ?? data.state),
      });
    }

    useAgentStore.setState((state) => {
      const generation = state.generating[context.conversationId] ?? { hint: '' };
      const drafts = { ...(generation.toolDrafts ?? {}) };
      const draft = drafts[toolCallId] ?? { name: toolName, text: '', input: '', status };
      if (eventType === 'TOOL_CALL_DELTA') draft.input += asString(data.delta);
      if (eventType === 'TOOL_RESULT_TEXT_DELTA' || eventType === 'TOOL_RESULT_DATA_DELTA') {
        const delta = typeof data.delta === 'string' ? data.delta : data.delta ? JSON.stringify(data.delta) : '';
        draft.text += delta;
      }
      draft.status = status;
      drafts[toolCallId] = draft;
      return {
        generating: {
          ...state.generating,
          [context.conversationId]: { ...generation, toolDrafts: drafts },
        },
      };
    });

    if (eventType === 'TOOL_RESULT_END') {
      const draft = useAgentStore.getState().generating[context.conversationId]?.toolDrafts?.[toolCallId];
      if (!effects.taskAccepted) effects.taskAccepted = taskAcceptedFromText(draft?.text ?? '');
      if (!effects.confirmResult) effects.confirmResult = confirmResultFromText(draft?.text ?? '');
      if (toolName === 'deliver_artifact') {
        const input = parseJsonRecord(draft?.input);
        const fileName = asString(input?.fileName) || asString(input?.filePath).split(/[\\/]/).pop() || '产物';
        upsertTimelineEntry(context.conversationId, {
          id: `${envelope.turnId}:${toolCallId}:artifact`,
          turnId: envelope.turnId,
          seq: envelope.seq,
          kind: 'artifact',
          title: fileName,
          status,
          summary: asString(input?.description) || draft?.text,
        });
      }
    }
    return;
  }

  if (eventType === 'SUBAGENT_EXPOSED') {
    const subagentId = asString(data.subagentId, `subagent-${envelope.seq}`);
    const label = asString(data.label ?? data.agentId, subagentId);
    upsertTimelineEntry(context.conversationId, {
      id: `${envelope.turnId}:${subagentId}:subagent`,
      turnId: envelope.turnId,
      seq: envelope.seq,
      kind: 'subagent',
      title: label,
      status: 'node_started',
      agent: asString(data.agentId, subagentId),
      summary: asString(data.sessionId),
    });
    return;
  }

  if (eventType === 'HINT_BLOCK') {
    const hint = asString(data.hint ?? data.delta);
    if (hint) {
      useAgentStore.setState((state) => ({
        ...(state.activeConversationId === context.conversationId ? { workflowHint: hint } : {}),
      }));
    }
    return;
  }

  if (eventType === 'CUSTOM') {
    const name = asString(data.name);
    const value = asRecord(data.value) ?? {};
    if (name.toLowerCase().includes('canvas_mode_required')) {
      effects.canvasModeRequired = { ...value, turnId: envelope.turnId };
      return;
    }
    const lower = name.toLowerCase();
    const kind: AgentScopeTimelineEntry['kind'] | null = lower.includes('plan')
      ? 'plan' : lower.includes('subagent') ? 'subagent' : lower.includes('artifact') ? 'artifact' : null;
    if (kind) {
      upsertTimelineEntry(context.conversationId, {
        id: `${envelope.turnId}:${name}:${envelope.seq}`,
        turnId: envelope.turnId,
        seq: envelope.seq,
        kind,
        title: asString(value.title ?? name),
        status: asString(value.status, 'node_running'),
        summary: asString(value.summary ?? value.content ?? value.message),
      });
    }
  }
}

/** Reduce a streamed envelope into store state; stale/gapped events are handled per turn. */
export function applyAgentTurnEnvelope(
  envelope: AgentTurnEventEnvelope,
  context: TurnReductionContext,
): AgentTurnEnvelopeEffects {
  const effects = emptyTurnEffects();
  for (const ready of turnEventSequencer.accept(envelope)) {
    applyOneEnvelope(ready, context, effects);
  }
  return effects;
}

export const useAgentStore = create<AgentState>((set, get) => ({
  timelineByConversation: {},
  replayTurnEvents: async (conversationId, turnId, afterSeq) => {
    if (!conversationId || !turnId) return;
    const assistantId = get().generating[conversationId]?.assistantId
      ?? get().pendingAssistantId
      ?? `turn-assistant-${turnId}`;
    const response = await agentApi.listTurnEvents(
      conversationId,
      turnId,
      afterSeq ?? turnEventSequencer.lastSeq(turnId),
    );
    for (const envelope of response.data.data ?? []) {
      const effects = applyAgentTurnEnvelope(envelope, { conversationId, assistantId });
      if (effects.completed) {
        finishAssistantReply(conversationId, assistantId);
        useAgentStore.setState((state) => {
          if (state.generating[conversationId]?.assistantId !== assistantId) return {};
          const generating = { ...state.generating };
          delete generating[conversationId];
          return {
            generating,
            streaming: false,
            workflowHint: '',
            ...(state.pendingAssistantId === assistantId ? { pendingAssistantId: null } : {}),
          };
        });
      }
      if (effects.canvasSessionRequired) {
        const session = effects.canvasSessionRequired;
        set({
          canvasSession: session,
          canvasSessionStatus: '',
          canvasSessionSelection: { createNew: false },
        });
        set((state) => ({
          generating: Object.fromEntries(
            Object.entries(state.generating).filter(([key]) => key !== conversationId),
          ),
          messages: state.messages.filter((message) => message.id !== assistantId),
          workflowHint: '',
        }));
        if (session.status === 'pending_open' && session.canvasId) {
          get().beginCanvasSessionOpen(session.canvasId, false);
        }
      }
    }
  },
  canvasEditContext: null,
  canvasBinding: null,
  canvasBindingStatus: '',
  canvasSession: null,
  canvasSessionStatus: '',
  canvasSessionSelection: { createNew: false },
  canvasExitPending: false,
  setCanvasEditContext: (context) => {
    const normalized = normalizeCanvasEditContext(context);
    if (normalized) persistCanvasEditContext(normalized);
    else clearCanvasEditContext();
    set({ canvasEditContext: normalized });
  },
  startCanvasBinding: (binding) => set({
    canvasBinding: binding,
    canvasBindingStatus: '正在准备画布…',
  }),
  setCanvasBindingStatus: (status) => set({ canvasBindingStatus: status }),
  cancelCanvasBinding: () => {
    const binding = get().canvasBinding;
    const conversationId = binding?.conversationId ?? get().activeConversationId;
    if (conversationId) {
      void agentApi.skipPendingCheckpoint(conversationId).catch(() => undefined);
    }
    set({ canvasBinding: null, canvasBindingStatus: '' });
  },
  resumeCanvasBinding: async (context) => {
    const binding = get().canvasBinding;
    if (!binding) return;
    while (get().streaming) {
      await new Promise((resolve) => window.setTimeout(resolve, 50));
    }
    if (get().canvasBinding?.formToken !== binding.formToken) return;
    if (binding.conversationId && binding.conversationId !== get().activeConversationId) {
      await get().selectConversation(binding.conversationId);
    }
    get().setCanvasEditContext(context);
    set({ canvasBinding: null, canvasBindingStatus: '' });
    await get().submitHumanInput('canvas-bound', '', undefined, binding.assetIds, {
      hiddenCard: true,
      formToken: binding.formToken,
      turnId: binding.turnId,
      canvasContext: context,
    });
  },
  beginCanvasSessionOpen: (canvasId, createNew = false) => set((state) => ({
    canvasSessionSelection: { canvasId, createNew },
    canvasSessionStatus: '正在打开画布…',
    canvasSession: state.canvasSession
      ? { ...state.canvasSession, status: 'opening', canvasId: canvasId ?? state.canvasSession.canvasId }
      : null,
  })),
  readyCanvasSession: async () => {
    const session = get().canvasSession;
    const conversationId = session?.conversationId ?? get().activeConversationId;
    if (!session || !conversationId || canvasSessionResumeLocks.has(session.sessionId)) return;
    canvasSessionResumeLocks.add(session.sessionId);
    const selection = get().canvasSessionSelection;
    const assistantId = `canvas-session-assistant-${Date.now()}`;
    let nextCanvasSession: CanvasSessionInfo | null = null;
    set((state) => ({
      streaming: true,
      streamError: null,
      canvasSessionStatus: '画布已就绪，正在继续设计…',
      pendingAssistantId: assistantId,
      messages: [...state.messages, {
        id: assistantId,
        conversationId,
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
    }));
    try {
      await streamCanvasSessionReady(
        conversationId,
        session.sessionId,
        (event) => {
          const effects = applyAgentTurnEnvelope(event, { conversationId, assistantId });
          if (effects.canvasSessionRequired) {
            nextCanvasSession = effects.canvasSessionRequired;
            set({ canvasSession: nextCanvasSession, canvasSessionStatus: '' });
          }
          if (effects.completed || effects.failed || effects.paused) {
            finishAssistantReply(conversationId, assistantId);
          }
        },
        selection.canvasId,
        selection.createNew,
      );
      set({
        canvasSession: nextCanvasSession,
        canvasSessionStatus: '',
        canvasSessionSelection: { createNew: false },
      });
      await refreshConversationMessages(conversationId);
      if (!nextCanvasSession) {
        await restorePendingCanvasSession(conversationId);
      }
    } catch (error) {
      set({
        streamError: error instanceof Error ? error.message : '画布恢复失败',
        canvasSessionStatus: '',
      });
    } finally {
      canvasSessionResumeLocks.delete(session.sessionId);
      set({ streaming: false, pendingAssistantId: null });
    }
  },
  cancelCanvasSession: async () => {
    const session = get().canvasSession;
    if (!session) return;
    try {
      await agentApi.cancelCanvasSession(session.conversationId, session.sessionId);
    } finally {
      canvasSessionResumeLocks.delete(session.sessionId);
      set({ canvasSession: null, canvasSessionStatus: '', canvasSessionSelection: { createNew: false } });
    }
  },
  addCanvasMediaRefs: (refs) => set((state) => {
    if (!state.canvasEditContext) return state;
    const mediaRefs = buildCanvasRequestContext(state.canvasEditContext, { mediaRefs: refs })?.mediaRefs ?? [];
    const next = { ...state.canvasEditContext, mediaRefs };
    persistCanvasEditContext(next);
    return {
      canvasEditContext: next,
      refImageUrls: filterCanvasReferenceUrls(state.refImageUrls, next),
    };
  }),
  removeCanvasMediaRef: (url) => set((state) => {
    if (!state.canvasEditContext || !url) return state;
    const next = withoutCanvasMediaRef(state.canvasEditContext, url);
    persistCanvasEditContext(next);
    return {
      canvasEditContext: next,
      refImageUrls: state.refImageUrls.filter((item) => mediaUrlKey(item) !== mediaUrlKey(url)),
    };
  }),
  moveCanvasMediaRef: (url, direction) => set((state) => {
    if (!state.canvasEditContext) return state;
    const key = mediaUrlKey(url);
    const refs = [...state.canvasEditContext.mediaRefs];
    const index = refs.findIndex((ref) => mediaUrlKey(ref.url) === key);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= refs.length) return state;
    [refs[index], refs[target]] = [refs[target], refs[index]];
    const next = { ...state.canvasEditContext, mediaRefs: refs };
    persistCanvasEditContext(next);
    return { canvasEditContext: next };
  }),
  restoreCanvasMode: async (conversationId) => {
    try {
      const detail = await agentApi.getConversation(conversationId);
      const context = detail.data.data.conversation.canvasContext;
      if (context?.mode === 'canvas-edit') {
        const normalized = normalizeCanvasEditContext(context);
        if (normalized) persistCanvasEditContext(normalized);
        set({ canvasEditContext: normalized, canvasExitPending: false });
      } else {
        clearCanvasEditContext();
        set({ canvasEditContext: null, canvasExitPending: false });
      }
    } catch {
      // 拉取失败时先退出本地画布态，避免把上一会话的画布目标带到当前会话。
      clearCanvasEditContext();
      set({ canvasEditContext: null, canvasExitPending: false });
    }
  },
  leaveCanvasEditMode: () => {
    if (!get().canvasEditContext) return;
    if (get().streaming || get().waitingHumanInput || get().waitingVideoPlan) {
      set({ canvasExitPending: true });
      return;
    }
    get().exitCanvasModeNow();
  },
  exitCanvasModeNow: () => {
    const id = get().activeConversationId;
    if (id) void agentApi.exitCanvasMode(id).catch(() => undefined);
    clearCanvasEditContext();
    set({ canvasEditContext: null, canvasExitPending: false, canvasBinding: null, canvasBindingStatus: '' });
  },

  sideChat: {
    open: false,
    loading: false,
    focusToken: 0,
    sourceConversationId: null,
    sourceTitle: '',
    projectId: null,
    conversationId: null,
    error: null,
  },
  openSideChat: async (sourceConversationId, sourceTitle) => {
    const sourceId = sourceConversationId ?? get().activeConversationId;
    if (!sourceId) return;
    useDocumentPreviewStore.getState().closeDocument();
    const focusToken = get().sideChat.focusToken + 1;
    if (get().sideChat.open && (get().sideChat.loading || get().sideChat.conversationId)) {
      set((state) => ({
        sideChat: { ...state.sideChat, focusToken },
      }));
      document.querySelector<HTMLIFrameElement>('iframe[data-app-key="side-chat"]')?.focus();
      return;
    }
    const requestSeq = ++sideChatRequestSeq;
    const source = get().conversations.find((conversation) => conversation.id === sourceId);
    const resolvedTitle = sourceTitle || source?.title || '当前对话';
    set((state) => ({
      sideChat: {
        ...state.sideChat,
        open: true,
        loading: true,
        focusToken,
        sourceConversationId: sourceId,
        sourceTitle: resolvedTitle,
        projectId: source?.projectId ?? useProjectStore.getState().currentProject?.id ?? null,
        conversationId: null,
        error: null,
      },
    }));
    try {
      const response = await agentApi.createSideChat(sourceId);
      const conversation = response.data.data;
      if (requestSeq !== sideChatRequestSeq) {
        void agentApi.closeSideChat(conversation.id).catch(() => undefined);
        return;
      }
      if (get().activeConversationId !== sourceId) {
        void agentApi.closeSideChat(conversation.id).catch(() => undefined);
        set((state) => ({
          sideChat: { ...state.sideChat, open: false, loading: false, conversationId: null },
        }));
        return;
      }
      set((state) => ({
        sideChat: {
          ...state.sideChat,
          open: true,
          loading: false,
          focusToken,
          projectId: conversation.projectId,
          conversationId: conversation.id,
          error: null,
        },
      }));
    } catch {
      set((state) => ({
        sideChat: {
          ...state.sideChat,
          open: true,
          loading: false,
          focusToken,
          conversationId: null,
          error: '侧边创建失败，请重试',
        },
      }));
    }
  },
  closeSideChat: async (options) => {
    sideChatRequestSeq += 1;
    useSideChatSelectionStore.getState().clear();
    const conversationId = get().sideChat.conversationId;
    set({
      sideChat: {
        open: false,
        loading: false,
        focusToken: 0,
        sourceConversationId: null,
        sourceTitle: '',
        projectId: null,
        conversationId: null,
        error: null,
      },
    });
    if (conversationId) {
      await agentApi.closeSideChat(conversationId, options?.keepalive)
        .catch(() => undefined);
    }
  },

  conversations: [],
  activeConversationId: null,
  pendingApprovalMode: DEFAULT_AGENT_APPROVAL_MODE,
  loadingConversations: false,
  loadConversations: async () => {
    const projectId = useProjectStore.getState().currentProject?.id;
    if (!projectId) {
      conversationListEpoch += 1;
      return;
    }
    const loadEpoch = ++conversationListEpoch;
    set({ loadingConversations: true });
    const res = await agentApi.listConversations(projectId);
    if (loadEpoch !== conversationListEpoch
        || useProjectStore.getState().currentProject?.id !== projectId) return;
    const list = res.data.data ?? [];
    set({ conversations: list, loadingConversations: false });
    // 自动选中最近会话
    if (list.length > 0 && !get().activeConversationId) {
      await get().selectConversation(list[0].id);
    }
  },

  // 新建对话：仅重置本地为空白草稿，不落库、不进列表——避免反复点「新对话」堆积空会话。
  // 真正建会话在 sendMessage 首次发送时由 createConversation 完成。
  newConversation: () => {
    clearCanvasEditContext();
    set({
      activeConversationId: null,
      pendingApprovalMode: DEFAULT_AGENT_APPROVAL_MODE,
      canvasEditContext: null,
      canvasBinding: null,
      canvasBindingStatus: '',
      canvasExitPending: false,
      messages: [],
      streaming: false,
      waitingHumanInput: null,
      
      waitingVideoPlan: null,
      streamError: null,
      pendingAssistantId: null,
      confirmResult: null,
      pendingPicUrls: [],
      refImageUrls: [],
      pendingAttachments: [],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
      workflowHint: '',
      generating: {},
    });
  },

  createConversation: async () => {
    const projectId = useProjectStore.getState().currentProject?.id;
    if (!projectId) return;
    const res = await agentApi.createConversation(
      projectId,
      '新对话',
      get().pendingApprovalMode,
    );
    const conv = res.data.data;
    set((s) => ({ conversations: [conv, ...s.conversations] }));
    await get().selectConversation(conv.id);
  },

  setApprovalMode: async (mode, options) => {
    if (get().streaming || get().waitingHumanInput || get().waitingVideoPlan
        || get().canvasBinding || get().canvasExitPending) return;
    if (mode === 'FULL_ACCESS' && !options?.confirmedFullAccess) return;
    const id = get().activeConversationId;
    if (!id) {
      set({ pendingApprovalMode: mode, streamError: null });
      return;
    }
    try {
      const res = await agentApi.updateApprovalMode(id, mode);
      const updated = res.data.data;
      set((s) => ({
        pendingApprovalMode: mode,
        conversations: s.conversations.map((conversation) =>
          conversation.id === id ? updated : conversation),
        streamError: null,
      }));
    } catch (error) {
      set({
        streamError: error instanceof Error ? error.message : '切换审批模式失败，请重试',
      });
    }
  },

  renameConversation: async (id, title) => {
    const res = await agentApi.updateConversation(id, { title });
    const updated = res.data.data;
    set((s) => ({
      conversations: s.conversations.map((c) => (c.id === id ? updated : c)),
    }));
  },

  setConversationStatus: async (id, status) => {
    const res = await agentApi.updateConversation(id, { status });
    const updated = res.data.data;
    set((s) => ({
      conversations: s.conversations.map((c) => (c.id === id ? updated : c)),
    }));
  },

  deleteConversation: async (id) => {
    await agentApi.deleteConversation(id);
    cancelConversationTitleRefresh(id);
    set((s) => {
      const g = { ...s.generating };
      delete g[id];
      const h = { ...s.hitlVideoPlan };
      delete h[id];
      const active = s.activeConversationId === id ? null : s.activeConversationId;
      const isActive = s.activeConversationId === id;
      const gview = active ? g[active] : undefined;
      if (isActive) clearCanvasEditContext();
      return {
        conversations: s.conversations.filter((c) => c.id !== id),
        activeConversationId: active,
        pendingApprovalMode: isActive ? DEFAULT_AGENT_APPROVAL_MODE : s.pendingApprovalMode,
        generating: g,
        streaming: generationInProgress(gview),
        workflowHint: generationHint(gview),
        messages: isActive ? [] : s.messages,
        waitingHumanInput: isActive ? null : s.waitingHumanInput, 
        waitingVideoPlan: isActive ? null : s.waitingVideoPlan,
        hitlVideoPlan: h,
        confirmResult: isActive ? null : s.confirmResult,
        ...(isActive ? {
          canvasEditContext: null,
          canvasBinding: null,
          canvasBindingStatus: '',
          canvasExitPending: false,
        } : {}),
      };
    });
  },

  // 清空当前会话聊天记录：删消息 + 重置 AI 上下文（会话），会话/资产保留
  clearMessages: async () => {
    const id = get().activeConversationId;
    // 守卫：无会话 / 流式生成中 / HITL 等待期 / 图生视频方案确认期禁止清空（防止删除进行中的上下文导致流事件污染）
    if (!id || get().streaming || get().waitingHumanInput || get().waitingVideoPlan) return;
    await agentApi.clearMessages(id);
    set((s) => { const h = { ...s.hitlVideoPlan }; delete h[id]; return { messages: [], waitingHumanInput: null,
       waitingVideoPlan: null, hitlVideoPlan: h, streamError: null, pendingAssistantId: null, confirmResult: null, pendingPicUrl: null }; });
  },

  selectConversation: async (id) => {
    const loadEpoch = ++conversationLoadEpoch;
    // HITL 按会话隔离（getPendingCheckpoint/hitlVideoPlan 各自恢复），允许切换会话
    set((s) => {
      const g = s.generating[id];
      return {
        activeConversationId: id,
        pendingApprovalMode: s.conversations.find((conversation) => conversation.id === id)
          ?.approvalMode ?? DEFAULT_AGENT_APPROVAL_MODE,
        messages: [],
        streaming: generationInProgress(g),
        workflowHint: generationHint(g),
        waitingHumanInput: null,
      
        waitingVideoPlan: s.hitlVideoPlan[id] ?? null,
        streamError: null,
        pendingAssistantId: null,
        confirmResult: null, pendingPicUrls: [], refImageUrls: [], pendingAttachments: [],
        pickedReferenceVideos: [], pickedReferenceAudios: [], pickedAssetIds: [],
        taskUnread: { ...s.taskUnread, [id]: 0 },
        replyUnread: { ...s.replyUnread, [id]: false },
        taskFocusId: null,
      };
    });
    void get().loadTasks(id);
    const page = await loadMessagePage(id);
    if (loadEpoch !== conversationLoadEpoch || get().activeConversationId !== id) return;
    await get().restoreCanvasMode(id);
    if (loadEpoch !== conversationLoadEpoch || get().activeConversationId !== id) return;
    const g = get().generating[id];
    const base = await refreshLibraryMediaMessages(
      page.records,
      (mediaId) => resolveLibraryMedia(id, mediaId),
    );
    const savedAttachments = readPendingAttachments(id);
    const restoredAttachments = await refreshLibraryMediaAttachments(savedAttachments.length
      ? savedAttachments
      : attachmentsFromMessages(base), (mediaId) => resolveLibraryMedia(id, mediaId));
    if (loadEpoch !== conversationLoadEpoch || get().activeConversationId !== id) return;
    set({
      messages: g?.assistantId
        ? [...base, { id: g.assistantId, conversationId: id, role: 'assistant' as const, content: g.assistantContent ?? '', createdAt: new Date().toISOString(), metadata: buildGeneratingMetadata(g) }]
        : base,
      messageNextCursor: page.nextCursor,
      messageHasMore: page.hasMore,
      pendingAttachments: restoredAttachments,
      ...pendingMediaFromAttachments(restoredAttachments),
    });
    void restorePersistedTurn(id);
    void restorePendingCanvasSession(id);
    const conv = get().conversations.find((c) => c.id === id);
    if (conv) {
      initialSceneCount = useProjectStore.getState().scenes.length;
      void get().loadAssets(1);
    }
    // 页面刷新恢复：检查是否有待处理的 HITL checkpoint，恢复 waitingHumanInput
    try {
      const cpRes = await agentApi.getPendingCheckpoint(id);
      if (loadEpoch !== conversationLoadEpoch || get().activeConversationId !== id) return;
      const cp = cpRes.data.data as (HumanInputInfo & Partial<VideoPlanInfo> & Partial<CanvasBinding> & {
        kind?: string;
      }) | null;
      const cpKind = (cp as { kind?: string } | null)?.kind;
      if (cpKind === 'canvas-bind' && cp?.formToken) {
        get().startCanvasBinding({
          conversationId: id,
          turnId: cp.turnId,
          projectId: cp.projectId,
          canvasId: cp.canvasId,
          canvasTitle: cp.canvasTitle,
          requestedIntent: cp.requestedIntent,
          formToken: cp.formToken,
          expirationTime: String(cp.expirationTime ?? ''),
          picUrls: cp.picUrls,
          assetIds: cp.assetIds,
          mediaRefs: cp.mediaRefs,
        });
        return;
      }
      if (cp && cp.formToken && cp.actions && cp.actions.length > 0) {
        if (cp.kind === 'video-plan' || cp.hitlType === 'generate_video') {
          const videoInfo: VideoPlanInfo = {
            planToken: cp.formToken,
            message: cp.message ?? cp.formContent,
            duration: cp.duration ?? 8,
            picUrl: cp.picUrl ?? '',
            actions: cp.actions,
            messageId: cp.messageId,
            models: cp.models,
            recommended: cp.recommended,
            reasons: cp.reasons,
          };
          set((s) => ({ waitingVideoPlan: videoInfo, hitlVideoPlan: { ...s.hitlVideoPlan, [id]: videoInfo } }));
        } else {
          set({ waitingHumanInput: cp });
        }
      }
    } catch { /* 静默失败，不影响正常加载 */ }
  },

  messages: [],
  messageNextCursor: null,
  messageHasMore: false,
  loadOlderMessages: async () => {
    const conversationId = get().activeConversationId;
    const cursor = get().messageNextCursor;
    if (!conversationId || !cursor || !get().messageHasMore) return;
    const page = await loadMessagePage(conversationId, cursor);
    if (get().activeConversationId !== conversationId) return;
    const older = await refreshLibraryMediaMessages(
      page.records,
      (mediaId) => resolveLibraryMedia(conversationId, mediaId),
    );
    const known = new Set(get().messages.map((message) => message.id));
    set({
      messages: [...older.filter((message) => !known.has(message.id)), ...get().messages],
      messageNextCursor: page.nextCursor,
      messageHasMore: page.hasMore,
    });
  },
  streaming: false,
  editAndResend: async (content, messageId) => {
    let targetMessageId = messageId;
    if (!targetMessageId) return;
    const conversationId = get().activeConversationId;
    if (!conversationId) return;
    if (targetMessageId.startsWith('tmp-')) {
      await refreshConversationMessages(conversationId);
      const persistedUser = [...get().messages].reverse()
        .find((message) => message.role === 'user');
      if (!persistedUser || persistedUser.id.startsWith('tmp-')) {
        set({ streamError: '要编辑的消息尚未同步，请刷新后重试' });
        return;
      }
      targetMessageId = persistedUser.id;
    }
    await get().sendMessage(content, { editMessageId: targetMessageId });
  },
  interruptAndSend: async (content) => {
    if (!content.trim()) return;
    await get().sendMessage(content, { interruptCurrent: true });
  },
  generating: {},
  waitingHumanInput: null,
      
  waitingVideoPlan: null,
  hitlVideoPlan: {},
  streamError: null,
  workflowHint: '',
  setWorkflowHint: (v) => set({ workflowHint: v }),
  agentVideoTask: null,
  tasks: {},
  taskNotices: {},
  taskUnread: {},
  replyUnread: {},
  taskDrawerId: null,
  taskFocusId: null,
  taskStreamConnected: false,
  taskStream: {},
  taskThinking: {},
  taskStreamSeq: {},
  upsertTask: (task, options) => set((state) => {
    const previous = state.tasks[task.taskId];
    const merged = mergeTask(state.tasks, task, options);
    const currentMessages = state.activeConversationId === task.conversationId
      ? appendTerminalTaskResult(state.messages, task)
      : state.messages;
    const active = state.activeConversationId === task.conversationId;
    const plotReady = active
      && task.kind === 'long-tool'
      && task.stage === 'plot_diagnosis'
      && previous?.stage === 'script_optimization';
    // 剧情/分镜长任务的正文（剧情稿、分镜结果）在终态才落库；wakeup_message 可能因 SSE 瞬断丢失，
    // 这里按版本变化对账刷新，配合任务快照重放与兜底轮询，避免必须手动刷新页面才能看到新内容。
    const longTaskSettled = active
      && merged.tasks !== state.tasks
      && task.kind === 'long-tool'
      && !isActiveTask(task);
    if (plotReady || longTaskSettled) {
      window.setTimeout(() => {
        void refreshConversationMessages(task.conversationId).catch(() => undefined);
      }, 0);
    }
    // 任务快照是刷新/重连后的兜底；已有实时帧时不回退到持久化旧值。
    const taskStream = state.taskStream[task.taskId]
      ? state.taskStream
      : withTaskStream(state.taskStream, task.taskId, task.liveText ?? '');
    const taskThinking = state.taskThinking[task.taskId]
      ? state.taskThinking
      : withTaskStream(state.taskThinking, task.taskId, task.liveThinking ?? '');
    if (!merged.completedNotice) {
      return { tasks: merged.tasks, messages: currentMessages, taskStream, taskThinking };
    }
    if (state.activeConversationId === task.conversationId) {
      window.setTimeout(() => {
        void refreshConversationMessages(task.conversationId).catch(() => undefined);
      }, 0);
    }
    const notice: AgentTaskNotice = {
      taskId: task.taskId,
      title: task.title,
      conversationId: task.conversationId,
      createdAt: new Date().toISOString(),
      storyboardRunId: storyboardRunIdFromTask(task) || undefined,
    };
    return {
      tasks: merged.tasks,
      messages: currentMessages,
      taskStream,
      taskThinking,
      taskNotices: active ? {
        ...state.taskNotices,
        [task.conversationId]: [
          ...(state.taskNotices[task.conversationId] ?? []).filter((item) => item.taskId !== task.taskId),
          notice,
        ],
      } : state.taskNotices,
      taskUnread: active ? state.taskUnread : {
        ...state.taskUnread,
        [task.conversationId]: (state.taskUnread[task.conversationId] ?? 0) + 1,
      },
    };
  }),
  openTaskDrawer: (taskId) => set({ taskDrawerId: taskId }),
  closeTaskDrawer: () => set({ taskDrawerId: null }),
  focusTask: (taskId) => set({ taskFocusId: taskId }),
  dismissTaskNotice: (conversationId, taskId) => set((state) => ({
    taskNotices: {
      ...state.taskNotices,
      [conversationId]: (state.taskNotices[conversationId] ?? [])
        .filter((item) => item.taskId !== taskId),
    },
  })),
  loadTasks: async (conversationId) => {
    const res = await agentApi.listTasks({
      conversationId: conversationId ?? undefined,
      status: 'all',
      page: 1,
      size: 100,
    });
    res.data.data.records.forEach((task) => {
      const completedRecently = !!task.completedAt
        && Date.now() - Date.parse(task.completedAt) <= 30 * 60 * 1000;
      get().upsertTask(task, { notifyIfTerminal: completedRecently });
    });
  },
  startTaskStream: () => {
    if (taskStreamRunning) return;
    taskStreamRunning = true;
    taskStreamAbort = new AbortController();
    const clearFallback = () => {
      if (taskFallbackTimer) clearTimeout(taskFallbackTimer);
      taskFallbackTimer = null;
    };
    const scheduleFallback = () => {
      clearFallback();
      const hasActive = Object.values(get().tasks).some(isActiveTask);
      taskFallbackTimer = setTimeout(async () => {
        await get().loadTasks().catch(() => undefined);
        if (!taskStreamAbort?.signal.aborted) scheduleFallback();
      }, hasActive ? 5000 : 30000);
    };
    const pump = async () => {
      const signal = taskStreamAbort?.signal;
      if (!signal || signal.aborted) return;
      clearFallback();
      // 兜底轮询与 SSE 并行：连接仍在但静默卡死（网关挂住、无错误无事件）时也能对账出新消息。
      scheduleFallback();
      try {
        set({ taskStreamConnected: true });
        await streamTaskEvents((event) => {
          if (event.type === 'task') {
            get().upsertTask(event.task);
          } else if (event.type === 'task_stream') {
            set((state) => {
              const previousSeq = state.taskStreamSeq[event.taskId] ?? 0;
              if (event.seq > 0 && event.seq <= previousSeq) return {};
              return {
                taskStream: withTaskStream(state.taskStream, event.taskId, event.text),
                taskThinking: withTaskStream(state.taskThinking, event.taskId, event.thinking),
                taskStreamSeq: {
                  ...state.taskStreamSeq,
                  [event.taskId]: event.seq || previousSeq,
                },
              };
            });
          } else {
            void refreshAfterWakeup(event.message).catch(() => undefined);
          }
        }, signal);
      } catch {
        // 断线后由并行兜底轮询对账，并持续尝试恢复 SSE。
      } finally {
        set({ taskStreamConnected: false });
      }
      if (signal.aborted) return;
      setTimeout(() => void pump(), 3000);
    };
    void get().loadTasks().catch(() => undefined);
    void pump();
  },
  stopTaskStream: () => {
    taskStreamRunning = false;
    taskStreamAbort?.abort();
    taskStreamAbort = null;
    if (taskFallbackTimer) clearTimeout(taskFallbackTimer);
    taskFallbackTimer = null;
    set({ taskStreamConnected: false });
  },
  cancelTask: async (taskId) => {
    const res = await agentApi.cancelTask(taskId);
    get().upsertTask(res.data.data);
  },
  retryTask: async (taskId) => {
    const res = await agentApi.retryTask(taskId);
    get().upsertTask(res.data.data);
    set({ taskDrawerId: res.data.data.taskId });
  },
  followUpTask: async (taskId, instruction) => {
    const res = await agentApi.followUpTask(taskId, instruction);
    get().upsertTask(res.data.data);
    set({ taskDrawerId: res.data.data.taskId });
  },
  storyboardVideoQueue: [],
  storyboardImageQueue: [],
  confirmResult: null,
  pendingPicUrls: [],
  pendingAssistantId: null,

  refImageUrls: [],
  setRefImageUrls: (v) => set({ refImageUrls: v }),
  addRefImageUrls: (urls) => set((s) => ({ refImageUrls: [...new Set([...s.refImageUrls, ...urls])] })),
  removeRefImageUrl: (index) => set((s) => {
    const removed = s.refImageUrls[index];
    return {
      refImageUrls: s.refImageUrls.filter((_, i) => i !== index),
      pendingAttachments: removed
        ? s.pendingAttachments.filter((item) => item.url !== removed)
        : s.pendingAttachments,
    };
  }),
  pendingAttachments: readPendingAttachments(null),
  setPendingAttachments: (attachments) => set((s) => {
    const next = attachments.filter(isAgentAttachment);
    persistPendingAttachments(s.activeConversationId, next);
    return { pendingAttachments: next, ...pendingMediaFromAttachments(next) };
  }),
  addPendingAttachments: (attachments) => set((s) => {
    const map = new Map(s.pendingAttachments.map((item) => [item.url, item]));
    attachments.filter(isAgentAttachment).forEach((item) => map.set(item.url, item));
    const next = [...map.values()];
    persistPendingAttachments(s.activeConversationId, next);
    return { pendingAttachments: next, ...pendingMediaFromAttachments(next) };
  }),
  removePendingAttachment: (url) => set((s) => {
    const next = s.pendingAttachments.filter((item) => item.url !== url);
    persistPendingAttachments(s.activeConversationId, next);
    return { pendingAttachments: next, ...pendingMediaFromAttachments(next) };
  }),
  clearPendingAttachments: () => set((s) => {
    persistPendingAttachments(s.activeConversationId, []);
    return {
      pendingAttachments: [],
      refImageUrls: [],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
    };
  }),

  pickedAssetIds: [],
  addPickedAssetId: (id) => set((s) => ({ pickedAssetIds: [...new Set([...s.pickedAssetIds, id])] })),
  removePickedAssetId: (id) => set((s) => ({ pickedAssetIds: s.pickedAssetIds.filter((a) => a !== id) })),
  clearPickedAssetIds: () => set({ pickedAssetIds: [] }),
  setPickedAssetIds: (ids) => set(() => ({ pickedAssetIds: [...new Set(ids)] })),
  pickedReferenceVideos: [],
  pickedReferenceAudios: [],
  setPickedReferenceMedia: (videos, audios) => set({
    pickedReferenceVideos: [...new Set(videos)],
    pickedReferenceAudios: [...new Set(audios)],
  }),
  clearPickedReferenceMedia: () => set((s) => ({
    pickedReferenceVideos: [],
    pickedReferenceAudios: [],
    pendingAttachments: s.pendingAttachments.filter((item) => item.kind === 'image' || item.kind === 'file'),
  })),

  uploadRefImage: async (file, purpose?, kind?) => {
    const res = await agentApi.uploadImage(file, get().activeConversationId ?? undefined, purpose, kind);
    const payload = res.data.data;
    const url = payload.url;
    const mediaKind = attachmentKind(file);
    if (mediaKind === 'file') rememberDocumentSource(url, file);
    const canvasKind: AgentMediaRef['kind'] = mediaKind;
    const absoluteUrl = (() => {
      try { return new URL(url, window.location.origin).toString(); } catch { return url; }
    })();
    get().addCanvasMediaRefs([{
      kind: canvasKind,
      url: absoluteUrl,
      name: file.name,
      mimeType: payload.mimeType || file.type || undefined,
      source: 'upload',
    }]);
    get().addPendingAttachments([{
      assetId: payload.assetId,
      url,
      name: payload.name || file.name || '未命名文件',
      mimeType: payload.mimeType || file.type || 'application/octet-stream',
      sizeBytes: payload.sizeBytes ?? file.size,
      kind: mediaKind,
      source: 'upload',
    }]);
  },
  uploadRefImages: async (files, purposes, kinds) => {
    for (let i = 0; i < files.length; i++) {
      try { await get().uploadRefImage(files[i], purposes?.[i], kinds?.[i]); } catch { /* 单张失败跳过，不阻断其他张 */ }
    }
  },
  addLocalUserMessage: (content) => set((s) => ({
    messages: [...s.messages, {
      id: `tmp-asset-${Date.now()}`,
      conversationId: s.activeConversationId ?? '',
      role: 'user',
      content,
      createdAt: new Date().toISOString(),
    }],
  })),

  agentGeneratedScenes: false,
  setAgentGeneratedScenes: (v) => set({ agentGeneratedScenes: v }),

  assets: null,
  loadAssets: async (page = 1) => {
    // M6：请求发起时的会话快照——返回时若已切换会话，丢弃过期分页数据
    const convId = get().activeConversationId;
    if (!convId) return;
    const res = await agentApi.listAssets(convId, page, 20);
    if (get().activeConversationId !== convId) return;
    set({ assets: res.data.data });
  },
  deleteAsset: async (assetId) => {
    await agentApi.deleteAsset(assetId);
    // M10：删除后若当前页只剩 1 条且非第一页，回退一页加载，避免停留在空页
    const assets = get().assets;
    const page = assets?.page ?? 1;
    if ((assets?.records.length ?? 0) === 1 && page > 1) {
      void get().loadAssets(page - 1);
    } else {
      void get().loadAssets(page);
    }
  },

  sendMessage: async (content, opts?: {
    picUrls?: string[];
    assetIds?: string[];
    assetGroupIds?: string[];
    assetGroupSnapshotIds?: string[];
    referenceVideos?: string[];
    referenceAudios?: string[];
    routingHint?: string;
    cancelActiveTasks?: boolean;
    editMessageId?: string;
    clientRequestId?: string;
    interruptCurrent?: boolean;
    attachments?: AgentAttachment[];
  }) => {
    const activeId = get().activeConversationId;
    const activeConversation = get().conversations.find((conversation) => conversation.id === activeId);
    if (activeConversation?.status === 'archived'
      || activeConversation?.orchestrationMode === 'LEGACY') {
      set({ streamError: '该会话已归档，只能查看历史记录' });
      return;
    }
    const editMessageId = opts?.editMessageId;
    const interruptCurrent = !!opts?.interruptCurrent && get().streaming;
    const editTargetIndex = editMessageId
      ? get().messages.findIndex((message) => message.id === editMessageId && message.role === 'user')
      : -1;
    if (editMessageId && editTargetIndex < 0) {
      set({ streamError: '要编辑的消息不存在，请刷新后重试' });
      return;
    }
    // 输入禁用条件：(streaming && !agentVideoTask) || waitingHumanInput || waitingVideoPlan
    // 视频异步任务轮询期间（agentVideoTask 存在）允许继续发送新消息
    if ((!interruptCurrent && get().streaming && !get().agentVideoTask) || get().waitingHumanInput || get().waitingVideoPlan
      || get().canvasBinding || get().canvasExitPending || !content.trim()) return;
    if (interruptCurrent) {
      const runningConversationId = get().activeConversationId;
      const runningAssistantId = get().pendingAssistantId;
      const runningTurnId = runningConversationId
        ? get().generating[runningConversationId]?.turnId
        : undefined;
      manualStop = true;
      streamAbort?.abort();
      streamAbort = null;
      if (runningConversationId && runningTurnId) {
        void agentApi.interruptTurn(runningConversationId, runningTurnId).catch(() => undefined);
      }
      set((s) => {
        const generating = { ...s.generating };
        if (runningConversationId) delete generating[runningConversationId];
        return {
          generating,
          streaming: false,
          workflowHint: '',
          pendingAssistantId: null,
          messages: runningAssistantId
            ? s.messages.filter((message) => message.id !== runningAssistantId)
            : s.messages,
        };
      });
    }
    // 新一轮发送：清除上次手动停止标志并新建 AbortController（供「停止生成」中断本流）。
    // 若视频异步任务仍轮询，先停止旧轮询并告知后端取消在途任务（cancelActiveTasks: true）。
    if (get().agentVideoTask) {
      if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
      videoPollToken += 1;
      set({ agentVideoTask: null });
      opts = { ...opts, cancelActiveTasks: true };
    }
    if (!interruptCurrent) manualStop = false;
    streamAbort = new AbortController();
    const signal = streamAbort.signal;
    // 快照参考图——createConversation → selectConversation 会把 refImageUrls/messages 清空，
    // 不先存会导致首条消息 picUrls 丢失（首会话上传图「说话后被吞」）
    const refBefore = get().refImageUrls.map((u) => u);
    const referenceVideosBefore = get().pickedReferenceVideos.map((u) => u);
    const referenceAudiosBefore = get().pickedReferenceAudios.map((u) => u);
    const attachmentsBefore = get().pendingAttachments.map((item) => ({ ...item }));
    // 同样的坑：createConversation → selectConversation 会清空 pickedAssetIds，必须先快照
    const assetIdsBefore = get().pickedAssetIds.map((id) => id);
    const requestPicUrls = (opts?.picUrls && opts.picUrls.length > 0) ? opts.picUrls
      : (get().pendingPicUrls.length > 0) ? get().pendingPicUrls
      : refBefore;
    const requestAssetIds = opts?.assetIds && opts.assetIds.length > 0 ? opts.assetIds
      : assetIdsBefore.length > 0 ? assetIdsBefore
      : get().pickedAssetIds;
    const requestAttachments = opts?.attachments && opts.attachments.length > 0
      ? opts.attachments
      : attachmentsBefore;
    const requestReferenceVideos = opts?.referenceVideos ?? referenceVideosBefore;
    const requestReferenceAudios = opts?.referenceAudios ?? referenceAudiosBefore;
    const canvasContext = buildCanvasRequestContext(get().canvasEditContext, {
      picUrls: requestPicUrls,
      assetIds: requestAssetIds,
      mediaRefs: get().canvasEditContext?.mediaRefs,
    });
    let id = get().activeConversationId;
    // 未选对话但已选项目：自动新建对话并选中，避免用户消息被吞
    if (!id) {
      const projectId = useProjectStore.getState().currentProject?.id;
      if (!projectId) { set({ streamError: '请先在左上角选择一个项目' }); return; }
      try {
        await get().createConversation();
      } catch {
        // 建会话失败（网络/后端）在此收口：调用方多为 fire-and-forget，不能让 rejection 外泄
        set({ streamError: '创建会话失败，请稍后重试' });
        return;
      }
      id = get().activeConversationId;
    }
    if (!id) return;
    persistPendingAttachments(id, attachmentsBefore);
    // 继续完善参考图：点"继续完善"后暂存的 PicUrls，随本条用户消息发送并消费（只带一次，
    // 避免后续普通消息也误带图片导致误判 pic-refine 意图）
    const pendingPic = get().pendingPicUrls;
    if (pendingPic.length > 0) set({ pendingPicUrls: [] });

    // 追加 user 消息（乐观 UI）
    const optimisticUser: AgentMessage = {
      id: `tmp-${Date.now()}`,
      conversationId: id,
      role: 'user',
      content,
      metadata: {
        messageInput: {
          ...(requestPicUrls.length > 0 ? { picUrls: requestPicUrls } : {}),
          ...(requestReferenceVideos.length > 0 ? { referenceVideos: requestReferenceVideos } : {}),
          ...(requestReferenceAudios.length > 0 ? { referenceAudios: requestReferenceAudios } : {}),
          ...(requestAssetIds.length > 0 ? { assetIds: requestAssetIds } : {}),
          ...(requestAttachments.length > 0 ? { attachments: requestAttachments } : {}),
          ...(opts?.routingHint ? { routingHint: opts.routingHint } : {}),
        },
      },
      createdAt: new Date().toISOString(),
    };
    set((s) => ({
      messages: editMessageId
        ? s.messages
            .slice(0, editTargetIndex + 1)
            .map((message) => message.id === editMessageId
              ? { ...message, content, metadata: optimisticUser.metadata }
              : message)
        : [...s.messages, optimisticUser],
      streaming: true,
      generating: { ...s.generating, [id]: { hint: '', finished: false } },
      streamError: null,
      waitingHumanInput: null,
    }));
    initialSceneCount = useProjectStore.getState().scenes.length;

    // 追加空 assistant 消息（流式填充）
    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      
      createdAt: new Date().toISOString(),
    };
    // I2：记录本轮 assistant 占位 id，HITL 续流时复用同一气泡追加（与后端消息合并对应）
    set((s) => ({
      messages: [...s.messages, optimisticAssistant],
      pendingAssistantId: assistantId,
      generating: { ...s.generating, [id]: { ...s.generating[id], assistantId, assistantContent: '' } },
    }));

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    // 结束标志：正常收尾（message_end / human_input）不算"未收到回复"
    let receivedMessageEnd = false;
    let receivedHumanInput = false;
    let acceptedTaskId: string | null = null;
    let acceptedTaskKind: VideoTaskStatus['kind'] | null = null;
    let receivedCanvasModeRequired = false;

    const releaseStream = await reserveStreamSlot(snapshotId);
    manualStop = false;
    try {
      const handleStreamEvent = (event: AgentTurnEventEnvelope) => {
        const effects = applyAgentTurnEnvelope(event, {
          conversationId: snapshotId,
          assistantId,
        });
        if (effects.paused) receivedHumanInput = true;
        if (effects.completed) {
          receivedMessageEnd = true;
          if (!acceptedTaskId || isDetachedAsyncTask(acceptedTaskKind)) {
            finishAssistantReply(snapshotId, assistantId);
          }
          const metadata = asRecord(event.payload.metadata) ?? {};
          const title = asString(metadata.title);
          const sceneCount = asNumber(metadata.sceneCount, -1);
          if (get().activeConversationId === snapshotId) {
            if (title) {
              cancelConversationTitleRefresh(snapshotId);
              set((state) => ({
                conversations: state.conversations.map((conversation) =>
                  conversation.id === snapshotId && conversation.title !== title
                    ? { ...conversation, title } : conversation),
              }));
            } else if (get().conversations.some((conversation) => conversation.id === snapshotId && conversation.title === '新对话')) {
              scheduleConversationTitleRefresh(snapshotId);
            }
            if (sceneCount >= 0) {
              if (sceneCount > initialSceneCount) get().setAgentGeneratedScenes(true);
              const currentProject = useProjectStore.getState().currentProject;
              if (currentProject) void useProjectStore.getState().loadProject(currentProject.id);
            }
            refreshStoryboardQueues();
          }
        }
        if (effects.taskAccepted) {
          receivedMessageEnd = true;
          acceptedTaskId = effects.taskAccepted.taskId;
          acceptedTaskKind = effects.taskAccepted.taskKind;
          if (acceptedTaskKind === 'long-tool' || acceptedTaskKind === 'image-batch') {
            attachTaskCard(snapshotId, assistantId, {
              taskId: acceptedTaskId,
              title: acceptedTaskKind === 'image-batch' ? '批量生成图片' : '生成剧情稿',
              kind: acceptedTaskKind,
            });
            void agentApi.getTask(acceptedTaskId)
              .then((response) => {
                get().upsertTask(response.data.data);
                attachTaskCard(snapshotId, assistantId, response.data.data);
              })
              .catch(() => undefined);
          } else {
            beginUnifiedVideoPolling(acceptedTaskId, snapshotId, assistantId, acceptedTaskKind);
          }
        }
        if (effects.confirmResult && get().activeConversationId === snapshotId) {
          set({ confirmResult: effects.confirmResult });
        }
        if (effects.canvasSessionRequired) {
          receivedCanvasModeRequired = true;
          const session = effects.canvasSessionRequired;
          set({
            canvasSession: session,
            canvasSessionStatus: '',
            canvasSessionSelection: { createNew: false },
          });
          set((state) => ({
            generating: Object.fromEntries(
              Object.entries(state.generating).filter(([key]) => key !== snapshotId),
            ),
            messages: state.messages.filter((message) => message.id !== assistantId),
            workflowHint: '',
          }));
          if (session.status === 'pending_open' && session.canvasId) {
            get().beginCanvasSessionOpen(session.canvasId, false);
          }
        }
        if (effects.canvasModeRequired) {
          receivedCanvasModeRequired = true;
          const canvas = effects.canvasModeRequired;
          const canvasConversationId = asString(canvas.conversationId);
          const projectId = asString(canvas.projectId);
          const formToken = asString(canvas.formToken);
          if (get().activeConversationId === snapshotId && canvasConversationId && projectId && formToken) {
            get().startCanvasBinding({
              conversationId: canvasConversationId,
              turnId: asString(canvas.turnId),
              projectId,
              canvasId: asString(canvas.canvasId),
              canvasTitle: asString(canvas.canvasTitle),
              requestedIntent: asString(canvas.requestedIntent),
              formToken,
              expirationTime: asString(canvas.expirationTime),
              picUrls: asStringArray(canvas.picUrls),
              assetIds: asStringArray(canvas.assetIds),
              mediaRefs: Array.isArray(canvas.mediaRefs)
                ? canvas.mediaRefs.filter(asRecord).map((ref) => ref as AgentMediaRef)
                : undefined,
            });
            set((state) => {
              const generating = { ...state.generating };
              delete generating[snapshotId];
              return {
                generating,
                messages: state.messages.filter((message) => message.id !== assistantId),
                workflowHint: '',
              };
            });
          }
        }
      };
      await streamChat(id, content, requestPicUrls, handleStreamEvent, requestAssetIds, opts?.assetGroupIds, signal, {
        ...opts,
        clientRequestId: opts?.clientRequestId ?? (
          typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
            ? crypto.randomUUID()
            : `${Date.now()}-${Math.random().toString(36).slice(2)}`
        ),
        editMessageId,
        canvasContext,
        referenceVideos: requestReferenceVideos,
        referenceAudios: requestReferenceAudios,
        attachments: requestAttachments,
      });
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误；用户主动停止不视为错误（不弹错误条）
      if (!manualStop && get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      releaseStream();
      if (!acceptedTaskId || isDetachedAsyncTask(acceptedTaskKind)) {
        // streaming=false 无条件（单流模型）；占位补写仅在仍处于原会话时执行
        // （已切换会话时旧占位消息已被 selectConversation 整组替换）
        // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
        const isThisStream = get().pendingAssistantId === assistantId;
        const stillSameConversation = get().activeConversationId === snapshotId;
        const recoverTurnId = get().generating[snapshotId]?.turnId;
        // 失败（streamError 非空）时占位显示"（回复失败）"，避免"（未收到回复）"误导；手动停止显示"（已停止）"
        const failedText = manualStop ? '（已停止）' : (get().streamError ? '（回复失败）' : '（未收到回复）');
        set((s) => {
          const g = { ...s.generating };
          if (g[snapshotId]?.assistantId === assistantId) delete g[snapshotId];
          const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
          return {
            generating: g,
            streaming: generationInProgress(gview),
            workflowHint: generationHint(gview),
            ...(isThisStream ? { pendingAssistantId: null } : {}),
            messages: stillSameConversation
            ? s.messages.map((m) =>
                m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput && !receivedCanvasModeRequired
                  ? { ...m, content: failedText }
                  : m,
              )
            : s.messages,
          };
        });
        // SSE 被网关/代理断开或超时：HITL 卡片可能已落库但未送达，立即恢复补显（无需刷新）
        if (stillSameConversation && !receivedMessageEnd && !receivedHumanInput && !receivedCanvasModeRequired && !manualStop) {
          void reconnectTurnThenRecover(snapshotId, recoverTurnId);
        }
      }
    }
    // 不再自动清空参考图和资产ID——用户要求持久化到意图节点消费
    // if (get().activeConversationId === snapshotId) set({ refImageUrls: [] });
  },

  submitHumanInput: async (actionId, customText = '', params, assetIds, opts) => {
    const info: HumanInputInfo | null = opts?.formToken
      ? {
          turnId: opts.turnId,
          formToken: opts.formToken,
          taskId: '',
          formContent: '',
          actions: [],
          expirationTime: 0,
        }
      : get().waitingHumanInput;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    if (get().conversations.find((conversation) => conversation.id === id)?.status === 'archived') {
      set({ streamError: '会话已归档，无法继续操作' });
      return;
    }
    const resumeTurnId = info.turnId ?? get().generating[id]?.turnId;
    if (!resumeTurnId) {
      set({ streamError: '当前确认卡片缺少 turnId，请刷新后重试' });
      return;
    }
    // 新一轮发送：清除上次手动停止标志并新建 AbortController（供「停止生成」中断本流）
    manualStop = false;
    streamAbort = new AbortController();
    const signal = streamAbort.signal;
    // 与 sendMessage 一致：提交时清除上次的 streamError，避免旧错误文案残留
    // 保存已提交的人工介入信息：human_input 事件已把当前 assistant 占位气泡转为 HITL 卡片
    // （metadata.hitl），提交后仅更新该卡片选中状态（不再 push 新的用户「确认：xxx」气泡）。
    const act = info.actions.find((a) => a.id === actionId);
    const cardId = info.messageId ?? `hitl-${Date.now()}`;
    const hitlMeta: AgentMessageMetadata = {
      kind: 'hitl',
      pending: false,
      hitl: {
        formContent: info.formContent || '',
        actions: info.actions || [],
        pending: false,
        selectedActionId: actionId,
        selectedActionTitle: act?.title ?? actionId,
        customText: actionId === 'custom' ? customText : undefined,
        params,
        assetIds,
        messageId: cardId,
      },
    };
    set((s) => {
      if (opts?.hiddenCard) {
        return {
          streaming: true,
          generating: { ...s.generating, [id]: { hint: '', finished: false } },
          waitingHumanInput: null,
          streamError: null,
        };
      }
      const hasCard = s.messages.some((m) => m.id === cardId && m.metadata?.kind === 'hitl');
      const newCard: AgentMessage = {
        id: cardId,
        conversationId: id,
        role: 'assistant',
        content: info.formContent || '',
        createdAt: new Date().toISOString(),
        metadata: hitlMeta,
      };
      return {
        streaming: true,
        generating: { ...s.generating, [id]: { hint: '', finished: false } },
        waitingHumanInput: null,
        streamError: null,
        messages: hasCard
          ? s.messages.map((m) => (m.id === cardId ? { ...m, metadata: { ...(m.metadata ?? {}), ...hitlMeta } } : m))
          : [...s.messages, newCard],
      };
    });

    const assistantId = `tmp-assistant-${Date.now()}`;
    // 预定义选项不 push 用户气泡（选中状态已标记在卡片 metadata.hitl 上）；
    // action=custom 只 push 用户原文气泡（与后端 persistUserConfirmation 对齐，不带「确认：」前缀）
    if (actionId === 'custom' && customText) {
      const confirmMsg: AgentMessage = {
        id: `tmp-user-${Date.now()}`,
        conversationId: id,
        role: 'user',
        content: customText,
        createdAt: new Date().toISOString(),
      };
      set((s) => ({ messages: [...s.messages, confirmMsg] }));
    }

    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      
      createdAt: new Date().toISOString(),
    };
    set((s) => ({
      messages: [...s.messages, optimisticAssistant],
      pendingAssistantId: assistantId,
      generating: { ...s.generating, [id]: { ...s.generating[id], assistantId, assistantContent: '' } },
    }));

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    // 结束标志：正常收尾（message_end / human_input）不算"未收到回复"
    let receivedMessageEnd = false;
    let receivedHumanInput = false;
    let acceptedTaskId: string | null = null;
    let acceptedTaskKind: VideoTaskStatus['kind'] | null = null;
    let receivedAnyEvent = false;
    let reconciledTerminal = false;
    let reconciliationBusy = false;
    let reconciliationTimer: number | null = null;

    const releaseStream = await reserveStreamSlot(snapshotId);
    reconciliationTimer = window.setInterval(() => {
      if (!receivedAnyEvent || receivedMessageEnd || receivedHumanInput
          || manualStop || reconciledTerminal || reconciliationBusy) return;
      reconciliationBusy = true;
      void agentApi.getRuntimeStatus(snapshotId)
        .then(async (response) => {
          const active = response.data.data.activeTurn;
          if (active?.status === 'running') return;
          await useAgentStore.getState().replayTurnEvents(snapshotId, resumeTurnId);
          await refreshConversationMessages(snapshotId);
          reconciledTerminal = true;
          streamAbort?.abort();
        })
        .catch(() => undefined)
        .finally(() => {
          reconciliationBusy = false;
        });
    }, 2_000);
    try {
      await submitForm(id, resumeTurnId, info.formToken, info.taskId, actionId, (event: AgentTurnEventEnvelope) => {
        receivedAnyEvent = true;
        const effects = applyAgentTurnEnvelope(event, {
          conversationId: snapshotId,
          assistantId,
        });
        if (effects.paused) receivedHumanInput = true;
        if (effects.completed) {
          receivedMessageEnd = true;
          if (!acceptedTaskId || isDetachedAsyncTask(acceptedTaskKind)) {
            finishAssistantReply(snapshotId, assistantId);
          }
          refreshStoryboardQueues();
        }
        if (effects.taskAccepted) {
          receivedMessageEnd = true;
          acceptedTaskId = effects.taskAccepted.taskId;
          acceptedTaskKind = effects.taskAccepted.taskKind;
          if (acceptedTaskKind === 'long-tool' || acceptedTaskKind === 'image-batch') {
            attachTaskCard(snapshotId, assistantId, {
              taskId: acceptedTaskId,
              title: acceptedTaskKind === 'image-batch' ? '批量生成图片' : '生成剧情稿',
              kind: acceptedTaskKind,
            });
            void agentApi.getTask(acceptedTaskId)
              .then((response) => {
                get().upsertTask(response.data.data);
                attachTaskCard(snapshotId, assistantId, response.data.data);
              })
              .catch(() => undefined);
          } else {
            beginUnifiedVideoPolling(acceptedTaskId, snapshotId, assistantId, acceptedTaskKind);
          }
        }
        if (effects.confirmResult && get().activeConversationId === snapshotId) {
          set({ confirmResult: effects.confirmResult });
        }
      }, customText, params, assetIds, signal, {
        canvasContext: buildCanvasRequestContext(get().canvasEditContext, {
          picUrls: get().refImageUrls,
          assetIds: assetIds ?? get().pickedAssetIds,
        }),
      });
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误；用户主动停止不视为错误（不弹错误条）
      if (!manualStop && !reconciledTerminal && get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      }
    } finally {
      if (reconciliationTimer) clearInterval(reconciliationTimer);
      releaseStream();
      if (!acceptedTaskId || isDetachedAsyncTask(acceptedTaskKind)) {
        // streaming=false 无条件（单流模型）；占位补写仅在仍处于原会话时执行
        // （已切换会话时旧占位消息已被 selectConversation 整组替换）
        // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
        const isThisStream = get().pendingAssistantId === assistantId;
        const stillSameConversation = get().activeConversationId === snapshotId;
        // 失败（streamError 非空）时占位显示"（回复失败）"，避免"（未收到回复）"误导；手动停止显示"（已停止）"
        const failedText = manualStop ? '（已停止）' : (get().streamError ? '（回复失败）' : '（未收到回复）');
        set((s) => {
          const g = { ...s.generating };
          if (g[snapshotId]?.assistantId === assistantId) delete g[snapshotId];
          const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
          return {
            generating: g,
            streaming: generationInProgress(gview),
            workflowHint: generationHint(gview),
            ...(isThisStream ? { pendingAssistantId: null } : {}),
            messages: stillSameConversation
            ? s.messages.map((m) =>
                m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput
                  ? { ...m, content: failedText }
                  : m,
              )
            : s.messages,
          };
        });
        // SSE 被网关/代理断开或超时：HITL 卡片可能已落库但未送达，立即恢复补显（无需刷新）
        if (stillSameConversation && !receivedMessageEnd && !receivedHumanInput
            && !manualStop && !reconciledTerminal) {
          void reconnectTurnThenRecover(snapshotId, resumeTurnId);
        }
      }
    }
  },

  /**
   * 图生视频方案确认卡片（video_plan 事件）：
   * - 开始生成视频：统一走 turn resume（action=generate_video）
   *   → 立即收到 task_accepted（视频任务已受理）→ 前端 5s 轮询 GET /tasks/{taskId}，
   *   completed 后渲染视频结果 + confirm_result 卡片；failed 显示错误；
   * - 继续完善：本地关闭卡片，参考图转 pendingPicUrl 保留（与图片完善 refine 同语义），
   *   用户输入完善需求后随下一条消息发送（重新分流 → 再设计）。
   */
  submitVideoPlan: async (actionId, params, customText) => {
    const info = get().waitingVideoPlan;
    const id = get().activeConversationId;
    if (!id || !info || get().streaming) return;
    if (get().conversations.find((conversation) => conversation.id === id)?.status === 'archived') {
      set({ streamError: '会话已归档，无法继续操作' });
      return;
    }
    const resumeTurnId = info.turnId ?? get().generating[id]?.turnId;
    if (!resumeTurnId) {
      set({ streamError: '当前视频方案缺少 turnId，请刷新后重试' });
      return;
    }
    // 新一轮发送：清除上次手动停止标志并新建 AbortController（供「停止生成」中断本流）
    manualStop = false;
    streamAbort = new AbortController();
    const signal = streamAbort.signal;
    const cardId = info.messageId ?? `video-plan-${Date.now()}`;
    const selectedActionTitle = info.actions.find((a) => a.id === actionId)?.title ?? actionId;
    const videoPlanMeta = {
      message: info.message,
      duration: info.duration,
      picUrl: info.picUrl,
      actions: info.actions,
      formToken: info.planToken,
      messageId: cardId,
      models: info.models,
      recommended: info.recommended,
      reasons: info.reasons,
    };
    const markVideoCardSubmitted = () => set((s) => {
      const hasCard = s.messages.some((m) => m.id === cardId);
      const nextCard: AgentMessage = {
        id: cardId,
        conversationId: id,
        role: 'assistant',
        content: info.message,
        createdAt: new Date().toISOString(),
        metadata: {
          kind: 'video-plan',
          hitl: {
            formContent: info.message,
            actions: info.actions,
            pending: false,
            selectedActionId: actionId,
            selectedActionTitle,
            customText: actionId === 'custom' ? customText : undefined,
            params,
            messageId: cardId,
          },
          videoPlan: videoPlanMeta,
        },
      };
      return {
        messages: hasCard
          ? s.messages.map((m) => m.id === cardId ? {
              ...m,
              metadata: {
                ...(m.metadata ?? {}),
                ...(nextCard.metadata ?? {}),
              } as AgentMessageMetadata,
            } : m)
          : [...s.messages, nextCard],
      };
    });
    markVideoCardSubmitted();
    // 继续完善：本地关闭卡片 + 保留参考图（assetUrl 转绝对 URL，后端容器内可访问）
    if (actionId === 'refine') {
      set((s) => { const h = { ...s.hitlVideoPlan }; delete h[id]; return { waitingVideoPlan: null, hitlVideoPlan: h, pendingPicUrl: assetUrl(info.picUrl) }; });
      return;
    }
    // 自定义输入：走 submitForm 发送 custom action + 自定义文本，与 HumanInputCard 的 custom 流程对齐
    if (actionId === 'custom') {
      set((s) => {
        const h = { ...s.hitlVideoPlan }; delete h[id];
        return { streaming: true, generating: { ...s.generating, [id]: { hint: '', finished: false } }, waitingVideoPlan: null, hitlVideoPlan: h, streamError: null };
      });
      const assistantId = `tmp-assistant-${Date.now()}`;
      set((s) => ({
        messages: [...s.messages, {
          id: `tmp-user-${Date.now()}`, conversationId: id, role: 'user' as const,
          content: customText || '',  createdAt: new Date().toISOString(),
        }, {
          id: assistantId, conversationId: id, role: 'assistant' as const,
          content: '',  createdAt: new Date().toISOString(),
        }],
        pendingAssistantId: assistantId,
        generating: { ...s.generating, [id]: { ...s.generating[id], assistantId, assistantContent: '' } },
      }));
      const snapshotId = id;
      let receivedMessageEnd = false;
      let receivedHumanInput = false;
      const releaseStream = await reserveStreamSlot(snapshotId);
      try {
        await submitForm(id, resumeTurnId, info.planToken, '', 'custom', (event: AgentTurnEventEnvelope) => {
          const effects = applyAgentTurnEnvelope(event, {
            conversationId: snapshotId,
            assistantId,
          });
          if (effects.paused) receivedHumanInput = true;
          if (effects.completed) {
            receivedMessageEnd = true;
            finishAssistantReply(snapshotId, assistantId);
            refreshStoryboardQueues();
          }
        }, customText, params, undefined, signal, {
          canvasContext: buildCanvasRequestContext(get().canvasEditContext, {
            picUrls: get().refImageUrls,
            assetIds: get().pickedAssetIds,
          }),
        });
      } catch (err) {
        if (!manualStop && get().activeConversationId === snapshotId) set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
      } finally {
        releaseStream();
        // 仅当本流仍是「当前活动流」时才清 streaming/占位（无新流接管、未被切换重置），防旧流误清新流
        const isThisStream = get().pendingAssistantId === assistantId;
        const stillSame = get().activeConversationId === snapshotId;
        const failedText = manualStop ? '（已停止）' : (get().streamError ? '（回复失败）' : '（未收到回复）');
        set((s) => {
          const g = { ...s.generating };
          if (g[snapshotId]?.assistantId === assistantId) delete g[snapshotId];
          const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
          return {
            generating: g,
            streaming: generationInProgress(gview),
            workflowHint: generationHint(gview),
            ...(isThisStream ? { pendingAssistantId: null } : {}),
            messages: stillSame
            ? s.messages.map((m) => m.id === assistantId && !m.content && !receivedMessageEnd && !receivedHumanInput ? { ...m, content: failedText } : m)
            : s.messages,
            };
            });
      }
      return;
    }
    // 开始生成视频：与 submitHumanInput 一致——提交时清除上次 streamError
    set((s) => {
      const h = { ...s.hitlVideoPlan }; delete h[id];
      return { streaming: true, generating: { ...s.generating, [id]: { hint: '', finished: false } }, waitingVideoPlan: null, hitlVideoPlan: h, streamError: null };
    });

    // 新建 assistant 占位气泡：视频异步生成期间为空，聊天区显示生成中加载条。
    // 完成后由轮询结果填充，MessageBubble 渲染 <video> 播放器
    const assistantId = `tmp-assistant-${Date.now()}`;
    const optimisticAssistant: AgentMessage = {
      id: assistantId,
      conversationId: id,
      role: 'assistant',
      content: '',
      
      createdAt: new Date().toISOString(),
    };
    set((s) => ({
      messages: [...s.messages, optimisticAssistant],
      pendingAssistantId: assistantId,
      generating: { ...s.generating, [id]: { ...s.generating[id], assistantId, assistantContent: '' } },
    }));

    const updateAssistantFull = (full: string) => storeAssistantContent(id, assistantId, full);

    // 流快照：发起流时的会话 id，防止切换会话后旧流事件污染新会话
    const snapshotId = id;
    let receivedResult = false;
    // 视频异步任务：task_accepted 后启动 5s 轮询（跨会话守卫；completed/failed 停止）
    let acceptedTaskId: string | null = null;
    let pollTick = 0; // 轮询次数（阶段提示「已等待约 X 秒」用，5s/tick）
    const startTaskPolling = (taskId: string, taskKind?: VideoTaskStatus['kind']) => {
      resumeGenerationForTask(snapshotId, assistantId,
        taskKind === 'image-batch' ? '批量生图中...' : taskKind === 'long-tool' ? '后台任务处理中...' : '视频生成中...');
      // 任务中心：任务开始，暴露进行中状态（completed/failed 清空）
      set({ agentVideoTask: { taskId, status: 'queued', waitingSec: 0 } });
      void agentApi.getTask(taskId).then((response) => {
        useAgentStore.getState().upsertTask(response.data.data, { notifyIfTerminal: true });
        attachTaskCard(snapshotId, assistantId, response.data.data);
      }).catch(() => undefined);
      const myToken = ++videoPollToken;
      const timer = setInterval(async () => {
        if (acceptedTaskId === null) { clearInterval(timer); videoPollTimer = null; return; }
        // 新一轮发送/停止生成时已作废旧轮询：直接退出（防止过期任务结果污染新流）
        if (videoPollToken !== myToken || get().agentVideoTask?.taskId !== taskId) { clearInterval(timer); return; }
        pollTick += 1;
        try {
          const res = await agentApi.getVideoTaskStatus(taskId);
          const t: VideoTaskStatus = res.data.data;
          if (t.status === 'completed') {
            void agentApi.getTask(taskId).then((detail) => {
              useAgentStore.getState().upsertTask(detail.data.data, { notifyIfTerminal: true });
            }).catch(() => undefined);
            clearInterval(timer); videoPollTimer = null;
            receivedResult = true;
            updateAssistantFull(appendTaskResult(
              get().generating[snapshotId]?.assistantContent,
              taskCompletedContent(t)));
            set((s) => {
              const g = { ...s.generating };
              if (g[snapshotId]?.assistantId === assistantId) delete g[snapshotId];
              const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
              return {
                generating: g,
                streaming: generationInProgress(gview),
                workflowHint: generationHint(gview),
                pendingAssistantId: null,
                agentVideoTask: null,
                confirmResult: t.kind === 'long-tool' ? s.confirmResult : {
                  kind: t.kind === 'image-batch' ? 'image' : 'video',
                  url: t.url || '',
                  urls: t.resultUrls,
                  assetId: t.assetId || t.assetIds?.[0],
                  sceneCount: 0,
                  actions: [{ id: 'refine', title: '继续完善' }, { id: 'done', title: '满意完成' }],
                },
              };
            });
            void get().loadAssets();
            const groupId = sessionStorage.getItem('moon.activeGroupId');
            if (groupId && t.url) {
              const projectId = useProjectStore.getState().currentProject?.id;
              void libraryApi.registerGeneratedOutput({
                producer: 'moon-agent',
                projectId,
                generationRecordId: t.assetId || taskId,
                resultIndex: 0,
                groupId,
                resultAssetId: t.assetId,
                resultUrl: t.url,
                mediaType: 'VIDEO',
              }).catch(() => undefined);
              sessionStorage.removeItem('moon.activeGroupId');
            }
            if (t.kind === 'image-batch' && t.followUpTaskId) {
              startTaskPolling(t.followUpTaskId, 'video');
            }
          } else if (t.status === 'failed') {
            clearInterval(timer); videoPollTimer = null;
            set((s) => {
              const g = { ...s.generating };
              if (g[snapshotId]?.assistantId === assistantId) delete g[snapshotId];
              const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
              const isThis = s.activeConversationId === snapshotId;
              return {
                generating: g,
                streaming: generationInProgress(gview),
                workflowHint: generationHint(gview),
                agentVideoTask: null,
                ...(isThis ? { streamError: t.error || '任务生成失败，请重试' } : {}),
              };
            });
          } else {
            // queued/running：写入属主会话 generating（切走后台继续、切回见最新进度）；仅当正看该会话才同步视图
            const waiting = Math.round(pollTick * 5);
            const stage = taskKindHint(t.kind ?? taskKind, t.status, waiting);
            set((s) => {
              const g = { ...s.generating, [snapshotId]: { ...(s.generating[snapshotId] ?? { hint: '' }), hint: stage } };
              const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
              return {
                generating: g,
                streaming: generationInProgress(gview),
                workflowHint: generationHint(gview),
                agentVideoTask: { taskId, status: t.status === 'queued' ? 'queued' : 'running', waitingSec: waiting },
              };
            });
          }
          // queued/running：继续轮询（5s 后下一次 tick）
        } catch {
          // 单次轮询失败不终止：下一 tick 重试（后端瞬时故障容错）
        }
      }, VIDEO_POLL_INTERVAL);
      videoPollTimer = timer;
    };

    // 统一 HITL 提交路径：video_plan 的 planToken 即 checkpoint formToken
    const releaseStream = await reserveStreamSlot(snapshotId);
    try {
      await submitForm(id, resumeTurnId, info.planToken, '', 'generate_video', (event: AgentTurnEventEnvelope) => {
        const effects = applyAgentTurnEnvelope(event, {
          conversationId: snapshotId,
          assistantId,
        });
        if (effects.taskAccepted) {
          acceptedTaskId = effects.taskAccepted.taskId;
          set((state) => ({
            workflowHint: effects.taskAccepted?.message ?? '视频任务已受理，正在排队生成…',
            generating: {
              ...state.generating,
              [snapshotId]: {
                ...(state.generating[snapshotId] ?? { hint: '' }),
                hint: effects.taskAccepted?.message ?? '视频任务已受理，正在排队生成…',
              },
            },
          }));
          if (acceptedTaskId) startTaskPolling(acceptedTaskId, effects.taskAccepted.taskKind);
        }
        if (effects.confirmResult && get().activeConversationId === snapshotId) {
          receivedResult = true;
          set({ confirmResult: effects.confirmResult });
        }
      }, '', params, undefined, signal, {
        canvasContext: buildCanvasRequestContext(get().canvasEditContext, {
          picUrls: get().refImageUrls,
          assetIds: get().pickedAssetIds,
        }),
      });
    } catch (err) {
      // M3：跨会话守卫——已切换会话则忽略旧流错误；用户主动停止不视为错误（不弹错误条）
      if (!manualStop && get().activeConversationId === snapshotId) {
        set({ streamError: err instanceof Error ? err.message : '生成出错，请重试' });
      }
    } finally {
      releaseStream();
      const stillSameConversation = get().activeConversationId === snapshotId;
      // 异步视频任务已受理：保持 streaming（轮询 completed 时关闭），占位气泡不补写
      if (acceptedTaskId) {
        if (!get().streaming) set({ streaming: true });
      } else {
        // 未受理（异常/失败）：该会话不在生成，删除 generating + 按 active 会话重置视图
        const failedText = manualStop ? '（已停止）' : (get().streamError ? '（生成失败）' : '（未收到回复）');
        set((s) => {
          const g = { ...s.generating };
          if (g[snapshotId]?.assistantId === assistantId) delete g[snapshotId];
          const gview = s.activeConversationId ? g[s.activeConversationId] : undefined;
          return {
            generating: g,
            streaming: generationInProgress(gview),
            workflowHint: generationHint(gview),
            pendingAssistantId: null,
            messages: stillSameConversation
            ? s.messages.map((m) =>
                m.id === assistantId && !m.content && !receivedResult
                  ? { ...m, content: failedText }
                  : m,
              )
            : s.messages,
          };
        });
      }
    }
  },

  /** 停止当前会话生成（「停止生成」按钮）：中断 SSE 流（打字机停）+ 后端协作式取消 + 复位视图 */
  stopGeneration: () => {
    const id = get().activeConversationId;
    const pendingAssistantId = get().pendingAssistantId;
    manualStop = true;
    // 中断当前 SSE fetch（若有）：consumeSse 循环检测 signal.aborted 会立即跳出 → 触发 sendMessage 等的 finally 清理
    streamAbort?.abort();
    streamAbort = null;
    // 视频异步任务轮询期：停止轮询并作废代次令牌
    if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
    videoPollToken += 1;
    // Turn-scoped cancellation; no active turn means there is nothing to interrupt.
    const turnId = id ? get().generating[id]?.turnId : undefined;
    if (id && turnId) void agentApi.interruptTurn(id, turnId).catch(() => { /* 静默 */ });
    // 视频异步任务轮询期 / 无活跃 SSE 流：直接复位当前会话视图（后端视频任务仍会后台跑完）
    set((s) => {
      const g = { ...s.generating };
      if (id) delete g[id];
      const gview = id && s.activeConversationId ? g[s.activeConversationId] : undefined;
      return {
        generating: g,
        streaming: generationInProgress(gview),
        workflowHint: generationHint(gview),
        ...(pendingAssistantId ? {
          pendingAssistantId: null,
          messages: s.messages.filter((message) => message.id !== pendingAssistantId),
        } : {}),
      };
    });
  },

  /** 跳过当前 HITL 卡片：清除等待状态，让用户继续输入其他话题（checkpoint 30 分钟后自动过期） */
  skipCurrentHITL: () => {
    const id = get().activeConversationId;
    // 后端作废 pending checkpoint，防止刷新/切换会话后旧卡片恢复
    if (id) void agentApi.skipPendingCheckpoint(id).catch(() => {});
    set((s) => { const h = { ...s.hitlVideoPlan }; if (id) delete h[id]; return { waitingHumanInput: null,
       waitingVideoPlan: null, hitlVideoPlan: h }; });
  },

  /** 流结束后恢复已落库的待处理 HITL 卡片：SSE 被网关/代理断开或超时，卡片只落库未送达时，可在不刷新页面的情况下补显。 */
  recoverPendingCheckpoint: async (conversationId: string) => {
    if (!conversationId) return;
    await restorePendingCanvasSession(conversationId);
    try {
      const cpRes = await agentApi.getPendingCheckpoint(conversationId);
      const cp = cpRes.data.data as (HumanInputInfo & Partial<VideoPlanInfo> & Partial<CanvasBinding> & {
        kind?: string;
      }) | null;
      const cpKind = (cp as { kind?: string } | null)?.kind;
      if (cpKind === 'canvas-bind' && cp?.formToken) {
        get().startCanvasBinding({
          conversationId,
          turnId: cp.turnId,
          projectId: cp.projectId,
          canvasId: cp.canvasId,
          canvasTitle: cp.canvasTitle,
          requestedIntent: cp.requestedIntent,
          formToken: cp.formToken,
          expirationTime: String(cp.expirationTime ?? ''),
          picUrls: cp.picUrls,
          assetIds: cp.assetIds,
          mediaRefs: cp.mediaRefs,
        });
        return;
      }
      if (!cp || !cp.formToken || !cp.actions || cp.actions.length === 0) return;
      const isActive = get().activeConversationId === conversationId;
      if (cp.kind === 'video-plan' || cp.hitlType === 'generate_video') {
        const videoInfo: VideoPlanInfo = {
          planToken: cp.formToken,
          message: cp.message ?? cp.formContent,
          duration: cp.duration ?? 8,
          picUrl: cp.picUrl ?? '',
          actions: cp.actions,
          messageId: cp.messageId,
          models: cp.models,
          recommended: cp.recommended,
          reasons: cp.reasons,
        };
        set((s) => ({
          waitingVideoPlan: videoInfo,
          hitlVideoPlan: { ...s.hitlVideoPlan, [conversationId]: videoInfo },
          workflowHint: isActive ? '' : s.workflowHint,
        }));
      } else {
        set((s) => ({
          waitingHumanInput: cp,
          workflowHint: isActive ? '' : s.workflowHint,
        }));
      }
    } catch { /* 静默：网络抖动不打断聊天 */ }
  },

  /** 分镜视频批量入队（全自动生成视频门禁确认后调用）。 */
  enqueueStoryboardVideoQueue: async (items, projectId) => {
    const pid = projectId ?? useProjectStore.getState().currentProject?.id;
    const res = await agentApi.enqueueStoryboardVideos({
      conversationId: get().activeConversationId ?? undefined,
      projectId: pid,
      items,
    });
    const created = res.data.data ?? [];
    // 入队后刷新整队（后端已启动串行处理）
    if (pid) void get().refetchStoryboardVideoQueue(pid);
    return created.map((row) => row.id);
  },

  /** 刷新任务队列（右上角面板轮询 + 分镜行状态）。 */
  refetchStoryboardVideoQueue: async (projectId) => {
    const pid = projectId ?? useProjectStore.getState().currentProject?.id;
    if (!pid) return;
    try {
      const res = await agentApi.listStoryboardVideoQueue(pid);
      set({ storyboardVideoQueue: res.data.data ?? [] });
    } catch {
      /* 静默：网络抖动不打断聊天 */
    }
  },

  /** 取消单个队列任务。 */
  cancelStoryboardVideoQueue: async (queueId) => {
    await agentApi.cancelStoryboardVideoQueue(queueId);
    const pid = useProjectStore.getState().currentProject?.id;
    if (pid) void get().refetchStoryboardVideoQueue(pid);
  },

  /** 分镜「完善图片」批量入队（图改图模式）。 */
  enqueueStoryboardImageRefine: async (items, projectId) => {
    const pid = projectId ?? useProjectStore.getState().currentProject?.id;
    const res = await agentApi.enqueueStoryboardImageRefine({
      conversationId: get().activeConversationId ?? undefined,
      projectId: pid,
      items,
    });
    const created = res.data.data ?? [];
    if (pid) void get().refetchStoryboardImageQueue(pid);
    return created.map((row) => row.id);
  },

  /** 刷新「完善图片」队列（右上角面板轮询 + 分镜行状态）。 */
  refetchStoryboardImageQueue: async (projectId) => {
    const pid = projectId ?? useProjectStore.getState().currentProject?.id;
    if (!pid) return;
    try {
      const res = await agentApi.listStoryboardImageQueue(pid);
      set({ storyboardImageQueue: res.data.data ?? [] });
    } catch {
      /* 静默 */
    }
  },

  /** 取消单个「完善图片」队列任务。 */
  cancelStoryboardImageQueue: async (queueId) => {
    await agentApi.cancelStoryboardImageQueue(queueId);
    const pid = useProjectStore.getState().currentProject?.id;
    if (pid) void get().refetchStoryboardImageQueue(pid);
  },

  /** 看图确认卡片：继续完善 → 暂存当前图 PicUrl，不自动发送；用户输入完善需求后随下一条消息发送 */
  refineAsset: () => {
    const { confirmResult } = get();
    if (!confirmResult || confirmResult.kind === 'script') return;
    // 审查修复：confirmResult.url 是后端相对路径（/api/files/images/x.png），容器内无法访问；
    // assetUrl() 拼接 BACKEND 前缀转绝对 URL（http/data: 透传）后再发给后端
    const picUrl = assetUrl(confirmResult.url);
    set({ confirmResult: null, pendingPicUrls: [picUrl] });
  },
  /** 取消继续完善：清空暂存参考图（输入框提示条上的 ✕ 触发） */
  cancelRefine: () => set({ pendingPicUrls: [] }),
  /**
   * 看图确认卡片：满意完成 → 通知后端清空图片上下文变量（下次图片需求走全新设计）。
   * 成功收起卡片 + 刷新资产；失败保留卡片并提示（可重试）。纯前端收起（旧行为）会
   * 变量残留 → 下次图片需求误走完善路径。
   */
  dismissConfirm: async () => {
    const id = get().activeConversationId;
    if (!id) {
      set({ confirmResult: null });
      return;
    }
    try {
      await agentApi.confirmDone(id);
      set({ confirmResult: null });
      void get().loadAssets();
    } catch {
      alert('操作失败，请重试');
    }
  },

  resetChatState: () => {
    if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
    videoPollToken += 1;
    clearCanvasEditContext();
    set({ messages: [], generating: {}, timelineByConversation: {}, streaming: false, waitingHumanInput: null,
       pendingApprovalMode: DEFAULT_AGENT_APPROVAL_MODE,
       waitingVideoPlan: null, hitlVideoPlan: {}, streamError: null, workflowHint: '', assets: null, refImageUrls: [],
       pendingAttachments: [], pickedReferenceVideos: [], pickedReferenceAudios: [],
       pendingAssistantId: null,
       confirmResult: null, pendingPicUrls: [], storyboardImageQueue: [],
       canvasEditContext: null, canvasBinding: null, canvasBindingStatus: '', canvasExitPending: false,
       sideChat: {
         open: false,
         loading: false,
         focusToken: 0,
         sourceConversationId: null,
         sourceTitle: '',
         projectId: null,
         conversationId: null,
         error: null,
       } });
  },

  activeModal: null,
  setActiveModal: (modal) => set({ activeModal: modal }),
  historyExpanded: false,
  setHistoryExpanded: (v) => set({ historyExpanded: v }),
}));
