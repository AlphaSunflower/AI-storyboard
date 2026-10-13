import { describe, expect, it } from 'vitest';
import {
  chatScrollSnapshot,
  distanceToBottom,
  isComposerCollapsed,
} from './chatScrollState';

describe('chatScrollSnapshot', () => {
  it('treats the bottom as near-bottom and keeps the full composer', () => {
    expect(chatScrollSnapshot(0)).toEqual({ nearBottom: true, awayFromBottom: false });
    expect(chatScrollSnapshot(63)).toEqual({ nearBottom: true, awayFromBottom: false });
  });

  it('switches to the compact composer as soon as the thread leaves the bottom', () => {
    expect(chatScrollSnapshot(64)).toEqual({ nearBottom: false, awayFromBottom: true });
    expect(chatScrollSnapshot(400)).toEqual({ nearBottom: false, awayFromBottom: true });
  });

  it('falls back to the bottom state for non-finite input', () => {
    expect(chatScrollSnapshot(Number.NaN).nearBottom).toBe(true);
  });
});

describe('distanceToBottom', () => {
  it('measures the remaining scroll distance', () => {
    expect(distanceToBottom({ scrollHeight: 1000, scrollTop: 400, clientHeight: 300 })).toBe(300);
  });
});

describe('isComposerCollapsed', () => {
  it('stays expanded while the input is focused', () => {
    expect(isComposerCollapsed(true, true)).toBe(false);
    expect(isComposerCollapsed(true, false)).toBe(true);
    expect(isComposerCollapsed(false, false)).toBe(false);
  });
});
