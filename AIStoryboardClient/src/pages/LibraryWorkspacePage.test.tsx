import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { libraryApi } from '../api/library';
import {
  CHAT_MEDIA_DRAG_MIME,
  serializeDragPayload,
} from '../lib/projectLibraryDrag';
import { useLibraryTaskStore } from '../stores/libraryTaskStore';
import { useProjectStore } from '../stores/projectStore';
import { LibraryWorkspacePage } from './LibraryWorkspacePage';

vi.mock('react-d3-tree', () => ({
  default: ({ data, renderCustomNodeElement }: {
    data: { id: string; name: string; children?: Array<{ id: string; name: string; children?: unknown[] }> };
    renderCustomNodeElement: (args: { nodeDatum: {
      id: string;
      name: string;
      children?: unknown[];
    } }) => ReactNode;
  }) => {
    const renderNode = (node: { id: string; name: string; children?: unknown[] }): ReactNode => (
      <g key={node.id}>
        {renderCustomNodeElement({ nodeDatum: node })}
        {(node.children || []).map((child) => renderNode(
          child as { id: string; name: string; children?: unknown[] },
        ))}
      </g>
    );
    return (
      <svg data-testid="library-tree">
        {renderNode(data)}
      </svg>
    );
  },
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  useLibraryTaskStore.setState({ tasks: [], panelOpen: false });
  vi.stubGlobal('ResizeObserver', ResizeObserverMock);
  useProjectStore.setState({
    projects: [
      { id: 'p1', name: '测试项目' } as never,
      { id: 'p2', name: '另一个项目' } as never,
    ],
    currentProject: { id: 'p1', name: '测试项目' } as never,
  });
  vi.spyOn(libraryApi, 'listTags').mockResolvedValue({
    data: { code: 0, message: '', data: [] },
  } as never);
  vi.spyOn(libraryApi, 'getFolderTree').mockResolvedValue({
    data: {
      code: 0,
      message: '',
      data: {
        scope: 'PROJECT',
        ownerProjectId: 'p1',
        unfiledAssetCount: 1,
        folders: [{
          id: 'folder-1',
          parentId: null,
          name: '角色',
          scope: 'PROJECT',
          ownerProjectId: 'p1',
          directAssetCount: 1,
          descendantAssetCount: 1,
          version: 1,
        }],
      },
    },
  } as never);
  vi.spyOn(libraryApi, 'listAssets').mockResolvedValue({
    data: { code: 0, message: '', data: { records: [], total: 0, page: 1, size: 100 } },
  } as never);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('LibraryWorkspacePage', () => {
  it('opens all projects by default while retaining the selected project context', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(libraryApi.getFolderTree).toHaveBeenCalledWith({ scope: 'PROJECT', projectId: 'p1' });
      expect(libraryApi.listAssets).toHaveBeenCalledWith(expect.objectContaining({
        scope: undefined,
        projectId: 'p1',
        type: 'GROUP',
      }));
    });
    expect(screen.getByRole('heading', { name: '项目库' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '当前项目' })).toBeNull();
    expect(screen.getByDisplayValue('全部类型')).toBeTruthy();
    expect(screen.queryByRole('option', { name: 'AI资产' })).toBeNull();
    expect(screen.getByRole('button', { name: '文件夹视图' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.queryByTestId('library-tree')).toBeNull();
    expect(screen.queryByText('项目素材工作台')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '树形视图' }));
    expect(screen.getByTestId('library-tree')).toBeTruthy();
  });

  it('stays in embedded window mode after synchronizing query parameters', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1&embedded=1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(libraryApi.getFolderTree).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByRole('button', { name: /返回 Moon/ })).toBeNull());
  });

  it('lists the assets inside an expanded asset group in the tree', async () => {
    const group = {
      id: 'group-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'GROUP' as const,
      aiKind: null,
      name: '主角资产组',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: null,
      version: 1,
      tags: [],
      projects: [],
      folderId: null,
      folderPath: [],
      sourceType: 'group',
      sourceId: null,
      createdAt: '',
      updatedAt: '',
    };
    const member = {
      ...group,
      id: 'asset-1',
      type: 'IMAGE' as const,
      name: '角色参考',
      sourceType: 'upload',
    };
    vi.mocked(libraryApi.listAssets).mockImplementation(async (params) => ({
      data: {
        code: 0,
        message: '',
        data: params.type === 'GROUP'
          ? { records: [group], total: 1, page: 1, size: 100 }
          : { records: [], total: 0, page: 1, size: 100 },
      },
    } as never));
    const getGroup = vi.spyOn(libraryApi, 'getGroup').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          groupId: group.id,
          name: group.name,
          description: null,
          version: 1,
          media: [{
            media: {
              id: 'media-1',
              blobId: 'blob-1',
              type: 'IMAGE',
              url: '/files/images/member.png',
              contentUrl: '/api/files/images/member.png',
              objectKey: 'images/member.png',
              fileName: 'member.png',
              relativePath: null,
              mimeType: 'image/png',
              sizeBytes: 1024,
              width: 512,
              height: 512,
              durationSeconds: null,
              sortOrder: 0,
              role: 'REFERENCE',
              primary: true,
              constraintText: null,
            },
            asset: member,
            assetId: member.id,
            assetName: member.name,
            assetDescription: null,
            assetVersion: member.version,
            assetTags: [],
          }],
        },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '树形视图' }));
    const groupLabel = await screen.findByText('主角资产组');
    fireEvent.click(groupLabel.closest('.library-tree-node')!);

    await waitFor(() => {
      expect(getGroup).toHaveBeenCalledWith('group-1');
      expect(getGroup).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(document.querySelector('[data-library-asset-id="asset-1"] strong')?.textContent)
        .toBe('角色参考');
    });
  });

  it('opens an asset detail view in narrow windows and returns to the material list', async () => {
    Object.defineProperty(window, 'innerWidth', { value: 600, configurable: true });
    const asset = {
      id: 'asset-detail-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      name: '详情图片',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/images/detail.png',
      version: 1,
      tags: [],
      projects: [],
      folderId: null,
      folderPath: [],
      sourceType: 'upload',
      sourceId: null,
      createdAt: '',
      updatedAt: '',
    };
    vi.mocked(libraryApi.listAssets).mockImplementation(async (params) => ({
      data: {
        code: 0,
        message: '',
        data: params.type === 'GROUP'
          ? { records: [], total: 0, page: 1, size: 100 }
          : { records: [asset], total: 1, page: 1, size: 100 },
      },
    } as never));
    const getAsset = vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { asset, media: [], linkedProjects: [] },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1&embedded=1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    const title = (await screen.findAllByText('详情图片'))[0];
    fireEvent.click(title.closest('button')!);

    await waitFor(() => {
      expect(getAsset).toHaveBeenCalledWith('asset-detail-1');
      expect(screen.getByRole('button', { name: '返回素材列表' })).toBeTruthy();
    });

    fireEvent.click(screen.getByRole('button', { name: '返回素材列表' }));
    await waitFor(() => {
      expect(document.querySelector('.library-workspace__content')?.classList.contains('is-detail-view')).toBe(false);
    });
  });

  it('explains when a duplicate folder name was replaced with a numbered name', async () => {
    vi.spyOn(libraryApi, 'createFolder').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          id: 'folder-2',
          parentId: null,
          name: '角色（1）',
          scope: 'PROJECT',
          ownerProjectId: 'p1',
          directAssetCount: 0,
          descendantAssetCount: 0,
          version: 1,
        },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByRole('heading', { name: '项目库' })).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: '新建目录' }));
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '角色' } });
    fireEvent.click(screen.getByRole('button', { name: '确定' }));

    await waitFor(() => {
      expect(screen.getByText('已有同名目录，已创建为「角色（1）」')).toBeTruthy();
    });
  });

  it('polls active image analysis and updates its task state', async () => {
    useLibraryTaskStore.setState({
      tasks: [{
        id: 'analysis:asset-1',
        kind: 'analysis',
        label: '角色参考',
        status: 'processing',
        progress: 20,
        assetId: 'asset-1',
      }],
    });
    const getAnalysisTasks = vi.spyOn(libraryApi, 'getAnalysisTasks').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: [{ assetId: 'asset-1', status: 'SUCCEEDED', errorMessage: null, updatedAt: '' }],
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(getAnalysisTasks).toHaveBeenCalledWith(['asset-1']);
      expect(useLibraryTaskStore.getState().tasks[0]?.status).toBe('completed');
    });
  });

  it('imports conversation media into the folder that receives the drop', async () => {
    const register = vi.spyOn(libraryApi, 'registerGeneratedOutput').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          id: 'asset-video-1',
          ownerUserId: 'user-1',
          scope: 'PROJECT',
          ownerProjectId: 'p1',
          type: 'VIDEO',
          name: '对话视频',
          description: null,
          aiDescription: null,
          manualOverride: false,
          metadataState: 'NONE',
          status: 'ACTIVE',
          coverUrl: '/api/files/videos/chat.mp4',
          version: 1,
          tags: [],
          projects: [],
          folderId: null,
          folderPath: [],
          sourceType: 'generation',
          sourceId: 'record-1',
          createdAt: '',
          updatedAt: '',
        },
      },
    } as never);
    const move = vi.spyOn(libraryApi, 'moveAssetsToFolder').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1&embedded=1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByRole('button', { name: /角色/ })).toBeTruthy());
    const payload = serializeDragPayload({
      projectId: 'p1',
      mediaType: 'VIDEO',
      url: '/api/files/videos/chat.mp4',
      name: '对话视频',
    });
    fireEvent.drop(screen.getByRole('button', { name: /角色/ }), {
      dataTransfer: {
        types: [CHAT_MEDIA_DRAG_MIME],
        getData: (type: string) => type === CHAT_MEDIA_DRAG_MIME ? payload : '',
        dropEffect: '',
      },
    });

    await waitFor(() => {
      expect(register).toHaveBeenCalledWith(expect.objectContaining({
        projectId: 'p1',
        producer: 'moon-chat',
        resultUrl: '/api/files/videos/chat.mp4',
        mediaType: 'VIDEO',
      }));
      expect(move).toHaveBeenCalledWith('folder-1', expect.objectContaining({
        scope: 'PROJECT',
        ownerProjectId: 'p1',
        assetIds: ['asset-video-1'],
      }));
    });
  });

  it('shows ten tags first and expands the rest through an ellipsis control', async () => {
    vi.spyOn(libraryApi, 'listTags').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: Array.from({ length: 12 }, (_, index) => ({
          id: `tag-${index + 1}`,
          name: `标签${index + 1}`,
        })),
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    expect(await screen.findByRole('button', { name: '标签10' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '标签11' })).toBeNull();
    const more = screen.getByRole('button', { name: '展开更多标签' });
    expect(more.textContent).toBe('…');

    fireEvent.click(more);
    expect(screen.getByRole('button', { name: '标签11' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '标签12' })).toBeTruthy();
  });

});
