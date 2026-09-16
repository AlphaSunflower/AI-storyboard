import type { XYPosition } from '@xyflow/react';
import type { CanvasEdge, CanvasNode } from '../../types';
import { nodeSize } from '../canvasSelectors';
import type { DagDirection } from './dagLayout';
import type { LayoutPosition } from './gridLayout';
import { collectConnectedComponents } from './layoutScope';

export interface ReadableDagLayoutOptions {
  gapX?: number;
  gapY?: number;
  componentGapY?: number;
  anchor?: XYPosition;
  direction?: DagDirection;
}

const DEFAULT_GAP_X = 240;
const DEFAULT_GAP_Y = 96;
const DEFAULT_COMPONENT_GAP_Y = 360;

const nodeOrder = (left: CanvasNode, right: CanvasNode): number => {
  const leftOrdinal = left.data.kind === 'shot' ? Number(left.data.shotOrdinal) : Number.NaN;
  const rightOrdinal = right.data.kind === 'shot' ? Number(right.data.shotOrdinal) : Number.NaN;
  if (Number.isFinite(leftOrdinal) && Number.isFinite(rightOrdinal) && leftOrdinal !== rightOrdinal) {
    return leftOrdinal - rightOrdinal;
  }
  return left.position.y - right.position.y
    || left.position.x - right.position.x
    || left.id.localeCompare(right.id);
};

const rankNodes = (nodes: CanvasNode[], edges: CanvasEdge[]) => {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const indegree = new Map(nodes.map((node) => [node.id, 0]));
  const outgoing = new Map(nodes.map((node) => [node.id, [] as string[]]));

  for (const edge of edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) continue;
    outgoing.get(edge.source)?.push(edge.target);
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
  }

  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const byNodeOrder = (leftId: string, rightId: string) =>
    nodeOrder(nodeById.get(leftId)!, nodeById.get(rightId)!);
  const ready = nodes
    .filter((node) => indegree.get(node.id) === 0)
    .map((node) => node.id)
    .sort(byNodeOrder);
  const rank = new Map(ready.map((nodeId) => [nodeId, 1]));
  const order = new Map<string, number>();
  let cursor = 0;

  while (ready.length) {
    const nodeId = ready.shift()!;
    order.set(nodeId, cursor++);
    for (const targetId of [...(outgoing.get(nodeId) ?? [])].sort(byNodeOrder)) {
      rank.set(targetId, Math.max(rank.get(targetId) ?? 1, (rank.get(nodeId) ?? 1) + 1));
      const remaining = (indegree.get(targetId) ?? 0) - 1;
      indegree.set(targetId, remaining);
      if (remaining === 0) {
        ready.push(targetId);
        ready.sort(byNodeOrder);
      }
    }
  }

  return { rank, order };
};

const layoutComponent = (
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  origin: XYPosition,
  options: Required<Pick<ReadableDagLayoutOptions, 'gapX' | 'gapY' | 'direction'>>,
): LayoutPosition[] => {
  const { rank, order } = rankNodes(nodes, edges);
  const groups = new Map<number, CanvasNode[]>();

  for (const node of nodes) {
    const nodeRank = rank.get(node.id) ?? Math.max(1, ...rank.values()) + 1;
    const group = groups.get(nodeRank) ?? [];
    group.push(node);
    groups.set(nodeRank, group);
  }

  const sortedGroups = [...groups.entries()].sort(([left], [right]) => left - right);
  const positions = new Map<string, XYPosition>();

  if (options.direction === 'down') {
    let y = origin.y;
    for (const [, group] of sortedGroups) {
      const orderedGroup = [...group].sort((left, right) =>
        (order.get(left.id) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER)
        || nodeOrder(left, right));
      let x = origin.x;
      let rowHeight = 0;
      for (const node of orderedGroup) {
        const size = nodeSize(node);
        positions.set(node.id, { x, y });
        x += size.width + options.gapX;
        rowHeight = Math.max(rowHeight, size.height);
      }
      y += rowHeight + options.gapY;
    }
  } else {
    let x = origin.x;
    for (const [, group] of sortedGroups) {
      const orderedGroup = [...group].sort((left, right) =>
        (order.get(left.id) ?? Number.MAX_SAFE_INTEGER) - (order.get(right.id) ?? Number.MAX_SAFE_INTEGER)
        || nodeOrder(left, right));
      let y = origin.y;
      let columnWidth = 0;
      for (const node of orderedGroup) {
        const size = nodeSize(node);
        positions.set(node.id, { x, y });
        y += size.height + options.gapY;
        columnWidth = Math.max(columnWidth, size.width);
      }
      x += columnWidth + options.gapX;
    }
  }

  return nodes.flatMap((node) => {
    const position = positions.get(node.id);
    return position ? [{ nodeId: node.id, position }] : [];
  });
};

export const computeReadableDagLayout = (
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  options: ReadableDagLayoutOptions = {},
): LayoutPosition[] => {
  if (!nodes.length) return [];

  const gapX = Math.max(0, options.gapX ?? DEFAULT_GAP_X);
  const gapY = Math.max(0, options.gapY ?? DEFAULT_GAP_Y);
  const componentGapY = Math.max(0, options.componentGapY ?? DEFAULT_COMPONENT_GAP_Y);
  const direction = options.direction ?? 'right';
  const anchor = options.anchor ?? {
    x: Math.min(...nodes.map((node) => node.position.x)),
    y: Math.min(...nodes.map((node) => node.position.y)),
  };
  const components = collectConnectedComponents(nodes, edges).sort((left, right) => {
    const leftTop = Math.min(...left.map((node) => node.position.y));
    const rightTop = Math.min(...right.map((node) => node.position.y));
    if (leftTop !== rightTop) return leftTop - rightTop;
    return Math.min(...left.map((node) => node.position.x))
      - Math.min(...right.map((node) => node.position.x));
  });
  const positions: LayoutPosition[] = [];
  let componentTop = anchor.y;

  for (const component of components) {
    const componentIds = new Set(component.map((node) => node.id));
    const componentEdges = edges.filter((edge) =>
      componentIds.has(edge.source) && componentIds.has(edge.target));
    const relative = layoutComponent(component, componentEdges, { x: anchor.x, y: 0 }, {
      gapX,
      gapY,
      direction,
    });
    let componentBottom = 0;
    for (const item of relative) {
      const node = component.find((candidate) => candidate.id === item.nodeId);
      if (!node) continue;
      positions.push({
        nodeId: item.nodeId,
        position: { x: item.position.x, y: componentTop + item.position.y },
      });
      componentBottom = Math.max(componentBottom, item.position.y + nodeSize(node).height);
    }
    componentTop += componentBottom + componentGapY;
  }

  return positions;
};
