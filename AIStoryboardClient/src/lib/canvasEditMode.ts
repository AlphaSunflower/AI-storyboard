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

/** 画布 URL 携带的媒体参数：画布子站按原样透传给内嵌 Moon 对话 */
export const CANVAS_MEDIA_PARAM = 'media';

const VIDEO_EXTENSIONS = /\.(mp4|webm|mov|m4v)(?:[?#]|$)/i;
const AUDIO_EXTENSIONS = /\.(mp3|wav|m4a|aac|ogg)(?:[?#]|$)/i;

export function mediaKindFromUrl(url: string): AgentMediaKind {
  if (VIDEO_EXTENSIONS.test(url)) return 'video';
  if (AUDIO_EXTENSIONS.test(url)) return 'audio';
  return 'image';
}

/**
 * 解析画布 URL 里的媒体参数（JSON 字符串数组，或 [{url, kind}]）。
 * 解析不了就当作没有携带，不影响页面其余部分。
 */
export function parseCanvasMediaParam(raw: string | null | undefined): AgentMediaRef[] {
  const value = raw?.trim();
  if (!value) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const refs: AgentMediaRef[] = [];
  for (const item of parsed) {
    if (typeof item === 'string') {
      const url = item.trim();
      if (url) refs.push({ kind: mediaKindFromUrl(url), url, source: 'upload' });
      continue;
    }
    if (!item || typeof item !== 'object') continue;
    const candidate = item as { url?: unknown; kind?: unknown };
    const url = typeof candidate.url === 'string' ? candidate.url.trim() : '';
    if (!url) continue;
    const kind = typeof candidate.kind === 'string'
      && ['image', 'video', 'audio', 'file'].includes(candidate.kind)
      ? candidate.kind as AgentMediaKind
      : mediaKindFromUrl(url);
    refs.push({ kind, url, source: 'upload' });
  }
  return refs;
}

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

/**
 * 画布编辑模式锁定标记（画布网站的 Moon 子站下发 canvasLock=1）。
 * 锁定后该子站始终处于画布编辑模式：不渲染退出入口，上下文被清空时按 URL 目标重新置回。
 */
export function parseCanvasEditLock(search: string): boolean {
  return new URLSearchParams(search).get('canvasLock') === '1';
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
