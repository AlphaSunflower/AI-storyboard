import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
    // 输入框与「发送」所在的底部行是卡片这一行里的兄弟节点
    expect(input.parentElement).toBe(card);
    expect(card?.contains(send)).toBe(true);
    expect(input.nextElementSibling).toBe(send.parentElement?.parentElement);
    expect(input.style.flex).toContain('1');
  });
});
