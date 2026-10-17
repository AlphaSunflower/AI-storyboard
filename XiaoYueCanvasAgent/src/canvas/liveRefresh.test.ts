// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { MAX_SAVE_RETRIES, decideLiveRefresh } from './liveRefresh';

/**
 * 这些断言守的是 2026-10-16 那次回归：**单独打开的画布窗口只有 4s 轮询这一条实时通道**，
 * 任何让路条件一旦卡住，画布就再也不更新（"必须刷新才出数据"）。所以：
 * ①「有未落库改动」必须是重试保存，不是永久让路；②重试预算用尽后必须重载，不能无限停摆；
 * ③「正在保存」看是否真有保存在飞，而不是可能卡死的状态字符串。
 */
describe('decideLiveRefresh', () => {
  const base = {
    hidden: false,
    savingInFlight: false,
    hasPendingDebounce: false,
    hasLocalChanges: false,
  };

  it('无未落库改动时去读服务端 revision', () => {
    expect(decideLiveRefresh(base)).toEqual({ action: 'check' });
  });

  it('保存失败留下未落库改动时重试保存，而不是永久让路', () => {
    expect(decideLiveRefresh({ ...base, hasLocalChanges: true }))
      .toEqual({ action: 'retry-save', reason: 'local-changes' });
  });

  it('重试预算未用尽仍继续重试', () => {
    expect(decideLiveRefresh({ ...base, hasLocalChanges: true, saveRetryCount: MAX_SAVE_RETRIES - 1 }))
      .toEqual({ action: 'retry-save', reason: 'local-changes' });
  });

  it('重试预算用尽后放弃保护本地改动、改为重载远端（避免无限停摆）', () => {
    expect(decideLiveRefresh({ ...base, hasLocalChanges: true, saveRetryCount: MAX_SAVE_RETRIES }))
      .toEqual({ action: 'reload-after-conflict', reason: 'retries-exhausted' });
  });

  it('有保存在飞时让路（避免用服务端图盖掉即将提交的编辑）', () => {
    expect(decideLiveRefresh({ ...base, savingInFlight: true }))
      .toEqual({ action: 'skip', reason: 'in-flight' });
  });

  it('防抖窗口内有编辑时让路', () => {
    expect(decideLiveRefresh({ ...base, hasPendingDebounce: true }))
      .toEqual({ action: 'skip', reason: 'debounce' });
  });

  it('页面隐藏时什么都不做', () => {
    expect(decideLiveRefresh({ ...base, hidden: true }))
      .toEqual({ action: 'skip', reason: 'hidden' });
  });

  it('隐藏优先于重试保存（后台标签页不发请求）', () => {
    expect(decideLiveRefresh({ ...base, hidden: true, hasLocalChanges: true }))
      .toEqual({ action: 'skip', reason: 'hidden' });
  });

  it('保存进行中优先于未落库改动（先让这次保存落地）', () => {
    expect(decideLiveRefresh({ ...base, savingInFlight: true, hasLocalChanges: true }))
      .toEqual({ action: 'skip', reason: 'in-flight' });
  });
});
