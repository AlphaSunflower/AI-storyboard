import { describe, expect, it } from 'vitest';
import { CARD_EDGE, resolveCardAnchor } from './statusCardAnchor';

const viewport = { width: 1424, height: 380 };

describe('resolveCardAnchor', () => {
  it('aligns the left card with the visible bar row, not the full-width wrapper', () => {
    // 真实 /chat：状态栏行居中在 545.75..878.25，外层盒子却是 16..1408
    const anchor = resolveCardAnchor({ left: 545.75, right: 878.25, top: 312 }, viewport);

    expect(anchor.left).toBe(545.75);
    expect(anchor.right).toBe(viewport.width - 878.25);
    expect(anchor.bottom).toBe(380 - 312 + 10);
    expect(anchor.maxHeight).toBe(312 - 10 - 8);
  });

  it('clamps both sides so the card never leaves the viewport', () => {
    // 状态栏过于贴左时，左对齐卡片不能被推到视口外
    expect(resolveCardAnchor({ left: 2, right: 300, top: 300 }, viewport).left).toBe(8);
    // 右对齐卡片同样夹到右留白上限，避免左边缘出屏
    expect(resolveCardAnchor({ left: 2, right: 100, top: 300 }, viewport).right)
      .toBe(Math.max(8, viewport.width - CARD_EDGE));
    // 正常靠右时按实际留白对齐
    expect(resolveCardAnchor({ left: 1180, right: 1400, top: 300 }, viewport).right).toBe(24);
  });

  it('never returns negative bottom or maxHeight for an off-screen bar', () => {
    const anchor = resolveCardAnchor({ left: 100, right: 400, top: 0 }, { width: 800, height: 600 });

    expect(anchor.bottom).toBe(600 + 10);
    expect(anchor.maxHeight).toBe(0);
  });
});
