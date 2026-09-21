import { describe, expect, it } from 'vitest';
import {
  isEmbeddedAppReadyMessage,
  isCanvasModeControlMessage,
  isCanvasModeReadyMessage,
  isCanvasModeStatusMessage,
} from './embeddedAppMessages';

describe('embedded app canvas messages', () => {
  it('accepts a canvas ready message with a real canvas id', () => {
    expect(isCanvasModeReadyMessage({
      type: 'ai-storyboard:canvas-mode-ready',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      bridgeReady: true,
    })).toBe(true);
  });

  it('rejects canvas ready messages without a project, canvas, or bridge readiness', () => {
    expect(isCanvasModeReadyMessage({
      type: 'ai-storyboard:canvas-mode-ready',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      canvasId: '',
      bridgeReady: true,
    })).toBe(false);
    expect(isCanvasModeReadyMessage({
      type: 'ai-storyboard:canvas-mode-ready',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      canvasId: 'canvas-1',
      bridgeReady: false,
    })).toBe(false);
  });

  it('accepts canvas status messages for selection and bridge state', () => {
    expect(isCanvasModeStatusMessage({
      type: 'ai-storyboard:canvas-mode-status',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      state: 'bridge-connecting',
    })).toBe(true);
    expect(isCanvasModeStatusMessage({
      type: 'ai-storyboard:canvas-mode-status',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      state: 'unknown',
    })).toBe(false);
  });

  it('accepts parent control messages that lock switching during execution', () => {
    expect(isCanvasModeControlMessage({
      type: 'ai-storyboard:canvas-mode-control',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      locked: true,
    })).toBe(true);
    expect(isCanvasModeControlMessage({
      type: 'ai-storyboard:canvas-mode-control',
      conversationId: 'conversation-1',
      projectId: 'project-1',
      locked: 'yes',
    })).toBe(false);
  });

  it('accepts the embedded project library ready message', () => {
    expect(isEmbeddedAppReadyMessage({
      type: 'ai-storyboard:embedded-app-ready',
      key: 'library',
    })).toBe(true);
  });
});
