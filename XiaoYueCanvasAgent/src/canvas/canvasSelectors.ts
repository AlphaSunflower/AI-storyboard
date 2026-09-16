import type { CanvasNode } from '../types';
import type { CanvasNodeSummary } from './canvasTypes';
import {
  getMediaPlaceholderContract,
  getMissingRequiredInputs,
  getNodeDefinition,
  validateRunnable,
  type CanvasGraphV2,
} from './graphContracts';
import { useCanvasStore } from '../store/canvasStore';

export const nodeSize = (node: CanvasNode) => {
  const fallback = getNodeDefinition(node.data.kind)?.defaultSize ?? { width: 250, height: 250 };
  return {
    width: node.width ?? node.measured?.width ?? fallback.width,
    height: node.height ?? node.measured?.height ?? fallback.height,
  };
};

export const toNodeSummary = (node: CanvasNode): CanvasNodeSummary => {
  const state = useCanvasStore.getState();
  const records = state.generationRecords.filter((record) =>
    record.producerNodeId === node.id || record.resultNodeIds.includes(node.id));
  const latest = records.at(-1);
  const definition = getNodeDefinition(node.data.kind);
  const graph: CanvasGraphV2 = {
    schemaVersion: 2,
    nodes: state.nodes as CanvasGraphV2['nodes'],
    edges: state.edges as CanvasGraphV2['edges'],
    viewport: { x: 0, y: 0, zoom: 1 },
    generationRecords: state.generationRecords,
    executions: state.executions,
  };
  const placeholderContract = getMediaPlaceholderContract(node.data.kind);
  const resultSlotEdge = placeholderContract
    ? state.edges.find((edge) => edge.target === node.id && edge.data?.resultSlot === true)
    : undefined;
  const missingInputs = getMissingRequiredInputs(graph, node.id);
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
    placeholder: placeholderContract ? {
      media: placeholderContract.media,
      state: node.data.assetId ? 'filled' : 'empty',
      resultSlot: Boolean(resultSlotEdge),
      producerNodeId: resultSlotEdge?.source,
      order: resultSlotEdge?.data?.resultSlotOrder,
      edgeId: resultSlotEdge?.id,
    } : undefined,
    missingInputs: missingInputs.length ? missingInputs : undefined,
    ports: definition ? {
      inputs: definition.inputs.map((port) => ({
        id: port.id,
        label: port.label,
        media: port.media,
        acceptsKinds: port.acceptsKinds,
        role: port.role,
        min: port.min,
        max: Number.isFinite(port.max) ? port.max : -1,
        required: port.required,
        ordered: port.ordered,
      })),
      outputs: definition.outputs.map((port) => ({
        id: port.id,
        label: port.label,
        media: port.media,
        role: port.role,
      })),
    } : undefined,
    actions: definition?.actions.map((action) => ({
      id: action.id,
      label: action.label,
      capabilityId: action.capabilityId,
      resultPolicy: action.resultPolicy,
      startable: action.automation.startable,
    })),
    runnable: definition ? Object.fromEntries(
      definition.actions.map((action) => [action.id, validateRunnable(graph, node.id, action.id).ok]),
    ) : undefined,
  };
};
