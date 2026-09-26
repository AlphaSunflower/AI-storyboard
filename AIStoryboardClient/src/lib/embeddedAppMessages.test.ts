import { describe, expect, it } from 'vitest';
import { buildSceneInspectPrompt } from './embeddedAppMessages';

describe('buildSceneInspectPrompt', () => {
  it('builds a normal project storyboard inspection request', () => {
    expect(buildSceneInspectPrompt([
      { id: 'scene-1', projectId: 'project-1', sceneNumber: 1 },
      { id: 'scene-2', projectId: 'project-1', sceneNumber: 2 },
    ])).toBe('请查看分镜1、分镜2');
  });
});
