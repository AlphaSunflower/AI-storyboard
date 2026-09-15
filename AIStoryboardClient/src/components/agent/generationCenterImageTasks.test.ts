import { describe, expect, it } from 'vitest';
import { partitionImageQueueTasks } from './generationCenterImageTasks';

describe('partitionImageQueueTasks', () => {
  it('separates automatic generation from image refinement', () => {
    const auto = { id: 'auto', mode: 'generate' };
    const refine = { id: 'refine', mode: 'edit' };
    const legacy = { id: 'legacy', mode: null };

    expect(partitionImageQueueTasks([auto, refine, legacy])).toEqual({
      auto: [auto],
      refine: [refine, legacy],
    });
  });
});
