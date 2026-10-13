import { describe, expect, it } from 'vitest';
import { resolveAbovePopover } from './abovePopover';

const viewport = { width: 1440, height: 900 };

describe('resolveAbovePopover', () => {
  it('opens above the anchor and keeps the fixed width for left-aligned popovers', () => {
    const placement = resolveAbovePopover(
      { left: 468, right: 754, top: 830, width: 286 },
      viewport,
      { gap: 10, width: 286 },
    );

    expect(placement.left).toBe(468);
    expect(placement.bottom).toBe(900 - 830 + 10);
    expect(placement.width).toBe(286);
    expect(placement.maxHeight).toBe(830 - 10 - 8);
  });

  it('stretches to the anchor width for the slash menu', () => {
    const placement = resolveAbovePopover(
      { left: 453, right: 1233, top: 437, width: 780 },
      viewport,
      { gap: 8, align: 'stretch' },
    );

    expect(placement.width).toBe(780);
    expect(placement.left).toBe(453);
    expect(placement.bottom).toBe(900 - 437 + 8);
  });

  it('clamps the popover inside the viewport on both sides', () => {
    expect(
      resolveAbovePopover({ left: -20, right: 266, top: 400, width: 286 }, viewport, { gap: 10, width: 286 }).left,
    ).toBe(8);
    expect(
      resolveAbovePopover({ left: 1400, right: 1686, top: 400, width: 286 }, viewport, { gap: 10, width: 286 }).left,
    ).toBe(1440 - 286 - 8);
  });

  it('caps the stretched width to the viewport and never returns a negative height', () => {
    const narrow = resolveAbovePopover(
      { left: 0, right: 2000, top: 0, width: 2000 },
      { width: 800, height: 600 },
      { gap: 8, align: 'stretch' },
    );

    expect(narrow.width).toBe(800 - 16);
    expect(narrow.maxHeight).toBe(0);
    expect(narrow.bottom).toBe(600 + 8);
  });
});
