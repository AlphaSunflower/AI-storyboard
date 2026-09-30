export type AgentMediaKind = 'image' | 'video' | 'audio' | 'file';

export interface AgentMediaRef {
  kind: AgentMediaKind;
  assetId?: string;
  url?: string;
  name?: string;
  mimeType?: string;
  source?: 'upload' | 'project' | 'canvas';
}

export interface CanvasEditContext {
  mode: 'canvas-edit';
  projectId: string;
  canvasId: string;
  mediaRefs: AgentMediaRef[];
  excludedMediaUrls?: string[];
}

export interface CanvasBinding {
  conversationId?: string;
  turnId?: string;
  projectId?: string;
  canvasId?: string;
  canvasTitle?: string;
  requestedIntent?: string;
  formToken: string;
  expirationTime?: string;
  picUrls?: string[];
  assetIds?: string[];
  mediaRefs?: AgentMediaRef[];
}

export type CanvasModePhase = 'natural' | 'binding' | 'locked' | 'exit_pending';

export interface CanvasRequestMedia {
  picUrls?: string[];
  assetIds?: string[];
  mediaRefs?: AgentMediaRef[];
}

const CANVAS_CONTEXT_STORAGE_KEY = 'moon.canvasEditContext';

export function normalizeCanvasEditContext(context: CanvasEditContext | null): CanvasEditContext | null {
  if (!context) return null;
  return {
    ...context,
    mediaRefs: Array.isArray(context.mediaRefs) ? context.mediaRefs : [],
    ...(Array.isArray(context.excludedMediaUrls)
      ? { excludedMediaUrls: context.excludedMediaUrls }
      : { excludedMediaUrls: undefined }),
  };
}

export function resolveCanvasModePhase(
  context: CanvasEditContext | null,
  binding: CanvasBinding | null,
  exitPending: boolean,
): CanvasModePhase {
  if (context && exitPending) return 'exit_pending';
  if (context) return 'locked';
  if (binding) return 'binding';
  return 'natural';
}

export function rebindCanvasEditContext(
  context: CanvasEditContext | null,
  projectId: string,
  canvasId: string,
): CanvasEditContext | null {
  if (!context || context.projectId !== projectId || !canvasId) return null;
  return { ...context, canvasId };
}

export function parseCanvasEditContext(search: string): CanvasEditContext | null {
  const params = new URLSearchParams(search);
  if (params.get('surface') !== 'canvas-edit') return null;
  const projectId = params.get('projectId')?.trim();
  const canvasId = params.get('canvasId')?.trim();
  if (!projectId || !canvasId) return null;
  return normalizeCanvasEditContext({
    mode: 'canvas-edit',
    projectId,
    canvasId,
    mediaRefs: [],
  });
}

export function persistCanvasEditContext(context: CanvasEditContext): void {
  sessionStorage.setItem(CANVAS_CONTEXT_STORAGE_KEY, JSON.stringify(context));
}

export function loadCanvasEditContext(): CanvasEditContext | null {
  const raw = sessionStorage.getItem(CANVAS_CONTEXT_STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as CanvasEditContext;
    if (parsed?.mode !== 'canvas-edit' || !parsed.projectId || !parsed.canvasId) return null;
    return normalizeCanvasEditContext({ ...parsed, mediaRefs: parsed.mediaRefs ?? [] });
  } catch {
    return null;
  }
}

export function clearCanvasEditContext(): void {
  sessionStorage.removeItem(CANVAS_CONTEXT_STORAGE_KEY);
}

export function buildCanvasRequestContext(
  context: CanvasEditContext | null,
  media: CanvasRequestMedia = {},
): CanvasEditContext | undefined {
  if (!context) return undefined;
  const normalized = normalizeCanvasEditContext(context);
  if (!normalized) return undefined;
  const refs = [
    ...(normalized.mediaRefs ?? []),
    ...(media.mediaRefs ?? []),
    ...(media.picUrls ?? []).map((url): AgentMediaRef => ({ kind: 'image', url, source: 'upload' })),
    ...(media.assetIds ?? []).map((assetId): AgentMediaRef => ({ kind: 'file', assetId, source: 'project' })),
  ];
  const seen = new Set<string>();
  const mediaRefs = refs.filter((ref) => {
    const urlKey = (() => {
      if (!ref.url) return '';
      try {
        const parsed = new URL(ref.url, 'http://canvas.local');
        return `${parsed.pathname}${parsed.search}`;
      } catch {
        return ref.url;
      }
    })();
    const key = `${ref.kind}:${ref.assetId ?? urlKey}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  const activeKeys = new Set(mediaRefs.map((ref) => mediaUrlKey(ref.url)));
  const excludedMediaUrls = normalized.excludedMediaUrls === undefined
    ? undefined
    : normalized.excludedMediaUrls.filter((url) => !activeKeys.has(mediaUrlKey(url)));
  return {
    ...normalized,
    mediaRefs,
    ...(excludedMediaUrls === undefined ? {} : { excludedMediaUrls }),
  };
}

export function mediaUrlKey(url?: string): string {
  if (!url) return '';
  try {
    const parsed = new URL(url, 'http://canvas.local');
    return `${parsed.pathname}${parsed.search}`;
  } catch {
    return url;
  }
}

export function withoutCanvasMediaRef(context: CanvasEditContext, url?: string): CanvasEditContext {
  if (!url) return context;
  const normalized = normalizeCanvasEditContext(context)!;
  const key = mediaUrlKey(url);
  return {
    ...normalized,
    mediaRefs: normalized.mediaRefs.filter((ref) => mediaUrlKey(ref.url) !== key),
    excludedMediaUrls: [...new Set([...(normalized.excludedMediaUrls ?? []), url])],
  };
}

export function filterCanvasReferenceUrls(
  urls: string[],
  context: CanvasEditContext | null,
): string[] {
  if (!context) return urls;
  const normalized = normalizeCanvasEditContext(context);
  if (!normalized) return urls;
  const mediaKeys = new Set(normalized.mediaRefs.map((ref) => mediaUrlKey(ref.url)));
  return urls.filter((url) => !mediaKeys.has(mediaUrlKey(url)));
}
