import { createId } from '../utils/id';
import { useCanvasStore } from '../store/canvasStore';
import type {
  CanvasExecution,
  ExecutionNodeState,
  ExecutionStatus,
  GenerationRecord,
} from './graphContracts';
import type { CanvasGraphV2 } from './graphContracts';
import {
  newlyReachableExecutableNodes,
  planGraphExecution,
  type PlannedNode,
} from './graphExecutionPlanner';
import { cancelNodeExecution, describeNodeExecution, executeNodeAction } from './nodeExecutionService';
import { RESULT_SLOT_ACTION_ID, resolveResultSlots } from './resultSlots';
import {
  cancelCanvasExecution,
  createCanvasExecution,
  getCanvasExecution,
  isFinalExecutionStatus,
  listCanvasExecutions,
  type CanvasExecutionSnapshot,
  type CanvasExecutionStep,
} from '../services/canvasExecution';
import { jobAdapter } from '../services/capabilities';
import { materializeJobAsset } from './generationRuntime';

interface ActiveExecution {
  cancelled: boolean;
  handles: Map<string, { jobId: string; recordId?: string }>;
  starting: Set<string>;
  /** 最近一次写回 store 的批次快照：整图重载把 store 里的批次盖掉时用它恢复。 */
  last?: CanvasExecution;
}

const activeExecutions = new Map<string, ActiveExecution>();

/**
 * 本地批次 id → 服务端批次。服务端执行是默认路径：批次状态落库，刷新/断网都不断。
 * 只有服务端接口不可用（老后端 / 网络错误）时才回退到浏览器内执行。
 */
interface ServerExecution {
  canvasId: string;
  serverId: string;
  timer: number;
  materialized: Set<string>;
}

const serverExecutions = new Map<string, ServerExecution>();

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
  const active = activeExecutions.get(executionId);
  if (active) active.last = next;
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

const terminalRecordStatuses = new Set(['completed', 'failed', 'cancelled', 'interrupted']);

/**
 * 兜底：任务事件流可能丢终态帧（onTerminal 永远不触发，实测 12 条事件只收到 8 条）。
 * 生成记录已经终态、执行节点却还挂在 running 时，直接按记录收敛，
 * 否则串行分组（maxParallel=1）会永远卡在第一个节点。
 *
 * @returns 是否真的收敛了某个节点（调用方据此避免脏读）
 */
const settleNodesFromRecords = (
  executionId: string,
  active: ActiveExecution,
  execution: CanvasExecution,
): boolean => {
  const records = new Map(useCanvasStore.getState().generationRecords.map((record) => [record.id, record]));
  let changed = false;
  for (const node of execution.nodes) {
    if (node.status !== 'running' || !node.generationRecordId) continue;
    const record = records.get(node.generationRecordId);
    if (!record || !terminalRecordStatuses.has(record.status)) continue;
    const status: ExecutionStatus = record.status === 'completed'
      ? 'succeeded'
      : record.status === 'cancelled' ? 'cancelled' : 'failed';
    patchNode(executionId, node.nodeId, { status, error: record.error, completedAt: Date.now() });
    active.handles.delete(node.nodeId);
    active.starting.delete(node.nodeId);
    changed = true;
  }
  return changed;
};

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
    if (!active) return;
    if (!executionOf(executionId) && active.last && !active.cancelled) {
      // 实时刷新 / 切换画布会整图重载：服务端快照可能比内存里的批次旧，甚至没有这条批次。
      // 把内存里最新的一份放回去，批次继续跑，而不是静默死掉（表现为「只有第一个节点生了」）。
      useCanvasStore.getState().appendExecution(active.last);
    }
    const execution = executionOf(executionId);
    if (!execution) return;
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
    let current = executionOf(executionId)!;
    let changed = settleNodesFromRecords(executionId, active, current);
    if (changed) current = executionOf(executionId)!;
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
    const nodeOrder = refreshed.nodeIds;
    const pendingReady = refreshed.nodes
      .filter((node) => node.status === 'ready' && !active.handles.has(node.nodeId))
      .sort((left, right) => nodeOrder.indexOf(left.nodeId) - nodeOrder.indexOf(right.nodeId));
    // 结果槽只是「等结果落地」的虚拟节点，不占并发名额。串行批次（分组执行 maxParallel=1）里
    // 如果让一个还没填上的结果槽占掉唯一名额，后面真正要跑的节点会永远排不上队。
    const readySlots = pendingReady.filter((node) => node.actionId === RESULT_SLOT_ACTION_ID);
    const ready = pendingReady
      .filter((node) => node.actionId !== RESULT_SLOT_ACTION_ID)
      .slice(0, availableSlots);

    if (!ready.length && !readySlots.length && active.handles.size === 0 && active.starting.size === 0) {
      const unresolved = executionOf(executionId)!.nodes.filter((node) => !terminal(node.status));
      if (unresolved.length) {
        for (const node of unresolved) patchNode(executionId, node.nodeId, { status: 'blocked', error: '依赖节点未完成' });
      }
      settleExecution(executionId);
      return;
    }

    for (const node of [...readySlots, ...ready]) {
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

    if ((!changed && !ready.length && !readySlots.length && (active.handles.size || active.starting.size)) || waitingForResultSlot) {
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

  const steps = buildServerSteps(graph, plan);
  if (steps.length) {
    // 服务端执行（默认）：批次落库，刷新 / HMR / 断网都不会丢；接口不可用时回退本地执行。
    void submitServerExecution(execution, state.canvasId, steps)
      .catch((error) => {
        console.warn('[canvas-exec] 服务端执行不可用，回退浏览器内执行', error);
        startLocalRun(execution.id, plan, actionByNode);
      });
  } else {
    startLocalRun(execution.id, plan, actionByNode);
  }
  return execution;
}

/** 浏览器内执行（兜底路径）。 */
function startLocalRun(executionId: string, plan: { nodes: PlannedNode[] }, actionByNode: Record<string, string>) {
  activeExecutions.set(executionId, { cancelled: false, handles: new Map(), starting: new Set() });
  void runExecution(executionId, new Map(plan.nodes.map((node) => [node.nodeId, node])), actionByNode);
}

/** 把计划翻译成服务端可执行的步骤（跳过结果槽这类虚拟节点）。 */
function buildServerSteps(graph: CanvasGraphV2, plan: { nodes: PlannedNode[] }): CanvasExecutionStep[] {
  const steps: CanvasExecutionStep[] = [];
  for (const node of plan.nodes) {
    if (node.actionId === RESULT_SLOT_ACTION_ID) continue;
    const info = describeNodeExecution(node.nodeId, node.actionId);
    if (!info) continue;
    const slot = resolveResultSlots(graph, node.nodeId)
      .find((candidate) => candidate.kind === 'image' || candidate.kind === 'video');
    steps.push({
      nodeId: node.nodeId,
      actionId: node.actionId,
      capabilityId: info.capabilityId,
      clientRequestId: createId(),
      sourceAssetIds: info.sourceAssetIds,
      params: info.params,
      resultSlotNodeId: slot?.nodeId,
    });
  }
  return steps;
}

async function submitServerExecution(execution: CanvasExecution, canvasId: string, steps: CanvasExecutionStep[]) {
  const snapshot = await createCanvasExecution(canvasId, steps, execution.maxParallel);
  serverExecutions.set(execution.id, { canvasId, serverId: snapshot.id, timer: 0, materialized: new Set() });
  applyServerSnapshot(execution.id, snapshot);
  startServerPolling(execution.id);
}

function startServerPolling(localId: string) {
  const entry = serverExecutions.get(localId);
  if (!entry) return;
  const tick = async () => {
    const current = serverExecutions.get(localId);
    if (!current) return;
    try {
      const snapshot = await getCanvasExecution(current.canvasId, current.serverId);
      applyServerSnapshot(localId, snapshot);
      await materializeFinishedSteps(localId, snapshot);
      if (isFinalExecutionStatus(snapshot.status)) {
        window.clearInterval(current.timer);
        serverExecutions.delete(localId);
      }
    } catch {
      // 网络抖动：下一轮（1.5s 后）再试，批次在服务端继续跑，不会丢。
    }
  };
  entry.timer = window.setInterval(() => { void tick(); }, 1500);
  void tick();
}

/** 把服务端快照镜像到本地批次与节点阶段（1.5s 一次，transient 更新不触发保存）。 */
function applyServerSnapshot(localId: string, snapshot: CanvasExecutionSnapshot) {
  const execution = executionOf(localId);
  if (!execution) return;
  const remote = new Map(snapshot.nodes.map((node) => [node.nodeId, node]));
  const nodes = execution.nodes.map((node) => {
    const step = remote.get(node.nodeId);
    if (!step) return node;
    return {
      ...node,
      status: mapServerNodeStatus(step.status),
      jobId: step.jobId ?? node.jobId,
      error: step.error,
      completedAt: step.completedAt,
    };
  });
  const status: ExecutionStatus = snapshot.status === 'succeeded' ? 'succeeded'
    : snapshot.status === 'failed' ? 'failed'
      : snapshot.status === 'cancelled' ? 'cancelled' : 'running';
  useCanvasStore.getState().updateExecution({ ...execution, status, nodes, updatedAt: Date.now() });
  for (const step of snapshot.nodes) {
    if (step.status === 'pending') continue;
    const phase = step.status === 'succeeded' ? 'done'
      : step.status === 'failed' ? 'failed'
        : step.status === 'blocked' ? 'blocked'
          : step.status === 'cancelled' ? 'idle'
            : 'running';
    useCanvasStore.getState().updateNodeTransient(step.nodeId, {
      automation: {
        ...automationOf(step.nodeId),
        phase,
        ...(step.error ? { error: step.error } : {}),
      },
    });
  }
}

const mapServerNodeStatus = (status: string): ExecutionStatus => (
  status === 'succeeded' ? 'succeeded'
    : status === 'failed' ? 'failed'
      : status === 'blocked' ? 'blocked'
        : status === 'cancelled' ? 'cancelled'
          : status === 'running' ? 'running' : 'pending'
);

/**
 * 服务端只负责跑作业；结果落地仍走前端既有物化链路（建画布资产 → 挂到结果槽）。
 * 每个「节点 + job」只落地一次；失败下一轮重试。
 */
async function materializeFinishedSteps(localId: string, snapshot: CanvasExecutionSnapshot) {
  const entry = serverExecutions.get(localId);
  const getJob = jobAdapter.getJob;
  if (!entry || !getJob) return;
  for (const step of snapshot.nodes) {
    if (step.status !== 'succeeded' || !step.jobId || !step.resultAssetIds.length) continue;
    const key = `${step.nodeId}:${step.jobId}`;
    if (entry.materialized.has(key)) continue;
    const graph = graphOf();
    const slot = resolveResultSlots(graph, step.nodeId)
      .find((candidate) => candidate.kind === 'image' || candidate.kind === 'video');
    const targetNodeId = slot?.nodeId ?? step.nodeId;
    const alreadyFilled = Boolean(graph.nodes.find((node) => node.id === targetNodeId)?.data.assetId);
    if (alreadyFilled) {
      entry.materialized.add(key);
      continue;
    }
    // 服务端已经把结果挂回画布图（关着浏览器也生效）：直接用画布资产 id 显示，避免重复映射。
    if (step.attachedAssetId) {
      const target = graph.nodes.find((node) => node.id === targetNodeId);
      useCanvasStore.getState().attachAssetToNode(targetNodeId, {
        assetId: step.attachedAssetId,
        label: target?.data.label ?? '生成结果',
        size: { width: 320, height: 320 },
      });
      entry.materialized.add(key);
      continue;
    }
    entry.materialized.add(key);
    try {
      const job = await getJob.call(jobAdapter, step.jobId);
      const record: GenerationRecord = {
        id: `generation-${step.jobId}`,
        producerNodeId: step.nodeId,
        actionId: step.actionId,
        attempt: 1,
        jobId: step.jobId,
        status: 'completed',
        progress: 1,
        queueStatus: 'terminal',
        materializationStatus: 'pending',
        materializedAssetIds: [],
        resultNodeIds: [],
        resultSlots: slot ? [slot] : [],
        sources: [],
        prompt: '',
        effectivePrompt: '',
        params: {},
        startedAt: step.startedAt ?? Date.now(),
        completedAt: step.completedAt ?? Date.now(),
      };
      for (const asset of job?.assets ?? []) {
        await materializeJobAsset(record, {
          assetId: asset.id,
          name: asset.name,
          mimeType: asset.mimeType,
          downloadUrl: asset.downloadUrl,
        }, step.jobId);
      }
    } catch (error) {
      entry.materialized.delete(key);
      console.warn('[canvas-exec] 生成结果落地失败', error);
    }
  }
}

/**
 * 刷新 / 重新打开画布后，把服务端上仍在跑的批次接回本地（轮询 + 落地结果）。
 * 老后端没有这批接口时会静默失败。
 */
export async function syncServerExecutions(canvasId: string): Promise<void> {
  try {
    const executions = await listCanvasExecutions(canvasId);
    const running = executions.find((item) => item.status === 'running' || item.status === 'queued');
    if (!running) return;
    const localId = `server-${running.id}`;
    if (!executionOf(localId)) {
      useCanvasStore.getState().appendExecution({
        id: localId,
        projectId: useCanvasStore.getState().projectId ?? '',
        canvasId,
        nodeIds: running.nodes.map((node) => node.nodeId),
        status: 'running',
        maxParallel: running.maxParallel ?? 1,
        nodes: running.nodes.map((node) => ({
          nodeId: node.nodeId,
          actionId: node.actionId,
          status: 'pending' as const,
        })),
        createdAt: running.createdAt ?? Date.now(),
        updatedAt: Date.now(),
      });
    }
    if (!serverExecutions.has(localId)) {
      serverExecutions.set(localId, { canvasId, serverId: running.id, timer: 0, materialized: new Set() });
      startServerPolling(localId);
    }
    applyServerSnapshot(localId, running);
  } catch {
    // 老后端 / 网络不可用：保持本地行为，不影响画布。
  }
}

export function getGraphExecution(executionId: string) {
  return executionOf(executionId);
}

export async function cancelGraphExecution(executionId: string) {
  const active = activeExecutions.get(executionId);
  const execution = executionOf(executionId);
  if (!execution) throw new Error('执行批次不存在');
  // 服务端执行的批次：取消要打到服务端（同时会取消当前 job），本地只等快照收敛。
  const server = serverExecutions.get(executionId);
  if (server) {
    await cancelCanvasExecution(server.canvasId, server.serverId).catch(() => undefined);
    return executionOf(executionId) ?? execution;
  }
  if (!active) return execution;
  active.cancelled = true;
  await Promise.all([...active.handles.values()].map((handle) => cancelNodeExecution(handle.jobId).catch(() => undefined)));
  return executionOf(executionId) ?? execution;
}

const INTERRUPTED_MESSAGE = '页面刷新后执行已中断，请重新发起';

/** 该节点是否已经有产出的结果（自身带素材，或它的结果槽已被填上）。 */
const hasProducedResult = (graph: CanvasGraphV2, nodeId: string): boolean => {
  const node = graph.nodes.find((candidate) => candidate.id === nodeId);
  if (node?.data.assetId) return true;
  return resolveResultSlots(graph, nodeId, { includeFilled: true })
    .some((slot) => Boolean(graph.nodes.find((candidate) => candidate.id === slot.nodeId)?.data.assetId));
};

const automationOf = (nodeId: string): Record<string, unknown> => {
  const node = useCanvasStore.getState().nodes.find((candidate) => candidate.id === nodeId);
  return typeof node?.data.automation === 'object' && node.data.automation
    ? node.data.automation as Record<string, unknown>
    : {};
};

/**
 * 页面刷新 / 重新打开画布后收拾「上次没跑完的批次」。
 *
 * <p>以前只把批次标成 interrupted，节点上的 `automation.phase` 却停在 `running`：
 * 分组标题条会永远显示「生成中 1/4」，看起来像还在跑，其实一个任务都没在跑。
 * 现在同时把节点状态收敛到真实结果：
 * <ul>
 *   <li>刷新前正在跑、而且结果已经落地的节点（服务端任务没被中断）→ succeeded/done；</li>
 *   <li>结果槽已经填上的 → succeeded/done；</li>
 *   <li>其余没跑完的 → interrupted/blocked，并清掉「生成中」标记。</li>
 * </ul>
 */
export function recoverGraphExecutions() {
  const state = useCanvasStore.getState();
  const running = state.executions.filter((execution) =>
    execution.status === 'running'
    // 服务端执行的批次的真相在服务端，本地这份只是镜像：交给 syncServerExecutions 续接，不要判中断。
    && !activeExecutions.has(execution.id)
    && !serverExecutions.has(execution.id)
    && !execution.id.startsWith('server-'));
  if (!running.length) return;
  const graph = graphOf();
  for (const execution of running) {
    const nodes = execution.nodes.map((node) => {
      if (terminal(node.status)) return node;
      const canvasNode = graph.nodes.find((candidate) => candidate.id === node.nodeId);
      const resultSlotFilled = node.actionId === RESULT_SLOT_ACTION_ID
        && Boolean(canvasNode?.data.assetId);
      const settledWhileReloading = node.status === 'running' && hasProducedResult(graph, node.nodeId);
      if (resultSlotFilled || settledWhileReloading) {
        useCanvasStore.getState().updateNodeTransient(node.nodeId, {
          isGenerating: false,
          progressPercent: 0,
          automation: { ...automationOf(node.nodeId), phase: 'done', error: undefined },
        });
        return { ...node, status: 'succeeded' as const, error: undefined, completedAt: node.completedAt ?? Date.now() };
      }
      useCanvasStore.getState().updateNodeTransient(node.nodeId, {
        isGenerating: false,
        progressPercent: 0,
        automation: { ...automationOf(node.nodeId), phase: 'blocked', error: INTERRUPTED_MESSAGE },
      });
      return { ...node, status: 'interrupted' as const, error: INTERRUPTED_MESSAGE };
    });
    useCanvasStore.getState().updateExecution({
      ...execution,
      status: nodes.some((node) => node.status === 'interrupted') ? 'interrupted' : 'succeeded',
      nodes,
      updatedAt: Date.now(),
    });
  }
}
