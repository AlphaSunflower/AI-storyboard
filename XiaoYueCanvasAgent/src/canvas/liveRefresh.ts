/**
 * 画布「拉取服务端更新」的决策（纯函数，便于单测）。
 *
 * <h3>为什么把它抽出来</h3>
 * 2026-10-16 的用户报告：「前端画布依旧要等我刷新才能出画布数据」，而且**只发生在单独打开的画布窗口**
 * ——那个窗口没有 postMessage 通道（`App.tsx` 的 message 分支只在内嵌模式生效），**4s revision
 * 轮询是唯一的实时通道**。所以任何"让路条件"一旦卡住，画布就彻底不更新，只能刷新。
 *
 * <p>原来卡住有两条路，都已修掉：
 * <ol>
 *   <li><b>保存失败留下未落库改动</b>（`hasLocalChanges`）原本是"永久让路"——只靠"保存成功"清零，
 *       而用户没在编辑就不会再触发保存 → 死锁。现在改为<b>主动重试保存</b>，并在重试预算用尽后
 *       <b>放弃保护本地改动、重载远端</b>（附明确提示），不再无限期停摆。</li>
 *   <li>「正在保存」原本看 `saveStatus === 'saving'` 这个<b>状态字符串</b>——它只在画布 id 未变时
 *       才会被置回 `'saved'`，期间换过画布就永远停在 `saving`。现在改为看<b>是否真的有保存在飞</b>
 *       （`savingInFlight`，由 `saveInFlight` ref 提供），保存一结束轮询立刻恢复。</li>
 * </ol>
 */
export type LiveRefreshDecision =
  | { action: 'skip'; reason: 'hidden' | 'in-flight' | 'debounce' }
  | { action: 'retry-save'; reason: 'local-changes' }
  | { action: 'reload-after-conflict'; reason: 'retries-exhausted' }
  | { action: 'check' };

/** 连续重试保存的上限：约 3 个轮询周期（~12s）。用尽后优先显示智能体的数据。 */
export const MAX_SAVE_RETRIES = 3;

export interface LiveRefreshInput {
  /** 页面是否隐藏（后台标签页不做网络请求）。 */
  hidden: boolean;
  /** 是否真的有保存在飞（看 ref，不看状态字符串）。 */
  savingInFlight: boolean;
  /** 550ms 防抖窗口里是否还有排队中的保存。 */
  hasPendingDebounce: boolean;
  /** 是否存在上次保存失败留下的未落库本地改动。 */
  hasLocalChanges: boolean;
  /** 已经连续重试了几次保存（由调用方累计）。 */
  saveRetryCount?: number;
}

export function decideLiveRefresh(input: LiveRefreshInput): LiveRefreshDecision {
  if (input.hidden) return { action: 'skip', reason: 'hidden' };
  if (input.savingInFlight) return { action: 'skip', reason: 'in-flight' };
  if (input.hasPendingDebounce) return { action: 'skip', reason: 'debounce' };
  if (input.hasLocalChanges) {
    return (input.saveRetryCount ?? 0) >= MAX_SAVE_RETRIES
      ? { action: 'reload-after-conflict', reason: 'retries-exhausted' }
      : { action: 'retry-save', reason: 'local-changes' };
  }
  return { action: 'check' };
}
