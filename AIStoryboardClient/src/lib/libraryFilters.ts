export type LibraryScopeView = 'PROJECT' | 'ALL' | 'GLOBAL' | 'TRASH';

export function libraryAssetScope(
  scopeView: LibraryScopeView,
  filteringByProject: boolean,
): 'PROJECT' | 'GLOBAL' | undefined {
  if (scopeView === 'GLOBAL') return 'GLOBAL';
  if (scopeView === 'PROJECT' || filteringByProject) return 'PROJECT';
  return undefined;
}

export function scopeFromQuery(value: string | null): LibraryScopeView {
  switch (value?.toUpperCase()) {
    case 'ALL': return 'ALL';
    case 'GLOBAL': return 'GLOBAL';
    case 'GROUPS': return 'PROJECT';
    case 'TAGS': return 'ALL';
    case 'TRASH': return 'TRASH';
    default: return 'PROJECT';
  }
}

export function buildLibraryQuery(input: {
  scopeView: LibraryScopeView;
  projectId?: string;
  type?: string;
  query?: string;
  view?: 'cards' | 'table';
  tagIds?: string[];
  tagMode?: 'ALL' | 'ANY';
}) {
  const params = new URLSearchParams();
  if (input.scopeView !== 'PROJECT') params.set('scope', input.scopeView.toLowerCase());
  if (input.projectId) params.set('projectId', input.projectId);
  if (input.type) params.set('type', input.type);
  if (input.query?.trim()) params.set('q', input.query.trim());
  if (input.view && input.view !== 'cards') params.set('view', input.view);
  if (input.tagIds?.length) params.set('tags', input.tagIds.join(','));
  if (input.tagMode && input.tagMode !== 'ALL') params.set('tagMode', input.tagMode);
  return params;
}

export function resolveLibraryProjectId(input: {
  requestedProjectId?: string | null;
  currentProjectId?: string | null;
  lastOpenedProjectId?: string | null;
  projects: Array<{ id: string }>;
}): string {
  const available = new Set(input.projects.map((project) => project.id));
  const candidates = [
    input.requestedProjectId,
    input.currentProjectId,
    input.lastOpenedProjectId,
    input.projects[0]?.id,
  ];
  return candidates.find((candidate): candidate is string => Boolean(candidate && available.has(candidate))) || '';
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

export function pageCount(total: number, size: number) {
  const safeSize = Math.max(1, Math.trunc(size));
  return Math.max(1, Math.ceil(Math.max(0, total) / safeSize));
}
