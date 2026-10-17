import type { CanvasGraphPatch } from './graphContracts';

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

/**
 * 增量补丁：`canvas_run_updated.payload.canvasPatch` → `canvas-run` 消息 metadata → 父页 postMessage。
 *
 * <p>协议在 AIStoryboardClient/src/lib/embeddedAppMessages.ts 有一份同形副本（两个包不共享代码），
 * 改这里必须同步改那边，否则一侧 guard 会把消息整条判为无效。
 *
 * <p>缺省 `patch` 时保持旧行为（只带 revision，画布整图重载），旧发送端零回归。
 */
export type { CanvasGraphPatch, CanvasGraphPatchEntry } from './graphContracts';

export interface CanvasGraphUpdatedMessage {
  type: typeof CANVAS_GRAPH_UPDATED_MESSAGE;
  conversationId: string;
  projectId: string;
  canvasId: string;
  revision: number;
  patch?: CanvasGraphPatch;
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

const isNonEmptyString = (value: unknown): value is string =>
  typeof value === 'string' && value.length > 0;

const isPatchEntry = (value: unknown): value is { id: string; [key: string]: unknown } =>
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

/** 一批图更新的处理计划：能安全增量应用就 patch，否则退回一次整图重载。 */
export type CanvasGraphUpdatePlan =
  | { mode: 'patch'; patches: CanvasGraphPatch[]; revision: number }
  | { mode: 'reload'; revision: number; reason: 'no-target' | 'missing-patch' | 'other-canvas' };

/**
 * 决定一批 postMessage 图更新怎么落地（纯函数，便于单测）。
 *
 * <p>只有「全部更新都带合法补丁」且「补丁目标就是当前打开的这张画布」才走增量；
 * 其余一律 `reload`（等价旧行为：整图重载），宁可多拉一次也不半应用。
 * 补丁是**有界累积**的，`canvasPatchOverflow` 由发送端负责降级成不带补丁的 revision 更新。
 */
export function planCanvasGraphUpdates(
  updates: CanvasGraphUpdatedMessage[],
  target: { canvasId: string; revision: number } | null,
): CanvasGraphUpdatePlan | null {
  if (!updates.length) return null;
  const revision = Math.max(...updates.map((update) => update.revision));
  if (!target) return { mode: 'reload', revision, reason: 'no-target' };
  if (updates.some((update) => !update.patch)) {
    return { mode: 'reload', revision, reason: 'missing-patch' };
  }
  if (updates.some((update) => update.canvasId !== target.canvasId)) {
    return { mode: 'reload', revision, reason: 'other-canvas' };
  }
  return { mode: 'patch', patches: updates.map((update) => update.patch!), revision };
}
