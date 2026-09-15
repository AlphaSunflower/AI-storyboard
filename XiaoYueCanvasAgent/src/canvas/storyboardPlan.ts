import type { CanvasNodeV2 } from './graphContracts';

export interface StoryboardShotPlan {
  shotId?: string;
  segmentId: string;
  shotOrdinal: number;
  sceneDescription: string;
  shotType: string;
  cameraMovement: string;
  durationSeconds: number;
  imagePrompt: string;
  videoPrompt: string;
  negativePrompt: string;
  referenceHints: string[];
  referenceAssetIds?: string[];
}

export interface ShotReconciliation {
  shots: CanvasNodeV2[];
  created: CanvasNodeV2[];
  updated: CanvasNodeV2[];
  stale: CanvasNodeV2[];
}

const stableHash = (value: string) => {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(36);
};

const fallbackKey = (plan: StoryboardShotPlan) =>
  `${plan.segmentId}:${plan.sceneDescription.trim()}`;

const identityOf = (plan: StoryboardShotPlan) => {
  if (plan.shotId) return { shotId: plan.shotId, shotKey: plan.shotId };
  const key = fallbackKey(plan);
  return { shotId: `shot-fallback-${stableHash(key)}`, shotKey: key };
};

const identityOfNode = (node: CanvasNodeV2) => {
  const shotId = typeof node.data.shotId === 'string' ? node.data.shotId : '';
  const shotKey = typeof node.data.shotKey === 'string' ? node.data.shotKey : shotId;
  return { shotId: shotId || shotKey, shotKey };
};

const patchShot = (node: CanvasNodeV2, plan: StoryboardShotPlan, identity: { shotId: string; shotKey: string }): CanvasNodeV2 => ({
  ...node,
  type: 'shot',
  data: {
    ...node.data,
    kind: 'shot',
    label: `镜头 ${plan.shotOrdinal}`,
    prompt: plan.sceneDescription,
    shotId: identity.shotId,
    shotKey: identity.shotKey,
    shotOrdinal: plan.shotOrdinal,
    sceneDescription: plan.sceneDescription,
    shotType: plan.shotType,
    cameraMovement: plan.cameraMovement,
    durationSeconds: plan.durationSeconds,
    imagePrompt: plan.imagePrompt,
    videoPrompt: plan.videoPrompt,
    negativePrompt: plan.negativePrompt,
    referenceHints: plan.referenceHints,
    referenceAssetIds: plan.referenceAssetIds ?? node.data.referenceAssetIds ?? [],
    automation: {
      ...(typeof node.data.automation === 'object' && node.data.automation ? node.data.automation : {}),
      phase: 'ready' as const,
    },
  },
});

export function reconcileStoryboardShots(
  existingShots: CanvasNodeV2[],
  plans: StoryboardShotPlan[],
): ShotReconciliation {
  const orderedPlans = [...plans].sort((a, b) => a.shotOrdinal - b.shotOrdinal);
  const used = new Set<string>();
  const created: CanvasNodeV2[] = [];
  const updated: CanvasNodeV2[] = [];
  const active: CanvasNodeV2[] = [];

  for (const plan of orderedPlans) {
    const identity = identityOf(plan);
    const match = existingShots.find((node) => {
      if (used.has(node.id)) return false;
      const nodeIdentity = identityOfNode(node);
      return nodeIdentity.shotId === identity.shotId || nodeIdentity.shotKey === identity.shotKey;
    });
    if (match) {
      used.add(match.id);
      const next = patchShot(match, plan, identity);
      active.push(next);
      updated.push(next);
      continue;
    }
    const node: CanvasNodeV2 = {
      id: identity.shotId,
      type: 'shot',
      position: { x: plan.shotOrdinal * 420, y: 0 },
      width: 360,
      height: 300,
      data: {
        kind: 'shot',
        label: `镜头 ${plan.shotOrdinal}`,
        prompt: plan.sceneDescription,
        status: 'idle',
        accent: '#ffb3a1',
        shotId: identity.shotId,
        shotKey: identity.shotKey,
        shotOrdinal: plan.shotOrdinal,
        sceneDescription: plan.sceneDescription,
        shotType: plan.shotType,
        cameraMovement: plan.cameraMovement,
        durationSeconds: plan.durationSeconds,
        imagePrompt: plan.imagePrompt,
        videoPrompt: plan.videoPrompt,
        negativePrompt: plan.negativePrompt,
        referenceHints: plan.referenceHints,
        referenceAssetIds: plan.referenceAssetIds ?? [],
        automation: { phase: 'ready' },
      },
    };
    active.push(node);
    created.push(node);
  }

  const stale = existingShots
    .filter((node) => !used.has(node.id))
    .map((node) => ({
      ...node,
      data: {
        ...node.data,
        automation: {
          ...(typeof node.data.automation === 'object' && node.data.automation ? node.data.automation : {}),
          phase: 'stale' as const,
        },
      },
    }));

  return { shots: [...active, ...stale], created, updated, stale };
}
