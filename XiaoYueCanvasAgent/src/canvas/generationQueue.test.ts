import { describe, expect, it, vi } from 'vitest';
import { acquireGenerationSlot, acquireLocalGenerationSlot, claimQueueItems, recoveryActionForQueueItem, type GenerationQueueItem } from './generationQueue';

const item = (id: string, overrides: Partial<GenerationQueueItem> = {}): GenerationQueueItem => ({
  id,
  projectId: 'project-1',
  canvasId: 'canvas-1',
  producerNodeId: 'node-1',
  generationRecordId: `record-${id}`,
  requestSnapshot: { capabilityId: 'image.generate', sourceAssetIds: [], params: {} },
  status: 'pending_submit',
  attempt: 1,
  createdAt: 0,
  updatedAt: 0,
  ...overrides,
});

describe('claimQueueItems', () => {
  it('claims at most the remaining global concurrency', () => {
    const values = [item('a'), item('b'), item('c')];
    const claimed = claimQueueItems(values, { now: 100, concurrency: 2 });

    expect(claimed.map((entry) => entry.id)).toEqual(['a', 'b']);
    expect(claimed.every((entry) => entry.status === 'claimed')).toBe(true);
  });

  it('accounts for another tab running valid leases', () => {
    const values = [
      item('a', { status: 'running', leaseOwner: 'other-tab', leaseExpiresAt: 500 }),
      item('b'),
    ];
    const claimed = claimQueueItems(values, { now: 100, concurrency: 2 });

    expect(claimed.map((entry) => entry.id)).toEqual(['b']);
  });

  it('does not auto-run an expired running item', () => {
    const values = [item('a', { status: 'running', leaseOwner: 'dead-tab', leaseExpiresAt: 50 })];
    const claimed = claimQueueItems(values, { now: 100, concurrency: 2 });

    expect(claimed).toEqual([]);
  });

  it('never claims cancelled or terminal items', () => {
    const values = [
      item('a', { status: 'terminal' }),
      item('b', { status: 'cancelled' }),
      item('c'),
    ];
    expect(claimQueueItems(values, { now: 100, concurrency: 2 }).map((entry) => entry.id)).toEqual(['c']);
  });
});

describe('acquireLocalGenerationSlot', () => {
  it('holds at most two execution slots', async () => {
    const releaseFirst = await acquireLocalGenerationSlot();
    const releaseSecond = await acquireLocalGenerationSlot();
    let thirdAcquired = false;
    const third = acquireLocalGenerationSlot().then((release) => {
      thirdAcquired = true;
      return release;
    });

    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(thirdAcquired).toBe(false);
    releaseFirst();
    const releaseThird = await third;
    expect(thirdAcquired).toBe(true);
    releaseSecond();
    releaseThird();
  });

  it('times out instead of waiting forever when all slots are occupied', async () => {
    vi.useFakeTimers();
    const releaseFirst = await acquireLocalGenerationSlot();
    const releaseSecond = await acquireLocalGenerationSlot();
    const pending = acquireLocalGenerationSlot(100);
    const outcome = pending.then(() => 'acquired', () => 'rejected');
    await vi.advanceTimersByTimeAsync(100);
    const result = await outcome;
    expect(result).toBe('rejected');
    releaseFirst();
    releaseSecond();
    vi.useRealTimers();
  });
});

describe('acquireGenerationSlot', () => {
  it('returns a releaser without waiting for the Web Lock callback to finish', async () => {
    const held = new Set<string>();
    const locks = {
      request: vi.fn(async (name: string, options: { ifAvailable?: boolean }, callback: (lock: { name: string } | null) => Promise<boolean>) => {
        if (held.has(name) && options.ifAvailable) return callback(null);
        held.add(name);
        try {
          return await callback({ name });
        } finally {
          held.delete(name);
        }
      }),
    };
    const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks } });
    try {
      const pending = acquireGenerationSlot(200);
      const result = await Promise.race([
        pending.then((release) => {
          release();
          return 'acquired';
        }, () => 'rejected'),
        new Promise((resolve) => setTimeout(() => resolve('deadlocked'), 20)),
      ]);
      expect(result).toBe('acquired');
    } finally {
      if (originalNavigator) Object.defineProperty(globalThis, 'navigator', originalNavigator);
      else delete (globalThis as { navigator?: unknown }).navigator;
    }
  });
});

describe('recoveryActionForQueueItem', () => {
  it('marks unsubmitted work interrupted and resumes jobs with a durable job id', () => {
    expect(recoveryActionForQueueItem(item('pending'))).toBe('interrupted');
    expect(recoveryActionForQueueItem(item('running', { jobId: 'job-1', status: 'running' }))).toBe('resume');
    expect(recoveryActionForQueueItem(item('running-null', { status: 'running' }))).toBe('interrupted');
    expect(recoveryActionForQueueItem(item('done', { status: 'terminal' }))).toBe('ignore');
  });
});
