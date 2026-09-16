import type { CanvasEdge, CanvasNode } from '../types';
import { getMissingRequiredInputs, getNodeDefinition, type CanvasGraphV2 } from './graphContracts';

export const RESULT_SLOT_ACTION_ID = '__result_slot__';

export interface ResultSlotBinding {
  nodeId: string;
  kind: 'image' | 'video';
  order: number;
  edgeId: string;
}

export interface ResultSlotValidation {
  ok: boolean;
  code?: string;
  message?: string;
}

export interface MediaRelayRepairSuggestion {
  producerNodeId: string;
  consumerNodeId: string;
  inputPortId: string;
  kind: 'image' | 'video';
}

export interface ResolveResultSlotOptions {
  includeFilled?: boolean;
}

const mediaKind = (kind: string | undefined): kind is 'image' | 'video' =>
  kind === 'image' || kind === 'video';

const isMediaPlaceholder = (
  node: CanvasNode | undefined,
  includeFilled = false,
): node is CanvasNode & { data: { kind: 'image' | 'video' } } =>
  Boolean(node && mediaKind(node.data.kind) && (includeFilled || !node.data.assetId));

const sourceCanProduce = (source: CanvasNode | undefined, kind: 'image' | 'video') => {
  const definition = getNodeDefinition(source?.data.kind);
  return Boolean(
    definition
    && definition.outputs.some((port) => port.media === kind)
    && definition.actions.some((action) => action.automation.startable && action.resultPolicy === 'media-nodes'),
  );
};

export function resolveResultSlots(
  graph: CanvasGraphV2,
  producerNodeId: string,
  options: ResolveResultSlotOptions = {},
): ResultSlotBinding[] {
  const producer = graph.nodes.find((node) => node.id === producerNodeId) as CanvasNode | undefined;
  if (!producer) return [];
  const candidates: Array<ResultSlotBinding & { sequence: number }> = [];
  graph.edges.forEach((edge, sequence) => {
    if (edge.source !== producerNodeId) return;
    const target = graph.nodes.find((node) => node.id === edge.target) as CanvasNode | undefined;
    if (!isMediaPlaceholder(target, options.includeFilled)) return;
    if (!sourceCanProduce(producer, target.data.kind)) return;
    const output = getNodeDefinition(producer.data.kind)?.outputs.find((port) => port.id === edge.sourceHandle);
    if (output && output.media !== target.data.kind) return;
    const explicitOrder = edge.data?.resultSlotOrder;
    candidates.push({
      nodeId: target.id,
      kind: target.data.kind,
      order: Number.isInteger(explicitOrder) ? explicitOrder! : sequence,
      edgeId: edge.id,
      sequence,
    });
  });
  return candidates
    .sort((left, right) => left.order - right.order || left.sequence - right.sequence)
    .map(({ nodeId, kind, order, edgeId }) => ({ nodeId, kind, order, edgeId }));
}

export function validateResultSlotOwnership(
  graph: CanvasGraphV2,
  producerNodeId: string,
  slots: ResultSlotBinding[],
): ResultSlotValidation {
  for (const slot of slots) {
    const otherProducer = graph.edges.find((edge) =>
      edge.target === slot.nodeId && edge.source !== producerNodeId);
    if (otherProducer) {
      return {
        ok: false,
        code: 'RESULT_SLOT_CONFLICT',
        message: `结果槽 ${slot.nodeId} 已连接其他生产节点 ${otherProducer.source}`,
      };
    }
  }
  return { ok: true };
}

export function withResultSlotData(
  source: CanvasNode,
  target: CanvasNode,
  existingEdges: CanvasEdge[],
  data: CanvasEdge['data'],
): CanvasEdge['data'] {
  if (!isMediaPlaceholder(target) || !sourceCanProduce(source, target.data.kind)) return data;
  const used = new Set(existingEdges
    .filter((edge) => edge.source === source.id && edge.data?.resultSlot)
    .map((edge) => edge.data?.resultSlotOrder)
    .filter((order): order is number => Number.isInteger(order)));
  let resultSlotOrder = 0;
  while (used.has(resultSlotOrder)) resultSlotOrder += 1;
  return { ...(data ?? {}), resultSlot: true, resultSlotOrder };
}

export function isVirtualResultSlotAction(actionId: string | undefined) {
  return actionId === RESULT_SLOT_ACTION_ID;
}

export function suggestMediaRelayRepairs(graph: CanvasGraphV2): MediaRelayRepairSuggestion[] {
  const producers = graph.nodes.filter((node) => {
    const definition = getNodeDefinition(node.data.kind);
    return Boolean(definition?.outputs.some((port) => mediaKind(port.media))
      && definition.actions.some((action) => action.automation.startable && action.resultPolicy === 'media-nodes'));
  });
  const suggestions: MediaRelayRepairSuggestion[] = [];

  for (const consumer of graph.nodes) {
    for (const missing of getMissingRequiredInputs(graph, consumer.id)) {
      if (!mediaKind(missing.media)) continue;
      const candidates = producers.filter((producer) =>
        producer.id !== consumer.id
        && getNodeDefinition(producer.data.kind)?.outputs.some((port) => port.media === missing.media));
      if (candidates.length !== 1) continue;
      suggestions.push({
        producerNodeId: candidates[0].id,
        consumerNodeId: consumer.id,
        inputPortId: missing.portId,
        kind: missing.media,
      });
    }
  }
  return suggestions;
}
