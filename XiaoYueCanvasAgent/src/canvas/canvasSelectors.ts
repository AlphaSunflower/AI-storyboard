import type { CanvasNode } from '../types';
import type { CanvasNodeSummary } from './canvasTypes';
import { getNodeDefinition } from './graphContracts';
import { useCanvasStore } from '../store/canvasStore';

export const nodeSize = (node: CanvasNode) => {
  const fallback = getNodeDefinition(node.data.kind)?.defaultSize ?? { width: 250, height: 250 };
  return {
    width: node.width ?? node.measured?.width ?? fallback.width,
    height: node.height ?? node.measured?.height ?? fallback.height,
  };
};

export const toNodeSummary = (node: CanvasNode): CanvasNodeSummary => {
  const records = useCanvasStore.getState().generationRecords.filter((record) =>
    record.producerNodeId === node.id || record.resultNodeIds.includes(node.id));
  const latest = records.at(-1);
  return {
    id: node.id,
    kind: node.data.kind,
    label: node.data.label,
    position: { ...node.position },
    size: nodeSize(node),
    assetId: node.data.assetId,
    status: node.data.status,
    derivedFrom: node.data.derivedFrom,
    generationRecordIds: records.map((record) => record.id),
    generationStatus: latest?.status,
    materializationStatus: latest?.materializationStatus,
    resultNodeIds: latest?.resultNodeIds,
  };
};
