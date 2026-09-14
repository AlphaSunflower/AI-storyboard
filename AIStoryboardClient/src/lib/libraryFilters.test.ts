import { describe, expect, it } from 'vitest';
import { buildLibraryQuery, formatBytes, scopeFromQuery } from './libraryFilters';

describe('libraryFilters', () => {
  it('keeps project as the default scope', () => {
    expect(scopeFromQuery(null)).toBe('PROJECT');
    expect(buildLibraryQuery({ scopeView: 'PROJECT' }).toString()).toBe('');
  });

  it('serializes shareable filters', () => {
    const params = buildLibraryQuery({
      scopeView: 'GLOBAL',
      type: 'IMAGE',
      query: '角色 室内',
      view: 'table',
      tagIds: ['t1', 't2'],
      tagMode: 'ANY',
    });

    expect(params.get('scope')).toBe('global');
    expect(params.get('type')).toBe('IMAGE');
    expect(params.get('q')).toBe('角色 室内');
    expect(params.get('view')).toBe('table');
    expect(params.get('tags')).toBe('t1,t2');
    expect(params.get('tagMode')).toBe('ANY');
  });

  it('formats byte sizes for import confirmation', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});
