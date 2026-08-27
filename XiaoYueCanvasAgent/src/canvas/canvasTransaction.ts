import { useCanvasStore } from '../store/canvasStore';
import { createId } from '../utils/id';
import { failure } from './canvasErrors';
import type { CanvasChangeSummary, CanvasCommandContext, CanvasCommandResult } from './canvasTypes';

// Validates that the target project is open and (optionally) that the caller's
// revision still matches the live canvas. Returns null when the command may proceed.
export const check = (context: CanvasCommandContext): CanvasCommandResult | null => {
  const state = useCanvasStore.getState();
  if (!context.projectId || state.projectId !== context.projectId) {
    return failure('PROJECT_NOT_OPEN', '目标项目当前未在浏览器中打开', { projectId: context.projectId });
  }
  if (context.expectedRevision !== undefined && context.expectedRevision !== state.revision) {
    return failure('CANVAS_REVISION_CONFLICT', '画布已被用户修改，请重新读取后再执行', {
      currentRevision: state.revision,
      expectedRevision: context.expectedRevision,
    });
  }
  return null;
};

// Wraps a write so that a dry run returns a preview without mutating the store,
// while a real run applies the recipe exactly once and stamps a transaction id.
export const result = <T>(
  context: CanvasCommandContext,
  changes: CanvasChangeSummary,
  value: T,
  apply: () => void,
): CanvasCommandResult<T> => {
  const transactionId = `tx-${createId()}`;
  if (context.dryRun) {
    return { ok: true, status: 'preview', transactionId, revision: useCanvasStore.getState().revision, changes, result: value };
  }
  apply();
  return { ok: true, status: 'applied', transactionId, revision: useCanvasStore.getState().revision, changes, result: value };
};
