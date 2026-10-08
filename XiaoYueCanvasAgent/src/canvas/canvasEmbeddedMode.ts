export const CANVAS_MODE_STATUS_MESSAGE = 'ai-storyboard:canvas-mode-status';
export const CANVAS_MODE_READY_MESSAGE = 'ai-storyboard:canvas-mode-ready';
export const CANVAS_MODE_CONTROL_MESSAGE = 'ai-storyboard:canvas-mode-control';
export const CANVAS_GRAPH_UPDATED_MESSAGE = 'ai-storyboard:canvas-graph-updated';
export const EMBEDDED_APP_READY_MESSAGE = 'ai-storyboard:embedded-app-ready';
export const CANVAS_AUTH_MESSAGE = 'ai-storyboard:canvas-auth';

export type CanvasBridgeStatusState = 'selecting' | 'bridge-connecting' | 'bridge-error';

export interface CanvasEmbeddedMode {
  conversationId: string;
  projectId: string;
  parentOrigin: string;
}

export interface CanvasAuthMessage {
  type: typeof CANVAS_AUTH_MESSAGE;
  accessToken: string;
  userId?: string;
}

export interface CanvasModeStatusMessage {
  type: typeof CANVAS_MODE_STATUS_MESSAGE;
  conversationId: string;
  projectId: string;
  state: CanvasBridgeStatusState;
  message?: string;
}

export interface CanvasModeReadyMessage {
  type: typeof CANVAS_MODE_READY_MESSAGE;
  conversationId: string;
  projectId: string;
  canvasId: string;
  bridgeReady: true;
  title?: string;
}

export interface CanvasModeControlMessage {
  type: typeof CANVAS_MODE_CONTROL_MESSAGE;
  conversationId: string;
  projectId: string;
  locked: boolean;
}

export interface CanvasGraphUpdatedMessage {
  type: typeof CANVAS_GRAPH_UPDATED_MESSAGE;
  conversationId: string;
  projectId: string;
  canvasId: string;
  revision: number;
}

export function parseCanvasEmbeddedMode(search: string): CanvasEmbeddedMode | null {
  const params = new URLSearchParams(search);
  if (params.get('embedded') !== '1' || params.get('canvasMode') !== '1') return null;
  const conversationId = params.get('conversationId')?.trim() ?? '';
  const projectId = params.get('projectId')?.trim() ?? '';
  const parentOrigin = params.get('parentOrigin')?.trim() ?? '';
  if (!conversationId || !projectId || !parentOrigin) return null;
  try {
    const origin = new URL(parentOrigin).origin;
    if (origin !== parentOrigin.replace(/\/$/, '')) return null;
  } catch {
    return null;
  }
  return { conversationId, projectId, parentOrigin };
}

export function isCanvasAuthMessage(value: unknown): value is CanvasAuthMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<CanvasAuthMessage>;
  return message.type === CANVAS_AUTH_MESSAGE
    && typeof message.accessToken === 'string'
    && message.accessToken.length > 0
    && (message.userId === undefined || typeof message.userId === 'string');
}

export function buildCanvasModeStatusMessage(
  mode: CanvasEmbeddedMode,
  state: CanvasBridgeStatusState,
  message?: string,
): CanvasModeStatusMessage {
  return {
    type: CANVAS_MODE_STATUS_MESSAGE,
    conversationId: mode.conversationId,
    projectId: mode.projectId,
    state,
    ...(message ? { message } : {}),
  };
}

export function buildCanvasModeReadyMessage(
  mode: CanvasEmbeddedMode,
  canvasId: string,
  title?: string,
): CanvasModeReadyMessage {
  return {
    type: CANVAS_MODE_READY_MESSAGE,
    conversationId: mode.conversationId,
    projectId: mode.projectId,
    canvasId,
    bridgeReady: true,
    ...(title ? { title } : {}),
  };
}

export function postCanvasModeMessage(
  mode: CanvasEmbeddedMode,
  message: CanvasModeStatusMessage | CanvasModeReadyMessage,
): void {
  if (window.parent === window) return;
  window.parent.postMessage(message, mode.parentOrigin);
}

export function isCanvasModeControlMessage(value: unknown): value is CanvasModeControlMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<CanvasModeControlMessage>;
  return message.type === CANVAS_MODE_CONTROL_MESSAGE
    && typeof message.conversationId === 'string' && message.conversationId.length > 0
    && typeof message.projectId === 'string' && message.projectId.length > 0
    && typeof message.locked === 'boolean';
}

export function isCanvasGraphUpdatedMessage(value: unknown): value is CanvasGraphUpdatedMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<CanvasGraphUpdatedMessage>;
  return message.type === CANVAS_GRAPH_UPDATED_MESSAGE
    && typeof message.conversationId === 'string' && message.conversationId.length > 0
    && typeof message.projectId === 'string' && message.projectId.length > 0
    && typeof message.canvasId === 'string' && message.canvasId.length > 0
    && typeof message.revision === 'number' && Number.isFinite(message.revision);
}
