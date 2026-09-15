import { describe, expect, it } from 'vitest';
import type { GenerationRecord } from '../types';
import { resolveStoryboardPlanButton } from './storyboardGenerationState';

const record = (overrides: Partial<GenerationRecord>): GenerationRecord => ({
  id: 'record-1',
  producerNodeId: 'planner-1',
  actionId: 'plan',
  attempt: 1,
  status: 'queued',
  queueStatus: 'queued',
  materializationStatus: 'pending',
  materializedAssetIds: [],
  resultNodeIds: [],
  sources: [],
  prompt: 'script',
  effectivePrompt: 'script',
  params: {},
  startedAt: 1,
  ...overrides,
});

describe('resolveStoryboardPlanButton', () => {
  it('disables the button while the plan is queued', () => {
    expect(resolveStoryboardPlanButton(record({}), false, true)).toEqual({
      busy: true,
      disabled: true,
      label: '排队中…',
      progress: 0,
    });
  });

  it('shows progress and keeps the button disabled while running', () => {
    expect(resolveStoryboardPlanButton(record({ status: 'running', progress: 0.42 }), false, true)).toEqual({
      busy: true,
      disabled: true,
      label: '生成中 42%',
      progress: 42,
    });
  });

  it('allows a new run after failure or completion', () => {
    expect(resolveStoryboardPlanButton(record({ status: 'failed' }), false, true)).toEqual({
      busy: false,
      disabled: false,
      label: '重试生成分镜',
      progress: 0,
    });
    expect(resolveStoryboardPlanButton(record({ status: 'completed' }), false, true).label).toBe('重新生成分镜');
  });
});
