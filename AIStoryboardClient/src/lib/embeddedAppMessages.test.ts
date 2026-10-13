import { describe, expect, it } from 'vitest';
import {
  buildSceneInspectPrompt,
  canvasBindingStatusText,
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

describe('canvasBindingStatusText', () => {
  const base = { type: 'ai-storyboard:canvas-mode-status' as const, conversationId: 'c1', projectId: 'p1' };

  it('把画布服务连接中/未就绪/选择画布三种状态翻成用户文案', () => {
    expect(canvasBindingStatusText({ ...base, state: 'bridge-connecting' })).toBe('正在连接画布服务…');
    expect(canvasBindingStatusText({ ...base, state: 'bridge-error', message: '画布服务连接中断，正在重试…' }))
      .toBe('画布服务连接中断，正在重试…');
    expect(canvasBindingStatusText({ ...base, state: 'selecting' })).toBe('请选择要编辑的画布');
  });

  it('画布侧没给原因时回落到通用重试提示', () => {
    expect(canvasBindingStatusText({ ...base, state: 'bridge-error' })).toBe('画布服务未就绪，正在重试…');
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
