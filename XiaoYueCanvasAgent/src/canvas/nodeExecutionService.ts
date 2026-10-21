import { useCanvasStore } from '../store/canvasStore';
import type { CanvasNode } from '../types';
import {
  getInputBindings,
  getNodeDefinition,
  validateRunnable,
  type CanvasGraphV2,
} from './graphContracts';
import {
  submitImageEdit,
  submitImageGeneration,
  submitImageRedraw,
  submitScriptDrafting,
  submitVideoGeneration,
  type GenerationTerminalOutcome,
} from '../services/jobRunner';
import { jobAdapter } from '../services/capabilities';
import { resolveResultSlots, validateResultSlotOwnership } from './resultSlots';
import { useMediaStore } from '../store/mediaStore';
import { getAsset } from '../services/projectStorage';
import { resolveActionId } from './generationModes';

export interface NodeExecutionHandle {
  jobId: string;
  recordId?: string;
  completion: Promise<GenerationTerminalOutcome>;
}

const textOf = (node: CanvasNode | undefined): string => {
  if (!node) return '';
  return String(
    node.data.scriptText
    ?? node.data.promptText
    ?? node.data.richText
    ?? node.data.sceneDescription
    ?? node.data.prompt
    ?? '',
  ).trim();
};

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

const executionParams = (node: CanvasNode, actionId: string): Record<string, unknown> => {
  const data = node.data;
  const gatewayParams = data.gatewayParams && typeof data.gatewayParams === 'object'
    ? data.gatewayParams as Record<string, unknown>
    : {};
  return {
    ...gatewayParams,
    actionId,
    ...(typeof data.model === 'string' ? { model: data.model } : {}),
    ...(typeof data.size === 'string' ? { size: data.size } : {}),
    ...(typeof data.quality === 'string' ? { quality: data.quality } : {}),
    ...(typeof data.style === 'string' ? { style: data.style } : {}),
    ...(typeof data.aspectRatio === 'string' ? { aspectRatio: data.aspectRatio } : {}),
    ...(typeof data.n === 'number' ? { n: data.n } : {}),
    ...(typeof data.resolution === 'string' ? { resolution: data.resolution } : {}),
    ...(typeof data.duration === 'number' ? { duration: data.duration } : {}),
    ...(typeof data.durationSeconds === 'number' ? { duration: data.durationSeconds } : {}),
    // 合并后的生成节点把模式存在 data.mode；旧字段 data.videoMode 继续兼容，对外仍发 videoMode
    ...(typeof data.mode === 'string'
      ? { videoMode: data.mode }
      : typeof data.videoMode === 'string' ? { videoMode: data.videoMode } : {}),
    ...(typeof data.negativePrompt === 'string' ? { negativePrompt: data.negativePrompt } : {}),
  };
};

const executionInputs = (nodeId: string, actionId: string) => {
  const graph = graphOf();
  const bindings = getInputBindings(graph, nodeId, actionId);
  const bindingList = Object.values(bindings).flat();
  const promptParts = [
    textOf(graph.nodes.find((node) => node.id === nodeId) as CanvasNode | undefined),
  ];
  for (const edge of graph.edges.filter((candidate) => candidate.target === nodeId)) {
    const source = graph.nodes.find((candidate) => candidate.id === edge.source);
    const text = textOf(source as CanvasNode | undefined);
    if (text) promptParts.push(text);
  }
  return {
    prompt: [...new Set(promptParts.filter(Boolean))].join('\n\n'),
    sourceAssetIds: [...new Set(bindingList.flatMap((binding) => binding.assetId ? [binding.assetId] : []))],
    sourceNodeIds: [...new Set(bindingList.map((binding) => binding.nodeId).filter(Boolean))],
  };
};

const completionHandle = (
  jobId: string,
  completion: Promise<GenerationTerminalOutcome>,
): { jobId: string; recordId?: string; completion: Promise<GenerationTerminalOutcome> } => {
  const recordId = useCanvasStore.getState().generationRecords.find((record) => record.jobId === jobId)?.id;
  return { jobId, recordId, completion };
};

export async function executeNodeAction(
  projectId: string,
  nodeId: string,
  requestedActionId?: string,
  signal?: AbortSignal,
): Promise<NodeExecutionHandle> {
  const state = useCanvasStore.getState();
  if (state.projectId !== projectId) throw new Error('目标项目当前未在浏览器中打开');
  const node = state.nodes.find((candidate) => candidate.id === nodeId);
  if (!node) throw new Error('节点不存在');
  const definition = getNodeDefinition(node.data.kind);
  if (!definition) throw new Error(`节点类型 ${node.data.kind} 不支持执行`);
  const startable = definition.actions.filter((action) => action.automation.startable);
  // 合并后的图片节点有两个动作（generate / redraw），默认动作由当前模式决定
  const preferredActionId = resolveActionId(node.data.kind, node.data);
  const action = requestedActionId
    ? startable.find((candidate) => candidate.id === requestedActionId)
    : startable.find((candidate) => candidate.id === preferredActionId) ?? startable[0];
  if (!action) throw new Error(requestedActionId ? `节点动作 ${requestedActionId} 不可执行` : '节点没有可执行动作');
  const runnable = validateRunnable(graphOf(), nodeId, action.id);
  if (!runnable.ok) throw new Error(runnable.message ?? '节点输入不完整，暂时不能运行');

  const input = executionInputs(nodeId, action.id);
  const resultKind = action.capabilityId === 'video.generate' ? 'video' : 'image';
  const resultSlots = action.capabilityId === 'image.generate'
    || action.capabilityId === 'image.redraw'
    || action.capabilityId === 'video.generate'
    ? resolveResultSlots(graphOf(), nodeId).filter((slot) => slot.kind === resultKind)
    : [];
  const slotConflict = validateResultSlotOwnership(graphOf(), nodeId, resultSlots);
  if (!slotConflict.ok) throw new Error(slotConflict.message ?? '结果槽配置冲突');
  let resolveTerminal!: (outcome: GenerationTerminalOutcome) => void;
  const completion = new Promise<GenerationTerminalOutcome>((resolve) => { resolveTerminal = resolve; });
  const onTerminal = (outcome: GenerationTerminalOutcome) => resolveTerminal(outcome);

  let jobId: string;
  if (node.data.kind === 'storyboardPlanner') {
    if (!input.prompt.trim()) throw new Error('分镜拆解需要剧本或画面提示词');
    jobId = await submitScriptDrafting(input.prompt, nodeId, () => undefined, {
      sourceAssetIds: input.sourceAssetIds,
      sourceNodeIds: input.sourceNodeIds,
      onTerminal,
    });
  } else if (action.capabilityId === 'video.generate') {
    if (!input.prompt.trim()) throw new Error('生视频需要提示词');
    jobId = await submitVideoGeneration(input.prompt, nodeId, () => undefined, {
      sourceAssetIds: input.sourceAssetIds,
      sourceNodeIds: input.sourceNodeIds,
      params: executionParams(node, action.id),
      resultSlots,
      onTerminal,
      signal,
    });
  } else if (action.capabilityId === 'image.redraw' && node.data.redraw) {
    if (!input.prompt.trim()) throw new Error('图生图需要提示词');
    const sourceAssetId = input.sourceAssetIds[0];
    const sourceAsset = sourceAssetId
      ? useMediaStore.getState().assets[sourceAssetId] ?? await getAsset(sourceAssetId).catch(() => undefined)
      : undefined;
    if (!sourceAsset) throw new Error('图生图缺少源图片');
    jobId = await submitImageRedraw(
      input.prompt,
      nodeId,
      node.data.redraw,
      { width: sourceAsset.width, height: sourceAsset.height },
      () => undefined,
      {
        sourceAssetIds: input.sourceAssetIds,
        sourceNodeIds: input.sourceNodeIds,
        params: executionParams(node, action.id),
        resultSlots,
        onTerminal,
        signal,
      },
    );
  } else if (action.capabilityId === 'image.generate' || action.capabilityId === 'image.redraw') {
    if (!input.prompt.trim()) throw new Error('生图需要提示词');
    // 图生图在没有区域掩码时走「整图编辑」，和节点上点生成完全同一条链路；
    // 绝不能因为缺少掩码把节点直接判失败（分组执行会连带把下游全 block 掉）。
    const submit = action.capabilityId === 'image.redraw' ? submitImageEdit : submitImageGeneration;
    jobId = await submit(input.prompt, nodeId, () => undefined, {
      sourceAssetIds: input.sourceAssetIds,
      sourceNodeIds: input.sourceNodeIds,
      params: executionParams(node, action.id),
      capabilityId: action.capabilityId,
      resultSlots,
      onTerminal,
      signal,
    });
  } else {
    throw new Error(`节点动作 ${action.id} 暂不支持后台执行`);
  }

  return completionHandle(jobId, completion);
}

export async function cancelNodeExecution(jobId: string) {
  await jobAdapter.cancel(jobId);
}

/**
 * 把「这个节点这一步要提交什么」抽成纯数据，供服务端批次执行使用。
 *
 * <p>服务端不复制画布节点契约（端口 / 模式 / 结果槽规则都在前端），只负责按序可靠地把
 * 这些 job 请求跑完；因此计划里必须带齐 capabilityId / params / 参考素材。
 */
export function describeNodeExecution(nodeId: string, actionId: string) {
  const graph = graphOf();
  const node = graph.nodes.find((candidate) => candidate.id === nodeId) as CanvasNode | undefined;
  const action = getNodeDefinition(node?.data.kind)?.actions.find((item) => item.id === actionId);
  if (!node || !action) return undefined;
  const input = executionInputs(nodeId, actionId);
  return {
    capabilityId: action.capabilityId,
    params: { ...executionParams(node, actionId), prompt: input.prompt },
    prompt: input.prompt,
    sourceAssetIds: input.sourceAssetIds,
  };
}
