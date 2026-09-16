import { createId } from '../utils/id';
import { useCanvasStore } from '../store/canvasStore';
import type {
  CanvasExecution,
  ExecutionNodeState,
  ExecutionStatus,
} from './graphContracts';
import type { CanvasGraphV2 } from './graphContracts';
import {
  newlyReachableExecutableNodes,
  planGraphExecution,
  type PlannedNode,
} from './graphExecutionPlanner';
import { cancelNodeExecution, executeNodeAction } from './nodeExecutionService';
import { RESULT_SLOT_ACTION_ID } from './resultSlots';

interface ActiveExecution {
  cancelled: boolean;
  handles: Map<string, { jobId: string; recordId?: string }>;
  starting: Set<string>;
}

const activeExecutions = new Map<string, ActiveExecution>();

const graphOf = (): CanvasGraphV2 => {
  const state = useCanvasStore.getState();
  return {
    schemaVersion: 2,
    nodes: state.nodes as CanvasGraphV2['nodes'],
    edges: state.edges as CanvasGraphV2['edges'],
    viewport: { x: 0, y: 0, zoom: 1 },
    generationRecords: state.generationRecords,
    executions: state.executions,
  };
};

const executionOf = (executionId: string) =>
  useCanvasStore.getState().executions.find((execution) => execution.id === executionId);

const patchExecution = (
  executionId: string,
  patch: (execution: CanvasExecution) => CanvasExecution,
) => {
  const current = executionOf(executionId);
  if (!current) return;
  const next = patch({ ...current, nodes: [...current.nodes], updatedAt: Date.now() });
  useCanvasStore.getState().updateExecution(next);
};

const patchNode = (
  executionId: string,
  nodeId: string,
  patch: Partial<ExecutionNodeState>,
) => {
  patchExecution(executionId, (execution) => ({
    ...execution,
    nodes: execution.nodes.map((node) => node.nodeId === nodeId ? { ...node, ...patch } : node),
  }));
  if (patch.status) {
    const phase = patch.status === 'succeeded'
      ? 'done'
      : patch.status === 'failed'
        ? 'failed'
        : patch.status === 'blocked'
          ? 'blocked'
          : patch.status === 'cancelled'
            ? 'idle'
            : patch.status;
    const node = useCanvasStore.getState().nodes.find((candidate) => candidate.id === nodeId);
    useCanvasStore.getState().updateNode(nodeId, {
      automation: {
        ...(typeof node?.data.automation === 'object' && node.data.automation ? node.data.automation : {}),
        phase,
      },
    });
  }
};

const dependencyStatuses = (
  execution: CanvasExecution,
  planned: PlannedNode,
): ExecutionStatus[] => planned.dependencies
  .map((dependency) => execution.nodes.find((node) => node.nodeId === dependency)?.status)
  .filter((status): status is ExecutionStatus => Boolean(status));

const terminal = (status: ExecutionStatus) =>
  status === 'succeeded'
  || status === 'failed'
  || status === 'blocked'
  || status === 'cancelled'
  || status === 'interrupted';

const settleExecution = (executionId: string) => {
  const execution = executionOf(executionId);
  const active = activeExecutions.get(executionId);
  if (!execution || active?.handles.size || active?.starting.size) return;
  if (execution.nodes.some((node) => !terminal(node.status))) return;
  const cancelled = execution.nodes.some((node) => node.status === 'cancelled');
  const failed = execution.nodes.some((node) => node.status === 'failed' || node.status === 'blocked');
  patchExecution(executionId, (current) => ({
    ...current,
    status: cancelled ? 'cancelled' : failed ? 'failed' : 'succeeded',
  }));
  activeExecutions.delete(executionId);
};

const runExecution = async (
  executionId: string,
  plannedByNode: Map<string, PlannedNode>,
  actionByNode: Record<string, string>,
) => {
  while (true) {
    const active = activeExecutions.get(executionId);
    const execution = executionOf(executionId);
    if (!active || !execution) return;
    if (active.cancelled) {
      patchExecution(executionId, (current) => ({
        ...current,
        status: 'cancelled',
        nodes: current.nodes.map((node) => terminal(node.status) ? node : { ...node, status: 'cancelled', completedAt: Date.now() }),
      }));
      activeExecutions.delete(executionId);
      return;
    }

    const graph = graphOf();
    const current = executionOf(executionId)!;
    const byNode = new Map(current.nodes.map((node) => [node.nodeId, node]));
    let changed = false;
    let waitingForResultSlot = false;

    for (const node of current.nodes) {
      const planned = plannedByNode.get(node.nodeId);
      if (!planned || terminal(node.status) || node.status === 'running') continue;
      const statuses = dependencyStatuses(current, planned);
      if (statuses.some((status) => status === 'failed' || status === 'blocked' || status === 'cancelled' || status === 'interrupted')) {
        patchNode(executionId, node.nodeId, { status: 'blocked', completedAt: Date.now() });
        changed = true;
      } else if (statuses.every((status) => status === 'succeeded') && node.status === 'pending') {
        patchNode(executionId, node.nodeId, { status: 'ready' });
        changed = true;
      }
    }

    const refreshed = executionOf(executionId)!;
    const availableSlots = Math.max(0, refreshed.maxParallel - active.handles.size - active.starting.size);
    const ready = refreshed.nodes
      .filter((node) => node.status === 'ready' && !active.handles.has(node.nodeId))
      .sort((left, right) => {
        const order = executionOf(executionId)!.nodeIds;
        return order.indexOf(left.nodeId) - order.indexOf(right.nodeId);
      })
      .slice(0, availableSlots);

    if (!ready.length && active.handles.size === 0 && active.starting.size === 0) {
      const unresolved = executionOf(executionId)!.nodes.filter((node) => !terminal(node.status));
      if (unresolved.length) {
        for (const node of unresolved) patchNode(executionId, node.nodeId, { status: 'blocked', error: '依赖节点未完成' });
      }
      settleExecution(executionId);
      return;
    }

    for (const node of ready) {
      if (node.actionId === RESULT_SLOT_ACTION_ID) {
        const slotNode = graph.nodes.find((candidate) => candidate.id === node.nodeId);
        const phase = slotNode?.data.automation?.phase;
        if (slotNode?.data.assetId && slotNode.data.mediaLoadState === 'ready') {
          patchNode(executionId, node.nodeId, { status: 'succeeded', completedAt: Date.now() });
          changed = true;
        } else if (phase === 'failed') {
          patchNode(executionId, node.nodeId, {
            status: 'failed',
            error: slotNode?.data.automation?.error ?? '结果槽未填充',
            completedAt: Date.now(),
          });
          changed = true;
        } else if (phase === 'blocked' || phase === 'stale') {
          patchNode(executionId, node.nodeId, {
            status: 'blocked',
            error: slotNode?.data.automation?.error ?? '结果槽未填充',
            completedAt: Date.now(),
          });
          changed = true;
        } else {
          waitingForResultSlot = true;
        }
        continue;
      }
      active.starting.add(node.nodeId);
      patchNode(executionId, node.nodeId, { status: 'running', startedAt: Date.now(), error: undefined });
      const running = executeNodeAction(current.projectId, node.nodeId, node.actionId)
        .then(async (handle) => {
          active.starting.delete(node.nodeId);
          active.handles.set(node.nodeId, { jobId: handle.jobId, recordId: handle.recordId });
          patchNode(executionId, node.nodeId, { jobId: handle.jobId, generationRecordId: handle.recordId });
          const outcome = await handle.completion;
          const status: ExecutionStatus = outcome.status === 'succeeded'
            ? 'succeeded'
            : outcome.status === 'cancelled' ? 'cancelled' : 'failed';
          patchNode(executionId, node.nodeId, {
            status,
            error: outcome.error,
            completedAt: Date.now(),
          });
          if (status === 'succeeded') {
            const known = new Set(executionOf(executionId)?.nodeIds ?? []);
            const additions = newlyReachableExecutableNodes(graphOf(), node.nodeId, known, actionByNode);
            if (additions.length) {
              for (const addition of additions) plannedByNode.set(addition.nodeId, addition);
              patchExecution(executionId, (currentExecution) => ({
                ...currentExecution,
                nodeIds: [...currentExecution.nodeIds, ...additions.map((addition) => addition.nodeId)],
                nodes: [
                  ...currentExecution.nodes,
                  ...additions.map((addition) => ({
                    nodeId: addition.nodeId,
                    actionId: addition.actionId,
                    status: 'pending' as const,
                  })),
                ],
              }));
            }
          }
          active.handles.delete(node.nodeId);
        })
        .catch((error) => {
          active.starting.delete(node.nodeId);
          patchNode(executionId, node.nodeId, {
            status: 'failed',
            error: error instanceof Error ? error.message : '执行失败',
            completedAt: Date.now(),
          });
          active.handles.delete(node.nodeId);
        });
      void running;
      changed = true;
    }

    if ((!changed && !ready.length && (active.handles.size || active.starting.size)) || waitingForResultSlot) {
      await new Promise((resolve) => setTimeout(resolve, 60));
    }
    if (!active.handles.size && !active.starting.size) {
      settleExecution(executionId);
      if (!activeExecutions.has(executionId)) return;
    }
  }
};

export function startGraphExecution(
  projectId: string,
  nodeIds?: string[],
  actionByNode: Record<string, string> = {},
  maxParallel = 2,
): CanvasExecution {
  const state = useCanvasStore.getState();
  if (state.projectId !== projectId || !state.canvasId) throw new Error('目标画布当前未在浏览器中打开');
  const graph = graphOf();
  const plan = planGraphExecution(graph, nodeIds, actionByNode);
  if (!plan.nodes.length) throw new Error('没有可执行的节点');
  if (plan.nodes.length > 100) throw new Error('单次最多执行 100 个节点');
  const limitedParallel = Math.max(1, Math.min(2, Math.floor(maxParallel || 2)));
  const execution: CanvasExecution = {
    id: `execution-${createId()}`,
    projectId,
    canvasId: state.canvasId,
    nodeIds: plan.order,
    status: 'running',
    maxParallel: limitedParallel,
    nodes: plan.nodes.map((node) => ({
      nodeId: node.nodeId,
      actionId: node.actionId,
      status: node.dependencies.length ? 'pending' : 'ready',
    })),
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
  useCanvasStore.getState().appendExecution(execution);
  activeExecutions.set(execution.id, { cancelled: false, handles: new Map(), starting: new Set() });
  void runExecution(execution.id, new Map(plan.nodes.map((node) => [node.nodeId, node])), actionByNode);
  return execution;
}

export function getGraphExecution(executionId: string) {
  return executionOf(executionId);
}

export async function cancelGraphExecution(executionId: string) {
  const active = activeExecutions.get(executionId);
  const execution = executionOf(executionId);
  if (!execution) throw new Error('执行批次不存在');
  if (!active) return execution;
  active.cancelled = true;
  await Promise.all([...active.handles.values()].map((handle) => cancelNodeExecution(handle.jobId).catch(() => undefined)));
  return executionOf(executionId) ?? execution;
}

export function recoverGraphExecutions() {
  const state = useCanvasStore.getState();
  const running = state.executions.filter((execution) =>
    execution.status === 'running' && !activeExecutions.has(execution.id));
  for (const execution of running) {
    useCanvasStore.getState().updateExecution({
      ...execution,
      status: 'interrupted',
      nodes: execution.nodes.map((node) => terminal(node.status)
        ? node
        : { ...node, status: 'interrupted', error: '页面刷新后执行已中断，请重新发起' }),
      updatedAt: Date.now(),
    });
  }
}
