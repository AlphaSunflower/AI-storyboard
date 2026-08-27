import type { CanvasCommandResult } from './canvasTypes';

export const CanvasErrorCode = {
  PROJECT_NOT_OPEN: 'PROJECT_NOT_OPEN',
  CANVAS_REVISION_CONFLICT: 'CANVAS_REVISION_CONFLICT',
  INVALID_ARGUMENTS: 'INVALID_ARGUMENTS',
  NODE_NOT_FOUND: 'NODE_NOT_FOUND',
  LIMIT_EXCEEDED: 'LIMIT_EXCEEDED',
  TOOL_NOT_FOUND: 'TOOL_NOT_FOUND',
  CAPABILITY_UNAVAILABLE: 'CAPABILITY_UNAVAILABLE',
} as const;

export type CanvasErrorCodeValue = (typeof CanvasErrorCode)[keyof typeof CanvasErrorCode];

export const failure = <T = unknown>(code: string, message: string, details?: unknown): CanvasCommandResult<T> => ({
  ok: false,
  status: 'failed',
  error: { code, message, details },
});
