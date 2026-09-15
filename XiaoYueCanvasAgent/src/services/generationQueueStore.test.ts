import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearLocalData,
  listGenerationQueueItems,
  putGenerationQueueItem,
} from './projectStorage';
import type { GenerationQueueItem } from '../canvas/generationQueue';

const item: GenerationQueueItem = {
  id: 'queue-1',
  projectId: 'project-1',
  canvasId: 'canvas-1',
  producerNodeId: 'node-1',
  generationRecordId: 'record-1',
  requestSnapshot: { capabilityId: 'image.generate', sourceAssetIds: [], params: {} },
  status: 'pending_submit',
  attempt: 1,
  createdAt: 1,
  updatedAt: 1,
};

describe('generation queue storage', () => {
  beforeEach(clearLocalData);

  it('persists queue items outside the canvas graph', async () => {
    await putGenerationQueueItem(item);
    await expect(listGenerationQueueItems('project-1')).resolves.toEqual([item]);
  });

  it('clears queue items with local project data', async () => {
    await putGenerationQueueItem(item);
    await clearLocalData();
    await expect(listGenerationQueueItems()).resolves.toEqual([]);
  });
});
