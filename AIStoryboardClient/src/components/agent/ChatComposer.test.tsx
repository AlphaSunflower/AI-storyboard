import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentConversation } from '../../api/agent';
import { libraryApi, type LibraryAsset, type LibraryAssetDetail } from '../../api/library';
import { useAgentStore } from '../../stores/agentStore';
import { useChatAnnotationStore } from '../../stores/chatAnnotationStore';
import { useProjectStore } from '../../stores/projectStore';
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

  it('shows reference image thumbnails instead of a count-only chip', () => {
    useAgentStore.setState({
      refImageUrls: ['/api/files/images/a.png', '/api/files/images/b.png'],
      pendingAttachments: [
        {
          url: '/api/files/images/a.png',
          name: '角色参考.png',
          mimeType: 'image/png',
          kind: 'image',
        },
        {
          url: '/api/files/images/b.png',
          name: '场景参考.png',
          mimeType: 'image/png',
          kind: 'image',
        },
      ],
    });

    render(<ChatComposer />);

    expect(screen.getByRole('button', { name: '预览参考图 1：角色参考.png' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '预览参考图 2：场景参考.png' })).toBeTruthy();
    expect(screen.queryByText('参考图 2 张')).toBeNull();
  });

  it('collapses reference previews after five items and exposes the remainder', () => {
    useAgentStore.setState({
      refImageUrls: Array.from({ length: 7 }, (_, index) => `/api/files/images/${index + 1}.png`),
      pendingAttachments: [],
    });

    render(<ChatComposer />);

    expect(screen.getAllByRole('button', { name: /预览参考图/ })).toHaveLength(4);
    expect(screen.getByRole('button', { name: '查看其余 3 张参考图' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '预览参考图 5：图 5' })).toBeNull();
  });

  it('removes one reference image directly from the compact preview strip', () => {
    useAgentStore.setState({
      refImageUrls: ['/api/files/images/a.png', '/api/files/images/b.png'],
      pendingAttachments: [],
    });

    render(<ChatComposer />);
    fireEvent.click(screen.getByRole('button', { name: '移除参考图 1' }));

    expect(useAgentStore.getState().refImageUrls).toEqual(['/api/files/images/b.png']);
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
  it('falls back to accumulated input tokens when the backend omits context fields', () => {
    useAgentStore.setState({
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '完成',
        createdAt: new Date().toISOString(),
        metadata: {
          metrics: {
            inputTokens: 64_000,
            outputTokens: 1_000,
            cachedInputTokens: 0,
            totalTokens: 65_000,
            durationMs: 100,
            modelCalls: 1,
            complete: true,
          },
        },
      }] as never,
      generating: {},
    });
    render(<ChatComposer />);
    // 128000 窗口下 64000 → 50%
    expect(screen.getByRole('meter').textContent).toContain('50%');
  });

  it('hides the context ring until there is real usage data', () => {
    useAgentStore.setState({ messages: [], generating: {} });
    render(<ChatComposer />);
    expect(screen.queryByRole('meter')).toBeNull();
    expect(document.querySelector('.chat-context-tip')).toBeNull();
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

describe('ChatComposer annotation context', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      conversations: [],
      messages: [],
      streaming: false,
      waitingHumanInput: null,
      waitingVideoPlan: null,
      canvasBinding: null,
      canvasExitPending: false,
    });
    useProjectStore.setState({ currentProject: { id: 'p1', name: '测试项目' } as never });
    useChatAnnotationStore.getState().reset();
    vi.spyOn(agentApi, 'listConversationAssetGroups').mockResolvedValue({
      data: { code: 0, message: '', data: [] },
    } as never);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useChatAnnotationStore.getState().reset();
    useAgentStore.getState().newConversation();
  });

  it('sends pending annotations as context and clears them afterwards', () => {
    const sendSpy = vi.spyOn(useAgentStore.getState(), 'sendMessage').mockResolvedValue(undefined);
    useChatAnnotationStore.setState({
      annotations: [{
        id: 'a1', conversationId: 'conversation-1', messageId: 'm1',
        quote: '被引用的原文', comment: '看看这里', createdAt: 1,
      }],
    });
    render(<ChatComposer />);

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '我的问题' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(sendSpy).toHaveBeenCalledTimes(1);
    const sent = sendSpy.mock.calls[0][0] as string;
    expect(sent).toContain('【引用注释】');
    expect(sent).toContain('被引用的原文');
    expect(sent).toContain('看看这里');
    expect(sent).toContain('我的问题');
    expect(useChatAnnotationStore.getState().annotations).toHaveLength(0);
  });

  it('keeps plain sends unchanged when there are no annotations', () => {
    const sendSpy = vi.spyOn(useAgentStore.getState(), 'sendMessage').mockResolvedValue(undefined);
    render(<ChatComposer />);

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '普通消息' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(sendSpy.mock.calls[0][0]).toBe('普通消息');
  });

  it('shows one annotation chip per message above the input and manages the list', () => {
    useChatAnnotationStore.setState({
      annotations: [
        { id: 'a1', conversationId: 'conversation-1', messageId: 'm1', quote: '被引用的原文', comment: '评论一', createdAt: 1 },
        { id: 'a2', conversationId: 'conversation-1', messageId: 'm2', quote: '第二段原文', comment: '', createdAt: 2 },
      ],
    });
    render(<ChatComposer />);

    const chips = screen.getAllByRole('button', { name: '1 条注释' });
    expect(chips).toHaveLength(2);

    fireEvent.mouseEnter(chips[0].parentElement!);
    const list = screen.getByRole('dialog', { name: '注释列表' });
    expect(list.textContent).toContain('被引用的原文');
    expect(list.textContent).toContain('评论一');

    fireEvent.click(screen.getByRole('button', { name: '删除注释 1' }));
    expect(useChatAnnotationStore.getState().annotations.map((annotation) => annotation.id)).toEqual(['a2']);
  });

  it('clears one message annotations from the chip close button', () => {
    useChatAnnotationStore.setState({
      annotations: [{
        id: 'a1', conversationId: 'conversation-1', messageId: 'm1',
        quote: '被引用的原文', comment: '', createdAt: 1,
      }],
    });
    render(<ChatComposer />);

    const chip = screen.getByRole('button', { name: '1 条注释' }).parentElement!;
    fireEvent.mouseEnter(chip);
    fireEvent.click(screen.getByRole('button', { name: '清空该消息全部注释' }));

    expect(useChatAnnotationStore.getState().annotations).toHaveLength(0);
  });
});
