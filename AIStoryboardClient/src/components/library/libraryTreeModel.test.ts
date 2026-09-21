import { describe, expect, it } from 'vitest';
import type { LibraryAsset, LibraryFolderNode } from '../../api/library';
import {
  buildFolderTree,
  canMergeIntoGroup,
  selectionForContextMenu,
} from './libraryTreeModel';

function folder(id: string, parentId: string | null, name: string): LibraryFolderNode {
  return {
    id,
    parentId,
    name,
    scope: 'PROJECT',
    ownerProjectId: 'p1',
    directAssetCount: 0,
    descendantAssetCount: 0,
    version: 1,
  };
}

function asset(id: string, type: LibraryAsset['type'] = 'IMAGE'): LibraryAsset {
  return {
    id,
    ownerUserId: 'u1',
    scope: 'PROJECT',
    ownerProjectId: 'p1',
    type,
    aiKind: null,
    name: id,
    description: null,
    aiDescription: null,
    manualOverride: false,
    metadataState: 'NONE',
    status: 'ACTIVE',
    coverUrl: null,
    version: 1,
    tags: [],
    projects: [],
    folderId: null,
    folderPath: [],
    sourceType: 'upload',
    sourceId: null,
    createdAt: '2026-09-18T00:00:00Z',
    updatedAt: '2026-09-18T00:00:00Z',
  };
}

describe('libraryTreeModel', () => {
  it('builds arbitrary folder depth while preserving sibling order', () => {
    const tree = buildFolderTree([
      folder('a', null, '角色'),
      folder('a1', 'a', '正面'),
      folder('b', null, '场景'),
    ]);

    expect(tree).toHaveLength(2);
    expect(tree[0].name).toBe('角色');
    expect(tree[0].children[0].name).toBe('正面');
    expect(tree[1].name).toBe('场景');
  });

  it('keeps the clicked selected asset and replaces selection for an unselected one', () => {
    expect(selectionForContextMenu(['a', 'b'], 'b')).toEqual(['a', 'b']);
    expect(selectionForContextMenu(['a', 'b'], 'c')).toEqual(['c']);
  });

  it('only enables merge for media assets from one project', () => {
    expect(canMergeIntoGroup([asset('a'), asset('b', 'VIDEO')])).toBe(true);
    expect(canMergeIntoGroup([asset('a'), asset('g', 'GROUP')])).toBe(false);
  });
});
