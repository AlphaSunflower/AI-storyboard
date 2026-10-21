import { create } from 'zustand';
import {
  agentApi, streamChat, submitForm, streamCanvasSessionReady, streamTaskEvents,
  streamTurnResume,
  type AgentConversation, type AgentMessage, type AgentAsset,
  type AgentPage, type AgentTurnEventEnvelope,
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
  type AgentReference,
  type AgentToolDraft,
  type CanvasSessionInfo,
  type ConfirmResultInfo,
  type ClearContextResult,
} from '../api/agent';
import { useProjectStore } from './projectStore';
import { notify } from './toastStore';
import { useSendQueueStore } from './sendQueueStore';
import { useAccountSettingsStore } from './accountSettingsStore';
import { useChatAnnotationStore } from './chatAnnotationStore';
import { rememberDocumentSource } from '../lib/documentPreview';
import { activeReferences } from '../lib/mentionReferences';
import { useDocumentPreviewStore } from './documentPreviewStore';
import { useSideChatSelectionStore } from './sideChatSelectionStore';
import { assetUrl, VIDEO_POLL_INTERVAL } from '../config';
import type { GatewayModelOption } from '../api/ai';
import { libraryApi, type LibraryMedia } from '../api/library';
import { perfMark } from '../lib/devLongTaskLogger';
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
import {
  loadGenerationPreferences,
  saveGenerationPreferences,
  toGenerationPreferencesPayload,
  type GenerationPreferences,
} from '../lib/generationPreferences';
import { collectDraftRenderIntents, renderIntentFromEventData } from '../lib/renderIntent';
import { isMediaTask } from '../lib/mediaTaskSlots';

let conversationLoadEpoch = 0;
let conversationListEpoch = 0;
let sideChatRequestSeq = 0;

/** 通知里回显的错误摘要：优先用接口/异常消息，兜底给一句中文 */
function errorText(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

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

/**
 * 一次「把这段文字放进输入框」的请求。
 *
 * `seq` 单调递增，输入框只在 seq 变大时消费一次：否则每次重渲染都会把同一段提示词
 * 再贴一遍，用户改过的内容会被冲掉。
 */
export interface ComposerInjection {
  seq: number;
  text: string;
}

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

function pendingMediaFromAttachments(attachments: AgentAttachment[]) {
  return {
    refImageUrls: attachments.filter((item) => item.kind === 'image').map((item) => item.url),
    pickedReferenceVideos: attachments.filter((item) => item.kind === 'video').map((item) => item.url),
    pickedReferenceAudios: attachments.filter((item) => item.kind === 'audio').map((item) => item.url),
  };
}

const PENDING_REFERENCES_KEY = 'moon.agent.pendingReferences';

function pendingReferencesKey(conversationId?: string | null) {
  return `${PENDING_REFERENCES_KEY}.${conversationId || 'draft'}`;
}

const AGENT_REFERENCE_KINDS: AgentReference['kind'][] = [
  'library_file', 'local_file', 'scene', 'conversation',
];

function isAgentReference(value: unknown): value is AgentReference {
  const item = asRecord(value);
  return !!item
    && typeof item.id === 'string'
    && typeof item.token === 'string'
    && AGENT_REFERENCE_KINDS.includes(String(item.kind) as AgentReference['kind']);
}

function readPendingReferences(conversationId?: string | null): AgentReference[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.localStorage.getItem(pendingReferencesKey(conversationId));
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter(isAgentReference) : [];
  } catch {
    return [];
  }
}

function persistPendingReferences(conversationId: string | null | undefined, references: AgentReference[]) {
  if (typeof window === 'undefined') return;
  try {
    const key = pendingReferencesKey(conversationId);
    if (references.length === 0) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, JSON.stringify(references));
  } catch {
    // Storage can be unavailable in private mode; the in-memory state still works.
  }
}

/** 同一份资料只保留一条（键 = `kind:id`），后写入的覆盖前者（名称/token 以最新一次为准）。 */
function dedupeReferences(references: AgentReference[]): AgentReference[] {
  const map = new Map<string, AgentReference>();
  references.forEach((item) => map.set(`${item.kind}:${item.id}`, item));
  return [...map.values()];
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

  /** 该 turn 是否有等待缺口的乱序事件（不猜序号，只报告事实）。 */
  hasGap(turnId: string): boolean {
    return (this.pendingByTurn.get(turnId)?.size ?? 0) > 0;
  }

  /** 等待缺口的乱序事件条数。 */
  pendingCount(turnId: string): number {
    return this.pendingByTurn.get(turnId)?.size ?? 0;
  }

  /** 缺口后的下一个期望序号（= last + 1）。 */
  expectedSeq(turnId: string): number {
    return this.lastSeq(turnId) + 1;
  }

  lastSeq(turnId: string): number {
    return this.lastSeqByTurn.get(turnId) ?? 0;
  }

  /** Move a turn to a known sequence boundary without replaying earlier phases. */
  seek(turnId: string, seq: number) {
    if (!turnId) return;
    this.pendingByTurn.delete(turnId);
    if (seq > 0) this.lastSeqByTurn.set(turnId, seq);
    else this.lastSeqByTurn.delete(turnId);
  }

  reset(turnId: string) {
    this.lastSeqByTurn.delete(turnId);
    this.pendingByTurn.delete(turnId);
  }
}

/**
 * 缺口恢复：`seq` 缺口超过 `turnGapTimeoutForAttempt(attempt)` 仍未补齐时，
 * 主动用既有 replay 能力（`?afterSeq=lastSeq`）补一次事件。
 *
 * 红线约束：
 * - 只补一次、带退避、有上限，绝不轮询死循环；
 * - 绝不猜测 / 重排序号：补回来的事件仍走同一个 sequencer 归并；
 * - 正常到达（无缺口）时这里完全不会被调用，直播行为零变化。
 */
export const TURN_GAP_TIMEOUT_MS = 2_500;
export const TURN_GAP_MAX_ATTEMPTS = 3;
export const TURN_GAP_BACKOFF_FACTOR = 2;

/**
 * 新会话的默认审批模式以账号级「全局权限」为准。
 *
 * 不能再用 DEFAULT_AGENT_APPROVAL_MODE：用户在设置页选了完全访问后，
 * newConversation / 删除当前会话 / 切换会话的回退值都会把 pendingApprovalMode
 * 重新打回方案批准，表现为「设置没生效」。
 */
function defaultApprovalMode(): AgentApprovalMode {
  return useAccountSettingsStore.getState().settings.defaultApprovalMode;
}

/** 第 n 次（从 0 计）缺口恢复的等待时长：2500 → 5000 → 10000 ms。 */
export function turnGapTimeoutForAttempt(attempt: number): number {
  const safeAttempt = Number.isFinite(attempt) && attempt > 0 ? Math.floor(attempt) : 0;
  return TURN_GAP_TIMEOUT_MS * TURN_GAP_BACKOFF_FACTOR ** Math.min(safeAttempt, 6);
}

type TurnGapRecoveryState = {
  attempts: number;
  timer: ReturnType<typeof setTimeout> | null;
  conversationId: string;
  assistantId: string;
};

const turnGapRecovery = new Map<string, TurnGapRecoveryState>();

/**
 * 记录一次缺口并安排一次补偿拉取；已排期 / 已达上限时是空操作。
 * @returns 本次是否新排期了补偿拉取（供测试与诊断使用）。
 */
export function scheduleTurnGapRecovery(
  turnId: string,
  conversationId: string,
  assistantId: string,
): boolean {
  if (!turnId || !conversationId) return false;
  const current = turnGapRecovery.get(turnId);
  if (current?.timer) return false;
  const attempts = current?.attempts ?? 0;
  if (attempts >= TURN_GAP_MAX_ATTEMPTS) return false;

  const state: TurnGapRecoveryState = {
    attempts,
    timer: null,
    conversationId,
    assistantId,
  };
  const delay = turnGapTimeoutForAttempt(attempts);
  state.timer = setTimeout(() => {
    const live = turnGapRecovery.get(turnId);
    if (!live || live.timer !== state.timer) return;
    live.timer = null;
    live.attempts += 1;
    void useAgentStore.getState()
      .replayTurnEvents(live.conversationId, turnId)
      .catch(() => undefined);
  }, delay);
  turnGapRecovery.set(turnId, state);
  return true;
}

/** 缺口补齐（或 turn 终态 / 复位）后回收补偿状态，避免跨轮次残留计数。 */
export function clearTurnGapRecovery(turnId?: string) {
  const clear = (key: string, state: TurnGapRecoveryState) => {
    if (state.timer) clearTimeout(state.timer);
    turnGapRecovery.delete(key);
  };
  if (turnId) {
    const state = turnGapRecovery.get(turnId);
    if (state) clear(turnId, state);
    return;
  }
  for (const [key, state] of Array.from(turnGapRecovery.entries())) clear(key, state);
}

/** 测试 / 诊断用：当前排期的缺口恢复快照。 */
export function turnGapRecoverySnapshot(turnId: string): { attempts: number; scheduled: boolean } {
  const state = turnGapRecovery.get(turnId);
  return { attempts: state?.attempts ?? 0, scheduled: !!state?.timer };
}

/** 已有缺口时补一次排期；无缺口则回收计数（正常到达的事件让缺口消失 → 不再补偿）。 */
function trackTurnGap(turnId: string, conversationId: string, assistantId: string) {
  if (!turnId) return;
  if (turnEventSequencer.hasGap(turnId)) {
    scheduleTurnGapRecovery(turnId, conversationId, assistantId);
    return;
  }
  clearTurnGapRecovery(turnId);
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
  toolDrafts?: Record<string, AgentToolDraft>;
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

/**
 * 用户打断/停止过的 turn：其后到达的正文 delta 不再写入气泡。
 *
 * 打断是协作式的，服务端停下来的这几十到几百毫秒里还会飘来几个 delta；
 * 不挡住的话气泡会在「…」之后继续长出新字，看起来像没停住。
 */
const interruptedTurns = new Set<string>();

/** 打断后等待旧回合真正收尾的上限；超时则不发新消息，把输入内容还给用户。 */
const INTERRUPT_SETTLE_TIMEOUT_MS = 20_000;
const INTERRUPT_SETTLE_POLL_MS = 120;
/** 终态事件早于服务端 finally 释放会话锁，发新回合前留一点余量。 */
const INTERRUPT_RELEASE_GRACE_MS = 150;
/** 正在「先打断、后发送」的会话：期间再次点发送不会提交第二条。 */
const pendingInterruptSends = new Set<string>();

/**
 * 被打断的回复以「…」收尾；已经以省略号结尾时不重复追加。
 * 与后端 `AgentChatServiceImpl.interruptedReplyContent` 同一口径，刷新前后文案一致。
 */
export function interruptedReplyText(text: string | null | undefined): string {
  const trimmed = (text ?? '').replace(/\s+$/, '');
  if (!trimmed) return '';
  return /(…|\.\.\.)$/.test(trimmed) ? trimmed : `${trimmed}…`;
}

/**
 * 打断/停止当前生成：把已经流出的正文定格在气泡上（末尾「…」），**不删除消息**。
 *
 * <p>旧实现直接 `messages.filter(id !== pendingAssistantId)`，用户已经看到的那段生成文字
 * 会整段消失，刷新后也找不回来。现在文字留在气泡里并标记 `metadata.interrupted`，
 * 服务端也会把同一段截断正文落库（刷新对账时由 {@link mergeInterruptedReplies} 兜底）。</p>
 *
 * @return 定格后的正文；本轮还没有任何可见正文时返回空串
 */
function freezeInterruptedReply(conversationId: string, assistantId?: string | null): string {
  const state = useAgentStore.getState();
  const generation = state.generating[conversationId];
  const targetId = assistantId ?? generation?.assistantId ?? state.pendingAssistantId ?? undefined;
  const target = targetId
    ? state.messages.find((message) => message.id === targetId)
    : undefined;
  const frozen = interruptedReplyText(
    generation?.assistantContent ?? target?.content ?? '');
  const frozenMessage: AgentMessage | null = frozen && target
    ? {
        ...target,
        content: frozen,
        metadata: { ...(target.metadata ?? {}), interrupted: true },
      }
    : null;
  useAgentStore.setState((s) => {
    const generating = { ...s.generating };
    const current = generating[conversationId];
    if (current) {
      generating[conversationId] = {
        ...current,
        hint: '',
        assistantContent: frozen ? frozen : current.assistantContent,
      };
    }
    return {
      generating,
      messages: frozenMessage
        ? s.messages.map((message) => (message.id === frozenMessage.id ? frozenMessage : message))
        : s.messages,
    };
  });
  return frozen;
}

/**
 * 本会话里已经定格的截断回复（`metadata.interrupted`）。
 *
 * <p>切换会话会把本地消息整体清空，所以要在清空**之前**取出来，再合并回服务端列表。</p>
 */
function interruptedRepliesOf(conversationId: string): AgentMessage[] {
  const state = useAgentStore.getState();
  if (state.activeConversationId !== conversationId) return [];
  return state.messages.filter((message) => message.metadata?.interrupted);
}

/**
 * 服务端消息列表落地前的兜底：本地已经定格的截断回复不能被对账冲掉。
 *
 * <p>打断后落库有一小段窗口，若此时刷新/对账（列表整体替换），用户刚保住的那段文字会再次
 * "被清空"。这里把本地 `metadata.interrupted` 且服务端还没有同内容消息的回复按时间插回去；
 * 服务端落库后内容一致，自然不会再重复插入。</p>
 */
function mergeInterruptedReplies(
  messages: AgentMessage[],
  interrupted: AgentMessage[],
): AgentMessage[] {
  if (interrupted.length === 0) return messages;
  const merged = [...messages];
  for (const message of interrupted) {
    const served = merged.some((candidate) =>
      candidate.id === message.id
      || (candidate.role === 'assistant' && candidate.content === message.content));
    if (served) continue;
    const at = Date.parse(message.createdAt);
    const index = Number.isFinite(at)
      ? merged.findIndex((candidate) => {
          const parsed = Date.parse(candidate.createdAt);
          return Number.isFinite(parsed) && parsed > at;
        })
      : -1;
    if (index < 0) merged.push(message);
    else merged.splice(index, 0, message);
  }
  return merged;
}

/**
 * 等服务端真的把这一轮停掉（终态事件或流结束都会清掉 generating）。
 * 只有等到它落定，后续新回合才不会撞会话锁、也不会把打断标志一并清掉。
 */
function waitForInterruptSettled(conversationId: string, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve) => {
    const check = () => {
      const generation = useAgentStore.getState().generating[conversationId];
      if (!generation || generation.finished) {
        resolve(true);
        return;
      }
      if (Date.now() >= deadline) {
        resolve(false);
        return;
      }
      window.setTimeout(check, INTERRUPT_SETTLE_POLL_MS);
    };
    check();
  });
}

/**
 * 把一条回合内插入的用户消息放到当前助手气泡之前。
 *
 * <p>同一 messageId 只插一次：服务端 USER_MESSAGE 事件与 HTTP 响应的乐观插入谁先到都安全。
 */
function insertSteerMessage(
  conversationId: string,
  beforeMessageId: string | undefined,
  message: AgentMessage,
) {
  useAgentStore.setState((state) => {
    if (state.activeConversationId !== conversationId) return {};
    if (state.messages.some((item) => item.id === message.id)) return {};
    const index = beforeMessageId
      ? state.messages.findIndex((item) => item.id === beforeMessageId)
      : -1;
    const messages = index >= 0
      ? [...state.messages.slice(0, index), message, ...state.messages.slice(index)]
      : [...state.messages, message];
    return { messages };
  });
}


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
  const pending = useAgentStore.getState().generating[conversationId];
  const applied = !!pending && pending.assistantId === assistantId;
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
  if (!applied) return;
  // 只提示「没在看的会话」：正在看的对话不打扰（与侧边栏「新」标记同一条件）
  const after = useAgentStore.getState();
  if (after.activeConversationId === conversationId) return;
  const title = after.conversations.find((c) => c.id === conversationId)?.title ?? '';
  notify({ title: '回复已完成', description: title ? `「${title}」` : undefined });
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
  /** 写任务图：逐节点/逐工具授权选项；缺失时保持整图批准 */
  writeTools?: { nodeId: string; role: string; tools: string[] }[];
  /** 卡片不可交互（只读回显）：formToken 为空/空白时置位，重放不得再触发提交。 */
  inactive?: boolean;
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

/** 生成结果确认信息：定义在 api/agent（它是消息 metadata 的一部分），此处转发以兼容既有引用。 */
export type { ConfirmResultInfo } from '../api/agent';

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
  expirationTime?: number;
  // 卡片模型/参数选项 + LLM 推荐（后端 video_plan 事件下发；无配置时为空数组/空对象）
  models?: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
}

/**
 * 一轮发送可携带的上下文快照。
 *
 * <p>排队消息「插队发送」会把排队时存下的 `opts` 原样交给 `sendMessage`，
 * 因此这里必须是引用/附件等字段的完整集合，不能在打断路径上丢附件。</p>
 */
export interface AgentSendOptions {
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
  attachments?: AgentAttachment[];
  references?: AgentReference[];
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
  /** run_task_graph 的逐节点进度，键为工具调用 id → 子任务 id。 */
  subagentProgress: Record<string, Record<string, {
    status: string;
    role?: string;
    output?: string;
    error?: string;
  }>>;
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
  /** 生成偏好（即梦式）：自动 / 自定义 + 图片、视频比例清晰度模型 + 忽略生成方案；存浏览器本地 */
  generationPreferences: GenerationPreferences;
  /** 更新生成偏好并写入本地存储（两个开关都记住，下次进入沿用） */
  updateGenerationPreferences: (
    updater: (previous: GenerationPreferences) => GenerationPreferences,
  ) => void;
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
  deleteConversation: (id: string, options?: { silent?: boolean }) => Promise<void>;
  // 清空当前会话聊天记录（删消息 + 重置 AI 上下文；会话与资产保留，其他会话不受影响）
  clearMessages: () => Promise<void>;
  /**
   * 「/清空上下文」：后端真删除该会话的全部上下文，前端把会话态整体退回新对话。
   * @returns 真删行数与重置后的标题（供提示文案用）
   */
  clearContext: () => Promise<ClearContextResult | null>;
  selectConversation: (id: string) => Promise<void>;
  /**
   * 项目切换时的会话态重置。
   *
   * <p>不带参数 = 普通项目切换，清空会话态让 `loadConversations` 选中最近会话；
   * 带 `deepLinkConversationId` = 本次跳转是「按会话 ID 深链」（项目库使用位置、产出对话、
   * 通知中心、观测页），`selectConversation` 已经切过去了，这里必须原样保留，
   * 否则目标会话会被「最近会话」顶掉。
   */
  resetConversationForProject: (deepLinkConversationId?: string | null) => void;

  // 消息
  messages: AgentMessage[];
  messageNextCursor: string | null;
  messageHasMore: boolean;
  /**
   * 消息已经落到 store 的会话 id（首屏骨架屏据此收起）。
   *
   * <p>切会话时 `messages` 会先被清空，光看 `messages.length === 0` 分不清
   * 「还在加载」和「这个会话本来就没消息」——空会话会一直转骨架屏。
   * 这里用"加载完成的会话 id"做判定，`newConversation` 也会同步写入。
   */
  messagesLoadedConversationId: string | null;
  loadOlderMessages: () => Promise<void>;
  streaming: boolean;
  /** Replay durable turn events after a known sequence, then reduce them in order. */
  replayTurnEvents: (conversationId: string, turnId: string, afterSeq?: number) => Promise<void>;
  /** 会话级进行中生成状态（key=conversationId）：切走保留、切回恢复；streaming/workflowHint 由此派生 */
  generating: Record<string, GenerationSnapshot>;
  waitingHumanInput: HumanInputInfo | null;
  streamError: string | null;
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
  /**
   * 打断当前生成并发送这句话。
   *
   * <p>顺序是「先让智能体停下来 → 已说出的文字以「…」定格 → 再把我们的话发出去」；
   * 返回 false 表示打断没能及时生效（消息没发出去，调用方应把输入内容还给用户）。</p>
   */
  interruptAndSend: (content: string, opts?: AgentSendOptions) => Promise<boolean>;
  /**
   * 回合中插入一条用户消息（steer）：不打断当前生成，在下一个模型步骤注入。
   * 回合已结束时自动改走普通发送（开新一轮）；失败返回 false，调用方负责把文字还给用户。
   */
  steerMessage: (content: string) => Promise<boolean>;

  // 输入
  refImageUrls: string[];
  setRefImageUrls: (v: string[]) => void;
  addRefImageUrls: (urls: string[]) => void;
  removeRefImageUrl: (index: number) => void;
  /**
   * 把一段文本塞进输入框（素材详情的「使用提示词」）。
   *
   * 输入框正文留在 `ChatComposer` 的局部 state 里，外部组件够不着它；这里只发一张
   * 「请求单」：seq 每次递增，输入框按 seq 消费一次就不再重复应用。刷新页面不会重放，
   * 因为 seq 只活在内存里。
   */
  composerInjection: ComposerInjection | null;
  injectComposerText: (text: string) => void;
  pendingAttachments: AgentAttachment[];
  setPendingAttachments: (attachments: AgentAttachment[]) => void;
  addPendingAttachments: (attachments: AgentAttachment[]) => void;
  removePendingAttachment: (url: string) => void;
  clearPendingAttachments: () => void;
  /**
   * 本轮 `@` 引用资料（分镜 / 对话 / 项目库文件 / 本地文件的结构化清单）。
   *
   * <p>与 `pendingAttachments` 分开：附件的去重键是 url，而引用资料的键是 `kind:id`
   * （分镜和对话根本没有 url）。正文里的 `@名称` 标记由输入框自己维护，这里只留机器可读部分。
   */
  pendingReferences: AgentReference[];
  setPendingReferences: (references: AgentReference[]) => void;
  addPendingReferences: (references: AgentReference[]) => void;
  removePendingReference: (kind: AgentReference['kind'], id: string) => void;
  clearPendingReferences: () => void;
  uploadRefImage: (file: File, purpose?: string, kind?: 'reference' | 'asset') => Promise<void>;
  uploadRefImages: (files: File[], purposes?: string[], kinds?: Array<'reference' | 'asset'>) => Promise<void>;
  addLocalUserMessage: (content: string) => void;
  /**
   * 追加一条只在本地存在的助手媒体消息（视频抽帧结果等）。
   * 后端没有对应记录，刷新后消失；会话内的渲染与悬浮操作与生成结果一致。
   */
  addLocalMediaMessage: (content: string) => void;

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
  sendMessage: (content: string, opts?: AgentSendOptions) => Promise<void>;
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
  // 结果确认动作（挂在产生结果的那条助手消息 metadata.confirmResult 上）：继续完善 / 满意完成
  refineAsset: (info: ConfirmResultInfo, messageId?: string) => void;
  dismissConfirm: (messageId?: string) => void;
  /** 收起某条消息上的结果确认动作；只影响动作，不影响结果图（图在消息附件/位条里）。 */
  clearMessageConfirmResult: (messageId: string) => void;
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
/** 兜底轮询的可见性监听：后台标签页不轮询，回到前台立即补一次对账。 */
let taskVisibilityHandler: (() => void) | null = null;
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
    messages: mergeInterruptedReplies(messages, interruptedRepliesOf(conversationId)),
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

/**
 * 把生成结果落到**产生它的那条助手消息**上（不再是全局单槽）。
 *
 * <p>背景（本次修复）：结果此前只写进全局 `confirmResult`，由 `ConfirmResultCard` 自己渲染图片，
 * 于是①同一会话里两个结果互相顶掉；②点「满意完成/继续完善」把全局槽清空，而图片只存在那张卡里，
 * 用户看到的就是"点一下图全没了"。
 *
 * <p>载体选择规则（保证**恰好一个**载体，不重复也不丢）：
 * <ul>
 *   <li>消息上已有媒体任务卡（批量图/视频，由预置位条渲染）→ 位条就是载体，
 *       不再补附件、也不弹旧确认卡；</li>
 *   <li>否则把结果 URL 补成 `sandboxAttachments`（与刷新后服务端回放的是同一字段，
 *       所以直播/刷新两条路径渲染一致），并挂上确认动作。</li>
 * </ul>
 *
 * <p>导出仅为聚焦测试可直接断言"载体选择"这一条不变量（见 `agentStore.resultCarrier.test.ts`）。
 */
export function attachResultToMessage(assistantId: string, info: ConfirmResultInfo | null) {
  if (!info || !assistantId) return;
  useAgentStore.setState((state) => {
    const message = state.messages.find((item) => item.id === assistantId);
    if (!message) return {};
    const carriedBySlotStrip = (message.metadata?.taskCards ?? [])
      .some((card) => isMediaTask(state.tasks[card.taskId]));
    const existing = message.metadata?.sandboxAttachments ?? [];
    const urls = (info.urls?.length ? info.urls : [info.url]).filter(Boolean);
    const known = new Set(existing.map((attachment) => attachment.url));
    const kind: AgentAttachmentKind = info.kind === 'video' ? 'video' : 'image';
    const added: AgentAttachment[] = carriedBySlotStrip || urls.length === 0
      ? []
      : urls.filter((url) => !known.has(url)).map((url, index) => ({
          url,
          name: `生成${kind === 'video' ? '视频' : '图片'}${urls.length > 1 ? ` ${index + 1}` : ''}`,
          mimeType: kind === 'video' ? 'video/mp4' : 'image/png',
          kind,
          ...(info.assetIds?.[index] ? { assetId: info.assetIds[index] } : {}),
        }));
    const sandboxAttachments = added.length > 0 ? [...existing, ...added] : existing;
    const nextMetadata: AgentMessageMetadata = { ...message.metadata };
    // 位条已承载时不再挂旧确认动作（"只在没有任何其他载体时兜底出现"）；
    // 否则结果图已进消息附件，确认动作只提供"继续完善/满意完成"，自身不再渲染图片，
    // 所以点它不会再让图片消失。
    if (carriedBySlotStrip) delete nextMetadata.confirmResult;
    else nextMetadata.confirmResult = info;
    if (added.length > 0) nextMetadata.sandboxAttachments = sandboxAttachments;
    return {
      messages: state.messages.map((item) => item.id === assistantId
        ? { ...item, metadata: nextMetadata }
        : item),
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
          };
        });
        if (useAgentStore.getState().activeConversationId === conversationId
            && task.kind !== 'long-tool') {
          attachResultToMessage(assistantId, {
            kind: task.kind === 'image-batch' ? 'image' : 'video',
            url: task.url || '',
            urls: task.resultUrls,
            assetId: task.assetId || task.assetIds?.[0],
            assetIds: task.assetIds,
            sceneCount: 0,
            actions: [{ id: 'refine', title: '继续完善' }, { id: 'done', title: '满意完成' }],
          });
        }
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

/**
 * 把「只活在 `generating` 里」的直播状态折进消息 metadata。
 *
 * <p>`reasoning` / `agentTrace` / `metrics` 会被 `setLive*` 顺手写进消息，`toolDrafts` 不会：
 * 它只存在于 `generating.toolDrafts`，而本轮流一结束 `generating` 就会被删除
 * （`sendMessage` / `submitHumanInput` 的 finally）。因此任何**非 `turn_completed` 的收尾**
 * ——HITL 暂停、`afterSeq` 重放暂停轮——都必须在那一刻把草稿折进消息，
 * 否则工具明细会在暂停瞬间从界面消失，只有刷新（服务端 metadata 回放）才重新出现。
 *
 * <p>优先级与服务端一致：服务端 payload metadata 优先，直播快照只补缺。
 */
function foldLiveTurnMetadata(options: {
  conversationId: string;
  serverMetadata?: AgentMessageMetadata;
  liveSandboxAttachments?: AgentMessageMetadata['sandboxAttachments'];
  generation?: GenerationSnapshot;
}): AgentMessageMetadata | undefined {
  const { conversationId, serverMetadata, liveSandboxAttachments } = options;
  const generation = options.generation ?? useAgentStore.getState().generating[conversationId];
  const liveToolDrafts = generation?.toolDrafts && Object.keys(generation.toolDrafts).length > 0
    ? generation.toolDrafts
    : undefined;
  const finalToolDrafts = serverMetadata?.toolDrafts ?? liveToolDrafts;
  const liveRenders = collectDraftRenderIntents(finalToolDrafts);
  const finalRenders = serverMetadata?.renders ?? (liveRenders.length > 0 ? liveRenders : undefined);
  const hasAnyLive = !!serverMetadata || !!generation?.reasoningDraft
    || !!generation?.traceDraft?.length || !!generation?.usageDraft
    || !!generation?.assistantMessageMetadata || !!finalToolDrafts || !!finalRenders;
  if (!hasAnyLive) return undefined;
  return {
    ...(generation?.assistantMessageMetadata ?? {}),
    ...(serverMetadata ?? {}),
    kind: serverMetadata?.kind ?? generation?.assistantMessageMetadata?.kind ?? 'assistant-reply',
    reasoning: serverMetadata?.reasoning ?? generation?.reasoningDraft,
    agentTrace: serverMetadata?.agentTrace ?? generation?.traceDraft,
    sandboxAttachments: serverMetadata?.sandboxAttachments ?? liveSandboxAttachments,
    metrics: serverMetadata?.metrics
      ?? generation?.usageDraft
      ?? generation?.assistantMessageMetadata?.metrics,
    ...(finalToolDrafts ? { toolDrafts: finalToolDrafts } : {}),
    ...(finalRenders ? { renders: finalRenders } : {}),
  };
}

/** 从消息元数据反解出 HITL 卡片信息（刷新后从 AgentMessage.metadata.hitl 恢复） */
export function hitlFromMetadata(metadata?: AgentMessageMetadata): HumanInputInfo | null {
  const h = metadata?.hitl;
  if (!h) return null;
  return {
    formToken: h.formToken ?? '',
    taskId: '',
    formContent: h.formContent ?? '',
    actions: h.actions ?? [],
    expirationTime: h.expirationTime ?? 0,
    messageId: h.messageId,
    inactive: h.inactive,
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

const SUBMITTED_HITL_KEY = 'moon.hitl.submitted.v1';

type SubmittedHitlReceipt = {
  conversationId: string;
  formToken: string;
  messageId?: string;
  expiresAt: number;
};

function readSubmittedHitlReceipts(): SubmittedHitlReceipt[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.sessionStorage.getItem(SUBMITTED_HITL_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((item): item is SubmittedHitlReceipt =>
      !!item
      && typeof item.conversationId === 'string'
      && typeof item.formToken === 'string'
      && typeof item.expiresAt === 'number'
      && item.expiresAt > Date.now());
  } catch {
    return [];
  }
}

function writeSubmittedHitlReceipts(receipts: SubmittedHitlReceipt[]) {
  if (typeof window === 'undefined') return;
  try {
    if (receipts.length === 0) window.sessionStorage.removeItem(SUBMITTED_HITL_KEY);
    else window.sessionStorage.setItem(SUBMITTED_HITL_KEY, JSON.stringify(receipts));
  } catch {
    // 服务端 metadata 仍是主要依据；存储不可用时只失去提交竞态保护。
  }
}

function rememberSubmittedHitlReceipt(
  conversationId: string,
  formToken: string | undefined,
  messageId?: string,
  expirationTime?: number,
) {
  if (!conversationId || !formToken) return;
  const expiresAt = expirationTime && expirationTime > 0
    ? (expirationTime > 1_000_000_000_000 ? expirationTime : expirationTime * 1000)
    : Date.now() + 30 * 60 * 1000;
  const next = readSubmittedHitlReceipts().filter((item) =>
    !(item.conversationId === conversationId
      && item.formToken === formToken
      && item.messageId === messageId));
  next.push({ conversationId, formToken, messageId, expiresAt });
  writeSubmittedHitlReceipts(next);
}

function hasSubmittedHitlReceipt(conversationId: string, formToken: string | undefined): boolean {
  if (!conversationId || !formToken) return false;
  return readSubmittedHitlReceipts().some((item) =>
    item.conversationId === conversationId && item.formToken === formToken);
}

function forgetSubmittedHitlReceipt(
  conversationId: string,
  formToken: string | undefined,
  messageId?: string,
) {
  if (!conversationId || !formToken) return;
  writeSubmittedHitlReceipts(readSubmittedHitlReceipts().filter((item) =>
    !(item.conversationId === conversationId
      && item.formToken === formToken
      && (!messageId || item.messageId === messageId))));
}

function hasSubmittedHitlCard(
  conversationId: string,
  formToken: string | undefined,
  messageId?: string,
): boolean {
  if (!conversationId || (!formToken && !messageId)) return false;
  return useAgentStore.getState().messages.some((message) => {
    const hitl = message.metadata?.hitl;
    if (hitl?.pending !== false) return false;
    return (!!formToken && hitl.formToken === formToken)
      || (!!messageId && (
        message.id === messageId
        || hitl.messageId === messageId
      ));
  });
}

// WS4：turn 事件续传的客户端支撑（续传游标、直播通道、分批让出主线程）在 stores/turnResume。
import {
  RESTORE_REPLAY_BATCH,
  createTurnResumeStream,
  forgetTurnLastSeq,
  rememberTurnLastSeq,
  yieldToEventLoop,
} from './turnResume';

/**
 * A page refresh drops the browser SSE connection, but the backend turn keeps
 * running and records every sequenced event. Rebuild the generating bubble from
 * the persisted turn, then **attach to the running turn's live stream**
 * (`GET /turns/{turnId}/stream?afterSeq=N`): history first, then live events,
 * until a terminal envelope arrives. Older backends without that endpoint fall
 * back to the previous polling replay.
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

  // 冷启动/刷新一律从 0 补齐历史：正在跑的助回复只存在于事件日志里（产品消息表里没有），
  // 少补一段就会渲染出"缺头"的回复，所以这里不按本地游标裁剪。补齐按批 + yield 防止卡 UI。
  let initialEvents: AgentTurnEventEnvelope[] = [];
  let afterSeq = 0;
  let bufferedEvents: AgentTurnEventEnvelope[] = [];
  try {
    const response = await agentApi.listTurnEvents(conversationId, active.turnId, 0);
    initialEvents = response.data.data ?? [];
  } catch {
    // Recovery can continue polling from sequence zero after a transient failure.
  }
  const resumeStartSeq = initialEvents
    .filter((event) => event.type === 'turn_started'
      && event.payload?.resume === true)
    .reduce((max, event) => Math.max(max, event.seq), 0);
  const lastInitialEvent = initialEvents.at(-1);
  const waitingForResumeStart = resumeStartSeq === 0
    && lastInitialEvent?.type === 'turn_paused';
  afterSeq = resumeStartSeq > 0
    ? resumeStartSeq - 1
    : waitingForResumeStart ? lastInitialEvent!.seq : 0;
  bufferedEvents = waitingForResumeStart
    ? []
    : initialEvents.filter((event) => event.seq > afterSeq);
  turnEventSequencer.reset(active.turnId);
  turnEventSequencer.seek(active.turnId, afterSeq);
  clearTurnGapRecovery(active.turnId);

  let terminal: AgentTurnEventEnvelope['type'] | null = null;
  let waitingUser = false;
  let pollCount = 0;

  // ── 直播续传通道 ─────────────────────────────────────────────
  // 先补历史（bufferedEvents），再持续接收直播事件直到终态；通道内部把连接失败暴露成
  // state()==='failed'，循环据此回退到既有轮询回放。订阅者断开不会中断 turn。
  // 直播起点 = 已经补过的历史末序号，不能直接用 afterSeq：后者是"要跳过多少历史"的游标
  // （例如 HITL 续跑时指向暂停点），从它开始会把已经补过的历史再发一遍，文本 delta 会重复。
  const liveCursor = bufferedEvents.length > 0
    ? bufferedEvents[bufferedEvents.length - 1].seq
    : afterSeq;
  const liveStream = createTurnResumeStream({
    conversationId,
    turnId: active.turnId,
    afterSeq: liveCursor,
    subscribe: streamTurnResume,
  });

  while (useAgentStore.getState().activeConversationId === conversationId) {
    let events: AgentTurnEventEnvelope[] = [];
    const liveState = liveStream.state();
    if (bufferedEvents.length > 0) {
      // 一次性重放按批进行：批间在循环末尾 yield，避免刷新后一个长任务卡住主线程。
      events = bufferedEvents.splice(0, RESTORE_REPLAY_BATCH);
    } else {
      // 先取队列，再看状态：直播可能在两次循环之间就把事件收完并结束（尤其恢复流程本身较慢时），
      // 若只在 streaming 时才 take，这批事件会被丢掉，恢复就永远停在这里。
      const batch = liveStream.take();
      if (batch.length > 0) {
        events = batch;
      } else if (liveState === 'streaming') {
        // 直播进行中且暂无新事件：等待推送，不再 1s 轮询。
        await liveStream.waitForEvents();
        continue;
      } else if (liveState === 'failed') {
        // 续传端点不可用：回退到既有轮询回放（行为与改造前一致）。
        try {
          const response = await agentApi.listTurnEvents(conversationId, active.turnId, afterSeq);
          events = response.data.data ?? [];
        } catch {
          await reconnectDelay(1500);
          continue;
        }
      } else {
        // 直播已正常结束、队列也空了但没拿到终态事件：用一次状态检查收口。
        let status: string | undefined;
        try {
          const latest = await agentApi.getRuntimeStatus(conversationId);
          status = latest.data.data.activeTurn?.status;
        } catch {
          // 状态查询失败：按已结束处理，避免在这里空转。
        }
        if (!status || status === 'waiting_user') {
          waitingUser = true;
          break;
        }
        // 服务端仍在跑但连接提前关闭：退回轮询继续追。
        liveStream.markFailed();
        continue;
      }
    }
    for (const envelope of events) {
      const effects = applyAgentTurnEnvelope(envelope, { conversationId, assistantId });
      afterSeq = Math.max(afterSeq, envelope.seq);
      // 记录已渲染到的序号：终态前若本页再次进入同一个 turn，可据此定位续传起点（不重复拉历史）。
      rememberTurnLastSeq(conversationId, active.turnId, afterSeq);
      if (effects.taskAccepted) {
        const accepted = effects.taskAccepted;
        if (accepted.taskKind === 'long-tool' || accepted.taskKind === 'image-batch') {
          attachTaskCard(conversationId, assistantId, {
            taskId: accepted.taskId,
            title: accepted.taskKind === 'image-batch' ? '批量生成图片' : '生成剧情稿',
            kind: accepted.taskKind,
          });
          void agentApi.getTask(accepted.taskId)
            .then((response) => {
              useAgentStore.getState().upsertTask(response.data.data);
              attachTaskCard(conversationId, assistantId, response.data.data);
            })
            .catch(() => undefined);
        } else {
          beginUnifiedVideoPolling(
            accepted.taskId, conversationId, assistantId, accepted.taskKind);
        }
      }
      if (effects.confirmResult
          && useAgentStore.getState().activeConversationId === conversationId) {
        attachResultToMessage(assistantId, effects.confirmResult);
      }
      if (effects.canvasSessionRequired) {
        const session = effects.canvasSessionRequired;
        useAgentStore.setState((state) => ({
          canvasSession: session,
          canvasSessionStatus: '',
          canvasSessionSelection: { createNew: false },
          generating: Object.fromEntries(
            Object.entries(state.generating).filter(([key]) => key !== conversationId),
          ),
          messages: state.messages.filter((message) => message.id !== assistantId),
          workflowHint: '',
        }));
        if (session.status === 'pending_open' && session.canvasId) {
          useAgentStore.getState().beginCanvasSessionOpen(session.canvasId, false);
        }
        terminal = 'turn_paused';
      }
      if (effects.canvasModeRequired) {
        const canvas = effects.canvasModeRequired;
        const formToken = asString(canvas.formToken);
        const canvasConversationId = asString(canvas.conversationId);
        const projectId = asString(canvas.projectId);
        if (canvasConversationId && projectId && formToken) {
          useAgentStore.getState().startCanvasBinding({
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
          useAgentStore.setState((state) => ({
            generating: Object.fromEntries(
              Object.entries(state.generating).filter(([key]) => key !== conversationId),
            ),
            messages: state.messages.filter((message) => message.id !== assistantId),
            workflowHint: '',
          }));
        }
        terminal = 'turn_paused';
      }
      if (effects.completed) terminal = 'turn_completed';
      else if (effects.failed) terminal = 'turn_failed';
      else if (effects.paused && !terminal) terminal = 'turn_paused';
    }
    if (terminal) break;
    pollCount += 1;
    if (pollCount % 5 === 0) {
      try {
        const latest = await agentApi.getRuntimeStatus(conversationId);
        const status = latest.data.data.activeTurn?.status;
        if (!status || status === 'waiting_user') {
          waitingUser = true;
          break;
        }
      } catch {
        // Keep replaying; a transient status failure should not abort recovery.
      }
    }
    if (bufferedEvents.length > 0 || liveStream.state() === 'streaming') {
      // 还有待补的历史或直播仍在进行：先让出主线程，避免一次重放变成超长任务。
      await yieldToEventLoop();
    } else {
      // 直播不可用（回退轮询）时才退避；直播进行中由 waitForEvents 唤醒，不做定时轮询。
      await reconnectDelay(1000);
    }
  }

  liveStream.stop();
  if (terminal || waitingUser) {
    // 终态/暂停：该 turn 不会再续传，清掉续传游标。
    forgetTurnLastSeq(conversationId, active.turnId);
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
  if (terminal === 'turn_paused' || waitingUser) {
    await useAgentStore.getState().recoverPendingCheckpoint(conversationId);
  }
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
    // ToolResultState 的真实取值是 SUCCESS / ERROR / INTERRUPTED / DENIED / RUNNING；
    // 只判 FAIL/DENIED 会把 ERROR 显示成「已完成」。后端落库的工具草稿用同一口径
    // （AgentTraceAccumulator.failedToolState），保证直播与刷新渲染一致。
    return /FAIL|ERROR|DENIED|INTERRUPT/.test(state) ? 'node_failed' : 'node_completed';
  }
  return 'node_running';
}

function toolTitle(eventType: string, toolName: string): string {
  if (eventType === 'TOOL_CALL_START') return `调用工具：${toolName}`;
  if (eventType === 'TOOL_RESULT_START') return `工具执行：${toolName}`;
  if (eventType === 'TOOL_RESULT_END') return `工具完成：${toolName}`;
  return `工具流式输出：${toolName}`;
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

/**
 * 只有真正产出媒体结果的工具，才允许把工具结果里的 `url` 解析成「继续完善/满意完成」。
 *
 * <p>真实事故（2026-10-19）：`request_human_input` 的返回里带 `url`（表单/方案里的参考图），
 * 旧逻辑对所有 TOOL_RESULT_END 一视同仁地跑 {@link confirmResultFromText}，于是 HITL
 * 「请求用户补充信息」卡片下面凭空长出「继续完善 / 满意完成」两个按钮——它们既不属于这张卡，
 * 也没有任何结果可完善。这里用白名单从源头切断。
 */
const MEDIA_RESULT_TOOLS = new Set([
  'generate_image',
  'generate_image_batch',
  'refineImage',
  'generate_video',
  'generate_storyboard_image',
  'generate_storyboard_images',
  'generate_storyboard_video',
  'generate_storyboard_videos',
  'produce_video_journey',
  'merge_storyboard_videos',
]);

export function confirmResultFromToolResult(
  toolName: string,
  value: string,
): ConfirmResultInfo | null {
  if (!MEDIA_RESULT_TOOLS.has(toolName)) return null;
  return confirmResultFromText(value);
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
  // 画布增量补丁：只在 payload 确实带对象/布尔时透传（不解析内部结构），
  // 未携带时保留 previous 上的值，避免后续事件把已累积的补丁抹掉。
  const canvasPatch = asRecord(payload.canvasPatch) ?? previous?.canvasPatch;
  const canvasPatchOverflow = typeof payload.canvasPatchOverflow === 'boolean'
    ? payload.canvasPatchOverflow
    : previous?.canvasPatchOverflow;
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
    ...(canvasPatch ? { canvasPatch } : {}),
    ...(canvasPatchOverflow === undefined ? {} : { canvasPatchOverflow }),
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
  if (envelope.type === 'USER_MESSAGE') {
    const messageId = asString(payload.messageId);
    const content = asString(payload.content);
    if (!messageId || !content) return;
    insertSteerMessage(context.conversationId, context.assistantId, {
      id: messageId,
      conversationId: context.conversationId,
      role: 'user',
      content,
      metadata: { steer: true, turnId: envelope.turnId },
      createdAt: asString(payload.createdAt) || new Date().toISOString(),
    });
    return;
  }

  if (envelope.type === 'SUBAGENT_STARTED' || envelope.type === 'SUBAGENT_COMPLETED') {
    const toolCallId = asString(payload.toolCallId);
    const subagentId = asString(payload.subagentId);
    if (!toolCallId || !subagentId) return;
    const status = asString(payload.status) || 'RUNNING';
    const role = asString(payload.role);
    const output = asString(payload.output);
    const error = asString(payload.error);
    useAgentStore.setState((state) => ({
      subagentProgress: {
        ...state.subagentProgress,
        [toolCallId]: {
          ...(state.subagentProgress[toolCallId] ?? {}),
          [subagentId]: { status, role, output, error },
        },
      },
    }));
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
      const hitlVideoPlan = { ...state.hitlVideoPlan };
      delete hitlVideoPlan[context.conversationId];
      return {
        generating,
        ...(state.activeConversationId === context.conversationId
          ? {
              workflowHint: 'MoonAgent 正在处理',
              streaming: true,
              waitingHumanInput: null,
              waitingVideoPlan: null,
              hitlVideoPlan,
            }
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
    /**
     * 空/空白 formToken 的 HITL 卡片不可交互：
     * - 提交必然失败（后端一次性 checkpoint 需要 token）；
     * - 空 token 会让 `hasSubmittedHitlReceipt` 的幂等判定失效（`!formToken` 直接 return false），
     *   于是重放 `turn_paused` 时又冒出一张能点的卡片。
     * 因此这类卡片只做只读回显，且不重新装配 `waitingHumanInput`。
     * 有 token 的卡片行为完全不变（`alreadySubmitted` 分支保持原样）。
     */
    const cardInteractive = !!formToken.trim();
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
      writeTools: source.writeTools as { nodeId: string; role: string; tools: string[] }[] | undefined,
      message: asString(source.message),
      duration: asNumber(source.duration, 8),
      picUrl: asString(source.picUrl),
      inactive: !cardInteractive,
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
        expirationTime: info.expirationTime,
        models: info.models,
        recommended: info.recommended,
        reasons: info.reasons,
      } : null;
      // 本轮只活在 generating 里的工具明细必须跟着卡片落进消息：暂停即收尾，
      // 之后 generating 会被删除，否则工具调用会在暂停瞬间被吞掉（刷新才回来）。
      const pausedLiveMetadata = foldLiveTurnMetadata({
        conversationId: context.conversationId,
        serverMetadata: metadata as unknown as AgentMessageMetadata,
        generation: state.generating[context.conversationId],
      });
      const messages = state.messages.map((message) => message.id === context.assistantId
        ? {
            ...message,
            id: messageId || message.id,
            content: message.content.trim()
              ? message.content
              : assistantContent,
            metadata: {
              ...(message.metadata ?? {}),
              ...(pausedLiveMetadata ?? {}),
              kind: videoPlan ? 'video-plan' as const : 'hitl' as const,
              hitl: {
                formContent: info.formContent || videoInfo?.message || '',
                actions,
                pending: true,
                messageId,
                formToken,
                expirationTime: info.expirationTime,
                ...(cardInteractive ? {} : { inactive: true }),
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
      const seen = new Set<string>();
      const dedupedMessages = messages.filter((message) => {
        if (seen.has(message.id)) return false;
        seen.add(message.id);
        return true;
      });
      const alreadySubmitted = state.messages.some((message) =>
        message.id === messageId && message.metadata?.hitl?.pending === false);
      // 不可交互的卡片（空 formToken）不重新装配待确认状态，避免重放复活点击入口。
      const armHumanInput = cardInteractive && !videoPlan && !alreadySubmitted;
      return {
        workflowHint: '',
        waitingHumanInput: armHumanInput ? info : null,
        waitingVideoPlan: alreadySubmitted ? null : videoInfo,
        hitlVideoPlan: videoInfo
          ? { ...state.hitlVideoPlan, [context.conversationId]: videoInfo }
          : state.hitlVideoPlan,
        messages: state.activeConversationId === context.conversationId ? dedupedMessages : state.messages,
      };
    });
    return;
  }

  if (envelope.type === 'turn_completed') {
    effects.completed = true;
    clearTurnGapRecovery(envelope.turnId);
    const content = asString(payload.content);
    const metadata = turnMetadata(payload);
    const generation = useAgentStore.getState().generating[context.conversationId];
    const liveSandboxAttachments = useAgentStore.getState().messages
      .find((message) => message.id === context.assistantId)?.metadata?.sandboxAttachments;
    /**
     * 终态 metadata 必须保留直播阶段才有的状态：
     * - `reasoning` / `agentTrace` / `metrics` 已有回退；
     * - `toolDrafts` 是本轮工具输出的回放载体（否则刷新后工具结果彻底消失）；
     * - `renders` 汇总渲染意图，供重放按 kind 分派。
     * 服务端已在 metadata 里给了对应字段时优先用服务端的，前端只补缺。
     */
    const finalMetadata = foldLiveTurnMetadata({
      conversationId: context.conversationId,
      serverMetadata: metadata,
      liveSandboxAttachments,
      generation,
    });
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
        const hitlVideoPlan = { ...state.hitlVideoPlan };
        delete hitlVideoPlan[context.conversationId];
        return {
          generating,
          ...(state.activeConversationId === context.conversationId
            ? {
                waitingHumanInput: null,
                waitingVideoPlan: null,
                hitlVideoPlan,
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
    const code = asString(payload.code);
    const message = asString(payload.message, 'MoonAgent 执行失败');
    /**
     * 用户主动停止（40904「生成已停止」）不是错误：
     * - 不能弹错误条（那是用户自己要停的）；
     * - 已流出的正文要就地定格成「…」，而不是被清空或被终态覆盖。
     * 其它失败路径行为不变（照旧报错）。
     */
    const userStopped = code === '40904' || interruptedTurns.has(envelope.turnId);
    if (userStopped) {
      interruptedTurns.delete(envelope.turnId);
      freezeInterruptedReply(context.conversationId, context.assistantId);
      return;
    }
    useAgentStore.setState((state) => ({
      ...(state.activeConversationId === context.conversationId ? { streamError: message } : {}),
    }));
    return;
  }

  const native = asRecord(payload);
  const eventType = asString(native?.eventType) || envelope.type;
  const data = asRecord(native?.data) ?? native ?? {};
  if (!eventType) return;

  if (eventType === 'TEXT_BLOCK_DELTA') {
    const delta = asString(data.delta);
    if (!delta) return;
    // 已被用户打断的 turn：气泡已经以「…」定格，后续在途 delta 不再续写
    if (interruptedTurns.has(envelope.turnId)) return;
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

  if (eventType === 'THINKING_BLOCK_START') {
    const previous = useAgentStore.getState().generating[context.conversationId]?.reasoningDraft;
    const details = previous?.details
      ? (previous.details.endsWith('\n\n') ? previous.details : `${previous.details}\n\n`)
      : '';
    setLiveReasoning(context, {
      summary: previous?.summary ?? '',
      details,
      activity: previous?.activity,
      durationMs: previous?.durationMs,
      source: 'agentscope',
    });
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

    useAgentStore.setState((state) => {
      const generation = state.generating[context.conversationId] ?? { hint: '' };
      const drafts: Record<string, AgentToolDraft> = { ...(generation.toolDrafts ?? {}) };
      const draft: AgentToolDraft = drafts[toolCallId]
        ?? { name: toolName, text: '', input: '', status, seq: envelope.seq, startedAt: Date.now() };
      if (eventType === 'TOOL_CALL_DELTA') draft.input += asString(data.delta);
      if (eventType === 'TOOL_RESULT_TEXT_DELTA' || eventType === 'TOOL_RESULT_DATA_DELTA') {
        const delta = typeof data.delta === 'string' ? data.delta : data.delta ? JSON.stringify(data.delta) : '';
        draft.text += delta;
      }
      draft.status = status;
      if (draft.seq === undefined) draft.seq = envelope.seq;
      // `render` 块（服务端新增）随工具结果到达；缺省时保持 undefined，渲染层回落纯文本。
      const renderIntent = renderIntentFromEventData(data);
      if (renderIntent) draft.render = renderIntent;
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
      // 只允许媒体生成工具产生「继续完善/满意完成」；HITL 等工具结果里的 url 不能当生成结果。
      if (!effects.confirmResult) {
        effects.confirmResult = confirmResultFromToolResult(
          toolName || draft?.name || '',
          draft?.text ?? '',
        );
      }
    }
    return;
  }

  if (eventType === 'SUBAGENT_EXPOSED') {
    // 此前这里只写 AgentScopeTimeline 的内存时间线，而那个组件的消费者从未被挂载（已删除）。
    // 当前没有 UI 消费子智能体暴露事件，显式忽略，避免落进下方通用分支被误处理。
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
  }
}

/** Reduce a streamed envelope into store state; stale/gapped events are handled per turn. */
export function applyAgentTurnEnvelope(
  envelope: AgentTurnEventEnvelope,
  context: TurnReductionContext,
): AgentTurnEnvelopeEffects {
  const effects = emptyTurnEffects();
  // 先归并再判定缺口：本次到达的事件补齐了缺口就回收补偿，仍缺则排一次补偿拉取。
  const ready = turnEventSequencer.accept(envelope);
  trackTurnGap(envelope.turnId, context.conversationId, context.assistantId);
  for (const item of ready) {
    applyOneEnvelope(item, context, effects);
  }
  if (effects.completed || effects.failed || effects.paused) {
    clearTurnGapRecovery(envelope.turnId);
  }
  return effects;
}

export const useAgentStore = create<AgentState>((set, get) => ({
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
    let paused = false;
    for (const envelope of response.data.data ?? []) {
      const effects = applyAgentTurnEnvelope(envelope, { conversationId, assistantId });
      if (effects.taskAccepted) {
        const accepted = effects.taskAccepted;
        if (accepted.taskKind === 'long-tool' || accepted.taskKind === 'image-batch') {
          attachTaskCard(conversationId, assistantId, {
            taskId: accepted.taskId,
            title: accepted.taskKind === 'image-batch' ? '批量生成图片' : '生成剧情稿',
            kind: accepted.taskKind,
          });
          void agentApi.getTask(accepted.taskId)
            .then((taskResponse) => {
              get().upsertTask(taskResponse.data.data);
              attachTaskCard(conversationId, assistantId, taskResponse.data.data);
            })
            .catch(() => undefined);
        } else {
          beginUnifiedVideoPolling(
            accepted.taskId, conversationId, assistantId, accepted.taskKind);
        }
      }
      if (effects.confirmResult && get().activeConversationId === conversationId) {
        attachResultToMessage(assistantId, effects.confirmResult);
      }
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
      if (effects.failed) {
        useAgentStore.setState((state) => {
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
      if (effects.paused) paused = true;
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
      if (effects.canvasModeRequired) {
        const canvas = effects.canvasModeRequired;
        const canvasConversationId = asString(canvas.conversationId);
        const projectId = asString(canvas.projectId);
        const formToken = asString(canvas.formToken);
        if (canvasConversationId && projectId && formToken) {
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
          set((state) => ({
            generating: Object.fromEntries(
              Object.entries(state.generating).filter(([key]) => key !== conversationId),
            ),
            messages: state.messages.filter((message) => message.id !== assistantId),
            workflowHint: '',
          }));
        }
      }
    }
    if (paused) {
      await get().recoverPendingCheckpoint(conversationId);
    }
  },
  canvasEditContext: null,
  subagentProgress: {},
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
  pendingApprovalMode: defaultApprovalMode(),
  generationPreferences: loadGenerationPreferences(),
  updateGenerationPreferences: (updater) => {
    set((state) => {
      const next = updater(state.generationPreferences);
      saveGenerationPreferences(next);
      return { generationPreferences: next };
    });
  },
  loadingConversations: false,
  loadConversations: async () => {
    const projectId = useProjectStore.getState().currentProject?.id;
    if (!projectId) {
      conversationListEpoch += 1;
      return;
    }
    const loadEpoch = ++conversationListEpoch;
    set({ loadingConversations: true });
    let res;
    try {
      res = await agentApi.listConversations(projectId);
    } catch (error) {
      // 失败也要收起骨架屏，否则侧栏会一直转"加载中"
      if (loadEpoch === conversationListEpoch) set({ loadingConversations: false });
      throw error;
    }
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
      pendingApprovalMode: defaultApprovalMode(),
      canvasEditContext: null,
      canvasBinding: null,
      canvasBindingStatus: '',
      canvasExitPending: false,
      messages: [],
      messagesLoadedConversationId: null,
      streaming: false,
      waitingHumanInput: null,
      
      waitingVideoPlan: null,
      streamError: null,
      pendingAssistantId: null,
      pendingPicUrls: [],
      refImageUrls: [],
      pendingAttachments: [],
      pendingReferences: [],
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
    try {
      const res = await agentApi.updateConversation(id, { title });
      const updated = res.data.data;
      set((s) => ({
        conversations: s.conversations.map((c) => (c.id === id ? updated : c)),
      }));
      notify({ title: '已重命名', description: `「${title}」` });
    } catch (error) {
      notify({ title: '重命名失败', description: errorText(error, '请稍后重试'), variant: 'error' });
      throw error;
    }
  },

  setConversationStatus: async (id, status) => {
    try {
      const res = await agentApi.updateConversation(id, { status });
      const updated = res.data.data;
      set((s) => ({
        conversations: s.conversations.map((c) => (c.id === id ? updated : c)),
      }));
      notify({
        title: status === 'archived' ? '已归档' : '已恢复',
        description: `「${updated.title}」`,
      });
    } catch (error) {
      notify({
        title: status === 'archived' ? '归档失败' : '恢复失败',
        description: errorText(error, '请稍后重试'),
        variant: 'error',
      });
      throw error;
    }
  },

  deleteConversation: async (id, options) => {
    const title = get().conversations.find((c) => c.id === id)?.title ?? '';
    try {
      await agentApi.deleteConversation(id);
    } catch (error) {
      notify({ title: '删除失败', description: errorText(error, '请稍后重试'), variant: 'error' });
      throw error;
    }
    if (!options?.silent) notify({ title: '已删除对话', description: title ? `「${title}」` : undefined });
    cancelConversationTitleRefresh(id);
    useSendQueueStore.getState().clear(id);
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
        pendingApprovalMode: isActive ? defaultApprovalMode() : s.pendingApprovalMode,
        generating: g,
        streaming: generationInProgress(gview),
        workflowHint: generationHint(gview),
        messages: isActive ? [] : s.messages,
        waitingHumanInput: isActive ? null : s.waitingHumanInput, 
        waitingVideoPlan: isActive ? null : s.waitingVideoPlan,
        hitlVideoPlan: h,
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
       waitingVideoPlan: null, hitlVideoPlan: h, streamError: null, pendingAssistantId: null, pendingPicUrl: null }; });
  },

  /**
   * 「/清空上下文」：后端真删除该会话的全部上下文（消息、轮次与直播事件、待确认卡片、
   * 画布会话与运行、文档索引…），前端同步把会话态退回「刚建出来的新对话」。
   *
   * <p>与 {@link clearMessages} 的区别是「真删 + 彻底」：后端删的是数据库行而不是标记，
   * 标题也回到「新对话」；因此前端要把所有只存活在内存里的会话态一起清掉
   * （进行中的生成快照、HITL 卡片、待发注释、附件、画布上下文），否则界面上仍会
   * 残留上一次对话的影子。
   *
   * @returns 真删行数与重置后的标题；无会话或在生成中时返回 null（调用方给提示）
   */
  clearContext: async () => {
    const id = get().activeConversationId;
    if (!id) return null;
    // 生成中禁止清空：后端会因会话锁返回 40901，前端直接前置拦截并保留输入内容
    if (get().streaming || get().waitingHumanInput || get().waitingVideoPlan) return null;

    const res = await agentApi.clearContext(id);
    const result = res.data.data ?? null;

    cancelConversationTitleRefresh(id);
    useChatAnnotationStore.getState().consumeConversation(id);
    // 画布编辑模式是会话级上下文：清空后退回普通对话
    clearCanvasEditContext();

    const generating = { ...get().generating };
    delete generating[id];

    set((s) => {
      const hitlVideoPlan = { ...s.hitlVideoPlan };
      delete hitlVideoPlan[id];
      return {
        messages: [],
        messageNextCursor: null,
        messageHasMore: false,
        generating,
        streaming: false,
        workflowHint: '',
        waitingHumanInput: null,
        waitingVideoPlan: null,
        hitlVideoPlan,
        streamError: null,
        pendingAssistantId: null,
        pendingPicUrls: [],
        refImageUrls: [],
        pendingAttachments: [],
        pendingReferences: [],
        pickedReferenceVideos: [],
        pickedReferenceAudios: [],
        pickedAssetIds: [],
        taskFocusId: null,
        canvasEditContext: null,
        canvasBinding: null,
        canvasBindingStatus: '',
        canvasExitPending: false,
        // 标题在后端已重置，列表与当前会话同步显示「新对话」
        conversations: result?.title
          ? s.conversations.map((c) => (c.id === id ? { ...c, title: result.title } : c))
          : s.conversations,
      };
    });
    return result;
  },

  resetConversationForProject: (deepLinkConversationId) => {
    // 深链（项目库「使用位置 · 对话」/ 产出对话 / 通知 / 观测页）在跳转当下已经由
    // selectConversation 切换到目标会话：这里再清一次有两个副作用——
    // 1) activeConversationId 变空 → 紧随其后的 loadConversations 自动选中最近会话，
    //    用户点的是 A 对话，落到的却是同名的 B 对话；
    // 2) 抹掉目标会话在途生成的 streaming/workflowHint。
    if (deepLinkConversationId) return;
    set({
      activeConversationId: null,
      messages: [],
      generating: {},
      replyUnread: {},
      streaming: false,
      workflowHint: '',
    });
  },

  selectConversation: async (id) => {
    const loadEpoch = ++conversationLoadEpoch;
    // 切会话会清空本地消息：先把已定格的截断回复取出来，等服务端列表回来再合并回去
    const localInterrupted = id === get().activeConversationId ? interruptedRepliesOf(id) : [];
    // HITL 按会话隔离（getPendingCheckpoint/hitlVideoPlan 各自恢复），允许切换会话
    set((s) => {
      const g = s.generating[id];
      // 画布绑定/退出中都属于「会话级」状态：留着上一个会话的会让新会话的输入框一直禁用
      // （busy 命中 canvasBinding / canvasExitPending，canSteer 又不成立 → textarea disabled）。
      const bindingForThisConversation = !!s.canvasBinding?.conversationId
        && s.canvasBinding.conversationId === id;
      return {
        activeConversationId: id,
        pendingApprovalMode: s.conversations.find((conversation) => conversation.id === id)
          ?.approvalMode ?? defaultApprovalMode(),
        messages: [],
        streaming: generationInProgress(g),
        workflowHint: generationHint(g),
        waitingHumanInput: null,
        canvasExitPending: false,
        canvasBinding: bindingForThisConversation ? s.canvasBinding : null,
        canvasBindingStatus: bindingForThisConversation ? s.canvasBindingStatus : '',
      
        waitingVideoPlan: s.hitlVideoPlan[id] ?? null,
        streamError: null,
        pendingAssistantId: null,
        pendingPicUrls: [], refImageUrls: [], pendingAttachments: [], pendingReferences: [],
        pickedReferenceVideos: [], pickedReferenceAudios: [], pickedAssetIds: [],
        taskUnread: { ...s.taskUnread, [id]: 0 },
        replyUnread: { ...s.replyUnread, [id]: false },
        taskFocusId: null,
        // 进入加载态：ChatPage 用骨架屏顶住首屏，等消息落到 store 再收起
        messagesLoadedConversationId: null,
      };
    });
    void get().loadTasks(id);
    perfMark('switch:start');
    const page = await loadMessagePage(id);
    if (loadEpoch !== conversationLoadEpoch || get().activeConversationId !== id) return;
    perfMark('switch:messages-loaded');
    // 画布态只影响画布 UI，绝不能拖住消息首屏：给 3 秒预算，超时就先渲染消息，
    // 结果晚到也没关系（restoreCanvasMode 自己会在回来时把 canvasEditContext 补上）。
    // 之前这里是裸 await：网关到后端是 900s 响应超时，后端一卡，整个会话就一直空白。
    await Promise.race([
      get().restoreCanvasMode(id),
      new Promise<void>((resolve) => { window.setTimeout(resolve, 3000); }),
    ]);
    if (loadEpoch !== conversationLoadEpoch || get().activeConversationId !== id) return;
    perfMark('switch:canvas-settled');
    const g = get().generating[id];
    const base = await refreshLibraryMediaMessages(
      page.records,
      (mediaId) => resolveLibraryMedia(id, mediaId),
    );
    perfMark('switch:media-refreshed');
    const savedAttachments = readPendingAttachments(id);
    // 只恢复「用户加了但还没发出去」的附件：已发送的素材不再回到输入框
    // （否则用户会以为本轮又带了一遍旧素材），它们留在会话消息里可见。
    const restoredAttachments = savedAttachments.length
      ? await refreshLibraryMediaAttachments(
          savedAttachments, (mediaId) => resolveLibraryMedia(id, mediaId))
      : [];
    if (loadEpoch !== conversationLoadEpoch || get().activeConversationId !== id) return;
    perfMark('switch:render');
    set({
      messages: g?.assistantId
        ? [...mergeInterruptedReplies(base, localInterrupted), { id: g.assistantId, conversationId: id, role: 'assistant' as const, content: g.assistantContent ?? '', createdAt: new Date().toISOString(), metadata: buildGeneratingMetadata(g) }]
        : mergeInterruptedReplies(base, localInterrupted),
      // 首屏骨架屏据此收起：空会话也能正确结束加载态
      messagesLoadedConversationId: id,
      messageNextCursor: page.nextCursor,
      messageHasMore: page.hasMore,
      pendingAttachments: restoredAttachments,
      ...pendingMediaFromAttachments(restoredAttachments),
      pendingReferences: readPendingReferences(id),
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
      if (cp?.formToken && (
        hasSubmittedHitlReceipt(id, cp.formToken)
        || hasSubmittedHitlCard(id, cp.formToken, cp.messageId)
      )) {
        set({ waitingHumanInput: null });
        return;
      }
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
            expirationTime: cp.expirationTime,
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
  messagesLoadedConversationId: null,
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
    // 编辑重发：引用以「编辑后的正文 + 这条消息原有的引用目录」为准。
    // 不取 store 快照（那是给人写新消息用的），也不留空（空 = 明确告诉后端「改后不再引用」，
    // 否则后端会从 previousInput 恢复旧引用，用户删掉的标记会复活）。
    const edited = get().messages.find((message) => message.id === targetMessageId);
    const previousReferences = edited?.metadata?.messageInput?.references ?? [];
    await get().sendMessage(content, {
      editMessageId: targetMessageId,
      references: activeReferences(content, previousReferences),
    });
  },
  /**
   * 回合中插入一条用户消息（steer）。
   *
   * <p>与「停止并发送」的区别：不打断当前回合，消息落在下一条模型请求里；
   * 已流出的正文、正在跑的工具都不受影响。</p>
   *
   * <p>回合已结束（服务端返回 not_running）时等本地流收尾，再按普通发送开新一轮；
   * 这样用户只按一次发送，不会丢消息，也不会撞会话锁。</p>
   *
   * @return true=已插入或已改走普通发送；false=既没插入也没发出，调用方应回填输入框
   */
  steerMessage: async (content) => {
    const trimmed = content.trim();
    const conversationId = get().activeConversationId;
    if (!conversationId || !trimmed) return false;
    const generation = get().generating[conversationId];
    const turnId = generation?.turnId;
    if (!get().streaming || !turnId
      || get().waitingHumanInput || get().waitingVideoPlan) {
      // 没有可插入的回合：不打断任何东西，直接按普通发送处理
      if (get().streaming) return false;
      void get().sendMessage(content);
      return true;
    }
    const clientMessageId = `steer-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    try {
      const res = await agentApi.steerTurn(conversationId, turnId, trimmed, clientMessageId);
      const result = res.data.data;
      if (result?.status === 'steered') {
        if (result.messageId) {
          // 乐观气泡：服务端 USER_MESSAGE 事件按同一 messageId 去重，谁先到都只出现一次
          insertSteerMessage(conversationId, generation?.assistantId, {
            id: result.messageId,
            conversationId,
            role: 'user',
            content: trimmed,
            metadata: { steer: true, turnId, clientMessageId },
            createdAt: new Date().toISOString(),
          });
        }
        return true;
      }
      // 回合刚结束：等本地终态清掉 generating，再走普通发送开新一轮
      const settled = await waitForInterruptSettled(conversationId, 4000);
      if (!settled || get().streaming) return false;
      void get().sendMessage(content);
      return true;
    } catch {
      set({ streamError: '消息插入失败，请重试' });
      return false;
    }
  },
  /**
   * 打断当前生成并发送这句话。
   *
   * <p>顺序刻意做成「先停、再定格、最后才发」：</p>
   * <ol>
   *   <li>立刻把已经流出的正文定格成「…」（用户马上看到"停住了"，且这段字不会被丢掉）；</li>
   *   <li>请求后端协作式取消，并**等这一轮真正走到终态**——
   *       抢跑提交新回合会撞上会话锁（40901「当前对话正在处理中」），
   *       而新回合的 clearInterrupt 也可能在旧回合观察到打断之前把信号清掉；</li>
   *   <li>旧轮落定后再把用户这句话作为新一轮发出去。</li>
   * </ol>
   *
   * @return true=新消息已发出；false=打断没能在超时内生效（消息未发出，调用方应回填输入框）
   */
  interruptAndSend: async (content, opts?: AgentSendOptions) => {
    if (!content.trim()) return false;
    const conversationId = get().activeConversationId;
    const running = !!conversationId && get().streaming
      && !get().agentVideoTask && !get().waitingHumanInput && !get().waitingVideoPlan;
    if (!conversationId || !running) {
      // 没有在跑的回合：按普通发送处理（消息是否真的发出由 store 自身的错误态兜底）
      void get().sendMessage(content, opts);
      return true;
    }
    // 同一条"先停后发"的链路只允许一个在跑：重复点击不再排第二条
    if (pendingInterruptSends.has(conversationId)) return false;
    pendingInterruptSends.add(conversationId);
    try {
      const generation = get().generating[conversationId];
      const turnId = generation?.turnId;
      const assistantId = generation?.assistantId ?? get().pendingAssistantId;
      // 旧流被我们主动打断：不要弹「回复失败」这类错误条
      manualStop = true;
      if (turnId) interruptedTurns.add(turnId);
      // ① 定格：文字留在气泡里，末尾「…」
      freezeInterruptedReply(conversationId, assistantId);
      set((s) => {
        const current = s.generating[conversationId];
        if (!current) return {};
        return {
          generating: {
            ...s.generating,
            [conversationId]: { ...current, hint: '正在停止当前生成…' },
          },
          ...(s.activeConversationId === conversationId
            ? { workflowHint: '正在停止当前生成…' }
            : {}),
        };
      });
      // ② 让后端把这一轮停掉（turnId 还没下发时退回会话级打断入口）
      if (turnId) {
        await agentApi.interruptTurn(conversationId, turnId).catch(() => undefined);
      } else {
        await agentApi.interruptConversation(conversationId).catch(() => undefined);
      }
      const settled = await waitForInterruptSettled(conversationId, INTERRUPT_SETTLE_TIMEOUT_MS);
      if (!settled) {
        // 打断没能在超时内生效：不发新消息（否则必然撞会话锁），把内容还给用户。
        // 这里不断流：流还在就让它继续，用户可以用「停止生成」强制收尾。
        set({ streamError: '停止当前生成超时，请等它结束后再发送' });
        return false;
      }
      // 终态事件可能早于服务端 finally 释放会话锁，留一点余量再提交新回合
      await new Promise((resolve) => window.setTimeout(resolve, INTERRUPT_RELEASE_GRACE_MS));
      manualStop = false;
      // ③ 现在发送我们的话：不 await 整轮，否则这一轮期间再点「停止并发送」会被上面的互斥挡掉
      void get().sendMessage(content, opts);
      return true;
    } finally {
      pendingInterruptSends.delete(conversationId);
    }
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
    /**
     * 兜底对账：只拉「在跑」的任务。
     *
     * <p>原来每 5 秒拉一次全量（`status=all&size=100`）：本机 39 条 / **395 KB**，
     * 实测 40 秒里 2.77 MB、每次 39 次合并。这是 /chat 一路常驻的隐性开销，
     * 叠在切会话的渲染峰值上就是"莫名卡"。`status=active` 只有 1 条 / **4.6 KB**。
     *
     * <p>不在 active 列表里、但本地还当成在跑的任务，单独补一次终态，
     * 避免界面永远停在"运行中"（兜底轮询的意义正在于此）。
     */
    const reconcileFromSnapshot = async () => {
      const response = await agentApi.listTasks({ status: 'active', page: 1, size: 50 });
      const records = response.data.data.records ?? [];
      records.forEach((task) => get().upsertTask(task));
      const seen = new Set(records.map((task) => task.taskId));
      const vanished = Object.values(get().tasks)
        .filter((task) => isActiveTask(task) && !seen.has(task.taskId))
        .slice(0, 10);
      await Promise.all(vanished.map((task) => agentApi.getTask(task.taskId)
        .then((res) => get().upsertTask(res.data.data))
        .catch(() => undefined)));
    };
    const scheduleFallback = () => {
      clearFallback();
      // 后台标签页不轮询：回到前台由 visibilitychange 立刻补一次
      if (typeof document !== 'undefined' && document.hidden) return;
      const hasActive = Object.values(get().tasks).some(isActiveTask);
      // SSE 正常时只做「静默卡死」兜底（30s）；SSE 断了才回到 5s；没有在跑的任务就慢查。
      const delay = hasActive ? (get().taskStreamConnected ? 30_000 : 5_000) : 60_000;
      taskFallbackTimer = setTimeout(async () => {
        await reconcileFromSnapshot().catch(() => undefined);
        if (!taskStreamAbort?.signal.aborted) scheduleFallback();
      }, delay);
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
    if (typeof document !== 'undefined') {
      if (taskVisibilityHandler) {
        document.removeEventListener('visibilitychange', taskVisibilityHandler);
      }
      taskVisibilityHandler = () => {
        if (!document.hidden && taskStreamRunning) scheduleFallback();
      };
      document.addEventListener('visibilitychange', taskVisibilityHandler);
    }
  },
  stopTaskStream: () => {
    taskStreamRunning = false;
    taskStreamAbort?.abort();
    taskStreamAbort = null;
    if (taskFallbackTimer) clearTimeout(taskFallbackTimer);
    taskFallbackTimer = null;
    if (taskVisibilityHandler && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', taskVisibilityHandler);
    }
    taskVisibilityHandler = null;
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
  pendingPicUrls: [],
  pendingAssistantId: null,

  refImageUrls: [],
  setRefImageUrls: (v) => set({ refImageUrls: v }),
  addRefImageUrls: (urls) => set((s) => ({ refImageUrls: [...new Set([...s.refImageUrls, ...urls])] })),
  composerInjection: null,
  injectComposerText: (text) => {
    const clean = text.trim();
    if (!clean) return;
    set((s) => ({
      composerInjection: { seq: (s.composerInjection?.seq ?? 0) + 1, text: clean },
    }));
  },
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

  pendingReferences: readPendingReferences(null),
  setPendingReferences: (references) => set((s) => {
    const next = dedupeReferences(references.filter(isAgentReference));
    persistPendingReferences(s.activeConversationId, next);
    return { pendingReferences: next };
  }),
  addPendingReferences: (references) => set((s) => {
    const next = dedupeReferences([
      ...s.pendingReferences,
      ...references.filter(isAgentReference),
    ]);
    persistPendingReferences(s.activeConversationId, next);
    return { pendingReferences: next };
  }),
  removePendingReference: (kind, id) => set((s) => {
    const next = s.pendingReferences.filter((item) => !(item.kind === kind && item.id === id));
    persistPendingReferences(s.activeConversationId, next);
    return { pendingReferences: next };
  }),
  clearPendingReferences: () => set((s) => {
    persistPendingReferences(s.activeConversationId, []);
    return { pendingReferences: [] };
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
    // 画布媒体引用必须是服务端可解析的相对路径：绝对化后 origin 会是浏览器地址
    // （开发端口/网关/站点域名），MoonAgent 校验会拒绝并报「媒体地址不在允许的服务范围内」。
    // 渲染侧相对路径已由聊天页自身的 /api 代理兜住，无需绝对化。
    get().addCanvasMediaRefs([{
      kind: canvasKind,
      url,
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
  addLocalMediaMessage: (content) => set((s) => ({
    messages: [...s.messages, {
      id: `local-media-${Date.now()}`,
      conversationId: s.activeConversationId ?? '',
      role: 'assistant',
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

  sendMessage: async (content, opts?: AgentSendOptions) => {
    const activeId = get().activeConversationId;
    const activeConversation = get().conversations.find((conversation) => conversation.id === activeId);
    if (activeConversation?.status === 'archived'
      || activeConversation?.orchestrationMode === 'LEGACY') {
      set({ streamError: '该会话已归档，只能查看历史记录' });
      return;
    }
    const editMessageId = opts?.editMessageId;
    const editTargetIndex = editMessageId
      ? get().messages.findIndex((message) => message.id === editMessageId && message.role === 'user')
      : -1;
    if (editMessageId && editTargetIndex < 0) {
      set({ streamError: '要编辑的消息不存在，请刷新后重试' });
      return;
    }
    // 输入禁用条件：(streaming && !agentVideoTask) || waitingHumanInput || waitingVideoPlan
    // 视频异步任务轮询期间（agentVideoTask 存在）允许继续发送新消息
    // 生成中要发新消息统一走 interruptAndSend（先停旧轮、再发新轮），这里不再有"边生成边发"的分支。
    if ((get().streaming && !get().agentVideoTask) || get().waitingHumanInput || get().waitingVideoPlan
      || get().canvasBinding || get().canvasExitPending || !content.trim()) return;
    // 新一轮发送：清除上次手动停止标志并新建 AbortController（供「停止生成」中断本流）。
    // 若视频异步任务仍轮询，先停止旧轮询并告知后端取消在途任务（cancelActiveTasks: true）。
    if (get().agentVideoTask) {
      if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
      videoPollToken += 1;
      set({ agentVideoTask: null });
      opts = { ...opts, cancelActiveTasks: true };
    }
    manualStop = false;
    streamAbort = new AbortController();
    const signal = streamAbort.signal;
    // 快照参考图——createConversation → selectConversation 会把 refImageUrls/messages 清空，
    // 不先存会导致首条消息 picUrls 丢失（首会话上传图「说话后被吞」）
    const refBefore = get().refImageUrls.map((u) => u);
    const referenceVideosBefore = get().pickedReferenceVideos.map((u) => u);
    const referenceAudiosBefore = get().pickedReferenceAudios.map((u) => u);
    const attachmentsBefore = get().pendingAttachments.map((item) => ({ ...item }));
    // 同一类快照：createConversation → selectConversation 会清空 pendingReferences
    const referencesBefore = get().pendingReferences.map((item) => ({ ...item }));
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
    // 引用资料与附件同源同命：优先用调用方显式传入的，否则用本轮快照。
    // 注意「停止并发送」路径（interruptAndSend → sendMessage）不传 opts，
    // 正是靠这条快照兜底才不丢引用。
    // `references: []` 与「没传」含义不同：编辑重发时会显式传空数组表示「改后的正文不再引用任何东西」，
    // 那是权威值，不能回落到快照（否则用户删掉标记重发，旧引用会被复活）。
    // 最后再按正文过滤一次：`@名称` 被删掉/改写了就不带走这条引用（防幽灵引用）。
    const requestReferences = activeReferences(content, dedupeReferences(
      opts?.references !== undefined ? opts.references : referencesBefore,
    ));
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
    persistPendingReferences(id, referencesBefore);
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
          ...(requestReferences.length > 0 ? { references: requestReferences } : {}),
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

    // 本轮素材已随消息送达（也写进消息 metadata 与会话历史）：从输入框上方彻底撤掉，
    // 并清掉本地快照，刷新后不再作为「待发素材」复活——否则用户会以为旧素材又被发了一遍。
    // 历史素材在会话消息里仍然可见；要再带素材时重新「加文件 / 加资产」。
    if (get().canvasEditContext) {
      const context = get().canvasEditContext!;
      const consumedContext = { ...context, mediaRefs: [], excludedMediaUrls: [] };
      persistCanvasEditContext(consumedContext);
      set({ canvasEditContext: consumedContext });
    }
    get().clearPendingAttachments();
    get().clearPendingReferences();

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
          attachResultToMessage(assistantId, effects.confirmResult);
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
        references: requestReferences,
        // 生成偏好：自动且不忽略方案时返回 undefined → 请求体不带该字段（零回归）
        generationPreferences: toGenerationPreferencesPayload(get().generationPreferences),
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
    let submittedCommitted = false;
    set((s) => {
      if (opts?.hiddenCard) {
        return {
          streaming: true,
          generating: { ...s.generating, [id]: { hint: '', finished: false } },
          waitingHumanInput: null,
          streamError: null,
        };
      }
      return {
        streaming: true,
        generating: { ...s.generating, [id]: { hint: '', finished: false } },
        streamError: null,
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

    const submittedHitlMeta = (): AgentMessageMetadata => ({
      kind: 'hitl',
      pending: false,
      hitl: {
        formContent: info.formContent || '',
        actions: info.actions || [],
        pending: false,
        formToken: info.formToken,
        expirationTime: info.expirationTime,
        selectedActionId: actionId,
        selectedActionTitle: act?.title ?? actionId,
        customText: actionId === 'custom' ? customText : undefined,
        params,
        assetIds,
        messageId: cardId,
      },
    });

    const commitSubmittedHitl = () => {
      if (submittedCommitted) return;
      submittedCommitted = true;
      rememberSubmittedHitlReceipt(id, info.formToken, cardId, info.expirationTime);
      const metadata = submittedHitlMeta();
      set((state) => {
        if (opts?.hiddenCard) return { waitingHumanInput: null };
        const hasCard = state.messages.some((message) => message.id === cardId);
        const nextCard: AgentMessage = {
          id: cardId,
          conversationId: id,
          role: 'assistant',
          content: info.formContent || '',
          createdAt: new Date().toISOString(),
          metadata,
        };
        return {
          waitingHumanInput: null,
          messages: hasCard
            ? state.messages.map((message) => message.id === cardId
              ? { ...message, metadata: { ...(message.metadata ?? {}), ...metadata } }
              : message)
            : [...state.messages, nextCard],
        };
      });
    };

    const rollbackSubmittedHitl = () => {
      forgetSubmittedHitlReceipt(id, info.formToken, cardId);
      set((state) => ({
        ...(opts?.hiddenCard ? {} : { waitingHumanInput: info }),
        workflowHint: '',
        messages: opts?.hiddenCard
          ? state.messages
          : state.messages.map((message) => message.id === cardId
            ? {
                ...message,
                content: message.content || info.formContent || '',
                metadata: {
                  ...(message.metadata ?? {}),
                  kind: 'hitl',
                  pending: true,
                  hitl: {
                    formContent: info.formContent || '',
                    actions: info.actions || [],
                    pending: true,
                    formToken: info.formToken,
                    expirationTime: info.expirationTime,
                    messageId: cardId,
                  },
                },
              }
            : message),
      }));
    };

    const reconcileFailedSubmission = async () => {
      if (submittedCommitted) return;
      try {
        const pending = await agentApi.getPendingCheckpoint(id);
        const checkpoint = pending.data.data as { formToken?: string } | null;
        if (checkpoint?.formToken === info.formToken) {
          rollbackSubmittedHitl();
          return;
        }
      } catch {
        // Fall through to the runtime check; an unknown state must not silently consume the card.
      }
      try {
        const runtime = await agentApi.getRuntimeStatus(id);
        if (runtime.data.data.activeTurn?.status === 'running'
            || hasSubmittedHitlCard(id, info.formToken, cardId)) {
          commitSubmittedHitl();
          return;
        }
      } catch {
        // A transient status failure is safest as a retryable pending card.
      }
      rollbackSubmittedHitl();
    };

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
        if (effects.started || effects.completed) {
          commitSubmittedHitl();
        }
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
          attachResultToMessage(assistantId, effects.confirmResult);
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
      if (!manualStop && get().activeConversationId === snapshotId) {
        await reconcileFailedSubmission();
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
    let videoSubmissionCommitted = false;
    const submittedVideoMeta = (): AgentMessageMetadata => ({
      kind: 'video-plan',
      hitl: {
        formContent: info.message,
        actions: info.actions,
        pending: false,
        formToken: info.planToken,
        expirationTime: Number(info.expirationTime ?? 0),
        selectedActionId: actionId,
        selectedActionTitle,
        customText: actionId === 'custom' ? customText : undefined,
        params,
        messageId: cardId,
      },
      videoPlan: videoPlanMeta,
    });
    const commitVideoSubmission = () => {
      if (videoSubmissionCommitted) return;
      videoSubmissionCommitted = true;
      rememberSubmittedHitlReceipt(
        id, info.planToken, cardId, Number(info.expirationTime ?? 0));
      const metadata = submittedVideoMeta();
      set((state) => {
        const hasCard = state.messages.some((message) => message.id === cardId);
        const nextCard: AgentMessage = {
          id: cardId,
          conversationId: id,
          role: 'assistant',
          content: info.message,
          createdAt: new Date().toISOString(),
          metadata,
        };
        const hitlVideoPlan = { ...state.hitlVideoPlan };
        delete hitlVideoPlan[id];
        return {
          waitingVideoPlan: null,
          hitlVideoPlan,
          messages: hasCard
            ? state.messages.map((message) => message.id === cardId
              ? { ...message, metadata: { ...(message.metadata ?? {}), ...metadata } }
              : message)
            : [...state.messages, nextCard],
        };
      });
    };
    const rollbackVideoSubmission = () => {
      forgetSubmittedHitlReceipt(id, info.planToken, cardId);
      set((state) => {
        const hitlVideoPlan = { ...state.hitlVideoPlan, [id]: info };
        return {
          waitingVideoPlan: info,
          hitlVideoPlan,
          workflowHint: '',
          messages: state.messages.map((message) => message.id === cardId
            ? {
                ...message,
                metadata: {
                  ...(message.metadata ?? {}),
                  kind: 'video-plan',
                  hitl: {
                    formContent: info.message,
                    actions: info.actions,
                    pending: true,
                    formToken: info.planToken,
                    expirationTime: Number(info.expirationTime ?? 0),
                    messageId: cardId,
                  },
                  videoPlan: videoPlanMeta,
                },
              }
            : message),
        };
      });
    };
    const reconcileVideoFailure = async () => {
      if (videoSubmissionCommitted) return;
      try {
        const pending = await agentApi.getPendingCheckpoint(id);
        const checkpoint = pending.data.data as { formToken?: string } | null;
        if (checkpoint?.formToken === info.planToken) {
          rollbackVideoSubmission();
          return;
        }
      } catch {
        // Fall through to the runtime check.
      }
      try {
        const runtime = await agentApi.getRuntimeStatus(id);
        if (runtime.data.data.activeTurn?.status === 'running'
            || hasSubmittedHitlCard(id, info.planToken, cardId)) {
          commitVideoSubmission();
          return;
        }
      } catch {
        // Unknown server state remains retryable.
      }
      rollbackVideoSubmission();
    };
    // 继续完善：本地关闭卡片 + 保留参考图（assetUrl 转绝对 URL，后端容器内可访问）
    if (actionId === 'refine') {
      commitVideoSubmission();
      set((s) => { const h = { ...s.hitlVideoPlan }; delete h[id]; return { waitingVideoPlan: null, hitlVideoPlan: h, pendingPicUrl: assetUrl(info.picUrl) }; });
      return;
    }
    // 自定义输入：走 submitForm 发送 custom action + 自定义文本，与 HumanInputCard 的 custom 流程对齐
    if (actionId === 'custom') {
      set((s) => {
        return {
          streaming: true,
          generating: { ...s.generating, [id]: { hint: '', finished: false } },
          streamError: null,
        };
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
          if (effects.started || effects.completed) commitVideoSubmission();
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
        if (!manualStop && get().activeConversationId === snapshotId) {
          set({ streamError: err instanceof Error ? err.message : '对话出错，请重试' });
          await reconcileVideoFailure();
        }
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
      return {
        streaming: true,
        generating: { ...s.generating, [id]: { hint: '', finished: false } },
        streamError: null,
      };
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
              };
            });
            if (t.kind !== 'long-tool') {
              attachResultToMessage(assistantId, {
                kind: t.kind === 'image-batch' ? 'image' : 'video',
                url: t.url || '',
                urls: t.resultUrls,
                assetId: t.assetId || t.assetIds?.[0],
                assetIds: t.assetIds,
                sceneCount: 0,
                actions: [{ id: 'refine', title: '继续完善' }, { id: 'done', title: '满意完成' }],
              });
            }
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
                metadata: get().activeConversationId
                  ? { conversationId: get().activeConversationId }
                  : undefined,
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
        if (effects.started || effects.completed) commitVideoSubmission();
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
          attachResultToMessage(assistantId, effects.confirmResult);
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
        await reconcileVideoFailure();
      }
    } finally {
      releaseStream();
      // 本轮流已结束：清除打断标记（后续不会再收到这一轮的 delta）
      const settledTurnId = get().generating[snapshotId]?.turnId;
      if (settledTurnId) interruptedTurns.delete(settledTurnId);
      if (interruptedTurns.size > 64) interruptedTurns.clear();
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

  /**
   * 停止当前会话生成（「停止生成」按钮）：定格已流出的文字 + 中断 SSE 流 + 后端协作式取消。
   *
   * <p>用户要求「停止后智能体已经发出来的文字不能被清空」：这里不再删除占位消息，
   * 而是把正文补上「…」后留在气泡里（后端同时会把这段截断正文落库）。</p>
   */
  stopGeneration: () => {
    const id = get().activeConversationId;
    const pendingAssistantId = get().pendingAssistantId;
    manualStop = true;
    const turnId = id ? get().generating[id]?.turnId : undefined;
    // 断流后不会再有任何 delta；无需登记 interruptedTurns（那是「保持连接等终态」路径才需要的）
    const frozen = id ? freezeInterruptedReply(id, pendingAssistantId) : '';
    // 中断当前 SSE fetch（若有）：consumeSse 循环检测 signal.aborted 会立即跳出 → 触发 sendMessage 等的 finally 清理
    streamAbort?.abort();
    streamAbort = null;
    // 视频异步任务轮询期：停止轮询并作废代次令牌
    if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
    videoPollToken += 1;
    // Turn-scoped cancellation; no active turn means there is nothing to interrupt.
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
          // 已定格的正文连气泡一起保留；一个字都没流出来时才收起空占位
          messages: frozen
            ? s.messages
            : s.messages.filter((message) => message.id !== pendingAssistantId),
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
      if (cp?.formToken && (
        hasSubmittedHitlReceipt(conversationId, cp.formToken)
        || hasSubmittedHitlCard(conversationId, cp.formToken, cp.messageId)
      )) {
        if (get().activeConversationId === conversationId) {
          set({ waitingHumanInput: null, workflowHint: '' });
        }
        return;
      }
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
          expirationTime: cp.expirationTime,
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

  /**
   * 结果确认：继续完善 → 暂存结果图 PicUrl，不自动发送；用户输入完善需求后随下一条消息发送。
   *
   * <p>只收起该消息上的**动作**：结果图已经在消息附件（或位条）里，
   * 因此点它不会再出现"点一下图片全没了"。
   */
  refineAsset: (info, messageId) => {
    if (!info || info.kind === 'script') return;
    // info.url 是后端相对路径（/api/files/images/x.png），容器内无法访问；
    // assetUrl() 拼接 BACKEND 前缀转绝对 URL（http/data: 透传）后再发给后端
    const picUrl = assetUrl(info.url);
    set({ pendingPicUrls: [picUrl] });
    if (messageId) get().clearMessageConfirmResult(messageId);
  },
  /** 取消继续完善：清空暂存参考图（输入框提示条上的 ✕ 触发） */
  cancelRefine: () => set({ pendingPicUrls: [] }),
  /**
   * 结果确认：满意完成 → 通知后端清空图片上下文变量（下次图片需求走全新设计）。
   * 成功才收起该消息上的动作 + 刷新资产；失败保留并提示（可重试）。
   * 纯前端收起（旧行为）会变量残留 → 下次图片需求误走完善路径。
   */
  dismissConfirm: async (messageId) => {
    const id = get().activeConversationId;
    if (!id) {
      if (messageId) get().clearMessageConfirmResult(messageId);
      return;
    }
    try {
      await agentApi.confirmDone(id);
      if (messageId) get().clearMessageConfirmResult(messageId);
      void get().loadAssets();
    } catch {
      alert('操作失败，请重试');
    }
  },
  clearMessageConfirmResult: (messageId) => {
    if (!messageId) return;
    set((state) => ({
      messages: state.messages.map((message) => {
        if (message.id !== messageId || !message.metadata?.confirmResult) return message;
        const metadata = { ...message.metadata };
        delete metadata.confirmResult;
        return { ...message, metadata };
      }),
    }));
  },

  resetChatState: () => {
    if (videoPollTimer) { clearInterval(videoPollTimer); videoPollTimer = null; }
    videoPollToken += 1;
    clearCanvasEditContext();
    set({ messages: [], generating: {}, streaming: false, waitingHumanInput: null,
       pendingApprovalMode: defaultApprovalMode(),
       waitingVideoPlan: null, hitlVideoPlan: {}, streamError: null, workflowHint: '', assets: null, refImageUrls: [],
       pendingAttachments: [], pendingReferences: [], pickedReferenceVideos: [], pickedReferenceAudios: [],
       pendingAssistantId: null,
       pendingPicUrls: [], storyboardImageQueue: [],
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
