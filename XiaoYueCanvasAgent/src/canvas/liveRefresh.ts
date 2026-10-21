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
  | { action: 'skip'; reason: 'hidden' | 'dragging' | 'executing' }
  | { action: 'retry-save'; reason: 'local-changes' }
  | { action: 'reload-after-conflict'; reason: 'retries-exhausted' }
  /**
   * 去读服务端 revision。`deferReload` = 本地还有保存在飞/排队：
   * 仍然允许检查（只读、很便宜），但这一轮先不要整图重载，免得盖掉正在提交的本地改动。
   */
  | { action: 'check'; deferReload?: boolean };

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
  /** 正在拖动节点：整图重载会把节点位置打回服务端旧值，必须等松手落库。 */
  dragging?: boolean;
  /** 本地有正在跑的执行批次：整图重载会替换 executions，直接杀掉批次。 */
  executing?: boolean;
}

export function decideLiveRefresh(input: LiveRefreshInput): LiveRefreshDecision {
  if (input.hidden) return { action: 'skip', reason: 'hidden' };
  // 拖动中整图重载 = 节点被拽回原位（用户视角就是「突然弹回去」）。松手后 endNodeDrag 会
  // commit 位置并触发保存，下一轮 tick 自然恢复检查。
  if (input.dragging) return { action: 'skip', reason: 'dragging' };
  // 批次在跑时不整图重载：executions 是本地内存态，重载会把它换成服务端旧快照，批次直接消失。
  if (input.executing) return { action: 'skip', reason: 'executing' };
  // 2026-10-19 回归：以前「有保存在飞 / 防抖窗口」直接 skip，连 revision 都不读。
  // 生图/生视频期间节点进度每 1.5s 就 commit 一次 → 保存几乎从不空闲 →
  // 智能体在服务端建的节点一直看不到，只能手动刷新。
  // 现在只把「整图重载」推迟到保存落地，revision 检查照做（只读 GET，代价可忽略）。
  if (input.savingInFlight || input.hasPendingDebounce) return { action: 'check', deferReload: true };
  if (input.hasLocalChanges) {
    return (input.saveRetryCount ?? 0) >= MAX_SAVE_RETRIES
      ? { action: 'reload-after-conflict', reason: 'retries-exhausted' }
      : { action: 'retry-save', reason: 'local-changes' };
  }
  return { action: 'check' };
}
