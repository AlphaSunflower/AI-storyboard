export type ChatDockMode = 'split' | 'docked';

export const CHAT_DOCK_ENTER_MIN_WIDTH = 560;
export const CHAT_DOCK_EXIT_MIN_WIDTH = 640;
export const CHAT_DOCK_APP_GUTTER = 6;
export const CHAT_DOCK_MIN_APP_WIDTH = 360;
export const CHAT_DOCK_MAX_SPLIT_APP_WIDTH = 1120;
export const CHAT_SIDEBAR_SPLIT_MIN_WIDTH = 720;
export const CHAT_SIDEBAR_RESTORE_HYSTERESIS = 64;

interface ResolveChatDockModeInput {
  previousMode: ChatDockMode;
  appOpen: boolean;
  mobile: boolean;
  fullscreen: boolean;
  viewportWidth: number;
  appWidth: number;
  sidebarWidth: number;
  requestedDock: boolean;
}

export function resolveChatDockMode({
  previousMode,
  appOpen,
  mobile,
  fullscreen,
  viewportWidth,
  appWidth,
  sidebarWidth,
  requestedDock,
}: ResolveChatDockModeInput): ChatDockMode {
  if (!appOpen || mobile) return 'split';
  if (fullscreen) return 'docked';

  const availableChatWidth = viewportWidth - sidebarWidth - CHAT_DOCK_APP_GUTTER - appWidth;
  if (previousMode === 'docked') {
    return availableChatWidth >= CHAT_DOCK_EXIT_MIN_WIDTH ? 'split' : 'docked';
  }

  return requestedDock || availableChatWidth < CHAT_DOCK_ENTER_MIN_WIDTH
    ? 'docked'
    : 'split';
}

export function resolveChatSplitMaxWidth({
  viewportWidth,
  sidebarWidth,
}: {
  viewportWidth: number;
  sidebarWidth: number;
}): number {
  const widthBeforeDock = viewportWidth
    - sidebarWidth
    - CHAT_DOCK_APP_GUTTER
    - CHAT_DOCK_ENTER_MIN_WIDTH;

  return Math.max(
    CHAT_DOCK_MIN_APP_WIDTH,
    Math.min(CHAT_DOCK_MAX_SPLIT_APP_WIDTH, widthBeforeDock),
  );
}

export function resolveChatSidebarCollapseWidth({
  viewportWidth,
  sidebarWidth,
}: {
  viewportWidth: number;
  sidebarWidth: number;
}): number {
  const widthBeforeCollapse = viewportWidth
    - sidebarWidth
    - CHAT_DOCK_APP_GUTTER
    - CHAT_SIDEBAR_SPLIT_MIN_WIDTH;

  return Math.max(CHAT_DOCK_MIN_APP_WIDTH, widthBeforeCollapse);
}

export function shouldRestoreAutoCollapsedSidebar({
  autoCollapsed,
  appOpen,
  docked,
  appWidth,
  collapseAtWidth,
}: {
  autoCollapsed: boolean;
  appOpen: boolean;
  docked: boolean;
  appWidth: number;
  collapseAtWidth: number;
}): boolean {
  if (!autoCollapsed) return false;
  if (!appOpen) return true;
  return !docked
    && appWidth <= collapseAtWidth - CHAT_SIDEBAR_RESTORE_HYSTERESIS;
}
