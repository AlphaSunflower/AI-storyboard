import { describe, expect, it } from 'vitest';
import { isPointerOutsideSidebar, resolveSidebarLeftEdge } from './useAgentAssetDragOutsideSidebar';

const SIDEBAR_WIDTH = 380;

describe('resolveSidebarLeftEdge', () => {
  it('sits flush against the right edge when nothing else is docked', () => {
    expect(resolveSidebarLeftEdge(1440, SIDEBAR_WIDTH, 0)).toBe(1060);
  });

  it('shifts left by the app window reserve', () => {
    // app 子窗口占据右侧时，侧边栏被推到它左边，边界也要跟着左移。
    expect(resolveSidebarLeftEdge(1440, SIDEBAR_WIDTH, 420)).toBe(640);
  });

  it('resolves to a non-positive edge when the viewport is narrower than the sidebar', () => {
    expect(resolveSidebarLeftEdge(300, SIDEBAR_WIDTH, 0)).toBe(-80);
  });
});

describe('isPointerOutsideSidebar', () => {
  const sidebarLeft = 1060;

  it('treats the region left of the sidebar as outside', () => {
    expect(isPointerOutsideSidebar(400, sidebarLeft)).toBe(true);
    expect(isPointerOutsideSidebar(1059, sidebarLeft)).toBe(true);
  });

  it('treats the sidebar itself and everything to its right as inside', () => {
    expect(isPointerOutsideSidebar(1060, sidebarLeft)).toBe(false);
    expect(isPointerOutsideSidebar(1400, sidebarLeft)).toBe(false);
  });
});
