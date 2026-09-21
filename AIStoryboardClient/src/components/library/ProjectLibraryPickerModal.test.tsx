import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { libraryApi, type LibraryAsset, type LibraryAssetDetail } from '../../api/library';
import { ProjectLibraryPickerModal } from './ProjectLibraryPickerModal';

const asset: LibraryAsset = {
  id: 'a1', ownerUserId: 'u1', scope: 'PROJECT', ownerProjectId: 'p1', type: 'IMAGE',
  aiKind: null, name: '参考图', description: null, aiDescription: null, manualOverride: false,
  metadataState: 'NONE', status: 'ACTIVE', coverUrl: '/api/files/images/a.png', version: 1,
  tags: [], projects: [], folderId: null, folderPath: [], sourceType: null, sourceId: null,
  createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z',
};

const detail: LibraryAssetDetail = {
  asset,
  media: [{
    id: 'm1', blobId: 'b1', type: 'IMAGE', url: '/api/files/images/a.png',
    contentUrl: '/api/library/media/m1/content', objectKey: 'images/a.png',
    fileName: 'a.png', relativePath: null, mimeType: 'image/png', sizeBytes: 12,
    width: 100, height: 100, durationSeconds: null, sortOrder: 0,
    role: 'REFERENCE', primary: true, constraintText: null,
  }],
  linkedProjects: [],
};

describe('ProjectLibraryPickerModal', () => {
  afterEach(() => cleanup());

  it('merges scopes, defaults to all types, and opens folders on double click', async () => {
    vi.spyOn(libraryApi, 'getFolderTree').mockImplementation(async ({ scope }) => ({
      data: {
        code: 0, message: 'ok', data: {
          scope,
          ownerProjectId: scope === 'PROJECT' ? 'p1' : null,
          folders: scope === 'PROJECT' ? [{
            id: 'folder-1', parentId: null, name: '人物', scope: 'PROJECT', ownerProjectId: 'p1',
            directAssetCount: 0, descendantAssetCount: 1, version: 1,
          }] : [],
          unfiledAssetCount: scope === 'PROJECT' ? 1 : 0,
        },
      },
    } as never));
    const list = vi.spyOn(libraryApi, 'listAssets').mockResolvedValue({
      data: { code: 0, message: 'ok', data: { records: [asset], total: 1, page: 1, size: 80 } },
    } as never);
    vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
      data: { code: 0, message: 'ok', data: detail },
    } as never);
    const confirm = vi.fn();

    render(
      <ProjectLibraryPickerModal
        open
        projectId="p1"
        type="image"
        purpose="image"
        onClose={() => undefined}
        onConfirm={confirm}
      />,
    );

    expect(screen.queryByText('全局库')).toBeNull();
    expect(screen.getByRole('button', { name: '全部' })).toBeTruthy();
    fireEvent.doubleClick(await screen.findByText('人物'));
    await waitFor(() => expect(list).toHaveBeenCalledWith(expect.objectContaining({ folderId: 'folder-1' })));

    fireEvent.click(await screen.findByText('a.png'));
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    await waitFor(() => expect(confirm).toHaveBeenCalledWith([
      expect.objectContaining({
        sourceType: 'library_asset',
        sourceId: 'a1',
        mediaId: 'm1',
        type: 'image',
      }),
    ]));
  });
});
