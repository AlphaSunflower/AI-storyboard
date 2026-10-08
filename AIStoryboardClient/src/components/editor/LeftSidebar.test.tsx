import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { assetApi } from '../../api/assets';
import { libraryApi, type LibraryAsset, type LibraryAssetDetail } from '../../api/library';
import { assetUrl } from '../../config';
import { useProjectStore } from '../../stores/projectStore';
import { LeftSidebar } from './LeftSidebar';

vi.mock('../library/ProjectLibraryPickerModal', () => ({
  ProjectLibraryPickerModal: ({ open }: { open: boolean }) => (
    open ? <div>项目库选择器</div> : null
  ),
}));

const projectAsset: LibraryAsset = {
  id: 'library-1',
  ownerUserId: 'u1',
  scope: 'PROJECT',
  ownerProjectId: 'p1',
  type: 'IMAGE',
  aiKind: null,
  name: '项目参考图',
  description: null,
  aiDescription: null,
  manualOverride: false,
  metadataState: 'NONE',
  status: 'ACTIVE',
  coverUrl: '/api/library/media/m1/content',
  version: 1,
  tags: [],
  projects: [],
  folderId: null,
  folderPath: [],
  sourceType: null,
  sourceId: null,
  createdAt: '2026-10-07T00:00:00Z',
  updatedAt: '2026-10-07T00:00:00Z',
};

const projectAssetDetail: LibraryAssetDetail = {
  asset: projectAsset,
  media: [{
    id: 'media-1',
    blobId: 'blob-1',
    type: 'IMAGE',
    url: '/api/files/images/codex.png',
    contentUrl: '/api/library/media/media-1/content?token=signed',
    objectKey: 'images/codex.png',
    fileName: 'codex.png',
    relativePath: null,
    mimeType: 'image/png',
    sizeBytes: 128,
    width: 100,
    height: 100,
    durationSeconds: null,
    sortOrder: 0,
    role: 'REFERENCE',
    primary: true,
    constraintText: null,
  }],
  linkedProjects: [],
};

describe('LeftSidebar project library integration', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('shows the project library contents and opens the project library reference picker', async () => {
    useProjectStore.setState({
      currentProject: {
        id: 'p1',
        name: '测试项目',
        description: '',
        creationType: 'movie',
        aspectRatio: '16:9',
        scriptText: '',
        status: 'draft',
        createdAt: '2026-10-07T00:00:00Z',
        updatedAt: '2026-10-07T00:00:00Z',
        scenes: [],
      },
      scenes: [],
      isLoading: false,
      understandingModel: 'mimo-v2.6-pro',
      understandingModelOptions: [
        { value: 'mimo-v2.6-pro', label: 'mimo-v2.6-pro' },
      ],
      videoPreset: '8s-16:9',
    });
    vi.spyOn(assetApi, 'list').mockResolvedValue({
      data: { code: 200, message: 'success', data: [], timestamp: '' },
    } as never);
    vi.spyOn(libraryApi, 'listAssets').mockResolvedValue({
      data: {
        code: 200,
        message: 'success',
        data: { records: [projectAsset], total: 1, page: 1, size: 8 },
        timestamp: '',
      },
    } as never);
    const getAsset = vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
      data: { code: 200, message: 'success', data: projectAssetDetail, timestamp: '' },
    } as never);

    render(
      <MemoryRouter>
        <LeftSidebar />
      </MemoryRouter>,
    );

    expect(await screen.findByText('项目参考图')).toBeTruthy();
    expect(screen.queryByText('资产生成（可选）')).toBeNull();
    const preview = await screen.findByRole('img', { name: '项目参考图' });
    expect(preview.getAttribute('src')).toBe(assetUrl('/api/library/media/media-1/content?token=signed'));
    expect(screen.queryByText('暂无资产，点击「管理」新建')).toBeNull();
    const libraryList = screen.getByTestId('project-library-list');
    expect(libraryList.style.flexGrow).toBe('1');
    expect(libraryList.style.maxHeight).toBe('');

    fireEvent.click(screen.getByRole('button', { name: '从项目库选择参考图' }));
    await waitFor(() => expect(screen.getByText('项目库选择器')).toBeTruthy());
    expect(libraryApi.listAssets).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 'p1',
      scope: 'PROJECT',
    }));
    expect(getAsset).toHaveBeenCalledWith('library-1');
  });
});
