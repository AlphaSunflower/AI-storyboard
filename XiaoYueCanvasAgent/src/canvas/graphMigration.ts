import type { Viewport } from '@xyflow/react';
import {
  CANVAS_NODE_DEFINITIONS,
  getNodeDefinition,
  normalizeGenerationPortId,
  type CanvasEdgeV2,
  type CanvasGraphV2,
  type CanvasNodeKind,
  type CanvasNodeV2,
  type GenerationRecord,
  type MigrationReport,
} from './graphContracts';
import {
  LEGACY_GENERATION_PORT_ORDER,
  mergedGenerationKind,
  resolveNodeMode,
} from './generationModes';

interface LegacyCanvasNode {
  id: string;
  type?: string;
  position: { x: number; y: number };
  width?: number;
  height?: number;
  selected?: boolean;
  data: Record<string, unknown> & { kind?: string; label?: string };
}

interface LegacyCanvasEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
  type?: string;
  selected?: boolean;
  data?: Record<string, unknown>;
}

export interface LegacyCanvasGraph {
  schemaVersion?: number;
  nodes: LegacyCanvasNode[];
  edges: LegacyCanvasEdge[];
  viewport: Viewport;
  generationRecords?: GenerationRecord[];
}

const newNode = (
  id: string,
  kind: CanvasNodeKind,
  source: LegacyCanvasNode,
  data: Record<string, unknown> = {},
): CanvasNodeV2 => {
  const definition = CANVAS_NODE_DEFINITIONS[kind];
  return {
    id,
    type: kind,
    position: { ...source.position },
    width: source.width ?? definition.defaultSize.width,
    height: source.height ?? definition.defaultSize.height,
    selected: Boolean(source.selected),
    data: {
      ...definition.createDefaultData(),
      ...source.data,
      ...data,
      kind,
      label: typeof data.label === 'string' ? data.label : source.data.label ?? definition.title,
    },
  };
};

const inferImageGenerator = (node: LegacyCanvasNode, edges: LegacyCanvasEdge[], kindById: Map<string, string>) => {
  const incomingKinds = edges
    .filter((edge) => edge.target === node.id)
    .map((edge) => kindById.get(edge.source))
    .filter(Boolean);
  return incomingKinds.includes('image') ? 'imageToImage' : 'textToImage';
};

const inferVideoGenerator = (node: LegacyCanvasNode, edges: LegacyCanvasEdge[], kindById: Map<string, string>) => {
  const incomingKinds = edges
    .filter((edge) => edge.target === node.id)
    .map((edge) => kindById.get(edge.source))
    .filter(Boolean);
  const mode = typeof node.data.videoMode === 'string' ? node.data.videoMode : '';
  if (mode === 'firstLastFrame') return 'firstLastFrameToVideo';
  if (mode === 'fullReference' || mode === 'videoReference' || incomingKinds.includes('video')) return 'referenceToVideo';
  if (mode === 'image2video' || incomingKinds.includes('image')) return 'imageToVideo';
  return 'textToVideo';
};

const firstOutput = (kind: CanvasNodeKind) => getNodeDefinition(kind)?.outputs[0]?.id;

const firstCompatibleInput = (sourceKind: CanvasNodeKind, targetKind: CanvasNodeKind) => {
  const sourceDef = getNodeDefinition(sourceKind);
  const targetDef = getNodeDefinition(targetKind);
  const sourcePort = sourceDef?.outputs[0];
  if (!sourcePort) return undefined;
  return targetDef?.inputs.find((port) =>
    port.acceptsKinds.includes(sourceKind) && port.media === sourcePort.media);
};

const normalizeNodeKind = (node: LegacyCanvasNode, edges: LegacyCanvasEdge[], kindById: Map<string, string>): CanvasNodeKind | null => {
  const kind = node.data.kind;
  if (kind === 'prompt') return 'text';
  if (kind === 'sequence') return null;
  if (kind === 'imageGen') return inferImageGenerator(node, edges, kindById);
  if (kind === 'videoGen') return inferVideoGenerator(node, edges, kindById);
  if (kind === 'storyboard' || kind === 'storyboardGen' || kind === 'storyboardPlanner') return 'storyboardPlanner';
  if (kind && kind in CANVAS_NODE_DEFINITIONS) return kind as CanvasNodeKind;
  return 'text';
};

export function migrateCanvasGraphV1ToV2(input: LegacyCanvasGraph): CanvasGraphV2 {
  const kindById = new Map(input.nodes.map((node) => [node.id, String(node.data.kind ?? '')]));
  const migratedNodes: CanvasNodeV2[] = [];
  const migratedNodeIds = new Set<string>();
  const warnings: string[] = [];

  for (const legacy of input.nodes) {
    const kind = normalizeNodeKind(legacy, input.edges, kindById);
    if (!kind) {
      warnings.push(`节点 ${legacy.id} 的 sequence 容器已移除，其子节点保持不变`);
      continue;
    }

    if (kind === 'storyboardPlanner') {
      const planner = newNode(legacy.id, kind, legacy, {
        label: legacy.data.label ?? '分镜拆解',
        prompt: String(legacy.data.prompt ?? ''),
      });
      migratedNodes.push(planner);
      migratedNodeIds.add(planner.id);
      const frames = Array.isArray(legacy.data.frames) ? legacy.data.frames as Array<Record<string, unknown>> : [];
      frames.forEach((frame, index) => {
        const shotId = `shot-${legacy.id}-${index + 1}`;
        const shot = newNode(shotId, 'shot', {
          ...legacy,
          id: shotId,
          position: {
            x: legacy.position.x + 620,
            y: legacy.position.y + index * 340,
          },
          data: {},
        }, {
          label: typeof frame.label === 'string' ? frame.label : `镜头 ${index + 1}`,
          prompt: typeof frame.prompt === 'string' ? frame.prompt : String(frame.description ?? ''),
          imagePrompt: typeof frame.prompt === 'string' ? frame.prompt : String(frame.description ?? ''),
          videoPrompt: String(frame.prompt ?? frame.description ?? ''),
          shotId,
          shotKey: `${legacy.id}:${index}`,
          shotOrdinal: index + 1,
          status: 'idle',
          frames: undefined,
        });
        migratedNodes.push(shot);
        migratedNodeIds.add(shot.id);
      });
      continue;
    }

    const migrated = newNode(legacy.id, kind, legacy, {
      prompt: String(legacy.data.prompt ?? legacy.data.promptText ?? ''),
      status: legacy.data.status ?? 'idle',
    });
    migratedNodes.push(migrated);
    migratedNodeIds.add(migrated.id);
  }

  const migratedEdges: CanvasEdgeV2[] = [];
  const invalidEdges: MigrationReport['invalidEdges'] = [];

  for (const edge of input.edges) {
    const source = migratedNodes.find((node) => node.id === edge.source);
    const target = migratedNodes.find((node) => node.id === edge.target);
    const sourceKind = source?.data.kind;
    const targetKind = target?.data.kind;
    const sourceHandle = sourceKind ? firstOutput(sourceKind) : undefined;
    const targetPort = sourceKind && targetKind ? firstCompatibleInput(sourceKind, targetKind) : undefined;

    if (!source || !target || !sourceHandle || !targetPort) {
      const message = !source || !target
        ? '端点节点已在迁移中移除或不存在'
        : '旧连线无法映射到明确的输入输出端口';
      migratedEdges.push({
        id: edge.id,
        source: edge.source,
        sourceHandle: '__migration_invalid__',
        target: edge.target,
        targetHandle: '__migration_invalid__',
        type: 'canvas',
        data: { migrationStatus: 'migration_invalid', migrationError: message },
      });
      invalidEdges.push({ edgeId: edge.id, code: 'INVALID_LEGACY_EDGE', message });
      continue;
    }

    migratedEdges.push({
      id: edge.id,
      source: edge.source,
      sourceHandle,
      target: edge.target,
      targetHandle: targetPort.id,
      type: edge.type ?? 'canvas',
      selected: Boolean(edge.selected),
      data: {
        ...(edge.data as CanvasEdgeV2['data']),
        portOrder: targetPort.ordered
          ? LEGACY_GENERATION_PORT_ORDER[edge.targetHandle ?? ''] ?? migratedEdges.filter((candidate) =>
            candidate.target === target.id && candidate.targetHandle === targetPort.id).length
          : undefined,
      },
    });
  }

  return normalizeGenerationNodes({
    schemaVersion: 2,
    nodes: migratedNodes,
    edges: migratedEdges,
    viewport: input.viewport,
    generationRecords: input.generationRecords ?? [],
    migrationReport: {
      sourceSchemaVersion: input.schemaVersion ?? 1,
      migratedNodeIds: [...migratedNodeIds],
      invalidEdges,
      warnings,
    },
  });
}

/**
 * 把已合并的旧生成节点 kind 归一为 canonical kind 并落 `data.mode`，同时把旧端口 id
 * （first/last/source/images）重写到 `reference`。对已经是 canonical 的图幂等。
 */
export function normalizeGenerationNodes(graph: CanvasGraphV2): CanvasGraphV2 {
  let changed = false;
  const nodes = graph.nodes.map((node) => {
    const merged = mergedGenerationKind(node.data.kind);
    if (!merged) return node;
    const needsKind = node.data.kind !== merged || node.type !== merged;
    const needsMode = typeof node.data.mode !== 'string';
    if (!needsKind && !needsMode) return node;
    changed = true;
    return {
      ...node,
      type: merged,
      data: {
        ...node.data,
        kind: merged,
        mode: needsMode ? resolveNodeMode(node.data.kind, node.data) : node.data.mode,
      },
    };
  });
  if (!changed) return graph;

  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const edges = graph.edges.map((edge) => {
    const target = nodeById.get(edge.target);
    const portId = normalizeGenerationPortId(target?.data.kind, edge.targetHandle);
    if (!portId || portId === edge.targetHandle) return edge;
    // 旧的首帧/尾帧端口自带顺序：重写端口 id 时把顺序落到 portOrder，避免首尾颠倒
    const legacyOrder = LEGACY_GENERATION_PORT_ORDER[edge.targetHandle ?? ''];
    return {
      ...edge,
      targetHandle: portId,
      data: legacyOrder === undefined || edge.data?.portOrder !== undefined
        ? edge.data
        : { ...edge.data, portOrder: legacyOrder },
    };
  });

  return { ...graph, nodes, edges };
}
