import { describe, expect, it } from 'vitest';
import {
  buildCanvasModeReadyMessage,
  buildCanvasModeStatusMessage,
  isCanvasModeControlMessage,
  isCanvasGraphPatch,
  isCanvasGraphUpdatedMessage,
  parseCanvasEmbeddedMode,
  planCanvasGraphUpdates,
  type CanvasGraphUpdatedMessage,
} from './canvasEmbeddedMode';

describe('canvas embedded mode', () => {
  it('parses a controlled canvas URL from /chat', () => {
    expect(parseCanvasEmbeddedMode(
      '?embedded=1&canvasMode=1&conversationId=conversation-1&projectId=project-1&parentOrigin=http%3A%2F%2Flocalhost%3A5173',
    )).toEqual({
      conversationId: 'conversation-1',
      projectId: 'project-1',
      parentOrigin: 'http://localhost:5173',
    });
  });

  it('ignores incomplete or ordinary embedded canvas URLs', () => {
    expect(parseCanvasEmbeddedMode('?embedded=1&projectId=project-1')).toBeNull();
    expect(parseCanvasEmbeddedMode('?canvasMode=1&projectId=project-1')).toBeNull();
  });

  it('builds ready and status messages for the chat parent', () => {
    const mode = {
      conversationId: 'conversation-1',
      projectId: 'project-1',
      parentOrigin: 'http://localhost:5173',
    };
    expect(buildCanvasModeReadyMessage(mode, 'canvas-1', '画布 1')).toMatchObject({
      type: 'ai-storyboard:canvas-mode-ready',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      bridgeReady: true,
      title: '画布 1',
    });
    expect(buildCanvasModeStatusMessage(mode, 'bridge-connecting')).toMatchObject({
      type: 'ai-storyboard:canvas-mode-status',
      state: 'bridge-connecting',
    });
  });

  it('parses parent control messages for execution locks', () => {
    expect(isCanvasModeControlMessage({
      type: 'ai-storyboard:canvas-mode-control',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      locked: false,
    })).toBe(true);
    expect(isCanvasModeControlMessage({
      type: 'ai-storyboard:canvas-mode-control',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      locked: true,
    })).toBe(true);
  });

  it('parses parent graph revision updates', () => {
    expect(isCanvasGraphUpdatedMessage({
      type: 'ai-storyboard:canvas-graph-updated',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      revision: 3,
    })).toBe(true);
    expect(isCanvasGraphUpdatedMessage({
      type: 'ai-storyboard:canvas-graph-updated',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      revision: '3',
    })).toBe(false);
  });

  // 与 AIStoryboardClient/src/lib/embeddedAppMessages.test.ts 用同一份字面量 fixture：
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
    // 只有 edges 的补丁（create_edge）与只有 removed*Ids 的补丁都必须合法
    expect(isCanvasGraphUpdatedMessage(patchMessage({ edges: validPatch.edges }))).toBe(true);
    expect(isCanvasGraphUpdatedMessage(patchMessage({ removedNodeIds: ['node-1'] }))).toBe(true);
    expect(isCanvasGraphUpdatedMessage(patchMessage({}))).toBe(true);
  });

  it('rejects the whole message when the patch is malformed', () => {
    expect(isCanvasGraphPatch([])).toBe(false);
    expect(isCanvasGraphPatch('patch')).toBe(false);
    // nodes 不是数组 / 元素缺非空 id
    expect(isCanvasGraphUpdatedMessage(patchMessage({ nodes: {} }))).toBe(false);
    expect(isCanvasGraphUpdatedMessage(patchMessage({ nodes: [{ position: { x: 0, y: 0 } }] }))).toBe(false);
    expect(isCanvasGraphUpdatedMessage(patchMessage({ nodes: [{ id: '' }] }))).toBe(false);
    expect(isCanvasGraphUpdatedMessage(patchMessage({ nodes: [null] }))).toBe(false);
    // removed*Ids 必须是字符串数组
    expect(isCanvasGraphUpdatedMessage(patchMessage({ removedEdgeIds: [7] }))).toBe(false);
    expect(isCanvasGraphUpdatedMessage(patchMessage({ removedNodeIds: 'node-1' }))).toBe(false);
  });

  it('plans incremental patches only when every update carries one for the open canvas', () => {
    // 注意：patchMessage 里 revision 是硬编码的 fixture 值，这里必须显式覆盖，
    // 否则 update(5) 实际带的是 4，会让"整批退回重载时取最大 revision"的断言假失败。
    const update = (revision: number, patch?: unknown, canvasId = 'canvas-1') =>
      ({ ...patchMessage(patch), revision, canvasId } as unknown as CanvasGraphUpdatedMessage & { revision: number; canvasId: string });
    const withPatch = (revision: number) => update(revision, validPatch);
    const target = { canvasId: 'canvas-1', revision: 3 };

    expect(planCanvasGraphUpdates([], target)).toBeNull();
    expect(planCanvasGraphUpdates([withPatch(4), withPatch(6)], target))
      .toEqual({ mode: 'patch', patches: [validPatch, validPatch], revision: 6 });
    // 任一条没带补丁（例如 canvasPatchOverflow 降级）→ 整批退回全量重载
    expect(planCanvasGraphUpdates([withPatch(4), update(5)], target))
      .toMatchObject({ mode: 'reload', reason: 'missing-patch', revision: 5 });
    // 补丁目标不是当前打开的画布 → 全量重载
    expect(planCanvasGraphUpdates([{ ...withPatch(4), canvasId: 'canvas-2' }], target))
      .toMatchObject({ mode: 'reload', reason: 'other-canvas' });
    // 画布文档不可用（未打开/已切换）→ 全量重载
    expect(planCanvasGraphUpdates([withPatch(4)], null))
      .toMatchObject({ mode: 'reload', reason: 'no-target', revision: 4 });
  });
});
