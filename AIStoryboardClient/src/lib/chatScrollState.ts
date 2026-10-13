/**
 * /chat 消息区滚动派生状态：向下按钮的显隐与输入卡收缩。
 *
 * 输入卡常驻绝对定位覆盖在消息区之上、消息列留固定底部内边距，
 * 因此收缩前后消息区几何完全不变——阈值可以贴着底部取，滚动一离开底部就收缩，不会来回抖。
 */

/** 贴底判定阈值：小于该距离视为「在底部」（自动滚底、还原完整输入卡都看它） */
export const NEAR_BOTTOM_DISTANCE = 64;

export interface ChatScrollSnapshot {
  /** 是否贴着底部（新消息自动跟随） */
  nearBottom: boolean;
  /** 是否离开底部：显示「回到底部」并把输入卡收缩成一行 */
  awayFromBottom: boolean;
}

export function chatScrollSnapshot(distanceToBottom: number): ChatScrollSnapshot {
  const distance = Number.isFinite(distanceToBottom) ? distanceToBottom : 0;
  const awayFromBottom = distance >= NEAR_BOTTOM_DISTANCE;
  return {
    nearBottom: !awayFromBottom,
    awayFromBottom,
  };
}

export function distanceToBottom(el: { scrollHeight: number; scrollTop: number; clientHeight: number }): number {
  return el.scrollHeight - el.scrollTop - el.clientHeight;
}

export function isComposerCollapsed(compact: boolean, focused: boolean): boolean {
  return compact && !focused;
}
