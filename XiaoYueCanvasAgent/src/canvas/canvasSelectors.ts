import type { CanvasNode } from '../types';
import type { CanvasNodeSummary } from './canvasTypes';

export const nodeSize = (node: CanvasNode) => ({
  width: node.width ?? node.measured?.width ?? (node.data.kind === 'text' ? 380 : node.data.kind === 'video' ? 420 : 250),
  height: node.height ?? node.measured?.height ?? (node.data.kind === 'text' ? 280 : node.data.kind === 'video' ? 236 : 250),
});

export const toNodeSummary = (node: CanvasNode): CanvasNodeSummary => ({
  id: node.id,
  kind: node.data.kind,
  label: node.data.label,
  position: { ...node.position },
  size: nodeSize(node),
  assetId: node.data.assetId,
  status: node.data.status,
  derivedFrom: node.data.derivedFrom,
});
