import type { CanvasJobRequest, GenerationQueueStatus } from './graphContracts';

export type { GenerationQueueStatus } from './graphContracts';

export interface GenerationQueueItem {
  id: string;
  projectId: string;
  canvasId: string;
  producerNodeId: string;
  generationRecordId: string;
  requestSnapshot: CanvasJobRequest;
  status: GenerationQueueStatus;
  jobId?: string;
  attempt: number;
  leaseOwner?: string;
  leaseExpiresAt?: number;
  createdAt: number;
  updatedAt: number;
  error?: string;
}

export interface ClaimOptions {
  now?: number;
  concurrency?: number;
}

export function claimQueueItems(
  items: GenerationQueueItem[],
  options: ClaimOptions = {},
): GenerationQueueItem[] {
  const now = options.now ?? Date.now();
  const concurrency = Math.max(1, options.concurrency ?? 2);
  const validRunning = items.filter((item) =>
    item.status === 'running' && Number(item.leaseExpiresAt ?? 0) > now);
  const remaining = Math.max(0, concurrency - validRunning.length);
  if (!remaining) return [];
  return items
    .filter((item) => item.status === 'pending_submit')
    .sort((a, b) => a.createdAt - b.createdAt)
    .slice(0, remaining)
    .map((item) => ({ ...item, status: 'claimed', updatedAt: now }));
}

export type RecoveryAction = 'resume' | 'interrupted' | 'ignore';

export function recoveryActionForQueueItem(item: GenerationQueueItem): RecoveryAction {
  if (item.status === 'terminal' || item.status === 'cancelled') return 'ignore';
  if (item.status === 'running') return item.jobId ? 'resume' : 'interrupted';
  if (item.status === 'pending_submit' || item.status === 'claimed' || item.status === 'interrupted') return 'interrupted';
  return 'ignore';
}

let fallbackLock = Promise.resolve();
let localSlots = 2;

const slotRelease = () => {
  let released = false;
  return () => {
    if (released) return;
    released = true;
    localSlots += 1;
  };
};

export const acquireLocalGenerationSlot = async (timeoutMs = 15_000) => {
  const deadline = Date.now() + timeoutMs;
  while (localSlots <= 0) {
    if (Date.now() >= deadline) throw new Error('生成队列繁忙，请稍后重试');
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  localSlots -= 1;
  return slotRelease();
};

export async function acquireGenerationSlot(timeoutMs = 15_000): Promise<() => void> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  if (locks?.request) {
    const deadline = Date.now() + timeoutMs;
    const tryAcquire = (name: string) => new Promise<(() => void) | null>((resolve) => {
      let releaseLock: (() => void) | null = null;
      void locks.request(name, { ifAvailable: true }, async (lock) => {
        if (!lock) {
          resolve(null);
          return false;
        }
        const held = new Promise<void>((release) => { releaseLock = release; });
        resolve(() => releaseLock?.());
        await held;
        return true;
      }).catch(() => resolve(null));
    });
    while (true) {
      if (Date.now() >= deadline) throw new Error('生成队列繁忙，请稍后重试');
      for (const name of ['morrow:generation-slot:0', 'morrow:generation-slot:1']) {
        const release = await tryAcquire(name);
        if (release) return release;
      }
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
  }
  return acquireLocalGenerationSlot(timeoutMs);
}

export async function withGlobalSchedulerLock<T>(task: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  if (locks?.request) {
    return locks.request('morrow:generation-scheduler', { mode: 'exclusive' }, task);
  }
  const previous = fallbackLock;
  let release!: () => void;
  fallbackLock = new Promise<void>((resolve) => { release = resolve; });
  await previous;
  try {
    return await task();
  } finally {
    release();
  }
}

export interface GenerationQueueStorage {
  list: () => Promise<GenerationQueueItem[]>;
  put: (item: GenerationQueueItem) => Promise<void>;
}

export class GenerationQueue {
  constructor(
    private readonly storage: GenerationQueueStorage,
    private readonly concurrency = 2,
  ) {}

  async claim() {
    return withGlobalSchedulerLock(async () => {
      const items = await this.storage.list();
      const claimed = claimQueueItems(items, { concurrency: this.concurrency });
      await Promise.all(claimed.map((item) => this.storage.put(item)));
      return claimed;
    });
  }

  async complete(id: string, patch: Partial<Pick<GenerationQueueItem, 'jobId' | 'error'>> = {}) {
    return withGlobalSchedulerLock(async () => {
      const item = (await this.storage.list()).find((candidate) => candidate.id === id);
      if (!item) return;
      await this.storage.put({
        ...item,
        ...patch,
        status: 'terminal',
        updatedAt: Date.now(),
        leaseOwner: undefined,
        leaseExpiresAt: undefined,
      });
    });
  }

  async fail(id: string, error: string) {
    return withGlobalSchedulerLock(async () => {
      const item = (await this.storage.list()).find((candidate) => candidate.id === id);
      if (!item) return;
      await this.storage.put({
        ...item,
        status: 'interrupted',
        error,
        updatedAt: Date.now(),
        leaseOwner: undefined,
        leaseExpiresAt: undefined,
      });
    });
  }
}
