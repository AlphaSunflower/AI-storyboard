import { create } from 'zustand';

/**
 * 智能体请求执行、但用户还没点确认时的待执行标记。
 *
 * <p>执行入口只存在于分组：服务端把 `canvas_execute_*` 强制成预览，浏览器把预览结果里的
 * `groupId` 记在这里，分组标题条上的「执行」按钮随即变成高亮的「确认执行」。
 * 只活在当前页面内存里，不落库、不参与撤销，刷新后重新由下一次预览产生。
 */
export interface PendingGroupRun {
  groupId: string;
  nodeIds: string[];
  requestedAt: number;
}

interface PendingGroupRunStore {
  byGroup: Record<string, PendingGroupRun>;
  mark: (run: PendingGroupRun) => void;
  clear: (groupId: string) => void;
  reset: () => void;
}

export const usePendingGroupRunStore = create<PendingGroupRunStore>((set) => ({
  byGroup: {},
  mark: (run) => set((state) => ({ byGroup: { ...state.byGroup, [run.groupId]: run } })),
  clear: (groupId) => set((state) => {
    if (!state.byGroup[groupId]) return state;
    const next = { ...state.byGroup };
    delete next[groupId];
    return { byGroup: next };
  }),
  reset: () => set({ byGroup: {} }),
}));

/**
 * 记录一次「智能体请求执行」的预览结果。
 *
 * @returns 是否真的记下了待确认标记（结果里没有 groupId 时不记）
 */
export function markPendingGroupRun(result: unknown): boolean {
  const payload = result && typeof result === 'object' ? result as Record<string, unknown> : null;
  const groupId = typeof payload?.groupId === 'string' ? payload.groupId : '';
  if (!groupId) return false;
  const nodeIds = Array.isArray(payload?.nodeIds)
    ? payload.nodeIds.filter((id): id is string => typeof id === 'string')
    : [];
  usePendingGroupRunStore.getState().mark({ groupId, nodeIds, requestedAt: Date.now() });
  // 执行入口在画布上，提示一次才不会「点了确认却什么都没发生」。
  if (typeof window !== 'undefined') {
    const groupTitle = typeof payload?.groupTitle === 'string' && payload.groupTitle
      ? payload.groupTitle
      : '分组';
    window.dispatchEvent(new CustomEvent('morrow:notice', {
      detail: `执行已就绪：请点分组「${groupTitle}」标题上的「执行」按钮确认开跑`,
    }));
  }
  return true;
}
