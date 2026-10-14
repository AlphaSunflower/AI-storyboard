/** 只依赖连线的最小结构：source / target / 可选的既有 hidden。 */
export interface DisplayEdge {
  source: string;
  target: string;
  hidden?: boolean;
}

export interface EdgeDisplayOptions {
  /** 是否处于「隐藏连线」模式。false 时原样返回，连数组引用都不换。 */
  edgesHidden: boolean;
  /** 当前选中的节点 id——与它们相连的边要露出来。 */
  revealedNodeIds: ReadonlySet<string>;
}

/**
 * 按「显示 / 隐藏连线」模式裁剪交给 React Flow 的边数组。
 *
 * 隐藏模式下只把「不与任何选中节点相连」的边标成 hidden，其余保持原样：
 * - 返回新对象而不是就地改，store 里保存的图不受影响，撤销/存盘/布局都照旧；
 * - 本来就 hidden 的边（比如被其它逻辑屏蔽掉的）不会被选中端点翻出来。
 *
 * 显示模式下直接返回入参本身，让依赖它的 memo 保持引用稳定。
 */
export function filterEdgesForDisplay<T extends DisplayEdge>(
  edges: T[],
  { edgesHidden, revealedNodeIds }: EdgeDisplayOptions,
): T[] {
  if (!edgesHidden) return edges;
  if (!revealedNodeIds.size) return edges.map((edge) => edge.hidden ? edge : { ...edge, hidden: true });
  return edges.map((edge) =>
    edge.hidden || revealedNodeIds.has(edge.source) || revealedNodeIds.has(edge.target)
      ? edge
      : { ...edge, hidden: true },
  );
}
