import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CanvasNode } from '../types';
import { useCanvasStore } from '../store/canvasStore';

const mocks = vi.hoisted(() => ({
  submitImageGeneration: vi.fn(),
  submitVideoGeneration: vi.fn(),
  submitScriptDrafting: vi.fn(),
}));

vi.mock('../services/jobRunner', () => ({
  submitImageGeneration: mocks.submitImageGeneration,
  submitVideoGeneration: mocks.submitVideoGeneration,
  submitScriptDrafting: mocks.submitScriptDrafting,
}));

vi.mock('../services/capabilities', () => ({
  jobAdapter: { cancel: vi.fn() },
}));

import { executeNodeAction } from './nodeExecutionService';

const imageNode = (): CanvasNode => ({
  id: 'image-node',
  type: 'textToImage',
  position: { x: 0, y: 0 },
  data: {
    kind: 'textToImage',
    label: '文生图',
    prompt: 'castle',
    status: 'idle',
    accent: '#fff',
    model: 'image-model',
    size: '2K',
    n: 3,
    gatewayParams: {
      size: '2K',
      n: 3,
      style: { style_type: 'photo', style_weight: 7 },
    },
  },
});

describe('node execution model params', () => {
  beforeEach(() => {
    mocks.submitImageGeneration.mockReset().mockResolvedValue('job-1');
    mocks.submitVideoGeneration.mockReset();
    mocks.submitScriptDrafting.mockReset();
    useCanvasStore.setState({
      projectId: 'project-1',
      canvasId: 'canvas-1',
      nodes: [imageNode()],
      edges: [],
      generationRecords: [],
      executions: [],
      past: [],
      future: [],
      selectedNodeId: 'image-node',
      dragStartSnapshot: null,
      revision: 0,
    });
  });

  it('passes canonical gateway params and legacy fields to image generation', async () => {
    await executeNodeAction('project-1', 'image-node', 'generate');

    expect(mocks.submitImageGeneration).toHaveBeenCalledWith(
      'castle',
      'image-node',
      expect.any(Function),
      expect.objectContaining({
        params: expect.objectContaining({
          model: 'image-model',
          size: '2K',
          n: 3,
          style: { style_type: 'photo', style_weight: 7 },
        }),
      }),
    );
  });
});
