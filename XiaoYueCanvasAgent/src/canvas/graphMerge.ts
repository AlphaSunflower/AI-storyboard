import type { CanvasEdge, CanvasNode } from '../types';
import type { CanvasExecution, GenerationRecord } from './graphContracts';

/**
 * 画布保存撞上 409 时，把服务端那份图并回本地，而不是拿本地旧图整份覆盖。
 *
 * <p>背景（2026-10-19 实测）：智能体先用 `canvas_graph_apply_plan` 在服务端建了 10 个节点，
 * 浏览器这边还有未落库的本地改动 → 保存 409 → 旧实现「以本地意图为准」拿服务端最新 revision
 * 把**本地旧图**再 PUT 一次，智能体刚建的节点/连线当场被抹掉；随后 `canvas_group_nodes`
 * 就报「节点不存在」。合并策略：
 *
 * <ul>
 *   <li>节点：按 id 取并集，同 id 以**本地**为准（用户正在编辑的那份更新），服务端独有的一律并入；</li>
 *   <li>连线：按 id 取并集，再按「源 + 源端口 + 目标 + 目标端口」去重，
 *       避免智能体与本地各建一条同语义的连线；</li>
 *   <li>生成记录 / 执行批次：按 id 取并集，同 id 以本地为准（本地进度更新）。</li>
 * </ul>
 *
 * <p>已知取舍：服务端**删除**的节点在合并后会被本地这份保留（两边没有共同祖先，无法区分
 * 「智能体删了」和「用户新建但还没保存」）。合并只在 409 冲突这一条路径上发生，属于低频兜底。
 */
export interface CanvasGraphSlices {
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  generationRecords?: GenerationRecord[];
  executions?: CanvasExecution[];
}

export interface CanvasGraphMergeResult extends Required<CanvasGraphSlices> {
  /** 服务端有、本地没有的节点 id（智能体新建的）。 */
  addedNodeIds: string[];
  /** 服务端有、本地没有的连线 id。 */
  addedEdgeIds: string[];
}

const edgeKey = (edge: CanvasEdge) =>
  `${edge.source}|${edge.sourceHandle ?? ''}|${edge.target}|${edge.targetHandle ?? ''}`;

const unionById = <T extends { id: string }>(local: T[], server: T[]): T[] => {
  const seen = new Set(local.map((item) => item.id));
  const merged = [...local];
  for (const item of server) {
    if (!item || seen.has(item.id)) continue;
    seen.add(item.id);
    merged.push(item);
  }
  return merged;
};

export function mergeCanvasGraph(
  local: CanvasGraphSlices,
  server: CanvasGraphSlices,
): CanvasGraphMergeResult {
  const nodes = unionById(local.nodes ?? [], server.nodes ?? []);
  const localNodeIds = new Set((local.nodes ?? []).map((node) => node.id));
  const addedNodeIds = (server.nodes ?? []).filter((node) => node && !localNodeIds.has(node.id)).map((node) => node.id);

  const localEdges = local.edges ?? [];
  const edges = [...localEdges];
  const edgeIds = new Set(localEdges.map((edge) => edge.id));
  const edgeKeys = new Set(localEdges.map(edgeKey));
  const addedEdgeIds: string[] = [];
  for (const edge of server.edges ?? []) {
    if (!edge || edgeIds.has(edge.id)) continue;
    const key = edgeKey(edge);
    if (edgeKeys.has(key)) continue;
    edgeIds.add(edge.id);
    edgeKeys.add(key);
    edges.push(edge);
    addedEdgeIds.push(edge.id);
  }

  return {
    nodes,
    edges,
    generationRecords: unionById(local.generationRecords ?? [], server.generationRecords ?? []),
    executions: unionById(local.executions ?? [], server.executions ?? []),
    addedNodeIds,
    addedEdgeIds,
  };
}
