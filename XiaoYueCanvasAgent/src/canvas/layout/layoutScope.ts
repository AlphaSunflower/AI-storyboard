import type { CanvasEdge, CanvasNode } from '../../types';

export const collectConnectedComponents = (
  nodes: CanvasNode[],
  edges: CanvasEdge[],
): CanvasNode[][] => {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const adjacency = new Map<string, Set<string>>();
  for (const node of nodes) adjacency.set(node.id, new Set());
  for (const edge of edges) {
    const migrationStatus = (edge.data as { migrationStatus?: string } | undefined)?.migrationStatus;
    if (migrationStatus === 'migration_invalid') continue;
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) continue;
    adjacency.get(edge.source)?.add(edge.target);
    adjacency.get(edge.target)?.add(edge.source);
  }

  const visited = new Set<string>();
  const components: CanvasNode[][] = [];
  for (const start of nodes) {
    if (visited.has(start.id)) continue;
    const componentIds: string[] = [];
    const queue = [start.id];
    for (let cursor = 0; cursor < queue.length; cursor += 1) {
      const id = queue[cursor];
      if (visited.has(id)) continue;
      visited.add(id);
      componentIds.push(id);
      for (const neighbor of adjacency.get(id) ?? []) {
        if (!visited.has(neighbor)) queue.push(neighbor);
      }
    }
    const componentIdSet = new Set(componentIds);
    components.push(nodes.filter((node) => componentIdSet.has(node.id)));
  }
  return components;
};

export const collectConnectedComponentNodeIds = (
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  seedNodeIds: string[],
): string[] => {
  const seeds = new Set(seedNodeIds);
  return collectConnectedComponents(nodes, edges)
    .filter((component) => component.some((node) => seeds.has(node.id)))
    .flatMap((component) => component.map((node) => node.id));
};
