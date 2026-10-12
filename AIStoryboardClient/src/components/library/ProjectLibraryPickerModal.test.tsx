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

const group = (id: string, name: string, scope: 'PROJECT' | 'GLOBAL'): LibraryAsset => ({
  ...asset,
  id,
  scope,
  ownerProjectId: scope === 'PROJECT' ? 'p1' : null,
  type: 'GROUP',
  aiKind: 'CHARACTER',
  name,
  coverUrl: null,
});

function mockLibrary(options: { projectGroups?: LibraryAsset[]; globalGroups?: LibraryAsset[] } = {}) {
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
  const list = vi.spyOn(libraryApi, 'listAssets').mockImplementation(async (query) => {
    if (query.type === 'GROUP') {
      const records = query.scope === 'PROJECT'
        ? (options.projectGroups ?? [group('g1', '主角组', 'PROJECT')])
        : (options.globalGroups ?? [group('g2', '全局场景', 'GLOBAL')]);
      return { data: { code: 0, message: 'ok', data: { records, total: records.length, page: 1, size: 100 } } } as never;
    }
    const materialRecords = query.scope === 'PROJECT' ? [asset] : [];
    return { data: { code: 0, message: 'ok', data: { records: materialRecords, total: materialRecords.length, page: 1, size: 80 } } } as never;
  });
  vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
    data: { code: 0, message: 'ok', data: detail },
  } as never);
  return list;
}

describe('ProjectLibraryPickerModal', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('merges scopes, defaults to all types, and opens folders on double click', async () => {
    const list = mockLibrary();
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
    expect(screen.queryByRole('tab')).toBeNull();
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

  it('lists documents and exposes the project output filter', async () => {
    const documentAsset: LibraryAsset = {
      ...asset,
      id: 'd1',
      type: 'DOCUMENT',
      name: '交付说明',
      coverUrl: '/api/files/files/report.md',
      sourceType: 'generation',
    };
    const documentDetail: LibraryAssetDetail = {
      asset: documentAsset,
      media: [{
        ...detail.media[0],
        id: 'md1',
        type: 'DOCUMENT',
        objectKey: 'files/report.md',
        fileName: 'report.md',
        mimeType: 'text/markdown',
        contentUrl: '/api/library/media/md1/content',
      }],
      linkedProjects: [],
    };
    vi.spyOn(libraryApi, 'getFolderTree').mockResolvedValue({
      data: {
        code: 0,
        message: 'ok',
        data: { scope: 'PROJECT', ownerProjectId: 'p1', folders: [], unfiledAssetCount: 0 },
      },
    } as never);
    const list = vi.spyOn(libraryApi, 'listAssets').mockImplementation(async (query) => {
      const records = query.scope === 'GLOBAL'
        ? []
        : query.type === 'DOCUMENT' ? [documentAsset] : [asset, documentAsset];
      return {
        data: { code: 0, message: 'ok', data: { records, total: records.length, page: 1, size: 80 } },
      } as never;
    });
    vi.spyOn(libraryApi, 'getAsset').mockImplementation(async (id) => ({
      data: { code: 0, message: 'ok', data: id === 'd1' ? documentDetail : detail },
    } as never));
    const confirm = vi.fn();

    render(
      <ProjectLibraryPickerModal
        open
        projectId="p1"
        type="image"
        allowedTypes={['image', 'video', 'audio', 'document']}
        assetGroups={{ selectedIds: [], onConfirm: vi.fn() }}
        onClose={() => undefined}
        onConfirm={confirm}
      />,
    );

    expect(await screen.findByRole('button', { name: '文档' })).toBeTruthy();
    fireEvent.click(await screen.findByText('report.md'));

    fireEvent.click(screen.getByRole('button', { name: '项目产出' }));
    await waitFor(() => expect(list).toHaveBeenCalledWith(expect.objectContaining({ origin: 'GENERATED' })));

    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));
    await waitFor(() => expect(confirm).toHaveBeenCalledWith([
      expect.objectContaining({ type: 'document', mediaId: 'md1' }),
    ]));
  });

  it('opens on the material tab and switches to the asset group panel on demand', async () => {
    mockLibrary();
    render(
      <ProjectLibraryPickerModal
        open
        projectId="p1"
        type="image"
        purpose="image"
        assetGroups={{ selectedIds: [], onConfirm: vi.fn() }}
        onClose={() => undefined}
        onConfirm={vi.fn()}
      />,
    );

    const materialTab = screen.getByRole('tab', { name: '素材' });
    const groupsTab = screen.getByRole('tab', { name: '资产组' });
    expect(materialTab.getAttribute('aria-selected')).toBe('true');
    expect(groupsTab.getAttribute('aria-selected')).toBe('false');
    expect(screen.queryByText('主角组')).toBeNull();

    fireEvent.click(groupsTab);
    expect(await screen.findByText('主角组')).toBeTruthy();
    expect(screen.getByRole('tab', { name: '资产组' }).getAttribute('aria-selected')).toBe('true');
  });

  it('keeps material and asset group selections across tab switches', async () => {
    mockLibrary();
    render(
      <ProjectLibraryPickerModal
        open
        projectId="p1"
        type="image"
        purpose="image"
        assetGroups={{ selectedIds: [], onConfirm: vi.fn() }}
        onClose={() => undefined}
        onConfirm={vi.fn()}
      />,
    );

    fireEvent.click(await screen.findByText('a.png'));
    expect(screen.getByText('已选 1 项素材 · 0 个资产组')).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: '资产组' }));
    fireEvent.click(await screen.findByText('主角组'));
    expect(screen.getByText('已选 1 项素材 · 1 个资产组')).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: '素材' }));
    expect(screen.getByText('已选 1 项素材 · 1 个资产组')).toBeTruthy();
    expect(screen.getByText('a.png')).toBeTruthy();
  });

  it('confirms asset groups before materials and forwards both selections', async () => {
    mockLibrary();
    const order: string[] = [];
    const groupConfirm = vi.fn(async () => { order.push('groups'); });
    const materialConfirm = vi.fn(async () => { order.push('materials'); });
    render(
      <ProjectLibraryPickerModal
        open
        projectId="p1"
        type="image"
        purpose="image"
        assetGroups={{ selectedIds: [], onConfirm: groupConfirm }}
        onClose={() => undefined}
        onConfirm={materialConfirm}
      />,
    );

    fireEvent.click(await screen.findByText('a.png'));
    fireEvent.click(screen.getByRole('tab', { name: '资产组' }));
    fireEvent.click(await screen.findByText('主角组'));
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    await waitFor(() => expect(order).toEqual(['groups', 'materials']));
    expect(groupConfirm).toHaveBeenCalledWith(['g1']);
    expect(materialConfirm).toHaveBeenCalledWith([
      expect.objectContaining({ sourceType: 'library_asset', sourceId: 'a1', mediaId: 'm1' }),
    ]);
  });

  it('submits an empty asset group selection to clear bindings', async () => {
    mockLibrary();
    const groupConfirm = vi.fn();
    const materialConfirm = vi.fn();
    render(
      <ProjectLibraryPickerModal
        open
        projectId="p1"
        type="image"
        purpose="image"
        assetGroups={{ selectedIds: ['g1'], onConfirm: groupConfirm }}
        onClose={() => undefined}
        onConfirm={materialConfirm}
      />,
    );

    fireEvent.click(screen.getByRole('tab', { name: '资产组' }));
    fireEvent.click(await screen.findByText('主角组'));
    expect(screen.getByText('已选 0 项素材 · 0 个资产组')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    await waitFor(() => expect(groupConfirm).toHaveBeenCalledWith([]));
    expect(materialConfirm).toHaveBeenCalledWith([]);
  });

  it('stays open and skips the material callback when asset group saving fails', async () => {
    mockLibrary();
    const groupConfirm = vi.fn().mockRejectedValue(new Error('资产组保存失败'));
    const materialConfirm = vi.fn();
    render(
      <ProjectLibraryPickerModal
        open
        projectId="p1"
        type="image"
        purpose="image"
        assetGroups={{ selectedIds: ['g1'], onConfirm: groupConfirm }}
        onClose={() => undefined}
        onConfirm={materialConfirm}
      />,
    );

    fireEvent.click(screen.getByRole('tab', { name: '资产组' }));
    await screen.findByText('主角组');
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    expect(await screen.findByText('资产组保存失败')).toBeTruthy();
    expect(materialConfirm).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText('已选 0 项素材 · 1 个资产组')).toBeTruthy();
  });

  it('keeps the modal and selections open when the material callback fails', async () => {
    mockLibrary();
    const groupConfirm = vi.fn();
    const materialConfirm = vi.fn().mockRejectedValue(new Error('素材保存失败'));
    render(
      <ProjectLibraryPickerModal
        open
        projectId="p1"
        type="image"
        purpose="image"
        assetGroups={{ selectedIds: [], onConfirm: groupConfirm }}
        onClose={() => undefined}
        onConfirm={materialConfirm}
      />,
    );

    fireEvent.click(await screen.findByText('a.png'));
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    expect(await screen.findByText('素材保存失败')).toBeTruthy();
    expect(groupConfirm).toHaveBeenCalledWith([]);
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText('已选 1 项素材 · 0 个资产组')).toBeTruthy();
  });
});
