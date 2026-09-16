import type { IncomingMessage, ServerResponse } from 'node:http';
import { McpServer } from '@modelcontextprotocol/server';
import {
  NodeStreamableHTTPServerTransport,
  localhostHostValidation,
  localhostOriginValidation,
} from '@modelcontextprotocol/node';
import { z } from 'zod';
import type { CanvasBridgeResponse } from '../src/canvas/bridgeProtocol.ts';
import type { CanvasBridgeCore } from './canvasBridgeCore.ts';

const contextSchema = {
  projectId: z.string().optional().describe('画布项目 ID；只有一个活动画布时可省略'),
  canvasId: z.string().optional().describe('画布 ID；同一项目有多个活动画布时必须指定'),
};

const positionSchema = z.object({ x: z.number(), y: z.number() });

const toolResult = (response: CanvasBridgeResponse) => ({
  content: [{
    type: 'text' as const,
    text: JSON.stringify(response.ok ? response.result ?? {} : response.error ?? { message: '画布命令失败' }),
  }],
  isError: !response.ok,
});

const command = async (
  core: CanvasBridgeCore,
  method: string,
  input: Record<string, unknown>,
) => toolResult(await core.command({
  source: 'mcp',
  projectId: typeof input.projectId === 'string' ? input.projectId : undefined,
  canvasId: typeof input.canvasId === 'string' ? input.canvasId : undefined,
  method,
  params: Object.fromEntries(Object.entries(input).filter(([key]) => key !== 'projectId' && key !== 'canvasId')),
  dryRun: input.dryRun === true,
  expectedRevision: typeof input.expectedRevision === 'number' ? input.expectedRevision : undefined,
}));

const contextResult = async (
  core: CanvasBridgeCore,
  input: { projectId?: string; canvasId?: string },
) => {
  const health = core.health();
  const options = health.projects.map((entry) => ({
    id: `${entry.projectId}::${entry.canvasId}`,
    title: `${entry.projectTitle || '未命名项目'} / ${entry.canvasTitle || '未命名画布'}`,
    value: {
      projectId: entry.projectId,
      projectTitle: entry.projectTitle || '未命名项目',
      canvasId: entry.canvasId,
      canvasTitle: entry.canvasTitle || '未命名画布',
    },
  }));
  if (input.projectId || input.canvasId) {
    const match = health.projects.find((entry) =>
      (!input.projectId || entry.projectId === input.projectId)
      && (!input.canvasId || entry.canvasId === input.canvasId));
    return match
      ? { content: [{ type: 'text' as const, text: JSON.stringify(match) }] }
      : { content: [{ type: 'text' as const, text: JSON.stringify({ code: 'CANVAS_NOT_OPEN', message: '目标画布未打开' }) }], isError: true };
  }
  if (health.projects.length === 1) {
    return { content: [{ type: 'text' as const, text: JSON.stringify(health.projects[0]) }] };
  }
  return { content: [{ type: 'text' as const, text: JSON.stringify({
    code: health.projects.length ? 'HUMAN_INPUT_REQUIRED' : 'PROJECT_NOT_OPEN',
    message: health.projects.length
      ? '检测到多个活动画布，请调用 moon_request_human_input，让用户按项目名和画布名选择。'
      : '当前没有活动画布',
    prompt: health.projects.length ? '请选择要编辑的画布：' : undefined,
    options: health.projects.length ? options : undefined,
  }) }], isError: health.projects.length === 0 };
};

function createCanvasMcpServer(core: CanvasBridgeCore) {
  const server = new McpServer({ name: 'morrow-canvas', version: '1.0.0' });

  server.registerTool('canvas_get_context', {
    title: '获取画布上下文',
    description: '获取当前打开的画布项目与画布名称。多个画布时返回 HUMAN_INPUT_REQUIRED，'
      + '必须使用 moon_request_human_input 让用户按名称点击选择，禁止要求用户输入 ID。',
    inputSchema: z.object(contextSchema),
    annotations: { readOnlyHint: true },
  }, (input) => contextResult(core, input));

  server.registerTool('canvas_get_node_model_config', {
    title: '读取节点模型参数',
    description: '读取生成节点当前模型、已保存参数、兼容模型目录和递归参数 Schema。',
    inputSchema: z.object({
      ...contextSchema,
      nodeId: z.string(),
    }),
    annotations: { readOnlyHint: true },
  }, (input) => command(core, 'canvas.model_config.get', input));

  const readTools: Array<[string, string, z.ZodObject<z.ZodRawShape>]> = [
    ['canvas_get_browser_context', 'canvas.get_context', z.object({})],
    ['canvas_list_node_types', 'canvas.list_node_types', z.object(contextSchema)],
    ['canvas_get_state', 'canvas.get_state', z.object({ ...contextSchema, limit: z.number().optional(), offset: z.number().optional(), includeEdges: z.boolean().optional() })],
    ['canvas_get_summary', 'canvas.get_summary', z.object(contextSchema)],
    ['canvas_list_nodes', 'canvas.list_nodes', z.object({ ...contextSchema, nodeIds: z.array(z.string()).optional() })],
    ['canvas_get_node', 'canvas.get_node', z.object({ ...contextSchema, nodeId: z.string() })],
    ['canvas_list_edges', 'canvas.list_edges', z.object({ ...contextSchema, edgeIds: z.array(z.string()).optional() })],
    ['canvas_get_selection', 'canvas.get_selection', z.object(contextSchema)],
  ];
  for (const [name, method, inputSchema] of readTools) {
    const description = name === 'canvas_get_state'
      ? '读取画布、连线、占位状态、missingInputs 和媒体中继 repairSuggestions。'
      : name === 'canvas_list_nodes'
        ? '读取节点摘要，包括 image/video 占位状态、结果槽绑定和缺失输入。'
        : name === 'canvas_list_edges'
          ? '读取连线及 resultSlot、resultSlotOrder、portOrder 元数据。'
          : name === 'canvas_list_node_types'
            ? '读取节点类型、flowRole、媒体占位契约、端口和动作。'
            : `画布读取工具：${method}`;
    server.registerTool(name, {
      description,
      inputSchema,
      annotations: { readOnlyHint: true },
    }, (input) => command(core, method, input));
  }

  server.registerTool('canvas_create_node', {
    title: '创建节点',
    description: '创建画布节点并返回真实 nodeId。',
    inputSchema: z.object({
      ...contextSchema,
      kind: z.string(),
      position: positionSchema.optional(),
      label: z.string().optional(),
      prompt: z.string().optional(),
      data: z.record(z.string(), z.unknown()).optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.create', input));

  server.registerTool('canvas_create_nodes', {
    title: '批量创建节点',
    description: '一次创建多个画布节点并返回 nodeIds。',
    inputSchema: z.object({
      ...contextSchema,
      items: z.array(z.object({
        kind: z.string(),
        position: positionSchema.optional(),
        label: z.string().optional(),
        prompt: z.string().optional(),
        data: z.record(z.string(), z.unknown()).optional(),
      })),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.create_many', input));

  server.registerTool('canvas_import_media', {
    title: '导入媒体',
    description: '下载图片或视频引用并导入当前画布，创建真实媒体节点。',
    inputSchema: z.object({
      ...contextSchema,
      kind: z.enum(['image', 'video']),
      url: z.string().describe('图片或视频的绝对 URL'),
      name: z.string().optional(),
      mimeType: z.string().optional(),
      label: z.string().optional(),
      position: z.object({ x: z.number(), y: z.number() }).optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.media.import', input));

  server.registerTool('canvas_create_canvas', {
    title: '新建画布',
    description: '在当前项目新建画布；activate=true 时创建后切换到新画布。',
    inputSchema: z.object({
      ...contextSchema,
      title: z.string().optional(),
      activate: z.boolean().optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.canvas.create', input));

  server.registerTool('canvas_rename_canvas', {
    title: '重命名画布',
    description: '重命名当前画布或指定 canvasId 的画布。',
    inputSchema: z.object({
      ...contextSchema,
      canvasId: z.string().optional(),
      title: z.string(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.canvas.rename', input));

  server.registerTool('canvas_create_result_slots', {
    title: '创建结果槽',
    description: '为生产节点创建有序 image/video 结果槽并自动连接。结果槽 origin 输入接收生成结果，媒体输出口可继续连接下游；最终结果槽只保留输入。',
    inputSchema: z.object({
      ...contextSchema,
      producerNodeId: z.string(),
      kind: z.enum(['image', 'video']),
      count: z.number().int().min(1).max(12),
      position: positionSchema.optional(),
      gapY: z.number().optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.result_slots.create', input));

  server.registerTool('canvas_mark_result_slots', {
    title: '标记结果槽',
    description: '把已有空 image/video 节点按 nodeIds 顺序标记为生产节点的结果槽；中间槽可继续连接下游，最终槽只保留输入。',
    inputSchema: z.object({
      ...contextSchema,
      producerNodeId: z.string(),
      nodeIds: z.array(z.string()).min(1),
      startOrder: z.number().int().min(0).optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.result_slots.mark', input));

  server.registerTool('canvas_update_node', {
    title: '更新节点',
    description: '更新节点标题、提示词或普通 data 字段；模型和模型参数必须使用 canvas_set_node_model_config。',
    inputSchema: z.object({
      ...contextSchema,
      nodeId: z.string(),
      patch: z.record(z.string(), z.unknown()),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.update', input));

  server.registerTool('canvas_set_node_model_config', {
    title: '设置节点模型参数',
    description: '严格按网关 Schema 修改生成节点的模型和参数；换模会先重置为新模型默认值，再应用 params 覆盖。',
    inputSchema: z.object({
      ...contextSchema,
      nodeId: z.string(),
      model: z.string().optional(),
      params: z.record(z.string(), z.unknown()).optional(),
      resetToDefaults: z.boolean().optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.model_config.set', input));

  server.registerTool('canvas_delete_nodes', {
    title: '删除节点',
    description: '删除节点及其连线。Moon Skill 必须先获得用户确认。',
    inputSchema: z.object({
      ...contextSchema,
      nodeIds: z.array(z.string()).min(1),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.delete', input));

  server.registerTool('canvas_create_edge', {
    title: '创建连线',
    description: '连接两个节点；省略端口时自动选择兼容端口。',
    inputSchema: z.object({
      ...contextSchema,
      source: z.string(),
      target: z.string(),
      sourceHandle: z.string().optional(),
      targetHandle: z.string().optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.edge.create', input));

  server.registerTool('canvas_delete_edges', {
    title: '删除连线',
    description: '删除指定连线。Moon Skill 必须先获得用户确认。',
    inputSchema: z.object({
      ...contextSchema,
      edgeIds: z.array(z.string()).min(1),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.edge.delete', input));

  server.registerTool('canvas_move_nodes', {
    title: '移动节点',
    description: '批量移动节点。',
    inputSchema: z.object({
      ...contextSchema,
      positions: z.array(z.object({ nodeId: z.string(), x: z.number(), y: z.number() })),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.move_many', input));

  server.registerTool('canvas_duplicate_nodes', {
    title: '复制节点',
    description: '复制节点及其内部连线。',
    inputSchema: z.object({
      ...contextSchema,
      nodeIds: z.array(z.string()).min(1),
      offset: positionSchema.optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.duplicate', input));

  server.registerTool('canvas_resize_nodes', {
    title: '调整节点尺寸',
    description: '批量调整节点尺寸。',
    inputSchema: z.object({
      ...contextSchema,
      sizes: z.array(z.object({ nodeId: z.string(), width: z.number().positive(), height: z.number().positive() })),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.resize', input));

  server.registerTool('canvas_pin_nodes', {
    title: '固定节点',
    description: '固定或取消固定节点。',
    inputSchema: z.object({
      ...contextSchema,
      nodeIds: z.array(z.string()).min(1),
      pinned: z.boolean(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.pin', input));

  server.registerTool('canvas_layout_graph', {
    title: '布局画布',
    description: '按网格、横向、纵向或 ELK 分层布局节点。',
    inputSchema: z.object({
      ...contextSchema,
      nodeIds: z.array(z.string()).optional(),
      mode: z.enum(['grid', 'horizontal', 'vertical', 'dag']).optional(),
      gapX: z.number().optional(),
      gapY: z.number().optional(),
      columns: z.number().optional(),
      anchor: positionSchema.optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.layout', input));

  server.registerTool('canvas_execute_node', {
    title: '执行节点',
    description: '提交单个节点的动作并立即返回 jobId。',
    inputSchema: z.object({
      ...contextSchema,
      nodeId: z.string(),
      actionId: z.string().optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.node.execute', input));

  server.registerTool('canvas_execute_graph', {
    title: '执行画布 DAG',
    description: '按依赖顺序执行节点，失败节点的下游阻断，独立分支继续。',
    inputSchema: z.object({
      ...contextSchema,
      nodeIds: z.array(z.string()).optional(),
      actionByNode: z.record(z.string(), z.string()).optional(),
      maxParallel: z.number().int().min(1).max(2).optional(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.graph.execute', input));

  server.registerTool('canvas_get_execution', {
    title: '查询 DAG 执行',
    description: '读取 DAG 执行批次和各节点状态。',
    inputSchema: z.object({ executionId: z.string() }),
    annotations: { readOnlyHint: true },
  }, (input) => command(core, 'canvas.execution.get', input));

  server.registerTool('canvas_cancel_execution', {
    title: '取消 DAG 执行',
    description: '取消 DAG 批跑及其仍在运行的任务。',
    inputSchema: z.object({ executionId: z.string() }),
  }, (input) => command(core, 'canvas.execution.cancel', input));

  server.registerTool('canvas_image_crop', {
    title: '裁剪图片',
    description: '裁剪图片节点并生成带因果边的派生节点。',
    inputSchema: z.object({
      ...contextSchema,
      nodeId: z.string(),
      crop: z.object({ x: z.number(), y: z.number(), width: z.number(), height: z.number() }),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.image.crop', input));

  server.registerTool('canvas_image_split', {
    title: '切分图片',
    description: '将图片节点切分为网格，生成派生节点。',
    inputSchema: z.object({
      ...contextSchema,
      nodeId: z.string(),
      rows: z.number().int().positive(),
      columns: z.number().int().positive(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.image.split', input));

  server.registerTool('canvas_image_adjust', {
    title: '调整图片',
    description: '调整亮度、对比度和饱和度并生成派生节点。',
    inputSchema: z.object({
      ...contextSchema,
      nodeId: z.string(),
      brightness: z.number(),
      contrast: z.number(),
      saturation: z.number(),
      dryRun: z.boolean().optional(),
    }),
  }, (input) => command(core, 'canvas.image.adjust', input));

  server.registerTool('canvas_undo', {
    title: '撤销画布',
    description: '撤销上一条画布事务。',
    inputSchema: z.object({ ...contextSchema, dryRun: z.boolean().optional() }),
  }, (input) => command(core, 'canvas.undo', input));

  server.registerTool('canvas_redo', {
    title: '重做画布',
    description: '重做上一条画布事务。',
    inputSchema: z.object({ ...contextSchema, dryRun: z.boolean().optional() }),
  }, (input) => command(core, 'canvas.redo', input));

  return server;
}

const validateHost = localhostHostValidation();
const validateOrigin = localhostOriginValidation();

export async function attachMcpEndpoint(
  core: CanvasBridgeCore,
  req: IncomingMessage,
  res: ServerResponse,
) {
  if (!validateHost(req, res)) return;
  if (!validateOrigin(req, res)) return;
  const server = createCanvasMcpServer(core);
  const transport = new NodeStreamableHTTPServerTransport({ sessionIdGenerator: undefined });
  await server.connect(transport);
  await transport.handleRequest(req, res);
}
