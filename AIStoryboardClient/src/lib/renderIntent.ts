import type { RenderIntentKind, ToolRenderIntent } from '../api/agent';

/**
 * 渲染意图（render block）的解析与去重。
 *
 * 服务端会在工具结果 / 结果 metadata 上新增 `render: {kind, id, status, items[], version}`，
 * `kind ∈ {table, list, gallery, progress, diff, form, preview}`。前端只按 kind 分派渲染器，
 * 未知 kind 走通用兜底；缺字段、类型不符、字符串 JSON 一律容忍，绝不抛错。
 */

const KNOWN_RENDER_KINDS: readonly string[] = [
  'table', 'list', 'gallery', 'progress', 'diff', 'form', 'preview',
];

export function isKnownRenderKind(kind: string): kind is RenderIntentKind {
  return KNOWN_RENDER_KINDS.includes(kind);
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

/** 把任意值规整为渲染意图；缺 `kind` 时返回 null（不是渲染意图）。 */
export function parseRenderIntent(value: unknown): ToolRenderIntent | null {
  const record = asRecord(value);
  if (!record) return null;
  const kind = typeof record.kind === 'string' ? record.kind.trim() : '';
  if (!kind) return null;
  const intent: ToolRenderIntent = { ...record, kind };
  if (record.id !== undefined && typeof record.id !== 'string') delete intent.id;
  if (record.status !== undefined && typeof record.status !== 'string') delete intent.status;
  if (record.version !== undefined && typeof record.version !== 'number') delete intent.version;
  if (record.items !== undefined && !Array.isArray(record.items)) delete intent.items;
  if (record.items !== undefined && Array.isArray(record.items)) intent.items = record.items;
  return intent;
}

/** 从事件 data 里取渲染意图：优先 `render`，兼容 `metadata.render`。 */
export function renderIntentFromEventData(data: unknown): ToolRenderIntent | null {
  const record = asRecord(data);
  if (!record) return null;
  return parseRenderIntent(record.render)
    ?? parseRenderIntent(asRecord(record.metadata)?.render);
}

/** 渲染意图的分组键：有 id 用 id，没有则按 kind+序号兜底。 */
export function renderIntentKey(intent: ToolRenderIntent, index: number): string {
  const id = typeof intent.id === 'string' ? intent.id.trim() : '';
  return id ? `${intent.kind}:${id}` : `${intent.kind}:#${index}`;
}

/**
 * 按 id 去重并保持首次出现顺序：重放 / 刷新不会重复渲染同一块。
 * 没有 id 的意图按 `kind + JSON 内容` 去重，避免同一次工具结果重复到达时出现两张卡。
 */
export function dedupeRenderIntents(intents: Array<ToolRenderIntent | null | undefined>): ToolRenderIntent[] {
  const seen = new Set<string>();
  const next: ToolRenderIntent[] = [];
  for (const intent of intents) {
    if (!intent) continue;
    const id = typeof intent.id === 'string' && intent.id.trim() ? intent.id.trim() : '';
    const key = id
      ? `${intent.kind}:${id}`
      : `${intent.kind}:${stableStringify(intent.items ?? intent)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    next.push(intent);
  }
  return next;
}

function stableStringify(value: unknown): string {
  try {
    return JSON.stringify(value) ?? '';
  } catch {
    return '';
  }
}

export function renderIntentTitle(intent: ToolRenderIntent): string {
  const title = typeof intent.title === 'string' ? intent.title.trim() : '';
  if (title) return title;
  return isKnownRenderKind(intent.kind) ? RENDER_KIND_LABELS[intent.kind] : `自定义结果（${intent.kind}）`;
}

export const RENDER_KIND_LABELS: Record<RenderIntentKind, string> = {
  table: '表格',
  list: '列表',
  gallery: '图集',
  progress: '进度',
  diff: '差异',
  form: '表单',
  preview: '预览',
};

/**
 * 稳定排序：先按 `version`（服务端版本号，越大越新），再保持原始到达顺序。
 * 目的是让同一次结果在直播与重放两条路径上落到相同的顺序，卡片不会因刷新换位。
 */
export function sortRenderIntents(intents: ToolRenderIntent[]): ToolRenderIntent[] {
  return intents
    .map((intent, index) => ({ intent, index }))
    .sort((a, b) => {
      const aVersion = typeof a.intent.version === 'number' ? a.intent.version : -1;
      const bVersion = typeof b.intent.version === 'number' ? b.intent.version : -1;
      if (aVersion !== bVersion) return aVersion - bVersion;
      return a.index - b.index;
    })
    .map((entry) => entry.intent);
}

/**
 * 合并「结果 metadata 上的 renders」与「工具草稿里的 render」，去重 + 稳定排序。
 * 直播路径通常只有草稿、重放路径通常只有 metadata，两条路径产出同一份列表。
 */
export function collectRenderIntents(
  metadataRenders: unknown,
  draftRenders: unknown,
): ToolRenderIntent[] {
  const fromMetadata = Array.isArray(metadataRenders)
    ? metadataRenders.map((item) => parseRenderIntent(item))
    : [];
  const fromDrafts = Array.isArray(draftRenders)
    ? draftRenders.map((item) => parseRenderIntent(item))
    : [];
  return sortRenderIntents(dedupeRenderIntents([...fromMetadata, ...fromDrafts]));
}

/** 从工具草稿快照里取渲染意图（按 seq 稳定排序后去重）。 */
export function collectDraftRenderIntents(
  drafts?: Record<string, { render?: unknown; seq?: number }>,
): ToolRenderIntent[] {
  if (!drafts) return [];
  const ordered = Object.entries(drafts)
    .map(([id, draft], index) => ({ id, draft, index }))
    .sort((a, b) => {
      const aSeq = typeof a.draft.seq === 'number' ? a.draft.seq : Number.MAX_SAFE_INTEGER;
      const bSeq = typeof b.draft.seq === 'number' ? b.draft.seq : Number.MAX_SAFE_INTEGER;
      if (aSeq !== bSeq) return aSeq - bSeq;
      return a.index - b.index;
    })
    .map((entry) => entry.draft.render);
  return sortRenderIntents(dedupeRenderIntents(ordered.map((item) => parseRenderIntent(item))));
}
