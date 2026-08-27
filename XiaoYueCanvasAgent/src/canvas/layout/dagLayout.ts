import type { XYPosition } from '@xyflow/react';
import type { CanvasEdge, CanvasNode } from '../../types';
import { nodeSize } from '../canvasSelectors';
import type { LayoutPosition } from './gridLayout';

export type DagDirection = 'right' | 'down';

export interface DagLayoutOptions {
  gapX?: number;
  gapY?: number;
  anchor?: XYPosition;
  direction?: DagDirection;
}

// 按连线方向做拓扑分层（最长路径层级）。有环时环内节点退化为排在最后一层，
// 不会无限循环。无连线的节点自然落在第 0 层。
export const computeDagLayout = (nodes: CanvasNode[], edges: CanvasEdge[], options: DagLayoutOptions = {}): LayoutPosition[] => {
  if (!nodes.length) return [];
  const gapX = Math.max(0, options.gapX ?? 180);
  const gapY = Math.max(0, options.gapY ?? 80);
  const direction = options.direction ?? 'right';
  const nodeIds = new Set(nodes.map((node) => node.id));
  const nodeById = new Map(nodes.map((node) => [node.id, node]));

  const successors = new Map<string, string[]>();
  const predecessors = new Map<string, string[]>();
  const indegree = new Map<string, number>();
  nodes.forEach((node) => {
    successors.set(node.id, []);
    predecessors.set(node.id, []);
    indegree.set(node.id, 0);
  });

  edges.forEach((edge) => {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target) || edge.source === edge.target) return;
    const outgoing = successors.get(edge.source)!;
    if (outgoing.includes(edge.target)) return; // 重复边去重
    outgoing.push(edge.target);
    predecessors.get(edge.target)!.push(edge.source);
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
  });

  // Kahn 拓扑排序。
  const queue = nodes.filter((node) => indegree.get(node.id) === 0).map((node) => node.id);
  const sorted: string[] = [];
  let cursor = 0;
  while (cursor < queue.length) {
    const id = queue[cursor++];
    sorted.push(id);
    successors.get(id)!.forEach((next) => {
      const degree = indegree.get(next)! - 1;
      indegree.set(next, degree);
      if (degree === 0) queue.push(next);
    });
  }
  // 环内或不可达节点，按原顺序补到末尾。
  nodes.forEach((node) => { if (!sorted.includes(node.id)) sorted.push(node.id); });

  // 最长路径层级：layer = max(前驱 layer) + 1。
  const layer = new Map<string, number>();
  sorted.forEach((id) => {
    const preds = predecessors.get(id)!;
    const max = preds.reduce((acc, predecessor) => Math.max(acc, layer.get(predecessor) ?? 0), 0);
    layer.set(id, preds.length ? max + 1 : 0);
  });

  const groups = new Map<number, string[]>();
  sorted.forEach((id) => {
    const level = layer.get(id) ?? 0;
    const list = groups.get(level) ?? [];
    list.push(id);
    groups.set(level, list);
  });
  const layerIndices = [...groups.keys()].sort((left, right) => left - right);
  const anchor = options.anchor ?? {
    x: Math.min(...nodes.map((node) => node.position.x)),
    y: Math.min(...nodes.map((node) => node.position.y)),
  };

  // 层间跨度取该层节点的最大尺寸，保证相邻层不会重叠。
  const spanFor = (level: number, axis: 'width' | 'height') => {
    const ids = groups.get(level) ?? [];
    if (!ids.length) return 0;
    return Math.max(...ids.map((id) => (axis === 'width' ? Math.max(180, nodeSize(nodeById.get(id)!).width) : Math.max(160, nodeSize(nodeById.get(id)!).height))));
  };

  const layerOffsets = new Map<number, number>();
  let primary = 0;
  layerIndices.forEach((level) => {
    layerOffsets.set(level, primary);
    primary += spanFor(level, direction === 'down' ? 'height' : 'width') + (direction === 'down' ? gapY : gapX);
  });

  const positions: LayoutPosition[] = [];
  layerIndices.forEach((level) => {
    const ids = groups.get(level) ?? [];
    let secondary = 0;
    ids.forEach((id) => {
      const node = nodeById.get(id)!;
      const size = nodeSize(node);
      const position: XYPosition = direction === 'down'
        ? { x: anchor.x + secondary, y: anchor.y + (layerOffsets.get(level) ?? 0) }
        : { x: anchor.x + (layerOffsets.get(level) ?? 0), y: anchor.y + secondary };
      secondary += (direction === 'down' ? Math.max(180, size.width) : Math.max(160, size.height)) + (direction === 'down' ? gapX : gapY);
      positions.push({ nodeId: id, position });
    });
  });

  return positions;
};
