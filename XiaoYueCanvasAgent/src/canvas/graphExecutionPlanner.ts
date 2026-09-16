import type { CanvasGraphV2 } from './graphContracts';
import { getNodeDefinition } from './graphContracts';
import { RESULT_SLOT_ACTION_ID, resolveResultSlots, type ResultSlotBinding } from './resultSlots';

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
      : actions[0]?.id;
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
          : actions[0]!.id,
        dependencies: executableDependencies(graph, nodeId, executable),
      });
    }
  }

  const byId = new Map(planned.map((node) => [node.nodeId, node]));
  const order: string[] = [];
  const visiting = new Set<string>();
  const visited = new Set<string>();
  const visit = (nodeId: string) => {
    if (visited.has(nodeId)) return;
    if (visiting.has(nodeId)) throw new Error(`画布存在循环依赖：${nodeId}`);
    visiting.add(nodeId);
    for (const dependency of byId.get(nodeId)?.dependencies ?? []) visit(dependency);
    visiting.delete(nodeId);
    visited.add(nodeId);
    if (byId.has(nodeId)) order.push(nodeId);
  };
  for (const node of [...planned].sort((left, right) => left.nodeId.localeCompare(right.nodeId))) visit(node.nodeId);
  return { nodes: order.map((nodeId) => byId.get(nodeId)!), order };
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
      : actions[0]?.id;
    return actionId ? [{
      nodeId,
      actionId,
      dependencies: executableDependencies(graph, nodeId, currentExecutable),
    } satisfies PlannedNode] : [];
  });
}
