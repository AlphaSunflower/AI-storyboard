export const STORYBOARD_REFERENCE_MESSAGE = 'ai-storyboard:reference-scenes';
export const EMBEDDED_APP_READY_MESSAGE = 'ai-storyboard:embedded-app-ready';
export const CANVAS_MODE_STATUS_MESSAGE = 'ai-storyboard:canvas-mode-status';
export const CANVAS_MODE_READY_MESSAGE = 'ai-storyboard:canvas-mode-ready';
export const CANVAS_MODE_CONTROL_MESSAGE = 'ai-storyboard:canvas-mode-control';
export const SCENE_DRAG_MIME = 'application/x-ai-storyboard-scenes';

export interface EmbeddedSceneReference {
  id: string;
  projectId: string;
  sceneNumber: number;
}

export interface StoryboardReferenceMessage {
  type: typeof STORYBOARD_REFERENCE_MESSAGE;
  projectId: string;
  scenes: EmbeddedSceneReference[];
}

export interface EmbeddedAppReadyMessage {
  type: typeof EMBEDDED_APP_READY_MESSAGE;
  key: 'canvas' | 'editor' | 'docs' | 'library';
}

export type CanvasModeStatusState = 'selecting' | 'bridge-connecting' | 'bridge-error';

export interface CanvasModeStatusMessage {
  type: typeof CANVAS_MODE_STATUS_MESSAGE;
  conversationId: string;
  projectId: string;
  state: CanvasModeStatusState;
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

export function isStoryboardReferenceMessage(value: unknown): value is StoryboardReferenceMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<StoryboardReferenceMessage>;
  return message.type === STORYBOARD_REFERENCE_MESSAGE
    && typeof message.projectId === 'string'
    && Array.isArray(message.scenes);
}

export function isEmbeddedAppReadyMessage(value: unknown): value is EmbeddedAppReadyMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<EmbeddedAppReadyMessage>;
  return message.type === EMBEDDED_APP_READY_MESSAGE
    && (message.key === 'canvas' || message.key === 'editor' || message.key === 'docs' || message.key === 'library');
}

export function isCanvasModeStatusMessage(value: unknown): value is CanvasModeStatusMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<CanvasModeStatusMessage>;
  return message.type === CANVAS_MODE_STATUS_MESSAGE
    && typeof message.conversationId === 'string' && message.conversationId.length > 0
    && typeof message.projectId === 'string' && message.projectId.length > 0
    && (message.state === 'selecting'
      || message.state === 'bridge-connecting'
      || message.state === 'bridge-error');
}

export function isCanvasModeReadyMessage(value: unknown): value is CanvasModeReadyMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<CanvasModeReadyMessage>;
  return message.type === CANVAS_MODE_READY_MESSAGE
    && typeof message.conversationId === 'string' && message.conversationId.length > 0
    && typeof message.projectId === 'string' && message.projectId.length > 0
    && typeof message.canvasId === 'string' && message.canvasId.length > 0
    && message.bridgeReady === true;
}

export function isCanvasModeControlMessage(value: unknown): value is CanvasModeControlMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<CanvasModeControlMessage>;
  return message.type === CANVAS_MODE_CONTROL_MESSAGE
    && typeof message.conversationId === 'string' && message.conversationId.length > 0
    && typeof message.projectId === 'string' && message.projectId.length > 0
    && typeof message.locked === 'boolean';
}

export function buildSceneReviewPrompt(scenes: EmbeddedSceneReference[]): string {
  const sceneRefs = scenes.map((scene) => `分镜${scene.sceneNumber}`).join('、');
  return `请审查 ${sceneRefs}`;
}
