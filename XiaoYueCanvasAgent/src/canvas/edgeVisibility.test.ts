import { describe, expect, it } from 'vitest';
import { filterEdgesForDisplay } from './edgeVisibility';

const edge = (id: string, source: string, target: string, extra: Record<string, unknown> = {}) =>
  ({ id, source, target, ...extra });

const chain = [edge('e1', 'a', 'b'), edge('e2', 'b', 'c'), edge('e3', 'c', 'd')];
const none: ReadonlySet<string> = new Set();
const visible = (result: readonly { hidden?: boolean }[]) => result.filter((item) => !item.hidden).map((item) => (item as { id: string }).id);

describe('filterEdgesForDisplay', () => {
  it('显示连线时原样返回，连数组引用都不换', () => {
    expect(filterEdgesForDisplay(chain, { edgesHidden: false, revealedNodeIds: none })).toBe(chain);
  });

  it('隐藏连线且没有选中节点时，全部隐藏', () => {
    const result = filterEdgesForDisplay(chain, { edgesHidden: true, revealedNodeIds: none });

    expect(visible(result)).toEqual([]);
    expect(result).toHaveLength(chain.length);
  });

  it('选中节点时，只显示与它相连的边（出边和入边都算）', () => {
    // b 同时是 e1 的终点和 e2 的起点，两条都该露出来。
    const result = filterEdgesForDisplay(chain, { edgesHidden: true, revealedNodeIds: new Set(['b']) });

    expect(visible(result)).toEqual(['e1', 'e2']);
  });

  it('多选时取并集', () => {
    const result = filterEdgesForDisplay(chain, { edgesHidden: true, revealedNodeIds: new Set(['a', 'd']) });

    expect(visible(result)).toEqual(['e1', 'e3']);
  });

  it('不碰原对象：隐藏的边是新对象，露出的边保持原引用', () => {
    const [first, second] = chain;
    const result = filterEdgesForDisplay(chain, { edgesHidden: true, revealedNodeIds: new Set(['a']) });

    expect(result[0]).toBe(first);        // 露出的边原样返回，不复制
    expect(result[1]).not.toBe(second);   // 隐藏的边是新对象
    expect(second.hidden).toBeUndefined(); // 原数组没被改脏
  });

  it('本来就隐藏的边，选中端点也不会被翻出来', () => {
    const withHidden = [edge('e1', 'a', 'b', { hidden: true }), edge('e2', 'b', 'c')];
    const result = filterEdgesForDisplay(withHidden, { edgesHidden: true, revealedNodeIds: new Set(['a', 'b']) });

    expect(visible(result)).toEqual(['e2']);
  });
});
