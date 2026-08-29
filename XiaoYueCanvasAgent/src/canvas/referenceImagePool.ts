import type { CanvasEdge, CanvasNode, MediaKind } from '../types';

/**
 * 参考图池（适配当前 Blob 资产模型，非参考工程的 tauri 文件路径模型）。
 *
 * 关键差异：参考工程从节点 `imageUrl` 字符串提取参考图并直接显示；当前画布是
 * Blob 资产模型——节点只存 `data.assetId`，图片本身在 mediaStore 里按 assetId 存
 * `previewBlob/thumbnailBlob`。所以本池**只产出一条"参考条目"（含 assetId + 编号）**，
 * 显示 URL 由 UI 层用 assetId → mediaStore blob → objectURL 转换，两层解耦。
 *
 * 能力（对齐参考 buildReferenceImagePool 语义，按需裁剪）：
 *  - `buildReferenceImagePool`：BFS 沿上游（含传递闭包）收集带 assetId 的源节点，
 *    按 mediaType(image/video) + 源节点 X 坐标排序，稳定编号（@图N = number）。
 *    - 去重：同一 assetId 只保留首个出现（防多节点引用同一图重复入池）。
 *    - caller 可设 maxDepth：1=只看直接上游（UI 展示），Infinity=全传递闭包（@图N 解析）。
 *  - @图1 格式 token 解析/构建/清洗见 referenceTokenEditing（独立模块）。
 *
 * 参考图中是否允许「容器/编排」节点透传参考（如 sequence 内子节点）？当前先不——
 * 只收集直接带 assetId 的源节点，保持简单；后续若需 sequence 透传再扩展。
 */

export type ReferenceMediaType = 'image' | 'video';

export interface ReferenceImageEntry {
  /** 稳定 1-based 编号（@图N 映射） */
  number: number;
  /** 资产 ID（当前 Blob 模型的关键：mediaStore 用它取 blob） */
  assetId: string;
  /** 产出该参考图的源节点 ID */
  sourceNodeId: string;
  /** 源节点显示名 */
  sourceNodeName: string;
  /** 媒体类型 */
  mediaType: ReferenceMediaType;
  /** 来源：上游节点引用 */
  source: 'upstream';
}

export interface ReferenceImagePoolResult {
  entries: ReferenceImageEntry[];
  count: number;
  getByNumber: (n: number) => ReferenceImageEntry | undefined;
  getByAssetId: (assetId: string) => ReferenceImageEntry | undefined;
  /** 全部 assetId（按编号顺序） */
  assetIds: string[];
  isValidNumber: (n: number) => boolean;
}

/** 是否允许被当作参考图来源的节点 kind（image/video 带真实资产；生成器节点本身无 assetId 不参与）。 */
const REFERENCE_SOURCE_KINDS: ReadonlySet<MediaKind> = new Set<MediaKind>(['image', 'video']);

/**
 * BFS 沿上游闭包收集参考条目。visited 防环。ordering: mediaType(image 优先) + 源 X 坐标。
 * 只收集"带 assetId 且 kind 属于参考源"的节点（text/script/prompt/note/sequence 无资产，跳过）。
 */
function collectUpstreamReferences(
  targetNodeId: string,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  seenAssetIds: Set<string>,
  maxDepth: number
): Omit<ReferenceImageEntry, 'number'>[] {
  const results: Omit<ReferenceImageEntry, 'number'>[] = [];
  const queue: string[] = [targetNodeId];
  const depth = new Map<string, number>([[targetNodeId, 0]]);
  const visited = new Set<string>();

  while (queue.length > 0) {
    const current = queue.shift()!;
    const currentDepth = depth.get(current) ?? 0;
    if (visited.has(current)) continue;
    visited.add(current);

    if (current !== targetNodeId) {
      const node = nodes.find((n) => n.id === current);
      if (node && REFERENCE_SOURCE_KINDS.has(node.data.kind)) {
        const assetId = node.data.assetId;
        if (assetId && !seenAssetIds.has(assetId)) {
          seenAssetIds.add(assetId);
          results.push({
            assetId,
            sourceNodeId: node.id,
            sourceNodeName: node.data.label || assetId.slice(0, 10),
            mediaType: node.data.kind === 'video' ? 'video' : 'image',
            source: 'upstream',
          });
        }
      }
    }

    if (currentDepth >= maxDepth) continue;
    for (const edge of edges) {
      if (edge.target === current && !visited.has(edge.source) && !queue.includes(edge.source)) {
        queue.push(edge.source);
        depth.set(edge.source, currentDepth + 1);
      }
    }
  }

  return results;
}

/**
 * 构建参考图池。
 * @param maxDepth 1=仅直接上游（UI 展示）；Infinity=全传递闭包（@图N 解析）。
 */
export function buildReferenceImagePool(
  targetNodeId: string,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  maxDepth: number = 1
): ReferenceImagePoolResult {
  const seenAssetIds = new Set<string>();
  const collected = collectUpstreamReferences(targetNodeId, nodes, edges, seenAssetIds, maxDepth);

  // 排序：image 优先，再按源节点 X 坐标（左→右）
  const sorted = [...collected].sort((a, b) => {
    const typeOrder: Record<ReferenceMediaType, number> = { image: 0, video: 1 };
    const typeDiff = (typeOrder[a.mediaType] ?? 0) - (typeOrder[b.mediaType] ?? 0);
    if (typeDiff !== 0) return typeDiff;
    const nodeA = nodes.find((n) => n.id === a.sourceNodeId);
    const nodeB = nodes.find((n) => n.id === b.sourceNodeId);
    return (nodeA?.position.x ?? 0) - (nodeB?.position.x ?? 0);
  });

  const entries: ReferenceImageEntry[] = sorted.map((item, index) => ({ ...item, number: index + 1 }));

  const numberMap = new Map(entries.map((e) => [e.number, e]));
  const assetIdMap = new Map(entries.map((e) => [e.assetId, e]));

  return {
    entries,
    count: entries.length,
    getByNumber: (n) => numberMap.get(n),
    getByAssetId: (assetId) => assetIdMap.get(assetId),
    assetIds: entries.map((e) => e.assetId),
    isValidNumber: (n) => n >= 1 && n <= entries.length,
  };
}
