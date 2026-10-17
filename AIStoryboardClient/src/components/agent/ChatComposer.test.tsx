import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentConversation } from '../../api/agent';
import { libraryApi, type LibraryAsset, type LibraryAssetDetail } from '../../api/library';
import { useAgentStore } from '../../stores/agentStore';
import { useChatAnnotationStore } from '../../stores/chatAnnotationStore';
import { useProjectStore } from '../../stores/projectStore';
import { useSideChatSelectionStore } from '../../stores/sideChatSelectionStore';
import {
  PROJECT_LIBRARY_DRAG_MIME,
  serializeDragPayload,
} from '../../lib/projectLibraryDrag';
import {
  AGENT_ASSET_DRAG_MIME,
  serializeAgentAssetDragPayload,
} from '../../lib/agentAssetDrag';
import { defaultGenerationPreferences } from '../../lib/generationPreferences';
import { ChatComposer } from './ChatComposer';

beforeEach(() => {
  useSideChatSelectionStore.getState().clear();
});

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

  it('keeps the drop highlight opaque so the card does not turn see-through over media', () => {
    render(<ChatComposer />);
    const card = document.querySelector('.chat-composer-card') as HTMLElement;

    fireEvent.dragEnter(card, {
      dataTransfer: {
        types: [AGENT_ASSET_DRAG_MIME],
        getData: () => '',
        files: [],
      },
    });

    // 输入卡绝对定位盖在消息流上，高亮底色只要带一点透明，背后有图就整块透出来。
    expect(card.style.background).not.toMatch(/rgba\([^)]*0\.0\d/);
    expect(card.style.border).toContain('65, 118, 230');
  });

  it('adds a dropped generation-center image to the composer references', async () => {
    render(<ChatComposer />);

    const payload = serializeAgentAssetDragPayload({
      url: '/api/files/images/generated.png',
      name: 'generated.png',
      kind: 'image',
    });
    fireEvent.drop(document.querySelector('.chat-composer-card')!, {
      dataTransfer: {
        types: [AGENT_ASSET_DRAG_MIME],
        getData: (type: string) => type === AGENT_ASSET_DRAG_MIME ? payload : '',
        files: [],
      },
    });

    await waitFor(() => {
      expect(useAgentStore.getState().refImageUrls).toContain('/api/files/images/generated.png');
    });
  });

  it('routes a dropped generation-center video to the reference media instead of images', async () => {
    render(<ChatComposer />);

    const payload = serializeAgentAssetDragPayload({
      url: '/api/files/videos/generated.mp4',
      name: 'generated.mp4',
      kind: 'video',
    });
    fireEvent.drop(document.querySelector('.chat-composer-card')!, {
      dataTransfer: {
        types: [AGENT_ASSET_DRAG_MIME],
        getData: (type: string) => type === AGENT_ASSET_DRAG_MIME ? payload : '',
        files: [],
      },
    });

    await waitFor(() => {
      expect(useAgentStore.getState().pickedReferenceVideos).toContain('/api/files/videos/generated.mp4');
    });
    expect(useAgentStore.getState().refImageUrls).not.toContain('/api/files/videos/generated.mp4');
  });

  it('does not treat a dropped asset as a scene reference', async () => {
    // 素材拖拽同样会写 text/plain；素材分支必须排在分镜兜底之前，
    // 否则会被 JSON.parse 成场景引用，直接发一条「请查看 分镜N」出去。
    const sendMessage = vi.spyOn(useAgentStore.getState(), 'sendMessage');
    render(<ChatComposer />);

    fireEvent.drop(document.querySelector('.chat-composer-card')!, {
      dataTransfer: {
        types: [AGENT_ASSET_DRAG_MIME],
        getData: (type: string) => {
          if (type === AGENT_ASSET_DRAG_MIME) {
            return serializeAgentAssetDragPayload({
              url: '/api/files/images/generated.png',
              name: 'generated.png',
              kind: 'image',
            });
          }
          return JSON.stringify([{ sceneNumber: 1 }]);
        },
        files: [],
      },
    });

    await waitFor(() => {
      expect(useAgentStore.getState().refImageUrls).toContain('/api/files/images/generated.png');
    });
    expect(sendMessage).not.toHaveBeenCalled();
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

    expect(screen.getByPlaceholderText('发消息或创建任务… / 使用技能 @ 添加资料')).toBeTruthy();
    expect(screen.getByRole('button', { name: '审批模式：方案批准' })).toBeTruthy();
    // 生成偏好入口：默认自动，可展开面板
    expect(screen.getByRole('button', { name: '生成偏好：自动' })).toBeTruthy();
  });

  it('lists the three upload sources directly in the plus menu', () => {
    render(<ChatComposer />);

    fireEvent.click(screen.getByLabelText('上传'));

    expect(screen.getByText('上传文件')).toBeTruthy();
    expect(screen.getByText('上传项目库素材')).toBeTruthy();
    expect(screen.getByText('上传分镜')).toBeTruthy();
    // 旧的两级弹窗入口已移除
    expect(screen.queryByText('上传素材')).toBeNull();
    expect(screen.queryByText('上传项目产出素材')).toBeNull();
  });

  it('opens the unified project library picker without a standalone asset group modal', async () => {
    mockChatLibrary();
    render(<ChatComposer />);

    fireEvent.click(screen.getByLabelText('上传'));
    fireEvent.click(screen.getByText('上传项目库素材'));

    expect(await screen.findByRole('tab', { name: '素材' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: '资产组' })).toBeTruthy();
    expect(screen.queryByText('选择资产组')).toBeNull();
    expect(screen.queryByRole('button', { name: '确认关联' })).toBeNull();
  });

  it('adds library material chosen from the unified picker to pending attachments', async () => {
    mockChatLibrary();
    render(<ChatComposer />);

    fireEvent.click(screen.getByLabelText('上传'));
    fireEvent.click(screen.getByText('上传项目库素材'));

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

    fireEvent.click(screen.getByLabelText('上传'));
    fireEvent.click(screen.getByText('上传项目库素材'));

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

  it('replaces the disclaimer with the footer status bar once usage exists', () => {
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
            cachedInputTokens: 60_000,
            totalTokens: 65_000,
            contextTokens: 64_000,
            contextWindowTokens: 128_000,
            durationMs: 4_000,
            modelCalls: 3,
            complete: true,
            modelDurationMs: 3_000,
            toolDurationMs: 500,
            systemPromptTokens: 1_500,
            toolDefinitionTokens: 5_600,
            messageTokens: 56_900,
          },
        },
      }] as never,
      generating: {},
    });

    const { container } = render(<ChatComposer />);

    expect(screen.queryByText('内容由 AI 生成，请仔细甄别')).toBeNull();
    const bar = container.querySelector('.chat-status-bar');
    expect(bar).not.toBeNull();
    // 圆环从输入卡工具栏移到底部状态栏
    expect(bar!.querySelector('[role="meter"]')).toBeTruthy();
    expect(screen.getByText('0 轮 3 步')).toBeTruthy();
  });

  it('hides the counter and adds the conversation entry only in the floating-dock state', () => {
    useAgentStore.setState({
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '完成',
        createdAt: new Date().toISOString(),
        metadata: {
          metrics: {
            inputTokens: 64_000, outputTokens: 1_000, cachedInputTokens: 60_000,
            totalTokens: 65_000, durationMs: 4_000, modelCalls: 3, complete: true,
          },
        },
      }] as never,
      generating: {},
    });

    const docked = render(<ChatComposer docked onViewConversation={() => {}} />);
    expect(docked.container.querySelector('.chat-status-bar')).toBeNull();
    expect(screen.getByRole('button', { name: '查看当前对话' })).toBeTruthy();
    cleanup();

    const split = render(<ChatComposer />);
    expect(split.container.querySelector('.chat-status-bar')).toBeTruthy();
    expect(screen.queryByRole('button', { name: '查看当前对话' })).toBeNull();
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

describe('ChatComposer 斜杠命令', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      refImageUrls: [],
    });
    vi.spyOn(agentApi, 'listConversationAssetGroups').mockResolvedValue({
      data: { code: 0, message: '', data: [] },
    } as never);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('输入 / 弹出命令列表', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/' } });
    expect(screen.getByRole('listbox', { name: '命令' })).toBeTruthy();
    expect(screen.getByText('压缩上下文')).toBeTruthy();
    expect(screen.getByText('状态')).toBeTruthy();
    expect(screen.getByText('侧边')).toBeTruthy();
    // 新增：清空上下文（真删除）
    expect(screen.getByText('清空上下文')).toBeTruthy();
    // 命令同时展示英文别名，便于 /clear 这类输入直接命中
    expect(screen.getByText('/compact')).toBeTruthy();
    expect(screen.getByText('/clear')).toBeTruthy();
  });

  it('「清空上下文」点选后走真删除接口，并把会话态退回新对话', async () => {
    const clearContextSpy = vi.spyOn(agentApi, 'clearContext').mockResolvedValue({
      data: { code: 0, message: '', data: { deletedRows: 17, title: '新对话' } },
    } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      conversations: [conversation('AGENTSCOPE')],
      messages: [{
        id: 'm1', role: 'user', content: '旧对话', createdAt: new Date().toISOString(),
      }],
      // 生成中/等待确认时前端会前置拦截，这里显式置于可清空状态
      streaming: false,
      waitingHumanInput: null,
      waitingVideoPlan: null,
    } as never);
    render(<ChatComposer />);

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/清空' } });
    fireEvent.click(screen.getByText('清空上下文'));

    await waitFor(() => expect(clearContextSpy).toHaveBeenCalledWith('conversation-1'));
    await waitFor(() => expect(useAgentStore.getState().messages).toHaveLength(0));
    expect(useAgentStore.getState().waitingHumanInput).toBeNull();
    // 标题跟着后端重置为「新对话」
    expect(useAgentStore.getState().conversations.find((c) => c.id === 'conversation-1')?.title)
      .toBe('新对话');
  });

  it('/clear 命中「清空上下文」（英文别名）', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/clear' } });

    expect(screen.getByText('清空上下文')).toBeTruthy();
    // 别名过滤同样生效：不匹配的命令不再出现
    expect(screen.queryByText('状态')).toBeNull();
  });

  it('无用量数据时「状态」沿用静态说明', () => {
    useAgentStore.setState({ messages: [] });
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/' } });

    expect(screen.getByText('显示会话 ID、背景信息与额度')).toBeTruthy();
  });

  it('「状态」把缓存、运行轮数与输出速度汇总成实时摘要（与底部状态栏同口径）', () => {
    useAgentStore.setState({
      messages: [
        {
          id: 'u1', role: 'user', content: '第一问', createdAt: new Date().toISOString(),
        },
        {
          id: 'a1', role: 'assistant', content: '第一答', createdAt: new Date().toISOString(),
          metadata: {
            kind: 'assistant-reply',
            metrics: {
              inputTokens: 1000, outputTokens: 300, cachedInputTokens: 800, totalTokens: 1300,
              contextTokens: 4000, contextWindowTokens: 8000,
              durationMs: 20000, modelDurationMs: 10000, modelCalls: 1, complete: true,
            },
          },
        },
        {
          id: 'a2', role: 'assistant', content: '第二答', createdAt: new Date().toISOString(),
          metadata: {
            kind: 'assistant-reply',
            metrics: {
              inputTokens: 1200, outputTokens: 200, cachedInputTokens: 400, totalTokens: 1400,
              contextTokens: 4200, contextWindowTokens: 8000,
              durationMs: 15000, modelDurationMs: 10000, modelCalls: 1, complete: true,
            },
          },
        },
      ],
    } as never);
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/' } });

    const summary = screen.getByText(/运行 1 轮/).textContent ?? '';
    // 轮 = 用户提问次数（1 条 user），步 = 各轮 modelCalls 之和（1 + 1）。
    expect(summary).toContain('运行 1 轮 2 步');
    // 缓存命中率 = 累计缓存读取 / 累计输入 = (800 + 400) / (1000 + 1200)。
    expect(summary).toContain('缓存命中 55%');
    // 输出速度分母优先用推理耗时：(300 + 200) / (10s + 10s) = 25 tok/s。
    expect(summary).toContain('输出 25 tok/s');
    // 上下文占用取最新一轮：4200 / 8000。
    expect(summary).toContain('上下文 53%');
  });

  it('「状态」窗口里也要有运行统计与缓存，且宽度跟输入框一致', () => {
    useAgentStore.setState({
      messages: [
        {
          id: 'a1', role: 'assistant', content: '答', createdAt: new Date().toISOString(),
          metadata: {
            kind: 'assistant-reply',
            metrics: {
              inputTokens: 2000, outputTokens: 400, cachedInputTokens: 1500, totalTokens: 2400,
              contextTokens: 6000, contextWindowTokens: 12000,
              durationMs: 20000, modelDurationMs: 8000, modelCalls: 3, complete: true,
            },
          },
        },
      ],
    } as never);
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/状态' } });
    fireEvent.keyDown(window, { key: 'Enter' });

    const card = document.querySelector<HTMLElement>('.chat-status-card');
    expect(card).not.toBeNull();
    const text = card!.textContent ?? '';
    expect(text).toContain('运行统计');
    expect(text).toContain('3 步');
    expect(text).toContain('50 tok/s');
    expect(text).toContain('缓存');
    expect(text).toContain('命中 75%');

    // 共用的 .chat-status-card 把宽度钉在 236px（底部状态栏那张深色小卡），
    // 这张卡必须显式放开宽度，靠左右贴边撑满输入卡。
    expect(card!.style.width).toBe('auto');
    expect(card!.style.left).toBe('0px');
    expect(card!.style.right).toBe('0px');
  });

  it('新对话（无消息）压缩项不可用', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/压缩' } });
    expect(screen.getByText('新对话暂无可压缩的上下文')).toBeTruthy();
  });

  it('新对话（无消息）侧边项不可用', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/侧边' } });
    expect(screen.getByText('先完成一轮对话后再开启侧边')).toBeTruthy();
  });

  it('已有对话时执行侧边命令会创建临时分支', () => {
    const openSideChat = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({
      messages: [{ id: 'm1', conversationId: 'conversation-1', role: 'user', content: 'hi' }] as never,
      conversations: [conversation('AGENTSCOPE')],
      openSideChat,
    });
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/侧边' } });
    fireEvent.click(screen.getByText('侧边'));

    expect(openSideChat).toHaveBeenCalledWith('conversation-1', '测试会话');
  });

  it('嵌入侧边聊天时不再展示侧边命令', () => {
    render(<ChatComposer enableSideChat={false} />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/' } });
    expect(screen.queryByText('侧边')).toBeNull();
  });

  it('已有对话时可压缩并展示结果', async () => {
    useAgentStore.setState({
      messages: [{ id: 'm1', conversationId: 'conversation-1', role: 'user', content: 'hi' }] as never,
    });
    const spy = vi.spyOn(agentApi, 'compactContext').mockResolvedValue({
      data: {
        code: 0, message: '', data: {
          compacted: true, status: 'COMPACTED',
          messagesBefore: 42, messagesAfter: 13, tokensBefore: 1, tokensAfter: 1,
        },
      },
    } as never);
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/' } });
    fireEvent.click(screen.getByText('压缩上下文'));

    await waitFor(() => expect(spy).toHaveBeenCalledWith('conversation-1'));
    await screen.findByText('上下文已压缩：42 条 → 13 条');
  });

  it('状态卡展示会话 ID / 背景信息 / 额度占位', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '/状态' } });
    fireEvent.click(screen.getByText('状态'));

    expect(screen.getByText('conversation-1')).toBeTruthy();
    expect(screen.getByText('背景信息:')).toBeTruthy();
    expect(screen.getByText('额度:')).toBeTruthy();
    expect(screen.getByText('不可用')).toBeTruthy();
  });
});

describe('ChatComposer 只有注释时发送', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      refImageUrls: [],
    });
    useProjectStore.setState({ currentProject: { id: 'project-1' } as never });
    vi.spyOn(agentApi, 'listConversationAssetGroups').mockResolvedValue({
      data: { code: 0, message: '', data: [] },
    } as never);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useChatAnnotationStore.setState({ annotations: [] });
    useSideChatSelectionStore.getState().clear();
  });

  it('无文字但有注释时发送按钮可用，并把注释块作为内容发出', () => {
    const sendMessage = vi.fn();
    useAgentStore.setState({ sendMessage: sendMessage as never });
    useChatAnnotationStore.setState({
      annotations: [{
        id: 'a1', conversationId: 'conversation-1', messageId: 'm1',
        quote: '被引用的原文', comment: '', createdAt: 1,
      }],
    });
    render(<ChatComposer />);

    const button = screen.getByRole('button', { name: '发送' }) as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    fireEvent.click(button);

    expect(sendMessage).toHaveBeenCalledTimes(1);
    const [content] = sendMessage.mock.calls[0] as [string];
    expect(content).toContain('【引用注释】');
    expect(content).toContain('被引用的原文');
  });

  it('无文字也无注释时发送按钮禁用', () => {
    render(<ChatComposer />);
    const button = screen.getByRole('button', { name: '发送' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });

  it('无文字但有侧边选中片段时发送，并携带片段上下文', () => {
    const sendMessage = vi.fn();
    useAgentStore.setState({ sendMessage: sendMessage as never });
    window.history.replaceState({}, '', '/chat?embed=1&sideChatId=side-1');
    useSideChatSelectionStore.getState().addSelection({
      text: '这是需要追问的原文片段',
      messageId: 'm1',
    });
    render(<ChatComposer />);

    const button = screen.getByRole('button', { name: '发送' }) as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    fireEvent.click(button);

    expect(sendMessage).toHaveBeenCalledTimes(1);
    const [content] = sendMessage.mock.calls[0] as [string];
    expect(content).toContain('<selected-text-context>');
    expect(content).toContain('这是需要追问的原文片段');
    window.history.replaceState({}, '', '/chat');
  });
});

describe('ChatComposer canvas mode command', () => {
  beforeEach(() => {
    useAgentStore.setState({ activeConversationId: 'conversation-1', messages: [] });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('exits canvas mode from the slash menu when the canvas sub-site is in canvas mode', async () => {
    const onExit = vi.fn();
    const onEnter = vi.fn();
    render(<ChatComposer canvasModeCommand={{ active: true, restorable: true, onExit, onEnter }} />);

    fireEvent.change(screen.getByPlaceholderText(/发消息或创建任务/), { target: { value: '/' } });
    fireEvent.click(await screen.findByRole('option', { name: /退出画布模式/ }));

    expect(onExit).toHaveBeenCalledTimes(1);
    expect(onEnter).not.toHaveBeenCalled();
  });

  it('re-enters canvas mode from the slash menu after the user detached', async () => {
    const onExit = vi.fn();
    const onEnter = vi.fn();
    render(<ChatComposer canvasModeCommand={{ active: false, restorable: true, onExit, onEnter }} />);

    fireEvent.change(screen.getByPlaceholderText(/发消息或创建任务/), { target: { value: '/' } });
    fireEvent.click(await screen.findByRole('option', { name: /进入画布模式/ }));

    expect(onEnter).toHaveBeenCalledTimes(1);
    expect(onExit).not.toHaveBeenCalled();
  });

  it('hides the canvas mode command outside the canvas sub-site', async () => {
    render(<ChatComposer />);

    fireEvent.change(screen.getByPlaceholderText(/发消息或创建任务/), { target: { value: '/' } });

    expect(screen.queryByRole('option', { name: /画布模式/ })).toBeNull();
  });
});

describe('ChatComposer compact mode', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
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
  });

  it('keeps only the input line and the send button while the thread is scrolled up', () => {
    const { container } = render(<ChatComposer compact />);

    expect(container.querySelectorAll('.chat-composer-extra--inline.is-collapsed')).toHaveLength(2);
    expect(screen.getByPlaceholderText(/发消息或创建任务/).getAttribute('rows')).toBe('1');
    expect(screen.getByRole('button', { name: '发送' })).toBeTruthy();
  });

  it('restores the full composer as soon as the input is focused', () => {
    const { container } = render(<ChatComposer compact />);

    fireEvent.focus(screen.getByPlaceholderText(/发消息或创建任务/));

    expect(container.querySelectorAll('.chat-composer-extra--inline.is-collapsed')).toHaveLength(0);
    expect(screen.getByPlaceholderText(/发消息或创建任务/).getAttribute('rows')).toBe('2');
  });

  it('marks the attachment block collapsed after an asset drop so it stops shoving the input aside', async () => {
    // 收缩态是 flex-direction: row，附加行会变成输入框的横向兄弟。
    // 它必须同时带上 is-collapsed，CSS 才会把宽度也收掉（只收高度的话仍按内容宽占位）。
    const { container } = render(<ChatComposer compact />);

    fireEvent.drop(document.querySelector('.chat-composer-card')!, {
      dataTransfer: {
        types: [AGENT_ASSET_DRAG_MIME],
        getData: (type: string) => type === AGENT_ASSET_DRAG_MIME
          ? serializeAgentAssetDragPayload({
              url: '/api/files/images/generated.png',
              name: 'generated.png',
              kind: 'image',
            })
          : '',
        files: [],
      },
    });

    await waitFor(() => {
      expect(useAgentStore.getState().refImageUrls).toHaveLength(1);
    });

    const card = container.querySelector('.chat-composer-card')!;
    expect(card.className).toContain('is-collapsed');
    expect(card.querySelector('.chat-composer-extra')?.className).toContain('is-collapsed');
  });

  it('expands a collapsed composer when an asset drag arrives from outside', () => {
    // 从生成中心往输入框拖素材时，页面按「指针是否越过侧边栏」把 forceExpanded 打开，
    // 收缩态必须让位，否则 380px 侧边栏旁只剩一条细输入框，图没有落点。
    const { container } = render(<ChatComposer compact forceExpanded />);

    expect(container.querySelector('.chat-composer-extra--inline.is-collapsed')).toBeNull();
    expect(screen.getByPlaceholderText(/发消息或创建任务/).getAttribute('rows')).toBe('2');
  });

  it('stays expanded when the thread is at the bottom', () => {
    const { container } = render(<ChatComposer />);

    expect(container.querySelector('.chat-composer-extra--inline.is-collapsed')).toBeNull();
  });

  it('narrows the input card horizontally while collapsed', () => {
    const collapsed = render(<ChatComposer compact />);
    const collapsedCard = collapsed.container.querySelector<HTMLElement>('.chat-composer-card');
    expect(collapsedCard?.style.maxWidth).toBe('480px');
    cleanup();

    const expanded = render(<ChatComposer />);
    const expandedCard = expanded.container.querySelector<HTMLElement>('.chat-composer-card');
    expect(expandedCard?.style.maxWidth).toBe('780px');
  });

  it('reports its collapsed state so outside controls can follow the card edge', () => {
    const onCollapsedChange = vi.fn();
    render(<ChatComposer compact onCollapsedChange={onCollapsedChange} />);

    expect(onCollapsedChange).toHaveBeenLastCalledWith(true);

    fireEvent.focus(screen.getByPlaceholderText(/发消息或创建任务/));

    expect(onCollapsedChange).toHaveBeenLastCalledWith(false);
  });

  it('collapses the footer status bar slot so the input card drops into its place', () => {
    const { container } = render(<ChatComposer compact />);
    const slot = container.querySelector('.chat-status-bar-slot');

    expect(slot?.classList.contains('is-collapsed')).toBe(true);
    expect(slot?.hasAttribute('inert')).toBe(true);

    fireEvent.focus(screen.getByPlaceholderText(/发消息或创建任务/));

    expect(container.querySelector('.chat-status-bar-slot')?.classList.contains('is-collapsed'))
      .toBe(false);
    expect(container.querySelector('.chat-status-bar-slot')?.hasAttribute('inert')).toBe(false);
  });

  it('keeps the footer status bar slot open when the thread is at the bottom', () => {
    const { container } = render(<ChatComposer />);

    expect(container.querySelector('.chat-status-bar-slot')?.classList.contains('is-collapsed'))
      .toBe(false);
  });

  it('puts the input and the send button on the same row while collapsed', () => {
    const { container } = render(<ChatComposer compact />);
    const card = container.querySelector<HTMLElement>('.chat-composer-card');
    const input = screen.getByPlaceholderText(/发消息或创建任务/);
    const send = screen.getByRole('button', { name: '发送' });

    expect(card?.style.flexDirection).toBe('row');
    expect(card?.style.background).toContain('rgba(255, 255, 255, 0.62)');
    expect(card?.style.backdropFilter).toContain('blur');
    // 输入框外面包了一层 `.chat-composer-input`（承载 `@` 标记的镜像高亮层），
    // 这一层才是卡片这一行里的兄弟节点；收缩态的可伸缩性挂在这一层上。
    const inputRow = input.parentElement as HTMLElement;
    expect(inputRow.classList.contains('chat-composer-input')).toBe(true);
    expect(inputRow.parentElement).toBe(card);
    expect(card?.contains(send)).toBe(true);
    expect(inputRow.nextElementSibling).toBe(send.parentElement?.parentElement);
    expect(inputRow.style.flex).toContain('1');
    expect(input.style.flex).toContain('1');
  });
});

describe('ChatComposer 窄栏底部行（画布 /canvas 内嵌小窗）', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
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
    vi.unstubAllGlobals();
  });

  const stubCardWidth = (width: number) => {
    vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(width);
  };

  it('窄于阈值时发送收成向上箭头图标按钮，文字不再折行', () => {
    stubCardWidth(330);
    render(<ChatComposer />);

    const send = screen.getByRole('button', { name: '发送' });
    expect(send.textContent).toBe('');
    expect(send.getAttribute('title')).toBe('发送');
    expect(send.querySelector('svg')).toBeTruthy();
  });

  it('够宽时发送仍是文字按钮', () => {
    stubCardWidth(760);
    render(<ChatComposer />);

    expect(screen.getByRole('button', { name: '发送' }).textContent).toBe('发送');
  });

  it('窄栏下生成偏好入口显示「自定」', () => {
    stubCardWidth(330);
    useAgentStore.setState({
      generationPreferences: { ...defaultGenerationPreferences(), auto: false },
    });
    render(<ChatComposer />);

    const trigger = screen.getByRole('button', { name: '生成偏好：自定义' });
    expect(trigger.textContent).toBe('自定');
  });
});

describe('ChatComposer 输入框上方只保留还没发出去的素材', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      streaming: false,
      waitingHumanInput: null,
      waitingVideoPlan: null,
      canvasBinding: null,
      canvasExitPending: false,
      refImageUrls: ['/api/files/images/a.png', '/api/files/images/b.png'],
      pendingAttachments: [],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
    });
    useProjectStore.setState({ currentProject: { id: 'p1', name: '测试项目' } as never });
    vi.spyOn(agentApi, 'listConversationAssetGroups').mockResolvedValue({
      data: { code: 0, message: '', data: [] },
    } as never);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    useAgentStore.getState().newConversation();
  });

  it('发送后输入框上方不再有旧素材，打字也不会让它回来', () => {
    render(<ChatComposer />);
    expect(screen.getByRole('button', { name: '预览参考图 1：图 1' })).toBeTruthy();

    // 本轮发送成功后由 store 清空输入框素材（clearPendingAttachments）
    act(() => {
      useAgentStore.getState().clearPendingAttachments();
    });

    expect(document.querySelector('.chat-reference-preview')).toBeNull();
    expect(useAgentStore.getState().refImageUrls).toEqual([]);

    // 继续写新指令：旧素材不该再出现（否则看起来像又被带了一遍）
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '再改一版' } });
    expect(screen.queryByRole('button', { name: '预览参考图 1：图 1' })).toBeNull();
    expect(document.querySelector('.chat-reference-preview')).toBeNull();
  });

  it('之后再添素材只展示新素材', () => {
    render(<ChatComposer />);
    act(() => {
      useAgentStore.getState().clearPendingAttachments();
    });

    act(() => {
      useAgentStore.getState().addRefImageUrls(['/api/files/images/c.png']);
    });

    const previews = screen.getAllByRole('button', { name: /预览参考图/ });
    expect(previews).toHaveLength(1);
    expect(previews[0].querySelector('img')?.getAttribute('src')).toContain('c.png');
  });
});


describe('ChatComposer 提示词回填', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      composerInjection: null,
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
    useAgentStore.setState({ composerInjection: null });
  });

  it('把素材详情送来的提示词填进空输入框', () => {
    render(<ChatComposer />);

    act(() => {
      useAgentStore.getState().injectComposerText('精细国风东方龙图案');
    });

    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('精细国风东方龙图案');
  });

  it('已经写过的内容不会被提示词覆盖，只换行追加', () => {
    render(<ChatComposer />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '帮我把这张图改成夜景' } });

    act(() => {
      useAgentStore.getState().injectComposerText('精细国风东方龙图案');
    });

    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value)
      .toBe('帮我把这张图改成夜景\n精细国风东方龙图案');
  });

  it('同一次注入不会被重复应用（重渲染不会把用户改掉的内容冲回去）', () => {
    render(<ChatComposer />);

    act(() => {
      useAgentStore.getState().injectComposerText('精细国风东方龙图案');
    });
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '我自己改过的内容' } });

    // 强制重渲染：seq 没变，输入框不该再动它一次。
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '我自己改过的内容' } });
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('我自己改过的内容');
  });

  it('第二次注入（seq 前进）才再次生效', () => {
    render(<ChatComposer />);

    act(() => {
      useAgentStore.getState().injectComposerText('第一段');
    });
    act(() => {
      useAgentStore.getState().injectComposerText('第二段');
    });

    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('第一段\n第二段');
  });

  it('空白提示词不产生注入', () => {
    render(<ChatComposer />);

    act(() => {
      useAgentStore.getState().injectComposerText('   ');
    });

    expect(useAgentStore.getState().composerInjection).toBeNull();
  });
});

describe('ChatComposer @ 引用资料', () => {
  beforeEach(() => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      refImageUrls: [],
      pendingAttachments: [],
      pendingReferences: [],
      conversations: [
        {
          id: 'conversation-1', userId: 'user-1', projectId: 'p1', title: '当前对话',
          status: 'active', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
        },
        {
          id: 'conversation-2', userId: 'user-1', projectId: 'p1', title: '分镜重做',
          status: 'active', messageCount: 23, createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
        {
          id: 'conversation-9', userId: 'user-1', projectId: 'p1', title: '别的项目',
          status: 'archived', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
        },
      ],
    });
    useProjectStore.setState({
      // 与 mockChatLibrary 的 ownerProjectId 对齐，避免选择器同时拉 PROJECT/GLOBAL 两份导致同名重复
      currentProject: { id: 'p1', name: '测试项目' } as never,
      scenes: [
        { id: 'scene-3', sceneNumber: 3, scriptContent: '雨夜街头，霓虹倒影' },
        { id: 'scene-4', sceneNumber: 4, scriptContent: '天台追逐' },
      ] as never,
    });
    vi.spyOn(agentApi, 'listConversationAssetGroups').mockResolvedValue({
      data: { code: 0, message: '', data: [] },
    } as never);
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  const typeMention = () => {
    const textarea = screen.getByRole('textbox') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: '@' } });
    return textarea;
  };

  it('输入 @ 列出四类来源，选中分镜后插入蓝色标记并登记引用', async () => {
    render(<ChatComposer />);
    const textarea = typeMention();

    const menu = await screen.findByRole('listbox', { name: '引用资料' });
    expect(menu.textContent).toContain('项目库文件');
    expect(menu.textContent).toContain('本地文件');
    expect(menu.textContent).toContain('分镜 3');
    expect(menu.textContent).toContain('对话《分镜重做》');

    fireEvent.click(screen.getByRole('option', { name: /分镜 3/ }));

    expect(textarea.value).toBe('@分镜3 ');
    expect(useAgentStore.getState().pendingReferences).toEqual([
      expect.objectContaining({ kind: 'scene', id: 'scene-3', token: '@分镜3', sceneNumber: 3 }),
    ]);
    // 镜像高亮层把标记渲染成品牌色片段
    const highlighted = document.querySelectorAll('[data-mention="true"]');
    expect(highlighted).toHaveLength(1);
    expect(highlighted[0].textContent).toBe('@分镜3');
    // 标记是原子的：镜像层里普通文本仍逐字保留
    expect(document.querySelector('.chat-composer-highlight')?.textContent).toContain('@分镜3');
  });

  it('退格一次删掉整个标记，并撤销这条引用', async () => {
    render(<ChatComposer />);
    const textarea = typeMention();
    fireEvent.click(await screen.findByRole('option', { name: /分镜 3/ }));

    fireEvent.change(textarea, { target: { value: '看 @分镜3 的效果' } });
    textarea.setSelectionRange(7, 7);
    fireEvent.keyDown(textarea, { key: 'Backspace' });

    expect(textarea.value).toBe('看 的效果');
    expect(useAgentStore.getState().pendingReferences).toEqual([]);
  });

  it('送出的引用清单只包含正文里还在的标记', async () => {
    const sendMessage = vi.fn();
    useAgentStore.setState({ sendMessage: sendMessage as never });
    render(<ChatComposer />);
    const textarea = typeMention();
    fireEvent.click(await screen.findByRole('option', { name: /分镜 3/ }));
    fireEvent.click(screen.getByRole('option', { name: /分镜 4/ }));

    // 用户把分镜 4 的标记删了：这条引用不该跟着发出去
    fireEvent.change(textarea, { target: { value: '把 @分镜3 的镜头拉近一点' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(sendMessage).toHaveBeenCalledTimes(1);
    const [content, options] = sendMessage.mock.calls[0] as [string, { references?: unknown[] }];
    expect(content).toContain('@分镜3');
    expect(options.references).toEqual([
      expect.objectContaining({ kind: 'scene', id: 'scene-3' }),
    ]);
  });

  it('引用对话暂时置灰（会话摘要未上线），点击不登记引用', async () => {
    render(<ChatComposer />);
    typeMention();

    const option = await screen.findByRole('option', { name: /对话《分镜重做》/ });
    expect((option as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(option);
    expect(useAgentStore.getState().pendingReferences).toEqual([]);
  });

  it('＋ → 上传分镜 也只插入标记，不再立即发送', async () => {
    const sendMessage = vi.fn();
    useAgentStore.setState({ sendMessage: sendMessage as never });
    render(<ChatComposer />);

    fireEvent.click(screen.getByRole('button', { name: '上传' }));
    fireEvent.click(await screen.findByText('上传分镜'));

    // 分镜选择器：第一个 checkbox 是「全选」，随后每个分镜一个
    const checkboxes = await screen.findAllByRole('checkbox');
    fireEvent.click(checkboxes[1]);
    fireEvent.click(screen.getByRole('button', { name: /进入分镜审核/ }));

    expect(sendMessage).not.toHaveBeenCalled();
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('@分镜3 ');
    expect(useAgentStore.getState().pendingReferences).toEqual([
      expect.objectContaining({ kind: 'scene', id: 'scene-3' }),
    ]);
  });

  it('内容里没有标记时目录不再残留（额度不被幽灵引用占满）', async () => {    render(<ChatComposer />);
    const textarea = typeMention();
    fireEvent.click(await screen.findByRole('option', { name: /分镜 3/ }));
    expect(useAgentStore.getState().pendingReferences).toHaveLength(1);

    // 全选删除后再插一条：目录里只剩新的这一条
    fireEvent.change(textarea, { target: { value: '@' } });
    await act(async () => { /* 等一次渲染，避免点到上一帧的旧节点 */ });
    fireEvent.click(screen.getByRole('option', { name: /分镜 4/ }));

    const references = useAgentStore.getState().pendingReferences;
    expect(references).toHaveLength(1);
    expect(references[0]).toMatchObject({ id: 'scene-4', token: '@分镜4' });
  });

  it('从 @ 菜单选项目库文件：既挂素材也插标记，引用带 mediaId（后端据此转存会话资产）', async () => {
    mockChatLibrary();
    render(<ChatComposer />);
    const textarea = typeMention();

    fireEvent.click(await screen.findByRole('option', { name: /项目库文件/ }));
    // 选择器按项目/全局两个 scope 各渲染一次同一份 mock 数据，取第一条即可
    fireEvent.click((await screen.findAllByText('a.png'))[0]);
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    await waitFor(() => expect(useAgentStore.getState().pendingReferences).toHaveLength(1));
    expect(useAgentStore.getState().pendingReferences[0]).toMatchObject({
      kind: 'library_file',
      name: 'a.png',
      mediaId: 'm1',
    });
    expect(textarea.value).toBe('@a.png ');
    // 素材照旧挂上：图片作为本轮参考媒体走既有附件通路
    expect(useAgentStore.getState().pendingAttachments).toHaveLength(1);
  });

  it('从 ＋ 菜单选项目库素材：只挂素材、不插标记（老行为保持）', async () => {
    mockChatLibrary();
    render(<ChatComposer />);

    fireEvent.click(screen.getByLabelText('上传'));
    fireEvent.click(screen.getByText('上传项目库素材'));
    fireEvent.click((await screen.findAllByText('a.png'))[0]);
    fireEvent.click(screen.getByRole('button', { name: '确认选择' }));

    await waitFor(() => expect(useAgentStore.getState().pendingAttachments).toHaveLength(1));
    expect(useAgentStore.getState().pendingReferences).toEqual([]);
    expect((screen.getByRole('textbox') as HTMLTextAreaElement).value).toBe('');
  });
});
