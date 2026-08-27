import { useCanvasStore } from '../store/canvasStore';
import { failure } from './canvasErrors';
import { toNodeSummary } from './canvasSelectors';

interface GetStateOptions {
  limit?: number;
  offset?: number;
  includeEdges?: boolean;
}

export const canvasQueryService = {
  getState(projectId: string, options: GetStateOptions = {}) {
    const state = useCanvasStore.getState();
    if (state.projectId !== projectId) return failure('PROJECT_NOT_OPEN', '目标项目当前未在浏览器中打开');
    const offset = Math.max(0, options.offset ?? 0);
    const limit = Math.min(500, Math.max(1, options.limit ?? 200));
    const nodes = state.nodes.slice(offset, offset + limit).map(toNodeSummary);
    return {
      ok: true as const,
      status: 'applied' as const,
      revision: state.revision,
      result: {
        projectId,
        revision: state.revision,
        nodeCount: state.nodes.length,
        edgeCount: state.edges.length,
        nodes,
        edges: options.includeEdges === false ? undefined : state.edges.slice(0, 1000),
      },
    };
  },

  listNodes(projectId: string, nodeIds?: string[]) {
    const state = useCanvasStore.getState();
    if (state.projectId !== projectId) return failure('PROJECT_NOT_OPEN', '目标项目当前未在浏览器中打开');
    const ids = nodeIds ? new Set(nodeIds) : undefined;
    return {
      ok: true as const,
      status: 'applied' as const,
      revision: state.revision,
      result: state.nodes.filter((node) => !ids || ids.has(node.id)).map(toNodeSummary),
    };
  },

  getNode(projectId: string, nodeId: string) {
    const state = useCanvasStore.getState();
    if (state.projectId !== projectId) return failure('PROJECT_NOT_OPEN', '目标项目当前未在浏览器中打开');
    const node = state.nodes.find((item) => item.id === nodeId);
    if (!node) return failure('NODE_NOT_FOUND', '节点不存在', { nodeId });
    return { ok: true as const, status: 'applied' as const, revision: state.revision, result: toNodeSummary(node) };
  },

  getSelection(projectId: string) {
    const state = useCanvasStore.getState();
    if (state.projectId !== projectId) return failure('PROJECT_NOT_OPEN', '目标项目当前未在浏览器中打开');
    return {
      ok: true as const,
      status: 'applied' as const,
      revision: state.revision,
      result: {
        selectedNodeIds: state.nodes.filter((node) => node.selected).map((node) => node.id),
        selectedNodeId: state.selectedNodeId,
      },
    };
  },

  listEdges(projectId: string, edgeIds?: string[]) {
    const state = useCanvasStore.getState();
    if (state.projectId !== projectId) return failure('PROJECT_NOT_OPEN', '目标项目当前未在浏览器中打开');
    const ids = edgeIds ? new Set(edgeIds) : undefined;
    const edges = state.edges.filter((edge) => !ids || ids.has(edge.id)).map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourceHandle: edge.sourceHandle,
      targetHandle: edge.targetHandle,
      type: edge.type,
    }));
    return { ok: true as const, status: 'applied' as const, revision: state.revision, result: edges };
  },

  getRevision(projectId: string) {
    const state = useCanvasStore.getState();
    if (state.projectId !== projectId) return failure('PROJECT_NOT_OPEN', '目标项目当前未在浏览器中打开');
    return { ok: true as const, status: 'applied' as const, revision: state.revision, result: { projectId, revision: state.revision } };
  },

  getSummary(projectId: string) {
    const state = useCanvasStore.getState();
    if (state.projectId !== projectId) return failure('PROJECT_NOT_OPEN', '目标项目当前未在浏览器中打开');
    return {
      ok: true as const,
      status: 'applied' as const,
      revision: state.revision,
      result: {
        projectId,
        revision: state.revision,
        nodeCount: state.nodes.length,
        edgeCount: state.edges.length,
        selectedNodeId: state.selectedNodeId,
      },
    };
  },
};
