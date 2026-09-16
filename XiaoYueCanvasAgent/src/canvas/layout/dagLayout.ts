import type { XYPosition } from '@xyflow/react';
import type { ELK, ElkExtendedEdge, ElkNode, ElkPort } from 'elkjs/lib/elk-api';
import elkWorkerUrl from 'elkjs/lib/elk-worker.min.js?url';
import type { CanvasEdge, CanvasNode } from '../../types';
import { getNodeDefinition } from '../graphContracts';
import { nodeSize } from '../canvasSelectors';
import type { LayoutPosition } from './gridLayout';
import { collectConnectedComponents } from './layoutScope';

export type DagDirection = 'right' | 'down';

export interface DagLayoutOptions {
  gapX?: number;
  gapY?: number;
  componentGapY?: number;
  anchor?: XYPosition;
  direction?: DagDirection;
}

export interface ElkGraphOptions {
  gapX?: number;
  gapY?: number;
  direction?: DagDirection;
}

const DEFAULT_GAP_X = 240;
const DEFAULT_GAP_Y = 96;
const DEFAULT_COMPONENT_GAP_Y = 360;

let elkPromise: Promise<ELK> | null = null;
let layoutQueue: Promise<void> = Promise.resolve();

const createElk = async (): Promise<ELK> => {
  if (import.meta.env.MODE === 'test') {
    const { default: BundledElk } = await import('elkjs/lib/elk.bundled.js');
    return new BundledElk();
  }
  const { default: ElkConstructor } = await import('elkjs/lib/elk-api');
  return new ElkConstructor({ workerUrl: elkWorkerUrl });
};

const getElk = () => {
  elkPromise ??= createElk();
  return elkPromise;
};

const runElkLayout = <T>(task: () => Promise<T>): Promise<T> => {
  const run = layoutQueue.then(task, task);
  layoutQueue = run.then(() => undefined, () => undefined);
  return run;
};

const graphPortId = (nodeId: string, portId: string) => `${nodeId}::${portId}`;

const port = (nodeId: string, id: string, side: 'WEST' | 'EAST', index: number): ElkPort => ({
  id: graphPortId(nodeId, id),
  width: 1,
  height: 1,
  layoutOptions: {
    'elk.port.side': side,
    'elk.port.index': String(index),
  },
});

const buildPorts = (node: CanvasNode): ElkPort[] => {
  const definition = getNodeDefinition(node.data.kind);
  if (!definition) return [];
  return [
    ...definition.inputs.map((input, index) => port(node.id, input.id, 'WEST', index)),
    ...definition.outputs.map((output, index) => port(node.id, output.id, 'EAST', index)),
  ];
};

const nodeOrder = (left: CanvasNode, right: CanvasNode): number => {
  const leftOrdinal = left.data.kind === 'shot' ? Number(left.data.shotOrdinal) : Number.NaN;
  const rightOrdinal = right.data.kind === 'shot' ? Number(right.data.shotOrdinal) : Number.NaN;
  if (Number.isFinite(leftOrdinal) && Number.isFinite(rightOrdinal) && leftOrdinal !== rightOrdinal) {
    return leftOrdinal - rightOrdinal;
  }
  return left.position.y - right.position.y
    || left.position.x - right.position.x
    || left.id.localeCompare(right.id);
};

export const buildElkGraph = (
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  options: ElkGraphOptions = {},
): ElkNode => {
  const direction = options.direction ?? 'right';
  const gapX = Math.max(0, options.gapX ?? DEFAULT_GAP_X);
  const gapY = Math.max(0, options.gapY ?? DEFAULT_GAP_Y);
  const nodeIds = new Set(nodes.map((node) => node.id));
  const children = [...nodes].sort(nodeOrder).map((node): ElkNode => {
    const size = nodeSize(node);
    const ports = buildPorts(node);
    return {
      id: node.id,
      width: Math.max(1, size.width),
      height: Math.max(1, size.height),
      ports,
      layoutOptions: ports.length ? { 'elk.portConstraints': 'FIXED_ORDER' } : undefined,
    };
  });
  const portIdsByNode = new Map(children.map((child) => [
    child.id,
    new Set((child.ports ?? []).map((item) => item.id)),
  ]));
  const graphEdges = edges.flatMap((edge): ElkExtendedEdge[] => {
    const sourcePort = edge.sourceHandle ? graphPortId(edge.source, edge.sourceHandle) : '';
    const targetPort = edge.targetHandle ? graphPortId(edge.target, edge.targetHandle) : '';
    if (
      !nodeIds.has(edge.source)
      || !nodeIds.has(edge.target)
      || !portIdsByNode.get(edge.source)?.has(sourcePort)
      || !portIdsByNode.get(edge.target)?.has(targetPort)
    ) return [];
    return [{
      id: edge.id,
      sources: [sourcePort],
      targets: [targetPort],
    }];
  });

  return {
    id: 'canvas',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': direction === 'down' ? 'DOWN' : 'RIGHT',
      'elk.spacing.nodeNode': String(gapY),
      'elk.layered.spacing.nodeNodeBetweenLayers': String(gapX),
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.randomSeed': '1',
      'elk.edgeRouting': 'ORTHOGONAL',
    },
    children,
    edges: graphEdges,
  };
};

const normalizeComponentLayout = (
  laidOutChildren: ElkNode[],
  nodes: CanvasNode[],
  options: Required<Pick<DagLayoutOptions, 'gapX' | 'gapY' | 'direction'>>,
): LayoutPosition[] => {
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const childById = new Map(laidOutChildren.map((child) => [child.id, child]));
  const children = nodes
    .map((node) => childById.get(node.id))
    .filter((child): child is ElkNode => Boolean(child));
  const center = (child: ElkNode) => {
    const node = nodeById.get(child.id);
    const size = node ? nodeSize(node) : { width: child.width ?? 1, height: child.height ?? 1 };
    return {
      x: (child.x ?? 0) + size.width / 2,
      y: (child.y ?? 0) + size.height / 2,
    };
  };
  const primary = (child: ElkNode) => {
    const node = nodeById.get(child.id);
    const size = node ? nodeSize(node) : { width: child.width ?? 1, height: child.height ?? 1 };
    return options.direction === 'down'
      ? (child.y ?? 0) + size.height
      : (child.x ?? 0) + size.width;
  };
  const secondary = (child: ElkNode) =>
    options.direction === 'down' ? center(child).x : center(child).y;
  const sorted = [...children].sort((left, right) =>
    primary(left) - primary(right)
    || secondary(left) - secondary(right)
    || left.id.localeCompare(right.id));
  const layers: ElkNode[][] = [];
  for (const child of sorted) {
    const current = layers.at(-1);
    if (!current || Math.abs(primary(child) - primary(current[0])) > 1) layers.push([child]);
    else current.push(child);
  }

  const layerLayouts = layers.map((layer) => {
    const orderedLayer = [...layer].sort((left, right) =>
      secondary(left) - secondary(right)
      || left.id.localeCompare(right.id));
    let secondaryOffset = 0;
    const placements = orderedLayer.flatMap((child) => {
      const node = nodeById.get(child.id);
      if (!node) return [];
      const size = nodeSize(node);
      const position = {
        child,
        secondaryOffset,
        secondarySize: options.direction === 'down' ? size.width : size.height,
      };
      secondaryOffset += position.secondarySize
        + (options.direction === 'down' ? options.gapX : options.gapY);
      return [position];
    });
    const secondarySpan = placements.length
      ? Math.max(0, secondaryOffset - (options.direction === 'down' ? options.gapX : options.gapY))
      : 0;
    const primarySpan = Math.max(...layer.map((child) => {
      const node = nodeById.get(child.id);
      if (!node) return 1;
      const size = nodeSize(node);
      return options.direction === 'down' ? size.height : size.width;
    }));
    return { placements, secondarySpan, primarySpan };
  });
  const componentSecondarySpan = Math.max(0, ...layerLayouts.map((layout) => layout.secondarySpan));
  const positions = new Map<string, XYPosition>();
  let primaryOffset = 0;
  for (const layer of layerLayouts) {
    const secondaryStart = Math.max(0, (componentSecondarySpan - layer.secondarySpan) / 2);
    for (const placement of layer.placements) {
      const { child } = placement;
      const node = nodeById.get(child.id);
      if (!node) continue;
      positions.set(child.id, options.direction === 'down'
        ? { x: secondaryStart + placement.secondaryOffset, y: primaryOffset }
        : { x: primaryOffset, y: secondaryStart + placement.secondaryOffset });
    }
    primaryOffset += layer.primarySpan + (options.direction === 'down' ? options.gapY : options.gapX);
  }

  return nodes.flatMap((node) => {
    const position = positions.get(node.id);
    return position ? [{ nodeId: node.id, position }] : [];
  });
};

const normalizeLayout = (
  laidOutChildren: ElkNode[],
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  options: Required<Pick<DagLayoutOptions, 'gapX' | 'gapY' | 'componentGapY' | 'anchor' | 'direction'>>,
): LayoutPosition[] => {
  const childById = new Map(laidOutChildren.map((child) => [child.id, child]));
  const components = collectConnectedComponents(nodes, edges).sort((left, right) => {
    const leftTop = Math.min(...left.map((node) => node.position.y));
    const rightTop = Math.min(...right.map((node) => node.position.y));
    if (leftTop !== rightTop) return leftTop - rightTop;
    const leftSide = Math.min(...left.map((node) => node.position.x));
    const rightSide = Math.min(...right.map((node) => node.position.x));
    return leftSide - rightSide;
  });
  const positions = new Map<string, XYPosition>();
  let componentTop = options.anchor.y;
  for (const component of components) {
    const componentChildren = component
      .map((node) => childById.get(node.id))
      .filter((child): child is ElkNode => Boolean(child));
    const relative = normalizeComponentLayout(componentChildren, component, options);
    let componentBottom = 0;
    for (const item of relative) {
      const node = component.find((candidate) => candidate.id === item.nodeId);
      if (!node) continue;
      positions.set(item.nodeId, {
        x: options.anchor.x + item.position.x,
        y: componentTop + item.position.y,
      });
      componentBottom = Math.max(componentBottom, item.position.y + nodeSize(node).height);
    }
    componentTop += componentBottom + options.componentGapY;
  }
  return nodes.flatMap((node) => {
    const position = positions.get(node.id);
    return position ? [{ nodeId: node.id, position }] : [];
  });
};

export const computeDagLayout = async (
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  options: DagLayoutOptions = {},
): Promise<LayoutPosition[]> => {
  if (!nodes.length) return [];
  const resolved = {
    gapX: Math.max(0, options.gapX ?? DEFAULT_GAP_X),
    gapY: Math.max(0, options.gapY ?? DEFAULT_GAP_Y),
    componentGapY: Math.max(0, options.componentGapY ?? DEFAULT_COMPONENT_GAP_Y),
    anchor: options.anchor ?? {
      x: Math.min(...nodes.map((node) => node.position.x)),
      y: Math.min(...nodes.map((node) => node.position.y)),
    },
    direction: options.direction ?? ('right' as DagDirection),
  };
  const graph = buildElkGraph(nodes, edges, resolved);
  const elk = await getElk();
  const laidOut = await runElkLayout(() => elk.layout(graph)) as ElkNode;
  return normalizeLayout(laidOut.children ?? [], nodes, edges, resolved);
};
