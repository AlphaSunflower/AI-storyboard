import { describe, expect, it } from 'vitest';
import { shouldShowFirstRunHero } from './sideChatFrame';

describe('side chat frame state', () => {
  it('does not show the first-run hero inside a temporary inherited branch', () => {
    expect(shouldShowFirstRunHero(true, 0, false, false, false)).toBe(false);
  });

  it('keeps the first-run hero in a genuinely empty main conversation', () => {
    expect(shouldShowFirstRunHero(false, 0, false, false, false)).toBe(true);
  });
});
