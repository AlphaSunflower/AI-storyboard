import type { CanvasNode } from '../../types';
import { nodeSize } from '../canvasSelectors';
import type { LayoutPosition } from './gridLayout';

export type AlignAxis = 'left' | 'right' | 'top' | 'bottom' | 'centerX' | 'centerY';
export type DistributeAxis = 'horizontal' | 'vertical';

export const computeAlignment = (nodes: CanvasNode[], axis: AlignAxis): LayoutPosition[] => {
  if (!nodes.length) return [];
  const bounds = {
    left: Math.min(...nodes.map((node) => node.position.x)),
    right: Math.max(...nodes.map((node) => node.position.x + nodeSize(node).width)),
    top: Math.min(...nodes.map((node) => node.position.y)),
    bottom: Math.max(...nodes.map((node) => node.position.y + nodeSize(node).height)),
  };
  const centerX = (bounds.left + bounds.right) / 2;
  const centerY = (bounds.top + bounds.bottom) / 2;

  return nodes.map((node) => {
    const size = nodeSize(node);
    const position = { ...node.position };
    if (axis === 'left') position.x = bounds.left;
    else if (axis === 'right') position.x = bounds.right - size.width;
    else if (axis === 'top') position.y = bounds.top;
    else if (axis === 'bottom') position.y = bounds.bottom - size.height;
    else if (axis === 'centerX') position.x = centerX - size.width / 2;
    else if (axis === 'centerY') position.y = centerY - size.height / 2;
    return { nodeId: node.id, position };
  });
};

// 沿一条轴把节点中心均匀分布；首尾节点保持不变。
export const computeDistribution = (nodes: CanvasNode[], axis: DistributeAxis): LayoutPosition[] => {
  if (nodes.length < 2) return nodes.map((node) => ({ nodeId: node.id, position: { ...node.position } }));
  const sorted = [...nodes].sort((left, right) => (axis === 'horizontal' ? left.position.x - right.position.x : left.position.y - right.position.y));
  const first = nodeSize(sorted[0]);
  const last = nodeSize(sorted[sorted.length - 1]);
  const firstCenter = (axis === 'horizontal' ? sorted[0].position.x : sorted[0].position.y) + (axis === 'horizontal' ? first.width : first.height) / 2;
  const lastCenter = (axis === 'horizontal' ? sorted[sorted.length - 1].position.x : sorted[sorted.length - 1].position.y) + (axis === 'horizontal' ? last.width : last.height) / 2;
  const step = (lastCenter - firstCenter) / (sorted.length - 1);

  return sorted.map((node, index) => {
    const size = nodeSize(node);
    const center = firstCenter + index * step;
    const position = { ...node.position };
    if (axis === 'horizontal') position.x = center - size.width / 2;
    else position.y = center - size.height / 2;
    return { nodeId: node.id, position };
  });
};
