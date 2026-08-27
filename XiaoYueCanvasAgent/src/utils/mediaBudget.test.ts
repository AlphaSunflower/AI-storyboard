import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mediaBudgetSnapshot, registerMediaCandidate, setMediaBudgetQuality } from './mediaBudget';

describe('media budget', () => {
  beforeEach(() => {
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { callback(0); return 1; });
  });

  it('bounds thumbnails and always keeps a selected candidate active', () => {
    setMediaBudgetQuality('thumbnail');
    const states = new Map<string, boolean>();
    const cleanups = Array.from({ length: 120 }, (_, index) => registerMediaCandidate({
      id: `node-${index}`,
      selected: index === 119,
      visible: true,
      distance: index,
      measureDistance: () => index,
      listener: (active) => states.set(`node-${index}`, active),
    }));
    expect(mediaBudgetSnapshot().active).toBeLessThanOrEqual(97);
    expect(states.get('node-119')).toBe(true);
    cleanups.forEach((cleanup) => cleanup());
  });

  it('activates no unselected media in placeholder quality', () => {
    setMediaBudgetQuality('placeholder');
    const listener = vi.fn();
    const cleanup = registerMediaCandidate({ id: 'placeholder', selected: false, visible: true, distance: 0, measureDistance: () => 0, listener });
    expect(mediaBudgetSnapshot().active).toBe(0);
    cleanup();
  });
});
