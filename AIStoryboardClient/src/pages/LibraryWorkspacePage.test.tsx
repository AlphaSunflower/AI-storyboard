import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { libraryApi } from '../api/library';
import { agentApi } from '../api/agent';
import {
  CHAT_MEDIA_DRAG_MIME,
  serializeDragPayload,
} from '../lib/projectLibraryDrag';
import { useLibraryTaskStore } from '../stores/libraryTaskStore';
import { useAgentStore } from '../stores/agentStore';
import { useProjectStore } from '../stores/projectStore';
import { useToastStore } from '../stores/toastStore';
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
  useToastStore.setState({ toasts: [] });
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
  vi.spyOn(libraryApi, 'listOutputConversations').mockResolvedValue({
    data: { code: 0, message: '', data: [] },
  } as never);
  vi.spyOn(libraryApi, 'listGroupInsights').mockResolvedValue({
    data: { code: 0, message: '', data: [] },
  } as never);
  vi.spyOn(libraryApi, 'listDuplicates').mockResolvedValue({
    data: { code: 0, message: '', data: { records: [], total: 0, page: 1, size: 20 } },
  } as never);
  vi.spyOn(libraryApi, 'mergeDuplicates').mockResolvedValue({
    data: { code: 0, message: '', data: null },
  } as never);
  vi.spyOn(libraryApi, 'listSavedViews').mockResolvedValue({
    data: { code: 0, message: '', data: [] },
  } as never);
  vi.spyOn(libraryApi, 'markAsset').mockResolvedValue({
    data: { code: 0, message: '', data: null },
  } as never);
  vi.spyOn(libraryApi, 'bulkMarkAssets').mockResolvedValue({
    data: { code: 0, message: '', data: { updatedIds: [], skipped: [] } },
  } as never);
  vi.spyOn(libraryApi, 'createSavedView').mockResolvedValue({
    data: { code: 0, message: '', data: null },
  } as never);
  vi.spyOn(libraryApi, 'getAssetUsage').mockResolvedValue({
    data: {
      code: 0,
      message: '',
      data: { assetId: '', total: 0, scenes: [], conversations: [], groups: [], canvases: [] },
    },
  } as never);
  vi.spyOn(libraryApi, 'listUnusedAssets').mockResolvedValue({
    data: { code: 0, message: '', data: { records: [], total: 0, page: 1, size: 40 } },
  } as never);
  vi.spyOn(libraryApi, 'bulkTrash').mockResolvedValue({
    data: { code: 0, message: '', data: { updatedIds: [], skipped: [] } },
  } as never);
  vi.spyOn(libraryApi, 'requestAnalysis').mockResolvedValue({
    data: { code: 0, message: '', data: { items: [], skipped: 0 } },
  } as never);
  vi.spyOn(libraryApi, 'searchAssets').mockResolvedValue({
    data: { code: 0, message: '', data: [] },
  } as never);
  vi.spyOn(agentApi, 'listProjectAssetGroupBindings').mockResolvedValue({
    data: { code: 0, message: '', data: [] },
  } as never);
  vi.spyOn(agentApi, 'listConversations').mockResolvedValue({
    data: { code: 0, message: '', data: [] },
  } as never);
  useAgentStore.setState({ activeConversationId: null });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  useToastStore.setState({ toasts: [] });
});

describe('LibraryWorkspacePage', () => {
  it('confirms creating a folder and an asset group', async () => {
    vi.spyOn(libraryApi, 'createFolder').mockResolvedValue({
      data: { code: 0, message: '', data: { id: 'folder-2', name: '场景' } },
    } as never);
    vi.spyOn(libraryApi, 'createGroup').mockResolvedValue({
      data: { code: 0, message: '', data: { id: 'group-1', name: '主角组' } },
    } as never);
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );
    await waitFor(() => expect(libraryApi.getFolderTree).toHaveBeenCalled());

    fireEvent.click(screen.getByRole('button', { name: /新建目录/ }));
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '场景' } });
    fireEvent.click(screen.getByRole('button', { name: '确定' }));

    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0]).toMatchObject({
      title: '已新建目录',
      description: '「场景」',
      variant: 'success',
    });

    fireEvent.click(screen.getByRole('button', { name: /新建资产组/ }));
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: '主角组' } });
    fireEvent.click(screen.getByRole('button', { name: '确定' }));

    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(2));
    expect(useToastStore.getState().toasts[1]).toMatchObject({
      title: '已新建资产组',
      description: '「主角组」',
    });
  });

  it('opens all projects by default while retaining the selected project context', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(libraryApi.getFolderTree).toHaveBeenCalledWith({ scope: 'PROJECT', projectId: 'p1' });
      expect(libraryApi.listAssets).toHaveBeenCalledWith(expect.objectContaining({
        scope: 'PROJECT',
        projectId: 'p1',
        type: 'GROUP',
        includeGlobal: true,
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

  it('renders the four library menus and loads generated output conversations', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1&scope=output']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(libraryApi.listOutputConversations).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 'p1',
    })));

    expect(screen.getByRole('heading', { name: '产出素材' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '项目库' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '全局库' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: '产出素材' }).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '回收站' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '文件夹视图' })).toBeNull();
    expect(screen.queryByTestId('library-tree')).toBeNull();
    expect(screen.queryByRole('button', { name: '上传素材' })).toBeNull();
  });

  it('puts generated output into one folder per conversation and opens that folder', async () => {
    const outputAsset = {
      id: 'output-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      name: '主视觉',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/images/a.png',
      version: 1,
      tags: [],
      projects: [],
      folderId: null,
      folderPath: [],
      sourceType: 'generation',
      sourceId: 'agent-asset-1',
      conversationId: 'conv-1',
      createdAt: '',
      updatedAt: '',
    };
    vi.mocked(libraryApi.listOutputConversations).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: [
          {
            conversationId: 'conv-1',
            assetCount: 3,
            imageCount: 2,
            videoCount: 1,
            documentCount: 0,
            coverUrl: '/api/files/images/a.png',
            previewUrls: ['/api/files/images/a.png'],
            lastAssetName: '主视觉',
            lastGeneratedAt: '2026-10-18T10:00:00+08:00',
          },
          {
            conversationId: null,
            assetCount: 1,
            imageCount: 1,
            videoCount: 0,
            documentCount: 0,
            coverUrl: null,
            previewUrls: [],
            lastAssetName: '旧图',
            lastGeneratedAt: '2026-10-17T10:00:00+08:00',
          },
        ],
      },
    } as never);
    vi.mocked(agentApi.listConversations).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: [{
          id: 'conv-1',
          userId: 'user-1',
          projectId: 'p1',
          title: '赛博朋克分镜',
          status: 'active',
          createdAt: '',
          updatedAt: '',
        }],
      },
    } as never);
    vi.mocked(libraryApi.listAssets).mockResolvedValue({
      data: { code: 0, message: '', data: { records: [outputAsset], total: 1, page: 1, size: 100 } },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1&scope=output']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('赛博朋克分镜')).toBeTruthy();
    expect(screen.getByText('未归属产出')).toBeTruthy();
    expect(screen.getByText('共 3 项 · 图片 2 · 视频 1')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /^赛博朋克分镜，/ }));

    await waitFor(() => expect(libraryApi.listAssets).toHaveBeenCalledWith(expect.objectContaining({
      scope: 'PROJECT',
      projectId: 'p1',
      origin: 'GENERATED',
      conversationId: 'conv-1',
    })));
    expect(await screen.findByText('主视觉')).toBeTruthy();
    expect(screen.getByRole('button', { name: /打开对话/ })).toBeTruthy();
  });

  it('shows asset-group usage and binds the group to the picked conversation', async () => {
    const groupAsset = {
      id: 'group-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'GROUP' as const,
      aiKind: null,
      name: '主角组',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: null,
      version: 2,
      tags: [],
      projects: [],
      folderId: null,
      folderPath: [],
      sourceType: 'group',
      sourceId: null,
      createdAt: '',
      updatedAt: '',
    };
    vi.mocked(libraryApi.listAssets).mockImplementation(async (params) => ({
      data: {
        code: 0,
        message: '',
        data: params.type === 'GROUP'
          ? { records: [groupAsset], total: 1, page: 1, size: 100 }
          : { records: [], total: 0, page: 1, size: 100 },
      },
    } as never));
    vi.mocked(libraryApi.listGroupInsights).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: [{
          groupId: 'group-1',
          version: 2,
          mediaCount: 1,
          snapshotCount: 2,
          lastSnapshotAt: '2026-10-18T09:00:00+08:00',
        }],
      },
    } as never);
    vi.mocked(agentApi.listProjectAssetGroupBindings).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: [{
          groupId: 'group-1',
          conversationId: 'conv-1',
          conversationTitle: '赛博朋克分镜',
          snapshotId: 'snapshot-1',
          groupVersion: 1,
          target: 'AGENT',
          boundAt: '2026-10-18T08:00:00+08:00',
        }],
      },
    } as never);
    vi.mocked(agentApi.listConversations).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: [
          {
            id: 'conv-1',
            userId: 'user-1',
            projectId: 'p1',
            title: '赛博朋克分镜',
            status: 'active',
            createdAt: '',
            updatedAt: '2026-10-18T10:00:00+08:00',
          },
          {
            id: 'conv-2',
            userId: 'user-1',
            projectId: 'p1',
            title: '旧版分镜',
            status: 'active',
            createdAt: '',
            updatedAt: '2026-10-17T10:00:00+08:00',
          },
        ],
      },
    } as never);
    vi.spyOn(libraryApi, 'getGroup').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { groupId: 'group-1', name: '主角组', description: null, version: 2, media: [] },
      },
    } as never);
    const attach = vi.spyOn(agentApi, 'attachConversationAssetGroup').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    useAgentStore.setState({ activeConversationId: 'conv-1' });

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('1 个对话使用 · 1 项素材')).toBeTruthy();
    fireEvent.click(screen.getByText('主角组'));

    expect(await screen.findByText('快照过期')).toBeTruthy();
    expect(screen.getAllByText('赛博朋克分镜').length).toBeGreaterThan(0);

    // 不依赖 Moon 当前对话：在项目库里改选另一个对话再绑定
    fireEvent.change(screen.getByLabelText('选择要绑定的对话'), { target: { value: 'conv-2' } });
    fireEvent.click(screen.getByRole('button', { name: /绑定到所选对话/ }));

    await waitFor(() => expect(attach).toHaveBeenCalledWith('conv-2', 'group-1'));
  });

  it('finds duplicate media and merges into the chosen survivor', async () => {
    const duplicateA = {
      id: 'dup-a',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      name: '主图.png',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/images/a.png',
      version: 1,
      tags: [],
      projects: [],
      folderId: null,
      folderPath: ['角色'],
      sourceType: 'upload',
      sourceId: null,
      createdAt: '',
      updatedAt: '',
    };
    const duplicateB = { ...duplicateA, id: 'dup-b', name: '副本.png' };
    vi.mocked(libraryApi.listDuplicates).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          records: [{
            blobId: 'blob-1',
            sha256: 'a'.repeat(64),
            mimeType: 'image/png',
            sizeBytes: 1024,
            scope: 'PROJECT',
            ownerProjectId: 'p1',
            assets: [duplicateA, duplicateB],
          }],
          total: 1,
          page: 1,
          size: 20,
        },
      },
    } as never);
    const merge = vi.spyOn(libraryApi, 'mergeDuplicates').mockResolvedValue({
      data: { code: 0, message: '', data: duplicateA },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1&scope=duplicates']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('2 份重复 · 1.0 KB')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /2 份重复，合并/ }));

    expect(await screen.findByText('合并重复素材')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /合并并移入回收站/ }));

    await waitFor(() => expect(merge).toHaveBeenCalledWith({
      survivorAssetId: 'dup-a',
      duplicateAssetIds: ['dup-b'],
    }));
  });

  it('stacks multiple generations of one scene into a version group', async () => {
    const base = {
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      version: 1,
      tags: [],
      projects: [],
      folderId: null,
      folderPath: [],
      sourceType: 'generation',
      sourceId: null,
      updatedAt: '',
      conversationId: 'conv-1',
    };
    const firstVersion = {
      ...base,
      id: 'v1',
      name: '镜头1_v1.png',
      coverUrl: '/api/files/images/v1.png',
      sceneId: 'scene-1',
      createdAt: '2026-10-18T09:00:00+08:00',
    };
    const secondVersion = {
      ...base,
      id: 'v2',
      name: '镜头1_v2.png',
      coverUrl: '/api/files/images/v2.png',
      sceneId: 'scene-1',
      createdAt: '2026-10-18T10:00:00+08:00',
    };
    const single = {
      ...base,
      id: 'single',
      name: '花絮.png',
      coverUrl: '/api/files/images/single.png',
      sceneId: null,
      createdAt: '2026-10-18T08:00:00+08:00',
    };
    vi.mocked(libraryApi.listAssets).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { records: [secondVersion, firstVersion, single], total: 3, page: 1, size: 100 },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1&scope=output&conversationId=conv-1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('镜头组 · 2 版')).toBeTruthy();
    expect(screen.getByText('花絮.png')).toBeTruthy();
    fireEvent.click(screen.getByText('镜头组 · 2 版'));

    expect(await screen.findByText('镜头版本 · 2 版')).toBeTruthy();
    expect(screen.getByText('镜头1_v2.png')).toBeTruthy();
    expect(screen.getByText('镜头1_v1.png')).toBeTruthy();
  });

  it('applies saved view filters to the asset query', async () => {
    vi.mocked(libraryApi.listSavedViews).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: [{
          id: 'view-1',
          ownerUserId: 'user-1',
          scope: 'PROJECT',
          ownerProjectId: 'p1',
          name: '四星精选',
          filters: { minRating: 4, flag: 'pick' },
          sort: {},
          viewMode: 'folders',
          pinned: false,
          createdAt: '',
          updatedAt: '',
        }],
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: '四星精选' }));

    await waitFor(() => expect(libraryApi.listAssets).toHaveBeenCalledWith(
      expect.objectContaining({ minRating: 4, flag: 'pick' }),
    ));
  });

  it('marks an asset from the inspector', async () => {
    const markTarget = {
      id: 'mark-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      name: '待挑片.png',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/images/mark.png',
      version: 1,
      tags: [],
      projects: [],
      folderId: null,
      folderPath: [],
      sourceType: 'upload',
      sourceId: null,
      rating: 0,
      flag: null,
      colorLabel: null,
      createdAt: '',
      updatedAt: '',
    };
    vi.mocked(libraryApi.listAssets).mockImplementation(async (params) => ({
      data: {
        code: 0,
        message: '',
        data: params.type === 'GROUP'
          ? { records: [], total: 0, page: 1, size: 100 }
          : { records: [markTarget], total: 1, page: 1, size: 100 },
      },
    } as never));
    vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { asset: markTarget, media: [], linkedProjects: [] },
      },
    } as never);
    const mark = vi.spyOn(libraryApi, 'markAsset').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { ...markTarget, rating: 5 },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(libraryApi.listAssets).toHaveBeenCalled());
    fireEvent.click(await screen.findByText('待挑片.png'));    fireEvent.click(await screen.findByRole('button', { name: '5 星' }));

    await waitFor(() => expect(mark).toHaveBeenCalledWith('mark-1', {
      rating: 5,
      flag: null,
      colorLabel: null,
    }));
  });

  it('lists unused assets and cleans the selection', async () => {
    const unusedAsset = {
      id: 'unused-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      name: '闲置素材.png',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/images/unused.png',
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
    vi.mocked(libraryApi.listUnusedAssets).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { records: [unusedAsset], total: 1, page: 1, size: 40 },
      },
    } as never);
    const bulkTrash = vi.spyOn(libraryApi, 'bulkTrash').mockResolvedValue({
      data: { code: 0, message: '', data: { updatedIds: ['unused-1'], skipped: [] } },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1&scope=unused']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    expect(await screen.findByText('闲置素材.png')).toBeTruthy();
    fireEvent.click(screen.getByText('闲置素材.png'));
    fireEvent.click(screen.getByRole('button', { name: /清理选中（1）/ }));

    await waitFor(() => expect(bulkTrash).toHaveBeenCalledWith(['unused-1']));
  });

  it('shows where an asset is used', async () => {
    const usedAsset = {
      id: 'used-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      name: '被引用.png',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/images/used.png',
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
          : { records: [usedAsset], total: 1, page: 1, size: 100 },
      },
    } as never));
    vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { asset: usedAsset, media: [], linkedProjects: [] },
      },
    } as never);
    vi.mocked(libraryApi.getAssetUsage).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          assetId: 'used-1',
          total: 2,
          scenes: [{ id: 'scene-1', title: '分镜 1', detail: '测试项目' }],
          conversations: [{ id: 'conv-1', title: '角色定稿', detail: null }],
          groups: [],
          canvases: [],
        },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByText('被引用.png'));

    expect(await screen.findByText('使用位置 · 2')).toBeTruthy();
    expect(screen.getByText('分镜 1 · 测试项目')).toBeTruthy();
    expect(screen.getByRole('button', { name: '角色定稿' })).toBeTruthy();
  });

  it('retries a failed analysis task from the queue', async () => {
    useLibraryTaskStore.setState({
      panelOpen: true,
      tasks: [{
        id: 'analysis:a1',
        kind: 'analysis',
        label: '主角.png',
        status: 'failed',
        progress: 0,
        assetId: 'a1',
        error: '模型不可用',
      }],
    });

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: '重试 主角.png' }));

    await waitFor(() => expect(libraryApi.requestAnalysis).toHaveBeenCalledWith(['a1']));
  });

  it('opens the command palette with Ctrl+K and navigates', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    expect(await screen.findByRole('dialog', { name: '项目库命令面板' })).toBeTruthy();

    const input = screen.getByRole('textbox', { name: '搜索素材、视图或动作' });
    fireEvent.change(input, { target: { value: '未使用' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(await screen.findByRole('heading', { name: '未使用素材' })).toBeTruthy();
  });

  it('runs a smart search from the toolbar', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: /智能/ }));
    fireEvent.change(screen.getByPlaceholderText('搜索名称、标签、描述'), {
      target: { value: '红衣女孩' },
    });

    await waitFor(() => expect(libraryApi.searchAssets).toHaveBeenCalledWith(
      expect.objectContaining({ projectId: 'p1', q: '红衣女孩', limit: 30 }),
    ));
  });

  it('searches by dominant color', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: /智能/ }));
    fireEvent.click(screen.getByRole('button', { name: '主色 红' }));

    await waitFor(() => expect(libraryApi.searchAssets).toHaveBeenCalledWith(
      expect.objectContaining({ projectId: 'p1', color: '#e05b4b' }),
    ));
  });

  it('finds similar assets from the inspector', async () => {
    const similarTarget = {
      id: 'similar-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      name: '原图.png',
      description: null,
      aiDescription: '红色人物',
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/images/similar.png',
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
          : { records: [similarTarget], total: 1, page: 1, size: 100 },
      },
    } as never));
    vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { asset: similarTarget, media: [], linkedProjects: [] },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByText('原图.png'));
    fireEvent.click(await screen.findByRole('button', { name: /找相似/ }));

    await waitFor(() => expect(libraryApi.searchAssets).toHaveBeenCalledWith(
      expect.objectContaining({ projectId: 'p1', similarTo: 'similar-1' }),
    ));
  });

  it('shows the global materials node in the project tree', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(libraryApi.getFolderTree).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '树形视图' }));

    expect(screen.getByTestId('library-tree')).toBeTruthy();
    expect(screen.getByText('全局素材')).toBeTruthy();
  });

  it('opens the enlarged preview when double-clicking a document card', async () => {
    const documentAsset = {
      id: 'doc-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'DOCUMENT' as const,
      aiKind: null,
      name: '交付说明',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/files/report.md',
      version: 1,
      tags: [],
      projects: [],
      folderId: null,
      folderPath: [],
      sourceType: 'generation',
      sourceId: null,
      createdAt: '',
      updatedAt: '',
    };
    vi.mocked(libraryApi.listAssets).mockImplementation(async (params) => ({
      data: {
        code: 0,
        message: '',
        data: params.folderId === 'UNFILED'
          ? { records: [documentAsset], total: 1, page: 1, size: 100 }
          : { records: [], total: 0, page: 1, size: 100 },
      },
    } as never));
    vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          asset: documentAsset,
          media: [{
            id: 'media-1',
            contentUrl: '/api/library/media/media-1/content?token=t',
            fileName: 'report.md',
            mimeType: 'text/markdown',
            primary: true,
            type: 'DOCUMENT',
          }],
          linkedProjects: [],
        },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    const card = await screen.findByRole('button', { name: /交付说明/ });
    fireEvent.doubleClick(card);

    expect(await screen.findByRole('dialog', { name: '预览 report.md' })).toBeTruthy();
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

  it('offers document and other type filters', async () => {
    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(libraryApi.getFolderTree).toHaveBeenCalled());
    expect(screen.getByRole('option', { name: '文档' })).toBeTruthy();
    expect(screen.getByRole('option', { name: '其他' })).toBeTruthy();

    fireEvent.change(screen.getByDisplayValue('全部类型'), { target: { value: 'DOCUMENT' } });

    await waitFor(() => expect(libraryApi.listAssets).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'DOCUMENT' }),
    ));
  });

  it('accepts any file type for direct upload and skips analysis for documents', async () => {
    const uploadDirect = vi.spyOn(libraryApi, 'uploadDirect').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { id: 'doc-1', name: '说明', type: 'DOCUMENT', metadataState: 'NONE' },
      },
    } as never);
    const { container } = render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    await waitFor(() => expect(libraryApi.getFolderTree).toHaveBeenCalled());
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input.getAttribute('accept')).toBeNull();

    fireEvent.change(input, {
      target: { files: [new File(['# 说明'], '说明.md', { type: 'text/markdown' })] },
    });

    await waitFor(() => expect(uploadDirect).toHaveBeenCalled());
    await waitFor(() => expect(
      useLibraryTaskStore.getState().tasks.some((task) => task.kind === 'analysis'),
    ).toBe(false));
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

  it('queues AI metadata enrichment only after the inspector action is clicked', async () => {
    const asset = {
      id: 'asset-enrich-1',
      ownerUserId: 'user-1',
      scope: 'PROJECT' as const,
      ownerProjectId: 'p1',
      type: 'IMAGE' as const,
      aiKind: null,
      name: '待完善图片',
      description: null,
      aiDescription: null,
      manualOverride: false,
      metadataState: 'NONE' as const,
      status: 'ACTIVE' as const,
      coverUrl: '/api/files/images/enrich.png',
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
    vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { asset, media: [], linkedProjects: [] },
      },
    } as never);
    const requestAnalysis = vi.spyOn(libraryApi, 'requestAnalysis').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { items: [{ assetId: asset.id, name: asset.name }], skipped: 0 },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    const title = (await screen.findAllByText('待完善图片'))[0];
    fireEvent.click(title.closest('button')!);
    await waitFor(() => expect(libraryApi.getAsset).toHaveBeenCalledWith('asset-enrich-1'));
    expect(useLibraryTaskStore.getState().tasks.some((task) => task.kind === 'analysis')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'AI 文件完善信息' }));

    await waitFor(() => {
      expect(requestAnalysis).toHaveBeenCalledWith(['asset-enrich-1']);
      expect(useLibraryTaskStore.getState().tasks.some((task) => (
        task.kind === 'analysis' && task.assetId === 'asset-enrich-1' && task.status === 'processing'
      ))).toBe(true);
    });
  });

  it('confirms before enriching a folder with many assets and then queues it', async () => {
    vi.mocked(libraryApi.getFolderTree).mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: {
          scope: 'PROJECT',
          ownerProjectId: 'p1',
          unfiledAssetCount: 0,
          folders: [{
            id: 'folder-1',
            parentId: null,
            name: '角色',
            scope: 'PROJECT',
            ownerProjectId: 'p1',
            directAssetCount: 12,
            descendantAssetCount: 12,
            version: 1,
          }],
        },
      },
    } as never);
    const requestFolderAnalysis = vi.spyOn(libraryApi, 'requestFolderAnalysis').mockResolvedValue({
      data: {
        code: 0,
        message: '',
        data: { items: [{ assetId: 'folder-image-1', name: '角色图' }], skipped: 0 },
      },
    } as never);

    render(
      <MemoryRouter initialEntries={['/library?projectId=p1']}>
        <LibraryWorkspacePage />
      </MemoryRouter>,
    );

    const folderCard = await screen.findByRole('button', { name: /角色/ });
    fireEvent.mouseDown(folderCard);

    fireEvent.click(await screen.findByRole('button', { name: 'AI 完善文件夹信息' }));
    expect(requestFolderAnalysis).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '开始完善' }));

    await waitFor(() => {
      expect(requestFolderAnalysis).toHaveBeenCalledWith('folder-1', {
        scope: 'PROJECT',
        projectId: 'p1',
      });
      expect(useLibraryTaskStore.getState().tasks.some((task) => (
        task.kind === 'analysis' && task.assetId === 'folder-image-1' && task.status === 'processing'
      ))).toBe(true);
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
