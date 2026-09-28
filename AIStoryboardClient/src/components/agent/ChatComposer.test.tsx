import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentConversation } from '../../api/agent';
import { libraryApi, type LibraryAsset, type LibraryAssetDetail } from '../../api/library';
import { useAgentStore } from '../../stores/agentStore';
import {
  PROJECT_LIBRARY_DRAG_MIME,
  serializeDragPayload,
} from '../../lib/projectLibraryDrag';
import { ChatComposer } from './ChatComposer';

describe('ChatComposer project library drops', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      refImageUrls: [],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
    });
    vi.spyOn(agentApi, 'listConversationAssetGroups').mockResolvedValue({
      data: { code: 0, message: '', data: [] },
    } as never);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useAgentStore.getState().newConversation();
  });

  it('adds a dropped project library image to the composer references', async () => {
    render(<ChatComposer />);

    const payload = serializeDragPayload({
      kind: 'asset',
      assetId: 'asset-1',
      mediaType: 'IMAGE',
      url: '/api/files/images/reference.png',
      name: '参考图',
    });
    fireEvent.drop(document.querySelector('.chat-composer-card')!, {
      dataTransfer: {
        types: [PROJECT_LIBRARY_DRAG_MIME],
        getData: (type: string) => type === PROJECT_LIBRARY_DRAG_MIME ? payload : '',
        files: [],
      },
    });

    await waitFor(() => {
      expect(useAgentStore.getState().refImageUrls).toContain('/api/files/images/reference.png');
    });
  });

  it('attaches a dropped project library group to the conversation', async () => {
    const attach = vi.spyOn(agentApi, 'attachConversationAssetGroup').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    render(<ChatComposer />);

    const payload = serializeDragPayload({
      kind: 'group',
      groupId: 'group-1',
      name: '主角集',
    });
    fireEvent.drop(document.querySelector('.chat-composer-card')!, {
      dataTransfer: {
        types: [PROJECT_LIBRARY_DRAG_MIME],
        getData: (type: string) => type === PROJECT_LIBRARY_DRAG_MIME ? payload : '',
        files: [],
      },
    });

    await waitFor(() => {
      expect(attach).toHaveBeenCalledWith('conversation-1', 'group-1');
    });
  });

  it('offers interrupt-and-send while the agent is streaming', () => {
    useAgentStore.setState({ streaming: true });
    render(<ChatComposer />);

    const input = screen.getByPlaceholderText('可输入新指令打断当前生成…');
    fireEvent.change(input, { target: { value: '改成新的方向' } });

    expect(screen.getByRole('button', { name: '停止并发送' })).toBeTruthy();
  });

  it('keeps AgentScope conversations writable', () => {
    useAgentStore.setState({ conversations: [conversation('AGENTSCOPE')] });

    render(<ChatComposer />);

    expect(screen.getByPlaceholderText('描述你的需求…')).toBeTruthy();
    expect(screen.getByRole('button', { name: '审批模式：方案批准' })).toBeTruthy();
  });

  it('opens the unified project library picker without a standalone asset group modal', async () => {
    mockChatLibrary();
    render(<ChatComposer />);

    fireEvent.click(screen.getByTitle('上传'));
    fireEvent.click(screen.getByText('上传素材'));
    fireEvent.click(screen.getByText('项目库素材'));

    expect(await screen.findByRole('tab', { name: '素材' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: '资产组' })).toBeTruthy();
    expect(screen.queryByText('选择资产组')).toBeNull();
    expect(screen.queryByRole('button', { name: '确认关联' })).toBeNull();
  });

  it('adds library material chosen from the unified picker to pending attachments', async () => {
    mockChatLibrary();
    render(<ChatComposer />);

    fireEvent.click(screen.getByTitle('上传'));
    fireEvent.click(screen.getByText('上传素材'));
    fireEvent.click(screen.getByText('项目库素材'));

    fireEvent.click(await screen.findByText('a.png'));
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    await waitFor(() => expect(useAgentStore.getState().pendingAttachments).toHaveLength(1));
    expect(useAgentStore.getState().pendingAttachments[0]).toMatchObject({
      url: '/api/library/media/m1/content',
      source: 'library',
      kind: 'image',
    });
  });

  it('updates conversation asset group bindings from the unified picker', async () => {
    const attach = vi.spyOn(agentApi, 'attachConversationAssetGroup').mockResolvedValue({
      data: { code: 0, message: '', data: null },
    } as never);
    mockChatLibrary();
    render(<ChatComposer />);

    fireEvent.click(screen.getByTitle('上传'));
    fireEvent.click(screen.getByText('上传素材'));
    fireEvent.click(screen.getByText('项目库素材'));

    fireEvent.click(await screen.findByRole('tab', { name: '资产组' }));
    fireEvent.click(await screen.findByText('全局场景'));
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    await waitFor(() => expect(attach).toHaveBeenCalledWith('conversation-1', 'g2'));
  });
  it('keeps legacy conversations read-only', () => {
    useAgentStore.setState({ conversations: [conversation('LEGACY')] });

    render(<ChatComposer />);

    expect(screen.getByPlaceholderText('该会话仅可查看历史记录')).toBeTruthy();
    expect(
      screen.getByText('该会话由旧版编排创建，仅可查看历史记录，请新建会话后继续。'),
    ).toBeTruthy();
  });
});

function conversation(orchestrationMode: AgentConversation['orchestrationMode']): AgentConversation {
  return {
    id: 'conversation-1',
    userId: 'user-1',
    projectId: 'project-1',
    title: '测试会话',
    status: 'active',
    orchestrationMode,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

const chatAsset: LibraryAsset = {
  id: 'a1', ownerUserId: 'u1', scope: 'GLOBAL', ownerProjectId: null, type: 'IMAGE',
  aiKind: null, name: '参考图', description: null, aiDescription: null, manualOverride: false,
  metadataState: 'NONE', status: 'ACTIVE', coverUrl: '/api/files/images/a.png', version: 1,
  tags: [], projects: [], folderId: null, folderPath: [], sourceType: null, sourceId: null,
  createdAt: '2026-09-21T00:00:00Z', updatedAt: '2026-09-21T00:00:00Z',
};

const chatDetail: LibraryAssetDetail = {
  asset: chatAsset,
  media: [{
    id: 'm1', blobId: 'b1', type: 'IMAGE', url: '/api/files/images/a.png',
    contentUrl: '/api/library/media/m1/content', objectKey: 'images/a.png',
    fileName: 'a.png', relativePath: null, mimeType: 'image/png', sizeBytes: 12,
    width: 100, height: 100, durationSeconds: null, sortOrder: 0,
    role: 'REFERENCE', primary: true, constraintText: null,
  }],
  linkedProjects: [],
};

const chatGroup = (id: string, name: string, scope: 'PROJECT' | 'GLOBAL'): LibraryAsset => ({
  ...chatAsset,
  id,
  scope,
  ownerProjectId: scope === 'PROJECT' ? 'p1' : null,
  type: 'GROUP',
  aiKind: 'CHARACTER',
  name,
  coverUrl: null,
});

function mockChatLibrary() {
  vi.spyOn(libraryApi, 'getFolderTree').mockImplementation(async ({ scope }) => ({
    data: {
      code: 0, message: 'ok', data: {
        scope,
        ownerProjectId: scope === 'PROJECT' ? 'p1' : null,
        folders: [],
        unfiledAssetCount: 1,
      },
    },
  } as never));
  vi.spyOn(libraryApi, 'listAssets').mockImplementation(async (query) => {
    if (query.type === 'GROUP') {
      const records = query.scope === 'PROJECT'
        ? [chatGroup('g1', '主角组', 'PROJECT')]
        : [chatGroup('g2', '全局场景', 'GLOBAL')];
      return { data: { code: 0, message: 'ok', data: { records, total: records.length, page: 1, size: 100 } } } as never;
    }
    return { data: { code: 0, message: 'ok', data: { records: [chatAsset], total: 1, page: 1, size: 80 } } } as never;
  });
  vi.spyOn(libraryApi, 'getAsset').mockResolvedValue({
    data: { code: 0, message: 'ok', data: chatDetail },
  } as never);
}
