import type { CanvasNode, GenerationRecord, GenerationSource } from '../types';

export interface BuildGenerationSourcesInput {
  sourceAssetIds: string[];
  sourceNodeIds?: string[];
  roleByAssetId?: Record<string, string>;
  nodes: CanvasNode[];
  fallbackRole?: string;
}

export function buildGenerationSources({
  sourceAssetIds,
  sourceNodeIds = [],
  roleByAssetId = {},
  nodes,
  fallbackRole = 'source',
}: BuildGenerationSourcesInput): GenerationSource[] {
  return sourceAssetIds.map((assetId, order) => ({
    nodeId: sourceNodeIds[order] || nodes.find((node) => node.data.assetId === assetId)?.id || '',
    assetId,
    role: roleByAssetId[assetId] || fallbackRole,
    order,
  }));
}

export function generationResultAssetIds(record: GenerationRecord, nodes: CanvasNode[]): string[] {
  const assetIds = new Set(record.materializedAssetIds);
  for (const nodeId of record.resultNodeIds) {
    const assetId = nodes.find((node) => node.id === nodeId)?.data.assetId;
    if (assetId) assetIds.add(assetId);
  }
  return [...assetIds];
}

export function generationRecordProgress(record: GenerationRecord, nodes: CanvasNode[]): number {
  const clamp = (value: number) => Math.max(0, Math.min(1, value));
  if (typeof record.progress === 'number') return clamp(record.progress);
  const nodeProgress = nodes.find((node) => node.id === record.producerNodeId)?.data.progressPercent;
  if (typeof nodeProgress === 'number') return clamp(nodeProgress / 100);
  return record.materializationStatus === 'materializing' ? .98 : 0;
}

export function selectActiveGenerationRecords(records: GenerationRecord[]): GenerationRecord[] {
  return records.filter((record) =>
    record.status === 'queued'
    || record.status === 'running'
    || record.materializationStatus === 'materializing');
}
