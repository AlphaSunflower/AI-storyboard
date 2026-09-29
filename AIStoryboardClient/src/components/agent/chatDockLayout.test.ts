import { describe, expect, it } from 'vitest';
import {
  resolveChatDockMode,
  resolveChatSidebarCollapseWidth,
  resolveChatSplitMaxWidth,
  shouldRestoreAutoCollapsedSidebar,
} from './chatDockLayout';

const base = {
  previousMode: 'split' as const,
  embedded: false,
  appOpen: true,
  mobile: false,
  fullscreen: false,
  viewportWidth: 1440,
  appWidth: 420,
  sidebarWidth: 240,
  requestedDock: false,
};

describe('chat dock layout', () => {
  it('keeps the split layout while the chat area is comfortably wide', () => {
    expect(resolveChatDockMode(base)).toBe('split');
  });

  it('docks when the remaining chat width drops below 560px', () => {
    expect(resolveChatDockMode({ ...base, appWidth: 640 })).toBe('docked');
  });

  it('keeps docked mode between the enter and exit thresholds', () => {
    expect(resolveChatDockMode({
      ...base,
      previousMode: 'docked',
      appWidth: 620,
    })).toBe('docked');
  });

  it('restores the split layout once 640px is available again', () => {
    expect(resolveChatDockMode({
      ...base,
      previousMode: 'docked',
      appWidth: 554,
    })).toBe('split');
  });

  it('honours an explicit dock request', () => {
    expect(resolveChatDockMode({ ...base, requestedDock: true })).toBe('docked');
  });

  it('uses docked mode for a fullscreen app', () => {
    expect(resolveChatDockMode({ ...base, fullscreen: true })).toBe('docked');
  });

  it('does not dock on mobile or when no app is open', () => {
    expect(resolveChatDockMode({ ...base, mobile: true, appWidth: 640 })).toBe('split');
    expect(resolveChatDockMode({ ...base, appOpen: false, appWidth: 640 })).toBe('split');
  });

  it('always keeps embedded canvas agents in split layout', () => {
    expect(resolveChatDockMode({
      ...base,
      embedded: true,
      appWidth: 900,
      requestedDock: true,
      fullscreen: true,
    })).toBe('split');
  });
});

describe('chat split max width', () => {
  it('leaves 560px for chat on wide viewports', () => {
    expect(resolveChatSplitMaxWidth({
      viewportWidth: 1920,
      sidebarWidth: 240,
    })).toBe(1114);
  });

  it('leaves 560px for chat before requesting docked mode', () => {
    expect(resolveChatSplitMaxWidth({
      viewportWidth: 1440,
      sidebarWidth: 240,
    })).toBe(634);
  });

  it('never shrinks the app below its minimum width', () => {
    expect(resolveChatSplitMaxWidth({
      viewportWidth: 900,
      sidebarWidth: 240,
    })).toBe(360);
  });
});

describe('chat sidebar resize sequence', () => {
  it('collapses the sidebar before the remaining chat area reaches the dock threshold', () => {
    expect(resolveChatSidebarCollapseWidth({
      viewportWidth: 1458,
      sidebarWidth: 240,
    })).toBe(492);
  });

  it('never requests sidebar collapse before the app reaches its minimum width', () => {
    expect(resolveChatSidebarCollapseWidth({
      viewportWidth: 900,
      sidebarWidth: 240,
    })).toBe(360);
  });

  it('restores an auto-collapsed sidebar only after the app shrinks past the hysteresis', () => {
    expect(shouldRestoreAutoCollapsedSidebar({
      autoCollapsed: true,
      appOpen: true,
      docked: false,
      appWidth: 429,
      collapseAtWidth: 492,
    })).toBe(false);
    expect(shouldRestoreAutoCollapsedSidebar({
      autoCollapsed: true,
      appOpen: true,
      docked: false,
      appWidth: 428,
      collapseAtWidth: 492,
    })).toBe(true);
  });
});
