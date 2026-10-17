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

/**
 * 画布增量补丁（wire 字段名 `patch`）。
 *
 * <p>来源：MoonAgent 把画布命令的增量**合并累积**到 `canvas-run` 消息 metadata 的
 * `metadata.canvasPatch`；累积超限时 `metadata.canvasPatchOverflow === true`，
 * 此时不发补丁、只发 revision，由画布整图重载兜底。
 *
 * <p>结构在 XiaoYueCanvasAgent/src/canvas/canvasEmbeddedMode.ts 有一份同形副本（两个包不共享代码），
 * 改这里必须同步改那边。缺省 `patch` 时保持旧行为（零回归）。
 */
export interface CanvasGraphPatch {
  nodes?: CanvasGraphPatchEntry[];
  edges?: CanvasGraphPatchEntry[];
  removedNodeIds?: string[];
  removedEdgeIds?: string[];
}

export interface CanvasGraphPatchEntry {
  id: string;
  [key: string]: unknown;
}

export interface CanvasGraphUpdatedMessage {
  type: typeof CANVAS_GRAPH_UPDATED_MESSAGE;
  conversationId: string;
  projectId: string;
  canvasId: string;
  revision: number;
  patch?: CanvasGraphPatch;
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

const isNonEmptyString = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0;

const isPatchEntry = (value: unknown): value is CanvasGraphPatchEntry =>
  Boolean(value) && typeof value === 'object' && !Array.isArray(value)
  && isNonEmptyString((value as { id?: unknown }).id);

/**
 * 补丁结构校验：缺省 patch → 合法（旧发送端）；给了 patch → nodes/edges 必须是数组、
 * 元素至少要有非空 id、removed*Ids 必须是字符串数组，否则整条消息判为无效。
 * 宁可不更新，也不要拿半截补丁写坏画布。
 */
export function isCanvasGraphPatch(value: unknown): value is CanvasGraphPatch {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const patch = value as Partial<CanvasGraphPatch>;
  if (patch.nodes !== undefined
      && (!Array.isArray(patch.nodes) || !patch.nodes.every(isPatchEntry))) return false;
  if (patch.edges !== undefined
      && (!Array.isArray(patch.edges) || !patch.edges.every(isPatchEntry))) return false;
  if (patch.removedNodeIds !== undefined
      && (!Array.isArray(patch.removedNodeIds) || !patch.removedNodeIds.every(isNonEmptyString))) return false;
  if (patch.removedEdgeIds !== undefined
      && (!Array.isArray(patch.removedEdgeIds) || !patch.removedEdgeIds.every(isNonEmptyString))) return false;
  return true;
}

export function isCanvasGraphUpdatedMessage(value: unknown): value is CanvasGraphUpdatedMessage {
  if (!value || typeof value !== 'object') return false;
  const message = value as Partial<CanvasGraphUpdatedMessage>;
  return message.type === CANVAS_GRAPH_UPDATED_MESSAGE
    && typeof message.conversationId === 'string' && message.conversationId.length > 0
    && typeof message.projectId === 'string' && message.projectId.length > 0
    && typeof message.canvasId === 'string' && message.canvasId.length > 0
    && typeof message.revision === 'number' && Number.isFinite(message.revision)
    && (message.patch === undefined || isCanvasGraphPatch(message.patch));
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

/**
 * 内嵌画布上报的连接状态 → 用户在聊天页看到的文案。
 * bridge-error 优先用画布侧给的具体原因，缺省时回落为通用重试提示。
 */
export function canvasBindingStatusText(message: CanvasModeStatusMessage): string {
  if (message.state === 'bridge-connecting') return '正在连接画布服务…';
  if (message.state === 'bridge-error') return message.message || '画布服务未就绪，正在重试…';
  return '请选择要编辑的画布';
}
