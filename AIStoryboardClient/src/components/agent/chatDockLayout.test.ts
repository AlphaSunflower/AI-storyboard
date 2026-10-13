import { describe, expect, it } from 'vitest';
import {
  CHAT_MESSAGE_RAIL_HYSTERESIS,
  CHAT_MESSAGE_RAIL_MIN_CHAT_WIDTH,
  CHAT_MESSAGE_RAIL_WIDTH,
  CHAT_SIDEBAR_COLLAPSED_WIDTH,
  CHAT_SIDEBAR_MAX_WIDTH,
  CHAT_SIDEBAR_MIN_WIDTH,
  resolveChatSidebarDrag,
  resolveChatColumnWidth,
  resolveChatDockMode,
  resolveChatSidebarCollapseWidth,
  resolveChatSplitMaxWidth,
  resolveExpandedChatWidth,
  resolveViewportSidebarAction,
  shouldRestoreAutoCollapsedSidebar,
  shouldShowChatMessageRail,
} from './chatDockLayout';

describe('resolveChatSidebarDrag', () => {
  it('resizes the sidebar between the min and max widths', () => {
    expect(resolveChatSidebarDrag({ startWidth: 240, deltaX: 40 }))
      .toEqual({ action: 'resize', width: 280 });
    expect(resolveChatSidebarDrag({ startWidth: 240, deltaX: -20 }))
      .toEqual({ action: 'resize', width: 220 });
  });

  it('clamps to the max width while dragging outward', () => {
    expect(resolveChatSidebarDrag({ startWidth: 240, deltaX: 500 }))
      .toEqual({ action: 'resize', width: CHAT_SIDEBAR_MAX_WIDTH });
  });

  it('collapses once dragged narrower than the minimum width', () => {
    expect(resolveChatSidebarDrag({ startWidth: 240, deltaX: -60 }))
      .toEqual({ action: 'resize', width: CHAT_SIDEBAR_MIN_WIDTH });
    expect(resolveChatSidebarDrag({ startWidth: 240, deltaX: -61 }))
      .toEqual({ action: 'collapse' });
    expect(resolveChatSidebarDrag({ startWidth: 240, deltaX: -400 }))
      .toEqual({ action: 'collapse' });
  });
});

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

describe('chat message rail visibility', () => {
  const base = { rounds: 3, mobile: false, embedded: false, docked: false };

  it('shows the rail from 660px of chat area upwards', () => {
    expect(shouldShowChatMessageRail({
      ...base,
      chatColumnWidth: CHAT_MESSAGE_RAIL_MIN_CHAT_WIDTH,
    })).toBe(true);
    expect(shouldShowChatMessageRail({
      ...base,
      chatColumnWidth: CHAT_MESSAGE_RAIL_MIN_CHAT_WIDTH - 1,
    })).toBe(false);
  });

  it('stays out of the way on mobile, embedded canvases, docked canvases and short chats', () => {
    expect(shouldShowChatMessageRail({ ...base, chatColumnWidth: 1200, mobile: true })).toBe(false);
    expect(shouldShowChatMessageRail({ ...base, chatColumnWidth: 1200, embedded: true })).toBe(false);
    expect(shouldShowChatMessageRail({ ...base, chatColumnWidth: 1200, docked: true })).toBe(false);
    expect(shouldShowChatMessageRail({ ...base, chatColumnWidth: 1200, rounds: 1 })).toBe(false);
    expect(shouldShowChatMessageRail({ ...base, chatColumnWidth: 0 })).toBe(false);
  });

  it('measures the same chat column whether or not the rail is mounted', () => {
    // 行边栏挂载会吃掉 44px：换算回不含行边栏的宽度后必须完全一致，
    // 否则阈值附近会"显示→变窄→隐藏→变宽→显示"自激抖动。
    const railFree = resolveChatColumnWidth({ chatMainWidth: 680, railMounted: false });
    const railMounted = resolveChatColumnWidth({
      chatMainWidth: 680 - CHAT_MESSAGE_RAIL_WIDTH,
      railMounted: true,
    });

    expect(railMounted).toBe(railFree);
    expect(shouldShowChatMessageRail({ ...base, chatColumnWidth: railFree }))
      .toBe(shouldShowChatMessageRail({ ...base, chatColumnWidth: railMounted }));
  });

  it('keeps a dead band so a 1px measurement wobble cannot flip it back and forth', () => {
    const edge = CHAT_MESSAGE_RAIL_MIN_CHAT_WIDTH - 1;

    // 未显示时不到 660 就不显示；已经显示时允许掉到 660 - 滞回带以内再隐藏
    expect(shouldShowChatMessageRail({ ...base, chatColumnWidth: edge })).toBe(false);
    expect(shouldShowChatMessageRail({ ...base, chatColumnWidth: edge, currentlyVisible: true })).toBe(true);
    expect(shouldShowChatMessageRail({
      ...base,
      chatColumnWidth: CHAT_MESSAGE_RAIL_MIN_CHAT_WIDTH - CHAT_MESSAGE_RAIL_HYSTERESIS - 1,
      currentlyVisible: true,
    })).toBe(false);
  });
});

describe('viewport driven sidebar collapse', () => {
  const sidebarWidth = 240;

  it('maps the current chat width back to its expanded width', () => {
    expect(resolveExpandedChatWidth({ chatMainWidth: 892, collapsed: true, sidebarWidth })).toBe(700);
    expect(resolveExpandedChatWidth({ chatMainWidth: 700, collapsed: false, sidebarWidth })).toBe(700);
  });

  it('collapses the sidebar before the rail would have to disappear', () => {
    // 展开态聊天区 700 < 720 → 收起；收起后聊天区 892，行边栏（≥660）还在
    expect(resolveViewportSidebarAction({ chatMainWidth: 700, collapsed: false, sidebarWidth })).toBe('collapse');
    expect(shouldShowChatMessageRail({
      chatColumnWidth: 892,
      rounds: 3,
      mobile: false,
      embedded: false,
      docked: false,
    })).toBe(true);
  });

  it('does not oscillate right after collapsing', () => {
    // 收起后按展开宽度重算仍是 700，既不满足还原（784）也不会再触发收起
    expect(resolveViewportSidebarAction({ chatMainWidth: 892, collapsed: true, sidebarWidth })).toBe('none');
    expect(resolveViewportSidebarAction({ chatMainWidth: 560, collapsed: true, sidebarWidth })).toBe('none');
  });

  it('restores the sidebar only once the expanded width clears the hysteresis', () => {
    // 收起态 976 → 展开态 784（720 + 64）→ 还原；975 → 783 仍留在滞回区内
    expect(resolveViewportSidebarAction({ chatMainWidth: 976, collapsed: true, sidebarWidth })).toBe('restore');
    expect(resolveViewportSidebarAction({ chatMainWidth: 975, collapsed: true, sidebarWidth })).toBe('none');
    expect(resolveViewportSidebarAction({ chatMainWidth: 900, collapsed: false, sidebarWidth })).toBe('none');
  });
});

// 两个面板都会反过来改变自己的测量值，必须在"渲染即重算"的反馈回路里收敛，
// 否则缩到阈值上就会 Maximum update depth exceeded（页面直接白屏）。
describe('panel decisions settle inside their own feedback loop', () => {
  const sidebarWidth = 240;
  const sidebarDelta = sidebarWidth - CHAT_SIDEBAR_COLLAPSED_WIDTH;

  it('settles the rail within one flip at every width', () => {
    for (let trueChatWidth = 560; trueChatWidth <= 1100; trueChatWidth += 1) {
      let mounted = false;
      let flips = 0;
      for (let step = 0; step < 6; step += 1) {
        const measured = trueChatWidth - (mounted ? CHAT_MESSAGE_RAIL_WIDTH : 0);
        const chatColumnWidth = resolveChatColumnWidth({ chatMainWidth: measured, railMounted: mounted });
        const next = shouldShowChatMessageRail({
          chatColumnWidth,
          rounds: 3,
          mobile: false,
          embedded: false,
          docked: false,
          currentlyVisible: mounted,
        });
        if (next === mounted) break;
        mounted = next;
        flips += 1;
      }
      expect(flips, `chat width ${trueChatWidth}`).toBeLessThanOrEqual(1);
    }
  });

  it('settles the sidebar within one flip at every width', () => {
    for (let trueChatWidth = 500; trueChatWidth <= 1500; trueChatWidth += 1) {
      let collapsed = false;
      let autoCollapsed = false;
      let flips = 0;
      for (let step = 0; step < 6; step += 1) {
        const measured = trueChatWidth + (collapsed ? sidebarDelta : 0);
        const action = resolveViewportSidebarAction({ chatMainWidth: measured, collapsed, sidebarWidth });
        let nextCollapsed: boolean = collapsed;
        if (action === 'collapse') {
          nextCollapsed = true;
          autoCollapsed = true;
        } else if (action === 'restore' && autoCollapsed) {
          nextCollapsed = false;
          autoCollapsed = false;
        }
        if (nextCollapsed === collapsed) break;
        collapsed = nextCollapsed;
        flips += 1;
      }
      expect(flips, `chat width ${trueChatWidth}`).toBeLessThanOrEqual(1);
    }
  });
});
