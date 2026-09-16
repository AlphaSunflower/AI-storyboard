import { canvasCommandService } from './canvasCommandService';
import { canvasQueryService } from './canvasQueryService';
import type { CanvasCommandContext, CanvasCommandResult } from './canvasTypes';
import { AUTO, CONFIRM, autoWithDryRun, APPROVAL_THRESHOLDS, type ApprovalPolicy } from './approvalPolicy';
import type { AlignAxis, DistributeAxis } from './layout/alignment';
import type { AgentToolCall, AgentToolResult, AnnotationShape, MediaKind } from '../types';
import { cancelGraphExecution, getGraphExecution, startGraphExecution } from './graphExecutionService';
import { planGraphExecution } from './graphExecutionPlanner';
import { executeNodeAction } from './nodeExecutionService';
import { useCanvasStore } from '../store/canvasStore';
import type { CanvasGraphV2 } from './graphContracts';
import { getNodeModelConfig, setNodeModelConfig } from './canvasModelConfigService';

export interface ToolDescriptor {
  name: string;
  description: string;
  readOnly: boolean;
  approval: ApprovalPolicy;
  execute: (context: CanvasCommandContext, args: Record<string, unknown>) => CanvasCommandResult | Promise<CanvasCommandResult>;
}

const stringValue = (value: unknown, field: string) => {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${field} 必须是非空字符串`);
  return value;
};

const booleanValue = (value: unknown, field: string) => {
  if (typeof value !== 'boolean') throw new Error(`${field} 必须是布尔值`);
  return value;
};

const objectValue = (value: unknown, field: string) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${field} 必须是对象`);
  return value as Record<string, unknown>;
};

const stringArray = (value: unknown, field: string) => {
  if (!Array.isArray(value) || !value.every((item) => typeof item === 'string')) throw new Error(`${field} 必须是字符串数组`);
  return [...new Set(value)];
};

const nodeIds = (value: unknown) => stringArray(value, 'nodeIds');

const position = (value: unknown, field = 'position') => {
  const item = objectValue(value, field);
  if (!Number.isFinite(item.x) || !Number.isFinite(item.y)) throw new Error(`${field}.x/y 必须是数字`);
  return { x: Number(item.x), y: Number(item.y) };
};

const sizes = (value: unknown) => {
  if (!Array.isArray(value)) throw new Error('sizes 必须是数组');
  return value.map((raw) => {
    const item = objectValue(raw, 'sizes[]');
    if (!Number.isFinite(item.width) || !Number.isFinite(item.height)) throw new Error('sizes[].width/height 必须是数字');
    return { nodeId: stringValue(item.nodeId, 'sizes[].nodeId'), width: Number(item.width), height: Number(item.height) };
  });
};

const numberValue = (value: unknown, field: string) => {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`${field} 必须是数字`);
  return value;
};

const cropValue = (value: unknown) => {
  const item = objectValue(value, 'crop');
  return {
    x: numberValue(item.x, 'crop.x'),
    y: numberValue(item.y, 'crop.y'),
    width: numberValue(item.width, 'crop.width'),
    height: numberValue(item.height, 'crop.height'),
  };
};

const shapesValue = (value: unknown) => {
  if (!Array.isArray(value)) throw new Error('shapes 必须是数组');
  return value as AnnotationShape[];
};

const oneOf = <T extends string>(value: unknown, field: string, allowed: readonly T[]): T => {
  const item = stringValue(value, field);
  if (!allowed.includes(item as T)) throw new Error(`${field} 必须是 ${allowed.join(' / ')} 之一`);
  return item as T;
};

const tool = (descriptor: ToolDescriptor): ToolDescriptor => descriptor;

export const CANVAS_TOOLS: ToolDescriptor[] = [
  // ── 读取 ─────────────────────────────────────────────────────────────
  tool({ name: 'canvas.get_context', description: '读取浏览器当前真实打开的项目与画布。', readOnly: true, approval: AUTO, execute: () => canvasQueryService.getContext() }),
  tool({ name: 'canvas.get_state', description: '读取当前画布摘要、节点、连线、占位状态、缺失输入和可自动修复的媒体中继建议。', readOnly: true, approval: AUTO, execute: (context, args) => canvasQueryService.getState(context.projectId, { limit: typeof args.limit === 'number' ? args.limit : undefined, offset: typeof args.offset === 'number' ? args.offset : undefined, includeEdges: args.includeEdges !== false }) }),
  tool({ name: 'canvas.get_summary', description: '读取画布摘要：节点数、连线数、revision。', readOnly: true, approval: AUTO, execute: (context) => canvasQueryService.getSummary(context.projectId) }),
  tool({ name: 'canvas.get_revision', description: '读取当前画布 revision。', readOnly: true, approval: AUTO, execute: (context) => canvasQueryService.getRevision(context.projectId) }),
  tool({ name: 'canvas.list_nodes', description: '列出当前画布节点摘要，包括 image/video 占位状态、结果槽绑定和缺失输入。', readOnly: true, approval: AUTO, execute: (context, args) => canvasQueryService.listNodes(context.projectId, args.nodeIds ? nodeIds(args.nodeIds) : undefined) }),
  tool({ name: 'canvas.get_node', description: '读取单个节点摘要。', readOnly: true, approval: AUTO, execute: (context, args) => canvasQueryService.getNode(context.projectId, stringValue(args.nodeId, 'nodeId')) }),
  tool({ name: 'canvas.list_edges', description: '列出连线及其 resultSlot、resultSlotOrder、portOrder 元数据。', readOnly: true, approval: AUTO, execute: (context, args) => canvasQueryService.listEdges(context.projectId, args.edgeIds ? stringArray(args.edgeIds, 'edgeIds') : undefined) }),
  tool({ name: 'canvas.get_selection', description: '读取当前用户选区。', readOnly: true, approval: AUTO, execute: (context) => canvasQueryService.getSelection(context.projectId) }),
  tool({ name: 'canvas.list_node_types', description: '列出节点类型、flowRole、媒体占位契约、端口、可接受上游和动作。', readOnly: true, approval: AUTO, execute: (context) => canvasCommandService.listNodeTypes(context) }),
  tool({ name: 'canvas.model_config.get', description: '读取生成节点当前模型、已保存参数、兼容模型目录和递归参数 Schema。', readOnly: true, approval: AUTO, execute: (context, args) => getNodeModelConfig(context, { nodeId: stringValue(args.nodeId, 'nodeId') }) }),

  // ── 节点创建 ─────────────────────────────────────────────────────────
  tool({ name: 'canvas.node.create', description: '创建一个画布节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNode(context, { kind: stringValue(args.kind, 'kind') as MediaKind, position: args.position ? position(args.position) : undefined, label: typeof args.label === 'string' ? args.label : undefined, prompt: typeof args.prompt === 'string' ? args.prompt : undefined, data: args.data ? objectValue(args.data, 'data') : undefined }) }),
  tool({ name: 'canvas.node.create_text', description: '创建一个文字节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNode(context, { kind: 'text', position: args.position ? position(args.position) : undefined, label: typeof args.label === 'string' ? args.label : undefined, prompt: typeof args.prompt === 'string' ? args.prompt : undefined }) }),
  tool({ name: 'canvas.node.create_image_placeholder', description: '创建一个空图片节点；若要承接生成结果，优先使用 canvas.result_slots.create/mark 建立结果槽语义。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNode(context, { kind: 'image', position: args.position ? position(args.position) : undefined, label: typeof args.label === 'string' ? args.label : undefined, prompt: typeof args.prompt === 'string' ? args.prompt : undefined }) }),
  tool({ name: 'canvas.node.create_video', description: '创建一个视频节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNode(context, { kind: 'video', position: args.position ? position(args.position) : undefined, label: typeof args.label === 'string' ? args.label : undefined, prompt: typeof args.prompt === 'string' ? args.prompt : undefined }) }),
  tool({ name: 'canvas.node.create_many', description: '一次创建多个节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNodes(context, { items: (Array.isArray(args.items) ? args.items : []).map((raw) => { const item = objectValue(raw, 'items[]'); return { kind: stringValue(item.kind, 'items[].kind') as MediaKind, position: item.position ? position(item.position, 'items[].position') : undefined, label: typeof item.label === 'string' ? item.label : undefined, prompt: typeof item.prompt === 'string' ? item.prompt : undefined, data: item.data ? objectValue(item.data, 'items[].data') : undefined }; }) }) }),
  tool({ name: 'canvas.media.import', description: '下载图片或视频引用并导入当前画布，创建真实媒体节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.importMedia(context, {
    kind: oneOf(args.kind, 'kind', ['image', 'video'] as const),
    url: stringValue(args.url, 'url'),
    name: typeof args.name === 'string' ? args.name : undefined,
    mimeType: typeof args.mimeType === 'string' ? args.mimeType : undefined,
    label: typeof args.label === 'string' ? args.label : undefined,
    position: args.position ? position(args.position) : undefined,
  }) }),
  tool({ name: 'canvas.canvas.create', description: '在当前项目新建一个画布；activate=true 时创建后切换过去。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createCanvas(context, {
    title: typeof args.title === 'string' ? args.title : undefined,
    activate: args.activate === true,
  }) }),
  tool({ name: 'canvas.canvas.rename', description: '重命名当前画布或指定 canvasId 的画布。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.renameCanvas(context, {
    canvasId: typeof args.canvasId === 'string' ? args.canvasId : undefined,
    title: stringValue(args.title, 'title'),
  }) }),
  tool({ name: 'canvas.result_slots.create', description: '为生产节点创建有序 image/video 结果槽并自动连接。结果槽的 origin 输入接收生成结果，媒体输出口可继续连接下游；最终结果槽只保留输入。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createResultSlots(context, {
    producerNodeId: stringValue(args.producerNodeId, 'producerNodeId'),
    kind: oneOf(args.kind, 'kind', ['image', 'video'] as const),
    count: numberValue(args.count, 'count'),
    position: args.position ? position(args.position) : undefined,
    gapY: typeof args.gapY === 'number' ? args.gapY : undefined,
  }) }),
  tool({ name: 'canvas.result_slots.mark', description: '把已有空 image/video 节点按顺序标记为生产节点的结果槽；中间槽可继续连接下游，最终槽只保留输入。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.markResultSlots(context, {
    producerNodeId: stringValue(args.producerNodeId, 'producerNodeId'),
    nodeIds: nodeIds(args.nodeIds),
    startOrder: typeof args.startOrder === 'number' ? args.startOrder : undefined,
  }) }),

  // ── 节点修改 ─────────────────────────────────────────────────────────
  tool({ name: 'canvas.model_config.set', description: '严格按网关 Schema 修改生成节点的模型和参数；换模会重置为新模型默认值。', readOnly: false, approval: AUTO, execute: (context, args) => setNodeModelConfig(context, {
    nodeId: stringValue(args.nodeId, 'nodeId'),
    model: typeof args.model === 'string' ? args.model : undefined,
    params: args.params ? objectValue(args.params, 'params') : undefined,
    resetToDefaults: args.resetToDefaults === true,
  }) }),
  tool({ name: 'canvas.node.update', description: '更新节点标题、提示词或普通节点数据；模型参数必须使用 canvas.model_config.set。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.updateNode(context, { nodeId: stringValue(args.nodeId, 'nodeId'), patch: objectValue(args.patch, 'patch') }) }),
  tool({ name: 'canvas.node.delete', description: '删除指定节点及其连线。删除操作需要确认。', readOnly: false, approval: CONFIRM, execute: (context, args) => canvasCommandService.deleteNodes(context, { nodeIds: nodeIds(args.nodeIds) }) }),
  tool({ name: 'canvas.node.duplicate', description: '复制指定节点和节点内部连线。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.duplicateNodes(context, { nodeIds: nodeIds(args.nodeIds), offset: args.offset ? position(args.offset, 'offset') : undefined }) }),
  tool({ name: 'canvas.node.copy', description: '复制指定节点到剪贴板。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.copyNodes(context, { nodeIds: nodeIds(args.nodeIds) }) }),
  tool({ name: 'canvas.node.paste', description: '在指定位置粘贴剪贴板节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.pasteNodes(context, { position: args.position ? position(args.position) : undefined }) }),
  tool({ name: 'canvas.node.move_many', description: '批量移动节点。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.moveNodes), execute: (context, args) => canvasCommandService.moveNodes(context, { positions: (Array.isArray(args.positions) ? args.positions : []).map((raw) => { const item = objectValue(raw, 'positions[]'); const point = position(item, 'positions[]'); return { nodeId: stringValue(item.nodeId, 'positions[].nodeId'), x: point.x, y: point.y }; }) }) }),
  tool({ name: 'canvas.node.resize', description: '调整节点尺寸。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.resizeNodes), execute: (context, args) => canvasCommandService.resizeNodes(context, { sizes: sizes(args.sizes) }) }),
  tool({ name: 'canvas.node.pin', description: '固定或取消固定节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.pinNodes(context, { nodeIds: nodeIds(args.nodeIds), pinned: booleanValue(args.pinned, 'pinned') }) }),

  // ── 连线 ─────────────────────────────────────────────────────────────
  tool({ name: 'canvas.edge.create', description: '在两个节点之间创建连线。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createEdge(context, { source: stringValue(args.source, 'source'), target: stringValue(args.target, 'target'), sourceHandle: typeof args.sourceHandle === 'string' ? args.sourceHandle : undefined, targetHandle: typeof args.targetHandle === 'string' ? args.targetHandle : undefined }) }),
  tool({ name: 'canvas.edge.delete', description: '删除指定连线。删除操作需要确认。', readOnly: false, approval: CONFIRM, execute: (context, args) => canvasCommandService.deleteEdges(context, { edgeIds: stringArray(args.edgeIds, 'edgeIds') }) }),

  // ── 执行 ─────────────────────────────────────────────────────────────
  tool({ name: 'canvas.node.execute', description: '提交单个节点的生成动作，立即返回 jobId。', readOnly: false, approval: AUTO, execute: async (context, args) => {
    const nodeId = stringValue(args.nodeId, 'nodeId');
    const actionId = typeof args.actionId === 'string' ? args.actionId : undefined;
    if (context.dryRun) return { ok: true, status: 'preview' as const, transactionId: `tx-preview-${context.requestId ?? 'execute'}`, revision: useCanvasStore.getState().revision, changes: { description: '执行 1 个节点' }, result: { nodeId, actionId } };
    const handle = await executeNodeAction(context.projectId, nodeId, actionId, context.signal);
    return {
      ok: true,
      status: 'accepted' as const,
      revision: useCanvasStore.getState().revision,
      changes: { affectedNodeIds: [nodeId], description: '执行 1 个节点' },
      result: { nodeId, actionId, jobId: handle.jobId, recordId: handle.recordId },
    };
  } }),
  tool({ name: 'canvas.graph.execute', description: '按依赖顺序执行画布节点，立即返回 executionId。', readOnly: false, approval: AUTO, execute: (context, args) => {
    const selectedNodeIds = args.nodeIds ? nodeIds(args.nodeIds) : undefined;
    const rawActions = args.actionByNode ? objectValue(args.actionByNode, 'actionByNode') : {};
    const actionByNode = Object.fromEntries(
      Object.entries(rawActions).filter((entry): entry is [string, string] => typeof entry[1] === 'string'),
    );
    const maxParallel = typeof args.maxParallel === 'number' ? args.maxParallel : 2;
    if (context.dryRun) {
      const state = useCanvasStore.getState();
      const graph: CanvasGraphV2 = {
        schemaVersion: 2,
        nodes: state.nodes as CanvasGraphV2['nodes'],
        edges: state.edges as CanvasGraphV2['edges'],
        viewport: { x: 0, y: 0, zoom: 1 },
        generationRecords: state.generationRecords,
        executions: state.executions,
      };
      const plan = planGraphExecution(graph, selectedNodeIds, actionByNode);
      return { ok: true, status: 'preview' as const, transactionId: `tx-preview-graph`, revision: state.revision, changes: { affectedNodeIds: plan.order, description: `预览执行 ${plan.order.length} 个节点` }, result: { nodeIds: plan.order, maxParallel } };
    }
    const execution = startGraphExecution(context.projectId, selectedNodeIds, actionByNode, maxParallel);
    return {
      ok: true,
      status: 'accepted' as const,
      revision: useCanvasStore.getState().revision,
      changes: { affectedNodeIds: execution.nodeIds, description: `开始执行 ${execution.nodeIds.length} 个节点` },
      result: { executionId: execution.id, nodeIds: execution.nodeIds, maxParallel: execution.maxParallel },
    };
  } }),
  tool({ name: 'canvas.execution.get', description: '读取 DAG 批跑状态。', readOnly: true, approval: AUTO, execute: (_context, args) => {
    const execution = getGraphExecution(stringValue(args.executionId, 'executionId'));
    return execution
      ? { ok: true, status: 'applied', result: execution }
      : { ok: false, status: 'failed', error: { code: 'EXECUTION_NOT_FOUND', message: '执行批次不存在' } };
  } }),
  tool({ name: 'canvas.execution.cancel', description: '取消 DAG 批跑及仍在运行的节点任务。', readOnly: false, approval: AUTO, execute: async (_context, args) => {
    const execution = await cancelGraphExecution(stringValue(args.executionId, 'executionId'));
    return { ok: true, status: 'applied', result: execution, changes: { affectedNodeIds: execution.nodeIds, description: '取消 DAG 批跑' } };
  } }),

  // ── 布局 ─────────────────────────────────────────────────────────────
  tool({ name: 'canvas.layout', description: '将选定节点按网格、横向、纵向或依赖层级排布。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.layoutNodes), execute: (context, args) => canvasCommandService.layout(context, { nodeIds: args.nodeIds ? nodeIds(args.nodeIds) : undefined, mode: oneOf(args.mode ?? 'grid', 'mode', ['grid', 'horizontal', 'vertical', 'dag'] as const), gapX: typeof args.gapX === 'number' ? args.gapX : undefined, gapY: typeof args.gapY === 'number' ? args.gapY : undefined, columns: typeof args.columns === 'number' ? args.columns : undefined, anchor: args.anchor ? position(args.anchor, 'anchor') : undefined }) }),
  tool({ name: 'canvas.layout.grid', description: '将节点按网格排布。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.layoutNodes), execute: (context, args) => canvasCommandService.layoutGrid(context, { nodeIds: args.nodeIds ? nodeIds(args.nodeIds) : undefined, gapX: typeof args.gapX === 'number' ? args.gapX : undefined, gapY: typeof args.gapY === 'number' ? args.gapY : undefined, columns: typeof args.columns === 'number' ? args.columns : undefined, anchor: args.anchor ? position(args.anchor, 'anchor') : undefined }) }),
  tool({ name: 'canvas.layout.dag', description: '根据连线依赖从左到右、从上到下排布节点。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.layoutNodes), execute: (context, args) => canvasCommandService.layoutDag(context, { nodeIds: args.nodeIds ? nodeIds(args.nodeIds) : undefined, gapX: typeof args.gapX === 'number' ? args.gapX : undefined, gapY: typeof args.gapY === 'number' ? args.gapY : undefined, anchor: args.anchor ? position(args.anchor, 'anchor') : undefined, direction: args.direction === 'down' ? 'down' : 'right' }) }),
  tool({ name: 'canvas.layout.align', description: '对齐节点。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.layoutNodes), execute: (context, args) => canvasCommandService.layoutAlign(context, { nodeIds: args.nodeIds ? nodeIds(args.nodeIds) : undefined, axis: oneOf<AlignAxis>(args.axis, 'axis', ['left', 'right', 'top', 'bottom', 'centerX', 'centerY']) }) }),
  tool({ name: 'canvas.layout.distribute', description: '沿水平或垂直方向均匀分布节点。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.layoutNodes), execute: (context, args) => canvasCommandService.layoutDistribute(context, { nodeIds: args.nodeIds ? nodeIds(args.nodeIds) : undefined, axis: oneOf<DistributeAxis>(args.axis, 'axis', ['horizontal', 'vertical']) }) }),

  // ── 语义操作（AI 驱动，落成因果链派生节点）──────────────────────────
  tool({ name: 'canvas.image.crop', description: '对指定图片节点裁剪，生成派生节点并连线。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.imageCrop(context, { nodeId: stringValue(args.nodeId, 'nodeId'), crop: cropValue(args.crop) }) }),
  tool({ name: 'canvas.image.split', description: '将指定图片节点切分为网格，生成派生节点并连线。', readOnly: false, approval: { level: 'auto', forceDryRun: (args) => (Number(args.rows) || 0) * (Number(args.columns) || 0) > 12 }, execute: (context, args) => canvasCommandService.imageSplit(context, { nodeId: stringValue(args.nodeId, 'nodeId'), rows: numberValue(args.rows, 'rows'), columns: numberValue(args.columns, 'columns') }) }),
  tool({ name: 'canvas.image.adjust', description: '调整指定图片节点的亮度/对比度/饱和度，生成派生节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.imageAdjust(context, { nodeId: stringValue(args.nodeId, 'nodeId'), brightness: numberValue(args.brightness, 'brightness'), contrast: numberValue(args.contrast, 'contrast'), saturation: numberValue(args.saturation, 'saturation') }) }),
  tool({ name: 'canvas.image.annotate', description: '在指定图片节点上绘制标注，生成派生节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.imageAnnotate(context, { nodeId: stringValue(args.nodeId, 'nodeId'), shapes: shapesValue(args.shapes) }) }),

  // ── 历史 ─────────────────────────────────────────────────────────────
  tool({ name: 'canvas.undo', description: '撤销上一条画布事务。', readOnly: false, approval: AUTO, execute: (context) => canvasCommandService.undo(context) }),
  tool({ name: 'canvas.redo', description: '重做上一条画布事务。', readOnly: false, approval: AUTO, execute: (context) => canvasCommandService.redo(context) }),
];

const registry = new Map(CANVAS_TOOLS.map((descriptor) => [descriptor.name, descriptor]));

export const getToolDescriptors = () => CANVAS_TOOLS.map(({ execute: _execute, ...descriptor }) => descriptor);

// 判断某次调用是否需要先生成 preview（confirm 工具，或 auto 工具但参数规模超阈值）。
export function shouldDryRun(name: string, args: Record<string, unknown>): boolean {
  const descriptor = registry.get(name);
  if (!descriptor) return true;
  if (descriptor.approval.level === 'confirm') return true;
  if (descriptor.approval.forceDryRun?.(args)) return true;
  return false;
}

export async function executeToolCall(projectId: string, call: AgentToolCall, options: { source?: CanvasCommandContext['source']; dryRun?: boolean; expectedRevision?: number; signal?: AbortSignal } = {}): Promise<AgentToolResult> {
  const descriptor = registry.get(call.name);
  if (!descriptor) return { toolCallId: call.id, name: call.name, ok: false, error: { code: 'TOOL_NOT_FOUND', message: `未知画布工具：${call.name}` } };
  const context: CanvasCommandContext = {
    projectId,
    source: options.source ?? 'agent',
    dryRun: options.dryRun ?? shouldDryRun(call.name, call.arguments ?? {}),
    expectedRevision: options.expectedRevision,
    signal: options.signal,
  };
  try {
    const result = await descriptor.execute(context, call.arguments ?? {});
    return { toolCallId: call.id, name: call.name, ok: result.ok, status: result.status, result: result.result, error: result.error, transactionId: result.transactionId, revision: result.revision };
  } catch (caught) {
    return { toolCallId: call.id, name: call.name, ok: false, error: { code: 'INVALID_ARGUMENTS', message: caught instanceof Error ? caught.message : '工具参数无效' }, status: 'failed' };
  }
}

export const getTool = (name: string) => registry.get(name);
