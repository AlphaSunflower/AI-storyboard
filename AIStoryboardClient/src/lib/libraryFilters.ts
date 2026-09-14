export type LibraryScopeView = 'PROJECT' | 'ALL' | 'GLOBAL' | 'GROUPS' | 'TAGS' | 'TRASH';

export function scopeFromQuery(value: string | null): LibraryScopeView {
  switch (value?.toUpperCase()) {
    case 'ALL': return 'ALL';
    case 'GLOBAL': return 'GLOBAL';
    case 'GROUPS': return 'GROUPS';
    case 'TAGS': return 'TAGS';
    case 'TRASH': return 'TRASH';
    default: return 'PROJECT';
  }
}

export function buildLibraryQuery(input: {
  scopeView: LibraryScopeView;
  type?: string;
  query?: string;
  view?: 'cards' | 'table';
  tagIds?: string[];
  tagMode?: 'ALL' | 'ANY';
}) {
  const params = new URLSearchParams();
  if (input.scopeView !== 'PROJECT') params.set('scope', input.scopeView.toLowerCase());
  if (input.type) params.set('type', input.type);
  if (input.query?.trim()) params.set('q', input.query.trim());
  if (input.view && input.view !== 'cards') params.set('view', input.view);
  if (input.tagIds?.length) params.set('tags', input.tagIds.join(','));
  if (input.tagMode && input.tagMode !== 'ALL') params.set('tagMode', input.tagMode);
  return params;
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}
