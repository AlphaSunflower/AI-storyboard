import type { GenerationRecord } from './graphContracts';
import type { CanvasNode } from '../types';

export function generationResultLabel(assetId?: string): string {
  return assetId?.trim() || '生成结果';
}

export function relabelGeneratedResultNodes(
  nodes: CanvasNode[],
  records: GenerationRecord[],
): CanvasNode[] {
  const resultNodeIds = new Set(records.flatMap((record) => record.resultNodeIds));
  return nodes.map((node) => {
    if (!resultNodeIds.has(node.id)) return node;
    const label = generationResultLabel(node.data.assetId);
    return label === node.data.label ? node : { ...node, data: { ...node.data, label } };
  });
}
