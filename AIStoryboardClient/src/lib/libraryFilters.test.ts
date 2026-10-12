import { describe, expect, it } from 'vitest';
import {
  buildLibraryQuery,
  formatBytes,
  libraryAssetScope,
  pageCount,
  resolveLibraryProjectId,
  scopeFromQuery,
} from './libraryFilters';

describe('libraryFilters', () => {
  it('keeps project as the default scope', () => {
    expect(scopeFromQuery(null)).toBe('PROJECT');
    expect(buildLibraryQuery({ scopeView: 'PROJECT' }).toString()).toBe('');
  });

  it('persists the selected project in the default project scope', () => {
    expect(buildLibraryQuery({ scopeView: 'PROJECT', projectId: 'project-1' }).toString())
      .toBe('projectId=project-1');
  });

  it('restores the project from the URL before local memory', () => {
    expect(resolveLibraryProjectId({
      requestedProjectId: 'url-project',
      currentProjectId: 'current-project',
      lastOpenedProjectId: 'last-project',
      projects: [{ id: 'url-project' }, { id: 'current-project' }, { id: 'last-project' }],
    })).toBe('url-project');
  });

  it('falls back from an invalid URL project to current, last, then first project', () => {
    expect(resolveLibraryProjectId({
      requestedProjectId: 'missing',
      currentProjectId: 'current-project',
      lastOpenedProjectId: 'last-project',
      projects: [{ id: 'first-project' }, { id: 'current-project' }, { id: 'last-project' }],
    })).toBe('current-project');
    expect(resolveLibraryProjectId({
      currentProjectId: 'missing',
      lastOpenedProjectId: 'last-project',
      projects: [{ id: 'first-project' }, { id: 'last-project' }],
    })).toBe('last-project');
    expect(resolveLibraryProjectId({
      currentProjectId: 'missing',
      lastOpenedProjectId: 'also-missing',
      projects: [{ id: 'first-project' }],
    })).toBe('first-project');
  });

  it('migrates the removed tags scope to all projects', () => {
    expect(scopeFromQuery('tags')).toBe('ALL');
  });

  it('moves the removed groups scope back into project view', () => {
    expect(scopeFromQuery('groups')).toBe('PROJECT');
  });

  it('lists deleted assets across every project in trash', () => {
    expect(libraryAssetScope('TRASH', false)).toBeUndefined();
  });

  it('serializes shareable filters', () => {
    const params = buildLibraryQuery({
      scopeView: 'ALL',
      projectId: 'project-1',
      type: 'IMAGE',
      query: '角色 室内',
      view: 'table',
      tagIds: ['t1', 't2'],
      tagMode: 'ANY',
    });

    expect(params.get('scope')).toBe('all');
    expect(params.get('projectId')).toBe('project-1');
    expect(params.get('type')).toBe('IMAGE');
    expect(params.get('q')).toBe('角色 室内');
    expect(params.get('view')).toBe('table');
    expect(params.get('tags')).toBe('t1,t2');
    expect(params.get('tagMode')).toBe('ANY');
  });

  it('serializes document and other type filters', () => {
    expect(buildLibraryQuery({ scopeView: 'PROJECT', type: 'DOCUMENT' }).get('type')).toBe('DOCUMENT');
    expect(buildLibraryQuery({ scopeView: 'PROJECT', type: 'OTHER' }).get('type')).toBe('OTHER');
  });

  it('formats byte sizes for import confirmation', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB');
  });

  it('calculates at least one page for an empty library', () => {
    expect(pageCount(0, 40)).toBe(1);
    expect(pageCount(81, 40)).toBe(3);
  });
});
