import { create } from 'zustand';
import type { AgentAttachment, AgentReference } from '../api/agent';

/**
 * 繁忙时「排队发送」的队列模型。
 *
 * 队列只保存一轮发送所需的快照，不预先创建 turn：当前回合结束后，前端按顺序把
 * 队首消息作为普通消息发送。这样排队消息不会插入当前上下文，也不会影响 SSE 序号。
 *
 * 持久化到 sessionStorage：同一标签页刷新/切路由不丢，换标签页各自独立。
 */
export interface QueuedSendOptions {
  picUrls?: string[];
  assetIds?: string[];
  assetGroupIds?: string[];
  assetGroupSnapshotIds?: string[];
  attachments?: AgentAttachment[];
  references?: AgentReference[];
  routingHint?: string;
}

export interface QueuedSendItem {
  id: string;
  conversationId: string;
  content: string;
  createdAt: number;
  /** 最近一次编辑时间；排队中改过正文时写入，用于面板提示。 */
  editedAt?: number;
  opts?: QueuedSendOptions;
}

export interface EnqueueSendInput extends QueuedSendOptions {
  conversationId: string;
  content: string;
}

const STORAGE_KEY = 'moon.send-queue.v1';
export const MAX_QUEUED_SENDS = 20;

function createId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `queued-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

function readQueues(): Record<string, QueuedSendItem[]> {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const queues: Record<string, QueuedSendItem[]> = {};
    for (const [conversationId, value] of Object.entries(parsed)) {
      if (!Array.isArray(value)) continue;
      const items = value.filter((item): item is QueuedSendItem => (
        !!item
        && typeof item === 'object'
        && typeof (item as QueuedSendItem).id === 'string'
        && typeof (item as QueuedSendItem).content === 'string'
        && typeof (item as QueuedSendItem).createdAt === 'number'
      )).map((item) => ({ ...item, conversationId }));
      if (items.length > 0) queues[conversationId] = items.slice(0, MAX_QUEUED_SENDS);
    }
    return queues;
  } catch {
    return {};
  }
}

function persist(queues: Record<string, QueuedSendItem[]>): void {
  if (typeof window === 'undefined') return;
  try {
    const compact = Object.fromEntries(
      Object.entries(queues)
        .filter(([, items]) => items.length > 0)
        .map(([conversationId, items]) => [conversationId, items]),
    );
    if (Object.keys(compact).length === 0) {
      window.sessionStorage.removeItem(STORAGE_KEY);
    } else {
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(compact));
    }
  } catch {
    // 隐私模式/配额不足时退化为本次页面内存队列。
  }
}

interface SendQueueState {
  queues: Record<string, QueuedSendItem[]>;
  /**
   * 队列「暂缓发送」计数（不持久化）。
   *
   * <p>两处会用：① 排队消息「插队发送」要先打断当前回合，打断成功前不能让
   * `SendQueueRunner` 抢先把队首发出去；② 队列内正在编辑某条消息时，不能让自动发送
   * 把未保存的旧正文发走。计数归零才恢复自动出队。</p>
   */
  holds: Record<string, number>;
  enqueue: (input: EnqueueSendInput) => boolean;
  shift: (conversationId: string) => QueuedSendItem | undefined;
  /** 按 id 取走一条（插队发送用），返回被移除的消息。 */
  take: (conversationId: string, itemId: string) => QueuedSendItem | undefined;
  /** 插队发送失败时把消息放回原位置，避免丢消息。 */
  restore: (item: QueuedSendItem, index: number) => void;
  /** 排队中重新编辑正文：保留附件/引用快照，只替换文字。 */
  update: (conversationId: string, itemId: string, content: string) => boolean;
  remove: (conversationId: string, itemId: string) => void;
  clear: (conversationId: string) => void;
  clearAll: () => void;
  move: (conversationId: string, itemId: string, direction: -1 | 1) => void;
  promote: (conversationId: string, itemId: string) => void;
  holdQueue: (conversationId: string) => void;
  releaseQueue: (conversationId: string) => void;
}

export const useSendQueueStore = create<SendQueueState>((set, get) => ({
  queues: readQueues(),
  holds: {},

  enqueue: (input) => {
    const conversationId = input.conversationId;
    if (!conversationId || !input.content.trim()) return false;
    const current = get().queues[conversationId] ?? [];
    if (current.length >= MAX_QUEUED_SENDS) return false;
    const item: QueuedSendItem = {
      id: createId(),
      conversationId,
      content: input.content,
      createdAt: Date.now(),
      opts: {
        picUrls: input.picUrls,
        assetIds: input.assetIds,
        assetGroupIds: input.assetGroupIds,
        assetGroupSnapshotIds: input.assetGroupSnapshotIds,
        attachments: input.attachments,
        references: input.references,
        routingHint: input.routingHint,
      },
    };
    const next = { ...get().queues, [conversationId]: [...current, item] };
    set({ queues: next });
    persist(next);
    return true;
  },

  shift: (conversationId) => {
    const current = get().queues[conversationId] ?? [];
    const [first, ...rest] = current;
    if (!first) return undefined;
    const next = { ...get().queues };
    if (rest.length > 0) next[conversationId] = rest;
    else delete next[conversationId];
    set({ queues: next });
    persist(next);
    return first;
  },

  take: (conversationId, itemId) => {
    const current = get().queues[conversationId] ?? [];
    const item = current.find((entry) => entry.id === itemId);
    if (!item) return undefined;
    const rest = current.filter((entry) => entry.id !== itemId);
    const next = { ...get().queues };
    if (rest.length > 0) next[conversationId] = rest;
    else delete next[conversationId];
    set({ queues: next });
    persist(next);
    return item;
  },

  restore: (item, index) => {
    const conversationId = item.conversationId;
    if (!conversationId) return;
    const current = get().queues[conversationId] ?? [];
    if (current.some((entry) => entry.id === item.id)) return;
    if (current.length >= MAX_QUEUED_SENDS) return;
    const at = Math.max(0, Math.min(index, current.length));
    const nextItems = [...current.slice(0, at), item, ...current.slice(at)];
    const next = { ...get().queues, [conversationId]: nextItems };
    set({ queues: next });
    persist(next);
  },

  update: (conversationId, itemId, content) => {
    const trimmed = content.trim();
    if (!trimmed) return false;
    const current = get().queues[conversationId] ?? [];
    const index = current.findIndex((item) => item.id === itemId);
    if (index < 0) return false;
    if (current[index].content === trimmed) return true;
    const nextItems = [...current];
    nextItems[index] = { ...current[index], content: trimmed, editedAt: Date.now() };
    const next = { ...get().queues, [conversationId]: nextItems };
    set({ queues: next });
    persist(next);
    return true;
  },

  remove: (conversationId, itemId) => {
    const current = get().queues[conversationId] ?? [];
    const rest = current.filter((item) => item.id !== itemId);
    const next = { ...get().queues };
    if (rest.length > 0) next[conversationId] = rest;
    else delete next[conversationId];
    set({ queues: next });
    persist(next);
  },

  clear: (conversationId) => {
    if (!get().queues[conversationId]) return;
    const next = { ...get().queues };
    delete next[conversationId];
    set({ queues: next });
    persist(next);
  },

  clearAll: () => {
    set({ queues: {}, holds: {} });
    persist({});
  },

  move: (conversationId, itemId, direction) => {
    const current = get().queues[conversationId] ?? [];
    const index = current.findIndex((item) => item.id === itemId);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= current.length) return;
    const nextItems = [...current];
    [nextItems[index], nextItems[target]] = [nextItems[target], nextItems[index]];
    const next = { ...get().queues, [conversationId]: nextItems };
    set({ queues: next });
    persist(next);
  },

  promote: (conversationId, itemId) => {
    const current = get().queues[conversationId] ?? [];
    const index = current.findIndex((item) => item.id === itemId);
    if (index <= 0) return;
    const item = current[index];
    const nextItems = [item, ...current.slice(0, index), ...current.slice(index + 1)];
    const next = { ...get().queues, [conversationId]: nextItems };
    set({ queues: next });
    persist(next);
  },

  holdQueue: (conversationId) => {
    if (!conversationId) return;
    set((state) => ({
      holds: { ...state.holds, [conversationId]: (state.holds[conversationId] ?? 0) + 1 },
    }));
  },

  releaseQueue: (conversationId) => {
    if (!conversationId) return;
    set((state) => {
      const current = state.holds[conversationId] ?? 0;
      if (current <= 1) {
        const next = { ...state.holds };
        delete next[conversationId];
        return { holds: next };
      }
      return { holds: { ...state.holds, [conversationId]: current - 1 } };
    });
  },
}));

/** 组件订阅单会话队列时复用同一个空数组，避免每次渲染都产生新引用。 */
export const EMPTY_QUEUE: QueuedSendItem[] = [];
