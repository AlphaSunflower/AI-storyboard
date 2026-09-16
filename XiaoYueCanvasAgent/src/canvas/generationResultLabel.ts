import type { GenerationRecord } from './graphContracts';
import type { CanvasEdge, CanvasNode } from '../types';

export function generationResultLabel(assetId?: string): string {
  return assetId?.trim() || '生成结果';
}

export function resultAttachmentLabel(assetId?: string, existingLabel?: string): string {
  return existingLabel?.trim() || generationResultLabel(assetId);
}

export function relabelGeneratedResultNodes(
  nodes: CanvasNode[],
  records: GenerationRecord[],
  edges: CanvasEdge[] = [],
): CanvasNode[] {
  const resultNodeIds = new Set(records.flatMap((record) => record.resultNodeIds));
  const resultSlotNodeIds = new Set(edges
    .filter((edge) => edge.data?.resultSlot === true)
    .map((edge) => edge.target));
  return nodes.map((node) => {
    if (!resultNodeIds.has(node.id) || resultSlotNodeIds.has(node.id)) return node;
    const label = generationResultLabel(node.data.assetId);
    return label === node.data.label ? node : { ...node, data: { ...node.data, label } };
  });
}
