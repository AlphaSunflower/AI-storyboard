import { describe, expect, it } from 'vitest';
import {
  buildCanvasModeReadyMessage,
  buildCanvasModeStatusMessage,
  isCanvasModeControlMessage,
  parseCanvasEmbeddedMode,
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
});
