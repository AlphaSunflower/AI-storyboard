import { canvasCommandService } from './canvasCommandService';
import { canvasQueryService } from './canvasQueryService';
import type { CanvasCommandContext, CanvasCommandResult } from './canvasTypes';
import { AUTO, CONFIRM, autoWithDryRun, APPROVAL_THRESHOLDS, type ApprovalPolicy } from './approvalPolicy';
import type { AlignAxis, DistributeAxis } from './layout/alignment';
import type { AgentToolCall, AgentToolResult, AnnotationShape, MediaKind } from '../types';

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
  tool({ name: 'canvas.get_state', description: '读取当前画布摘要、节点和连线。', readOnly: true, approval: AUTO, execute: (context, args) => canvasQueryService.getState(context.projectId, { limit: typeof args.limit === 'number' ? args.limit : undefined, offset: typeof args.offset === 'number' ? args.offset : undefined, includeEdges: args.includeEdges !== false }) }),
  tool({ name: 'canvas.get_summary', description: '读取画布摘要：节点数、连线数、revision。', readOnly: true, approval: AUTO, execute: (context) => canvasQueryService.getSummary(context.projectId) }),
  tool({ name: 'canvas.get_revision', description: '读取当前画布 revision。', readOnly: true, approval: AUTO, execute: (context) => canvasQueryService.getRevision(context.projectId) }),
  tool({ name: 'canvas.list_nodes', description: '列出当前画布节点摘要。', readOnly: true, approval: AUTO, execute: (context, args) => canvasQueryService.listNodes(context.projectId, args.nodeIds ? nodeIds(args.nodeIds) : undefined) }),
  tool({ name: 'canvas.get_node', description: '读取单个节点摘要。', readOnly: true, approval: AUTO, execute: (context, args) => canvasQueryService.getNode(context.projectId, stringValue(args.nodeId, 'nodeId')) }),
  tool({ name: 'canvas.list_edges', description: '列出连线。', readOnly: true, approval: AUTO, execute: (context, args) => canvasQueryService.listEdges(context.projectId, args.edgeIds ? stringArray(args.edgeIds, 'edgeIds') : undefined) }),
  tool({ name: 'canvas.get_selection', description: '读取当前用户选区。', readOnly: true, approval: AUTO, execute: (context) => canvasQueryService.getSelection(context.projectId) }),

  // ── 节点创建 ─────────────────────────────────────────────────────────
  tool({ name: 'canvas.node.create', description: '创建一个图片、文字或视频节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNode(context, { kind: stringValue(args.kind, 'kind') as MediaKind, position: args.position ? position(args.position) : undefined, label: typeof args.label === 'string' ? args.label : undefined, prompt: typeof args.prompt === 'string' ? args.prompt : undefined }) }),
  tool({ name: 'canvas.node.create_text', description: '创建一个文字节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNode(context, { kind: 'text', position: args.position ? position(args.position) : undefined, label: typeof args.label === 'string' ? args.label : undefined, prompt: typeof args.prompt === 'string' ? args.prompt : undefined }) }),
  tool({ name: 'canvas.node.create_image_placeholder', description: '创建一个空图片占位节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNode(context, { kind: 'image', position: args.position ? position(args.position) : undefined, label: typeof args.label === 'string' ? args.label : undefined, prompt: typeof args.prompt === 'string' ? args.prompt : undefined }) }),
  tool({ name: 'canvas.node.create_video', description: '创建一个视频节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNode(context, { kind: 'video', position: args.position ? position(args.position) : undefined, label: typeof args.label === 'string' ? args.label : undefined, prompt: typeof args.prompt === 'string' ? args.prompt : undefined }) }),
  tool({ name: 'canvas.node.create_many', description: '一次创建多个节点。', readOnly: false, approval: AUTO, execute: (context, args) => canvasCommandService.createNodes(context, { items: (Array.isArray(args.items) ? args.items : []).map((raw) => { const item = objectValue(raw, 'items[]'); return { kind: stringValue(item.kind, 'items[].kind') as MediaKind, position: position(item.position, 'items[].position'), label: typeof item.label === 'string' ? item.label : undefined, prompt: typeof item.prompt === 'string' ? item.prompt : undefined }; }) }) }),

  // ── 节点修改 ─────────────────────────────────────────────────────────
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

  // ── 布局 ─────────────────────────────────────────────────────────────
  tool({ name: 'canvas.layout', description: '将选定节点按网格、横向、纵向或 DAG 风格排布。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.layoutNodes), execute: (context, args) => canvasCommandService.layout(context, { nodeIds: args.nodeIds ? nodeIds(args.nodeIds) : undefined, mode: oneOf(args.mode ?? 'grid', 'mode', ['grid', 'horizontal', 'vertical', 'dag'] as const), gapX: typeof args.gapX === 'number' ? args.gapX : undefined, gapY: typeof args.gapY === 'number' ? args.gapY : undefined, columns: typeof args.columns === 'number' ? args.columns : undefined, anchor: args.anchor ? position(args.anchor, 'anchor') : undefined }) }),
  tool({ name: 'canvas.layout.grid', description: '将节点按网格排布。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.layoutNodes), execute: (context, args) => canvasCommandService.layoutGrid(context, { nodeIds: args.nodeIds ? nodeIds(args.nodeIds) : undefined, gapX: typeof args.gapX === 'number' ? args.gapX : undefined, gapY: typeof args.gapY === 'number' ? args.gapY : undefined, columns: typeof args.columns === 'number' ? args.columns : undefined, anchor: args.anchor ? position(args.anchor, 'anchor') : undefined }) }),
  tool({ name: 'canvas.layout.dag', description: '根据连线方向对节点做 DAG 分层排布。', readOnly: false, approval: autoWithDryRun(APPROVAL_THRESHOLDS.layoutNodes), execute: (context, args) => canvasCommandService.layoutDag(context, { nodeIds: args.nodeIds ? nodeIds(args.nodeIds) : undefined, gapX: typeof args.gapX === 'number' ? args.gapX : undefined, gapY: typeof args.gapY === 'number' ? args.gapY : undefined, anchor: args.anchor ? position(args.anchor, 'anchor') : undefined, direction: args.direction === 'down' ? 'down' : 'right' }) }),
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

export async function executeToolCall(projectId: string, call: AgentToolCall, options: { source?: CanvasCommandContext['source']; dryRun?: boolean; expectedRevision?: number } = {}): Promise<AgentToolResult> {
  const descriptor = registry.get(call.name);
  if (!descriptor) return { toolCallId: call.id, name: call.name, ok: false, error: { code: 'TOOL_NOT_FOUND', message: `未知画布工具：${call.name}` } };
  const context: CanvasCommandContext = { projectId, source: options.source ?? 'agent', dryRun: options.dryRun ?? shouldDryRun(call.name, call.arguments ?? {}), expectedRevision: options.expectedRevision };
  try {
    const result = await descriptor.execute(context, call.arguments ?? {});
    return { toolCallId: call.id, name: call.name, ok: result.ok, status: result.status, result: result.result, error: result.error, transactionId: result.transactionId, revision: result.revision };
  } catch (caught) {
    return { toolCallId: call.id, name: call.name, ok: false, error: { code: 'INVALID_ARGUMENTS', message: caught instanceof Error ? caught.message : '工具参数无效' }, status: 'failed' };
  }
}

export const getTool = (name: string) => registry.get(name);
