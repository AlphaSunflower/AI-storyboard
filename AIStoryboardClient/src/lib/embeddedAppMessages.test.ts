import { describe, expect, it } from 'vitest';
import {
  buildSceneInspectPrompt,
  isCanvasGraphUpdatedMessage,
} from './embeddedAppMessages';

describe('buildSceneInspectPrompt', () => {
  it('builds a normal project storyboard inspection request', () => {
    expect(buildSceneInspectPrompt([
      { id: 'scene-1', projectId: 'project-1', sceneNumber: 1 },
      { id: 'scene-2', projectId: 'project-1', sceneNumber: 2 },
    ])).toBe('请查看分镜1、分镜2');
  });
});

describe('isCanvasGraphUpdatedMessage', () => {
  it('accepts a canvas graph revision update', () => {
    expect(isCanvasGraphUpdatedMessage({
      type: 'ai-storyboard:canvas-graph-updated',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      revision: 3,
    })).toBe(true);
  });

  it('rejects an update without a numeric revision', () => {
    expect(isCanvasGraphUpdatedMessage({
      type: 'ai-storyboard:canvas-graph-updated',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      revision: '3',
    })).toBe(false);
  });
});
