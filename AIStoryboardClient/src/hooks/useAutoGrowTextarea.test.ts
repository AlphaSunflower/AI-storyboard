import { describe, expect, it } from 'vitest';
import { autoGrowTextareaMetrics } from './useAutoGrowTextarea';

describe('autoGrowTextareaMetrics', () => {
  it('keeps the textarea at two lines while content is short', () => {
    const metrics = autoGrowTextareaMetrics(20, 24, 20, 2, 240);

    expect(metrics.height).toBe(70);
    expect(metrics.overflowY).toBe('hidden');
  });

  it('grows before enabling the scrollbar', () => {
    const metrics = autoGrowTextareaMetrics(140, 24, 20, 2, 240);

    expect(metrics.height).toBe(140);
    expect(metrics.overflowY).toBe('hidden');
  });

  it('stops at max height and enables the scrollbar', () => {
    const metrics = autoGrowTextareaMetrics(500, 24, 20, 2, 240);

    expect(metrics.height).toBe(240);
    expect(metrics.overflowY).toBe('auto');
  });
});
