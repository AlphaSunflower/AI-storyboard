export type ChatDockMode = 'split' | 'docked';

export const CHAT_DOCK_ENTER_MIN_WIDTH = 560;
export const CHAT_DOCK_EXIT_MIN_WIDTH = 640;
export const CHAT_DOCK_APP_GUTTER = 6;
export const CHAT_DOCK_MIN_APP_WIDTH = 360;
export const CHAT_DOCK_MAX_SPLIT_APP_WIDTH = 1120;
export const CHAT_SIDEBAR_SPLIT_MIN_WIDTH = 720;
export const CHAT_SIDEBAR_RESTORE_HYSTERESIS = 64;
/** 会话栏收起后的图标 rail 宽度 */
export const CHAT_SIDEBAR_COLLAPSED_WIDTH = 48;
/** 消息导航行边栏自身占用的独立窄列宽度（与 ChatMessageRail 的 --rail-width 同源） */
export const CHAT_MESSAGE_RAIL_WIDTH = 44;
/** 低于该宽度就不显示消息导航行边栏（行边栏占 44px 独立窄列） */
export const CHAT_MESSAGE_RAIL_MIN_CHAT_WIDTH = 660;
/** 行边栏已显示时的滞回带：避免测量取整让阈值两侧互相抖动 */
export const CHAT_MESSAGE_RAIL_HYSTERESIS = 8;

interface ResolveChatDockModeInput {
  previousMode: ChatDockMode;
  embedded: boolean;
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
  embedded,
  appOpen,
  mobile,
  fullscreen,
  viewportWidth,
  appWidth,
  sidebarWidth,
  requestedDock,
}: ResolveChatDockModeInput): ChatDockMode {
  if (embedded || !appOpen || mobile) return 'split';
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

/** 把当前聊天区宽度换算回"侧栏未收起"时的宽度 */
export function resolveExpandedChatWidth({
  chatMainWidth,
  collapsed,
  sidebarWidth,
}: {
  chatMainWidth: number;
  collapsed: boolean;
  sidebarWidth: number;
}): number {
  if (!collapsed) return chatMainWidth;
  return chatMainWidth - Math.max(0, sidebarWidth - CHAT_SIDEBAR_COLLAPSED_WIDTH);
}

/**
 * 没有子窗口吃掉宽度时（无子窗口 / docked 浮窗），会话侧栏是否该为"聊天区太窄"自动收起。
 * 判定统一换算成"侧栏未收起时的聊天区宽度"，避免收起后立刻满足还原条件造成自激抖动。
 */
export function resolveViewportSidebarAction({
  chatMainWidth,
  collapsed,
  sidebarWidth,
}: {
  chatMainWidth: number;
  collapsed: boolean;
  sidebarWidth: number;
}): 'collapse' | 'restore' | 'none' {
  if (!Number.isFinite(chatMainWidth) || chatMainWidth <= 0) return 'none';

  const expandedChatWidth = resolveExpandedChatWidth({ chatMainWidth, collapsed, sidebarWidth });

  if (expandedChatWidth < CHAT_SIDEBAR_SPLIT_MIN_WIDTH) {
    return collapsed ? 'none' : 'collapse';
  }
  if (expandedChatWidth >= CHAT_SIDEBAR_SPLIT_MIN_WIDTH + CHAT_SIDEBAR_RESTORE_HYSTERESIS) {
    return collapsed ? 'restore' : 'none';
  }
  return 'none';
}

/**
 * 消息导航行边栏的显示条件：桌面正常窗口 + 至少两轮 + 聊天区还够宽。
 *
 * `chatColumnWidth` 必须是**不含行边栏自身**的聊天区宽度（行边栏可见时把
 * CHAT_MESSAGE_RAIL_WIDTH 加回去）。否则行边栏显隐本身会改变这个宽度，
 * 卡在阈值上就会"显示→变窄→隐藏→变宽→显示"无限抖动。
 */
export function resolveChatColumnWidth({
  chatMainWidth,
  railMounted,
}: {
  /** 实测的聊天区宽度（行边栏可见时已被它占掉 44px） */
  chatMainWidth: number;
  /** 当前这一帧是否渲染了行边栏窄列 */
  railMounted: boolean;
}): number {
  if (!Number.isFinite(chatMainWidth)) return chatMainWidth;
  return chatMainWidth + (railMounted ? CHAT_MESSAGE_RAIL_WIDTH : 0);
}

export function shouldShowChatMessageRail({
  chatColumnWidth,
  rounds,
  mobile,
  embedded,
  docked,
  currentlyVisible = false,
}: {
  chatColumnWidth: number;
  rounds: number;
  mobile: boolean;
  embedded: boolean;
  docked: boolean;
  /** 上一帧是否已经显示，用于滞回：已显示时允许略窄再隐藏 */
  currentlyVisible?: boolean;
}): boolean {
  if (mobile || embedded || docked) return false;
  if (rounds < 2) return false;
  if (!Number.isFinite(chatColumnWidth)) return false;
  const minWidth = currentlyVisible
    ? CHAT_MESSAGE_RAIL_MIN_CHAT_WIDTH - CHAT_MESSAGE_RAIL_HYSTERESIS
    : CHAT_MESSAGE_RAIL_MIN_CHAT_WIDTH;
  return chatColumnWidth >= minWidth;
}
