import { describe, expect, it } from 'vitest';
import type { CanvasNodeV2 } from './graphContracts';
import { reconcileStoryboardShots, type StoryboardShotPlan } from './storyboardPlan';

const shotNode = (id: string, shotId: string, ordinal: number, description: string): CanvasNodeV2 => ({
  id,
  type: 'shot',
  position: { x: ordinal * 400, y: 0 },
  data: {
    kind: 'shot',
    label: `镜头 ${ordinal}`,
    prompt: description,
    status: 'idle',
    accent: '#fff',
    shotId,
    shotKey: shotId,
    shotOrdinal: ordinal,
    imagePrompt: description,
    videoPrompt: description,
  },
});

const plan = (shotId: string, ordinal: number, description: string): StoryboardShotPlan => ({
  shotId,
  segmentId: `segment-${shotId}`,
  shotOrdinal: ordinal,
  sceneDescription: description,
  shotType: '中景',
  cameraMovement: '推进',
  durationSeconds: 3,
  imagePrompt: description,
  videoPrompt: description,
  negativePrompt: '',
  referenceHints: [],
});

describe('reconcileStoryboardShots', () => {
  it('updates matching shot ids and preserves their node ids', () => {
    const existing = [shotNode('node-a', 'shot-a', 1, '旧描述')];
    const result = reconcileStoryboardShots(existing, [plan('shot-a', 1, '新描述')]);

    expect(result.created).toHaveLength(0);
    expect(result.stale).toHaveLength(0);
    expect(result.shots[0]).toMatchObject({
      id: 'node-a',
      data: { shotId: 'shot-a', prompt: '新描述' },
    });
  });

  it('creates new shots, reorders by ordinal, and preserves stale shots', () => {
    const existing = [
      shotNode('node-a', 'shot-a', 1, 'A'),
      shotNode('node-b', 'shot-b', 2, 'B'),
    ];
    const result = reconcileStoryboardShots(existing, [
      plan('shot-b', 1, 'B2'),
      plan('shot-c', 2, 'C'),
    ]);

    expect(result.created.map((shot) => shot.data.shotId)).toEqual(['shot-c']);
    expect(result.updated.map((shot) => shot.id)).toEqual(['node-b']);
    expect(result.stale.map((shot) => shot.data.shotId)).toEqual(['shot-a']);
    expect(result.shots.find((shot) => shot.data.shotId === 'shot-b')?.id).toBe('node-b');
  });

  it('derives deterministic fallback identity from segment and content fingerprint', () => {
    const value = plan('', 1, '一扇门打开');
    const result = reconcileStoryboardShots([], [{ ...value, shotId: undefined }]);

    expect(result.created[0].data.shotKey).toBe('segment-:一扇门打开');
    expect(result.created[0].data.shotId).toMatch(/^shot-fallback-/);
  });

  it('keeps resolved reference assets on each shot', () => {
    const result = reconcileStoryboardShots([], [{
      ...plan('shot-a', 1, '角色走进房间'),
      referenceHints: ['@image#1'],
      referenceAssetIds: ['asset-character'],
    }]);

    expect(result.created[0].data.referenceAssetIds).toEqual(['asset-character']);
  });
});
