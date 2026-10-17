import type { AgentTurnEventEnvelope } from '../api/agent';

/**
 * turn 事件续传的客户端支撑（WS4）：
 *
 * 1. **直播通道**：刷新会断开浏览器 SSE，但后端 turn 继续跑并逐条落库。恢复时先补历史，
 *    再接到 `GET /api/agent/conversations/{id}/turns/{turnId}/stream?afterSeq=N` 的直播上，
 *    直到终态；老后端没有该端点时回退到 `listTurnEvents` 轮询回放。
 * 2. **续传游标**：把每个 turn 已渲染到的最大序号落到 sessionStorage（key 带 conversationId
 *    与 turnId，有 TTL 与条数上限），终态/暂停清理。
 * 3. **分批让出主线程**：一次性重放可能上百条事件（画布 turn 单条就是 KB 级），
 *    连续同步 apply 会把刷新后的首帧渲染和用户输入一起卡住，所以按批 yield。
 */

/** 一次性历史的批大小：每批 apply 之后让出主线程，避免刷新后一个长任务卡住 UI。 */
export const RESTORE_REPLAY_BATCH = 50;

/** 让出主线程（宏任务边界）：长重放分段执行，刷新后的首帧渲染与输入不被阻塞。 */
export function yieldToEventLoop(): Promise<void> {
  return new Promise<void>((resolve) => setTimeout(resolve, 0));
}

export const TURN_RESUME_CURSOR_KEY = 'moon.turn.lastSeq.v1';
/** 游标条数上限：只服务"当前正在看的 turn"，不做历史归档。 */
export const TURN_RESUME_CURSOR_LIMIT = 20;
/** 游标过期时间：超过 6 小时的 turn 不可能还在跑。 */
export const TURN_RESUME_CURSOR_TTL_MS = 6 * 60 * 60 * 1000;

export type TurnResumeCursor = {
  conversationId: string;
  turnId: string;
  seq: number;
  updatedAt: number;
};

function isCursor(value: unknown): value is TurnResumeCursor {
  if (!value || typeof value !== 'object') return false;
  const item = value as Partial<TurnResumeCursor>;
  return typeof item.conversationId === 'string'
    && typeof item.turnId === 'string'
    && typeof item.seq === 'number'
    && typeof item.updatedAt === 'number';
}

/** 读取全部续传游标（损坏 / 不可用时返回空数组，绝不抛错）。 */
export function readTurnResumeCursors(): TurnResumeCursor[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = window.sessionStorage.getItem(TURN_RESUME_CURSOR_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isCursor);
  } catch {
    return [];
  }
}

function writeTurnResumeCursors(cursors: TurnResumeCursor[]) {
  if (typeof window === 'undefined') return;
  try {
    if (cursors.length === 0) window.sessionStorage.removeItem(TURN_RESUME_CURSOR_KEY);
    else window.sessionStorage.setItem(TURN_RESUME_CURSOR_KEY, JSON.stringify(cursors));
  } catch {
    // 存储不可用时只失去增量定位能力，恢复仍走全量补齐。
  }
}

/** 记录某个 turn 已经渲染到的最大序号。 */
export function rememberTurnLastSeq(
  conversationId: string,
  turnId: string,
  seq: number,
): void {
  if (!conversationId || !turnId || !Number.isFinite(seq) || seq <= 0) return;
  const now = Date.now();
  const next = readTurnResumeCursors().filter((item) =>
    !(item.conversationId === conversationId && item.turnId === turnId)
    && now - item.updatedAt < TURN_RESUME_CURSOR_TTL_MS);
  next.push({ conversationId, turnId, seq: Math.floor(seq), updatedAt: now });
  writeTurnResumeCursors(next.slice(-TURN_RESUME_CURSOR_LIMIT));
}

/** 读取某个 turn 已渲染到的最大序号（无记录 / 已过期返回 0）。 */
export function readTurnLastSeq(conversationId: string, turnId: string): number {
  if (!conversationId || !turnId) return 0;
  const now = Date.now();
  const hit = readTurnResumeCursors().find((item) =>
    item.conversationId === conversationId
    && item.turnId === turnId
    && now - item.updatedAt < TURN_RESUME_CURSOR_TTL_MS);
  return hit && hit.seq > 0 ? Math.floor(hit.seq) : 0;
}

/** 终态/暂停后清理游标：该 turn 不会再有续传。 */
export function forgetTurnLastSeq(conversationId: string, turnId: string): void {
  if (!conversationId || !turnId) return;
  writeTurnResumeCursors(readTurnResumeCursors().filter((item) =>
    !(item.conversationId === conversationId && item.turnId === turnId)));
}

// ── 直播续传通道 ────────────────────────────────────────────────

export type TurnResumeStreamState = 'streaming' | 'failed' | 'finished';

export type TurnResumeStreamOptions = {
  conversationId: string;
  turnId: string;
  /**
   * 直播起点 = **已经补过的历史末序号**。
   *
   * <p>注意不要传"要跳过多少历史"的游标（例如 HITL 续跑时指向暂停点的 seq）：那会把已经
   * 补过的事件再发一遍，文本 delta 会重复。两者的区别只在"历史是要渲染还是要跳过"。
   */
  afterSeq: number;
  /** 实际建立 SSE 连接的函数；由 store 注入，测试可替换。 */
  subscribe: (
    conversationId: string,
    turnId: string,
    afterSeq: number,
    onEvent: (event: AgentTurnEventEnvelope) => void,
    signal: AbortSignal,
  ) => Promise<void>;
};

export type TurnResumeStream = {
  /** streaming=直播中；failed=端点不可用/连接失败（应回退轮询）；finished=直播已正常结束。 */
  state(): TurnResumeStreamState;
  /** 取一批待应用事件（空数组=暂无，用 state() 判断是等推送还是已结束）。 */
  take(): AgentTurnEventEnvelope[];
  /** 等到有新事件或状态变化；由调用方在 take() 为空时调用。 */
  waitForEvents(): Promise<void>;
  /** 强制切到轮询模式（直播正常结束但没拿到终态时用）。 */
  markFailed(): void;
  /** 主动收尾：中止连接并唤醒等待者。 */
  stop(): void;
};

/**
 * 建立一个直播续传通道：后台持续接收事件，调用方按批 `take()`。
 *
 * <p>任何连接异常都不会抛出到调用方，只体现为 `state() === 'failed'`。
 */
export function createTurnResumeStream(options: TurnResumeStreamOptions): TurnResumeStream {
  const pending: AgentTurnEventEnvelope[] = [];
  const controller = new AbortController();
  let state: TurnResumeStreamState = 'streaming';
  let wake: (() => void) | null = null;

  const notify = () => {
    const resume = wake;
    wake = null;
    if (resume) resume();
  };

  void options.subscribe(
    options.conversationId,
    options.turnId,
    options.afterSeq,
    (event) => {
      pending.push(event);
      notify();
    },
    controller.signal,
  )
    .then(() => {
      if (state === 'streaming') state = 'finished';
    })
    .catch(() => {
      if (state === 'streaming') state = 'failed';
    })
    .finally(notify);

  return {
    state: () => state,
    take: () => pending.splice(0, RESTORE_REPLAY_BATCH),
    waitForEvents: () => new Promise<void>((resolve) => { wake = resolve; }),
    markFailed: () => {
      if (state === 'streaming') state = 'failed';
      notify();
    },
    stop: () => {
      if (state === 'streaming') state = 'finished';
      controller.abort();
      notify();
    },
  };
}
