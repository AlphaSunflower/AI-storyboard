export const STORYBOARD_REFERENCE_MESSAGE = 'ai-storyboard:reference-scenes';
export const EMBEDDED_APP_READY_MESSAGE = 'ai-storyboard:embedded-app-ready';
export const CANVAS_AUTH_MESSAGE = 'ai-storyboard:canvas-auth';
export const CANVAS_MODE_STATUS_MESSAGE = 'ai-storyboard:canvas-mode-status';
export const CANVAS_MODE_READY_MESSAGE = 'ai-storyboard:canvas-mode-ready';
export const CANVAS_MODE_CONTROL_MESSAGE = 'ai-storyboard:canvas-mode-control';
export const CANVAS_GRAPH_UPDATED_MESSAGE = 'ai-storyboard:canvas-graph-updated';
export const SIDE_CHAT_CONTEXT_MESSAGE = 'ai-storyboard:side-chat-context';
export const SIDE_CHAT_CONTEXT_UPDATE_MESSAGE = 'ai-storyboard:side-chat-context-update';
export const SIDE_CHAT_CONTEXT_REQUEST_MESSAGE = 'ai-storyboard:side-chat-context-request';
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

export interface CanvasGraphUpdatedMessage {
  type: typeof CANVAS_GRAPH_UPDATED_MESSAGE;
  conversationId: string;
  projectId: string;
  canvasId: string;
  revision: number;
}

export interface SideChatContextItem {
  id: string;
  text: string;
  messageId?: string;
  occurrence?: number;
  createdAt: number;
}

export interface SideChatContextMessage {
  type: typeof SIDE_CHAT_CONTEXT_MESSAGE;
  conversationId: string;
  selections: SideChatContextItem[];
}

export interface SideChatContextUpdateMessage {
  type: typeof SIDE_CHAT_CONTEXT_UPDATE_MESSAGE;
  conversationId: string;
  selections: SideChatContextItem[];
}

export interface SideChatContextRequestMessage {
  type: typeof SIDE_CHAT_CONTEXT_REQUEST_MESSAGE;
  conversationId: string;
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

export function isCanvasGraphUpdatedMessage(value: unknown): value is CanvasGraphUpdatedMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<CanvasGraphUpdatedMessage>;
  return message.type === CANVAS_GRAPH_UPDATED_MESSAGE
    && typeof message.conversationId === 'string' && message.conversationId.length > 0
    && typeof message.projectId === 'string' && message.projectId.length > 0
    && typeof message.canvasId === 'string' && message.canvasId.length > 0
    && typeof message.revision === 'number' && Number.isFinite(message.revision);
}

function isSideChatContextItem(value: unknown): value is SideChatContextItem {
  if (!value || typeof value !== 'object') return false;
  const item = value as Partial<SideChatContextItem>;
  return typeof item.id === 'string'
    && typeof item.text === 'string'
    && typeof item.createdAt === 'number';
}

export function isSideChatContextMessage(value: unknown): value is SideChatContextMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<SideChatContextMessage>;
  return message.type === SIDE_CHAT_CONTEXT_MESSAGE
    && typeof message.conversationId === 'string'
    && Array.isArray(message.selections)
    && message.selections.every(isSideChatContextItem);
}

export function isSideChatContextUpdateMessage(value: unknown): value is SideChatContextUpdateMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<SideChatContextUpdateMessage>;
  return message.type === SIDE_CHAT_CONTEXT_UPDATE_MESSAGE
    && typeof message.conversationId === 'string'
    && Array.isArray(message.selections)
    && message.selections.every(isSideChatContextItem);
}

export function isSideChatContextRequestMessage(value: unknown): value is SideChatContextRequestMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<SideChatContextRequestMessage>;
  return message.type === SIDE_CHAT_CONTEXT_REQUEST_MESSAGE
    && typeof message.conversationId === 'string'
    && message.conversationId.length > 0;
}

export function buildSceneInspectPrompt(scenes: EmbeddedSceneReference[]): string {
  const sceneRefs = scenes.map((scene) => `分镜${scene.sceneNumber}`).join('、');
  return `请查看${sceneRefs}`;
}
