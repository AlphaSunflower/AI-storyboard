import type { XYPosition } from '@xyflow/react';
import type { CanvasNode } from '../../types';
import { nodeSize } from '../canvasSelectors';

export type GridDirection = 'grid' | 'horizontal' | 'vertical';

export interface GridLayoutOptions {
  gapX?: number;
  gapY?: number;
  columns?: number;
  anchor?: XYPosition;
  direction?: GridDirection;
}

export interface LayoutPosition {
  nodeId: string;
  position: XYPosition;
}

export const computeGridLayout = (nodes: CanvasNode[], options: GridLayoutOptions = {}): LayoutPosition[] => {
  if (!nodes.length) return [];
  const gapX = Math.max(0, options.gapX ?? 120);
  const gapY = Math.max(0, options.gapY ?? 80);
  const direction = options.direction ?? 'grid';
  const anchor = options.anchor ?? {
    x: Math.min(...nodes.map((node) => node.position.x)),
    y: Math.min(...nodes.map((node) => node.position.y)),
  };

  if (direction === 'vertical') {
    let y = anchor.y;
    return nodes.map((node) => {
      const position = { x: anchor.x, y };
      y += Math.max(160, nodeSize(node).height) + gapY;
      return { nodeId: node.id, position };
    });
  }

  if (direction === 'horizontal') {
    let x = anchor.x;
    return nodes.map((node) => {
      const position = { x, y: anchor.y };
      x += Math.max(180, nodeSize(node).width) + gapX;
      return { nodeId: node.id, position };
    });
  }

  // 网格：每列取该列最大宽度、每行取该行最大高度，避免节点重叠。
  const columns = Math.max(1, Math.min(nodes.length, options.columns ?? Math.ceil(Math.sqrt(nodes.length))));
  const columnWidths = new Array<number>(columns).fill(0);
  const rowHeights: number[] = [];
  const placements = nodes.map((node, index) => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const size = nodeSize(node);
    columnWidths[column] = Math.max(columnWidths[column], Math.max(180, size.width));
    rowHeights[row] = Math.max(rowHeights[row] ?? 0, Math.max(160, size.height));
    return { node, column, row };
  });

  const xOffsets = columnWidths.map((_, column) => columnWidths.slice(0, column).reduce((sum, width) => sum + width + gapX, 0));
  const yOffsets = rowHeights.map((_, row) => rowHeights.slice(0, row).reduce((sum, height) => sum + height + gapY, 0));

  return placements.map(({ node, column, row }) => ({
    nodeId: node.id,
    position: { x: anchor.x + xOffsets[column], y: anchor.y + yOffsets[row] },
  }));
};
