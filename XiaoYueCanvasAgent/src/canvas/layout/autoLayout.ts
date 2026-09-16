import { useCanvasStore } from '../../store/canvasStore';
import { canvasCommandService } from '../canvasCommandService';
import type { CanvasCommandResult } from '../canvasTypes';
import { collectConnectedComponentNodeIds } from './layoutScope';

let autoLayoutQueue: Promise<void> = Promise.resolve();

export const requestAutoLayout = (seedNodeIds: string[]): Promise<CanvasCommandResult | null> => {
  const state = useCanvasStore.getState();
  const projectId = state.projectId;
  const canvasId = state.canvasId;
  const seeds = [...new Set(seedNodeIds.filter(Boolean))];
  if (!projectId || !canvasId || !seeds.length) return Promise.resolve(null);

  const task = autoLayoutQueue.then(async () => {
    const current = useCanvasStore.getState();
    if (current.projectId !== projectId || current.canvasId !== canvasId) return null;
    const nodeIds = collectConnectedComponentNodeIds(current.nodes, current.edges, seeds);
    if (!nodeIds.length) return null;
    return canvasCommandService.layoutConnectedComponent(
      { projectId, source: 'agent' },
      { nodeIds },
    );
  }).catch((error) => {
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('morrow:notice', {
        detail: error instanceof Error ? `自动整理失败：${error.message}` : '自动整理失败',
      }));
    }
    return null;
  });
  autoLayoutQueue = task.then(() => undefined, () => undefined);
  return task;
};
