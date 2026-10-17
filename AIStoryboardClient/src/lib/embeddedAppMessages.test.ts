import { describe, expect, it } from 'vitest';
import {
  buildSceneInspectPrompt,
  canvasBindingStatusText,
  isCanvasGraphPatch,
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

  // 与 XiaoYueCanvasAgent/src/canvas/canvasEmbeddedMode.test.ts 用同一份字面量 fixture：
  // 两个包各一份 guard，形状必须一致。
  const patchMessage = (patch: unknown) => ({
    type: 'ai-storyboard:canvas-graph-updated',
    conversationId: 'conversation-1',
    projectId: 'project-1',
    canvasId: 'canvas-1',
    revision: 4,
    patch,
  });

  const validPatch = {
    nodes: [{ id: 'node-1', type: 'image', position: { x: 10, y: 20 }, data: { kind: 'image' } }],
    edges: [{ id: 'edge-1', source: 'node-1', target: 'node-2' }],
    removedNodeIds: ['node-9'],
    removedEdgeIds: ['edge-9'],
  };

  it('accepts a graph update carrying a structurally valid patch', () => {
    expect(isCanvasGraphPatch(validPatch)).toBe(true);
    expect(isCanvasGraphUpdatedMessage(patchMessage(validPatch))).toBe(true);
    // 只有 edges（create_edge）与只有 removed*Ids 的补丁也必须合法
    expect(isCanvasGraphUpdatedMessage(patchMessage({ edges: validPatch.edges }))).toBe(true);
    expect(isCanvasGraphUpdatedMessage(patchMessage({}))).toBe(true);
  });

  it('rejects the whole message when the patch is malformed', () => {
    for (const bad of [
      [], 'patch', 7, { nodes: {} },
      { nodes: [{ position: { x: 0, y: 0 } }] },
      { nodes: [{ id: '' }] },
      { nodes: [null] },
      { removedEdgeIds: [7] },
      { removedNodeIds: 'node-1' },
    ]) {
      expect(isCanvasGraphPatch(bad)).toBe(false);
      expect(isCanvasGraphUpdatedMessage(patchMessage(bad))).toBe(false);
    }
  });
});
