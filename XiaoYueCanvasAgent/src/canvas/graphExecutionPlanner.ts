import type { CanvasGraphV2 } from './graphContracts';
import { getNodeDefinition } from './graphContracts';
import { RESULT_SLOT_ACTION_ID, resolveResultSlots, type ResultSlotBinding } from './resultSlots';
import { resolveActionId } from './generationModes';

export interface PlannedNode {
  nodeId: string;
  actionId: string;
  dependencies: string[];
}

export interface GraphExecutionPlan {
  nodes: PlannedNode[];
  order: string[];
}

const startableActions = (graph: CanvasGraphV2, nodeId: string) => {
  const node = graph.nodes.find((candidate) => candidate.id === nodeId);
  return getNodeDefinition(node?.data.kind)?.actions.filter((action) => action.automation.startable) ?? [];
};

/** 未显式指定动作时，按节点模式挑默认动作（合并后的图片节点在 generate / redraw 之间切换）。 */
const defaultActionId = (graph: CanvasGraphV2, nodeId: string, actions: { id: string }[]): string | undefined => {
  const node = graph.nodes.find((candidate) => candidate.id === nodeId);
  const preferred = resolveActionId(node?.data.kind, node?.data);
  return actions.find((action) => action.id === preferred)?.id ?? actions[0]?.id;
};

const ancestorClosure = (graph: CanvasGraphV2, roots: Iterable<string>) => {
  const result = new Set<string>(roots);
  const queue = [...result];
  while (queue.length) {
    const nodeId = queue.shift()!;
    for (const edge of graph.edges.filter((candidate) => candidate.target === nodeId)) {
      if (result.has(edge.source)) continue;
      result.add(edge.source);
      queue.push(edge.source);
    }
  }
  return result;
};

const descendantClosure = (graph: CanvasGraphV2, roots: Iterable<string>) => {
  const result = new Set<string>(roots);
  const queue = [...result];
  while (queue.length) {
    const nodeId = queue.shift()!;
    for (const edge of graph.edges.filter((candidate) => candidate.source === nodeId)) {
      if (result.has(edge.target)) continue;
      result.add(edge.target);
      queue.push(edge.target);
    }
  }
  return result;
};

const executableDependencies = (
  graph: CanvasGraphV2,
  nodeId: string,
  executable: Set<string>,
) => {
  const dependencies = new Set<string>();
  const queue = graph.edges.filter((edge) => edge.target === nodeId).map((edge) => edge.source);
  const visited = new Set<string>();
  while (queue.length) {
    const candidate = queue.shift()!;
    if (visited.has(candidate)) continue;
    visited.add(candidate);
    if (executable.has(candidate)) dependencies.add(candidate);
    queue.push(...graph.edges.filter((edge) => edge.target === candidate).map((edge) => edge.source));
  }
  return [...dependencies];
};

export function planGraphExecution(
  graph: CanvasGraphV2,
  requestedNodeIds: string[] | undefined,
  actionByNode: Record<string, string> = {},
  includeDescendants = false,
): GraphExecutionPlan {
  const requested = requestedNodeIds?.filter((nodeId) => graph.nodes.some((node) => node.id === nodeId));
  const scope = requested?.length
    ? ancestorClosure(graph, requested)
    : new Set(graph.nodes.map((node) => node.id));
  const executable = new Set<string>();
  for (const node of graph.nodes) {
    if (!scope.has(node.id) || !startableActions(graph, node.id).length) continue;
    executable.add(node.id);
  }
  const resultSlots = new Map<string, ResultSlotBinding>();
  for (const producerId of [...executable]) {
    for (const slot of resolveResultSlots(graph, producerId, { includeFilled: true })) {
      if (!scope.has(slot.nodeId)) continue;
      resultSlots.set(slot.nodeId, slot);
      executable.add(slot.nodeId);
    }
  }
  const planned = [...executable].map((nodeId) => {
    const resultSlot = resultSlots.get(nodeId);
    if (resultSlot) {
      return {
        nodeId,
        actionId: RESULT_SLOT_ACTION_ID,
        dependencies: executableDependencies(graph, nodeId, executable),
      };
    }
    const actions = startableActions(graph, nodeId);
    const actionId = actionByNode[nodeId] && actions.some((action) => action.id === actionByNode[nodeId])
      ? actionByNode[nodeId]
      : defaultActionId(graph, nodeId, actions);
    return {
      nodeId,
      actionId,
      dependencies: executableDependencies(graph, nodeId, executable),
    };
  }).filter((node): node is PlannedNode => Boolean(node.actionId));

  if (includeDescendants) {
    const closure = descendantClosure(graph, executable);
    for (const nodeId of closure) {
      if (executable.has(nodeId) || !startableActions(graph, nodeId).length) continue;
      if (requested?.length && !requested.some((root) => closure.has(root))) continue;
      executable.add(nodeId);
      const actions = startableActions(graph, nodeId);
      planned.push({
        nodeId,
        actionId: actionByNode[nodeId] && actions.some((action) => action.id === actionByNode[nodeId])
          ? actionByNode[nodeId]
          : defaultActionId(graph, nodeId, actions)!,
        dependencies: executableDependencies(graph, nodeId, executable),
      });
    }
  }

  const byId = new Map(planned.map((node) => [node.nodeId, node]));
  const order = topologicalOrderByPosition(graph, byId);
  return { nodes: order.map((nodeId) => byId.get(nodeId)!), order };
}

/**
 * 拓扑排序，同一层内按画布位置（上 → 下、左 → 右）决定先后。
 *
 * <p>节点 id 是随机 UUID：以前按 id 字典序做 DFS，「执行分组」的先后和用户看到的上下顺序
 * 完全无关（实测：分组里视觉上的第 3 个节点被第一个跑，用户会以为只有它生成）。
 * 分组执行是串行的（maxParallel=1），用户必须能从画布上预期「第几个先跑」。
 */
const topologicalOrderByPosition = (
  graph: CanvasGraphV2,
  byId: Map<string, PlannedNode>,
): string[] => {
  const positionOf = (nodeId: string) => graph.nodes.find((node) => node.id === nodeId)?.position;
  const candidates = [...byId.keys()].sort((left, right) => {
    const a = positionOf(left) ?? { x: 0, y: 0 };
    const b = positionOf(right) ?? { x: 0, y: 0 };
    return a.y - b.y || a.x - b.x || left.localeCompare(right);
  });
  const remaining = new Map<string, Set<string>>();
  for (const [nodeId, node] of byId) {
    remaining.set(nodeId, new Set(
      node.dependencies.filter((dependency) => dependency !== nodeId && byId.has(dependency)),
    ));
  }
  const order: string[] = [];
  const emitted = new Set<string>();
  while (order.length < candidates.length) {
    const next = candidates.find((nodeId) => !emitted.has(nodeId)
      && [...(remaining.get(nodeId) ?? [])].every((dependency) => emitted.has(dependency)));
    if (!next) {
      // 没有可推进的节点 = 成环；调用方靠这个异常提示用户。
      throw new Error(`画布存在循环依赖：${candidates.find((nodeId) => !emitted.has(nodeId)) ?? ''}`);
    }
    emitted.add(next);
    order.push(next);
  }
  return order;
}

export function newlyReachableExecutableNodes(
  graph: CanvasGraphV2,
  completedNodeId: string,
  knownNodeIds: Set<string>,
  actionByNode: Record<string, string> = {},
) {
  const currentExecutable = new Set(
    graph.nodes.filter((node) => startableActions(graph, node.id).length).map((node) => node.id),
  );
  const resultSlots = new Map<string, ResultSlotBinding>();
  for (const producerId of [...currentExecutable]) {
    for (const slot of resolveResultSlots(graph, producerId, { includeFilled: true })) {
      resultSlots.set(slot.nodeId, slot);
      currentExecutable.add(slot.nodeId);
    }
  }
  const reachable = descendantClosure(graph, [completedNodeId]);
  return [...reachable].flatMap((nodeId) => {
    if (knownNodeIds.has(nodeId) || !currentExecutable.has(nodeId)) return [];
    if (resultSlots.has(nodeId)) {
      return [{
        nodeId,
        actionId: RESULT_SLOT_ACTION_ID,
        dependencies: executableDependencies(graph, nodeId, currentExecutable),
      } satisfies PlannedNode];
    }
    const actions = startableActions(graph, nodeId);
    const actionId = actionByNode[nodeId] && actions.some((action) => action.id === actionByNode[nodeId])
      ? actionByNode[nodeId]
      : defaultActionId(graph, nodeId, actions);
    return actionId ? [{
      nodeId,
      actionId,
      dependencies: executableDependencies(graph, nodeId, currentExecutable),
    } satisfies PlannedNode] : [];
  });
}
