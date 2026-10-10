import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_AGENT_APPROVAL_MODE,
  agentApi,
  type AgentConversation,
  type AgentTaskView,
} from '../api/agent';
import {
  AgentTurnEventSequencer,
  applyAgentTurnEnvelope,
  taskStatusLabel,
  useAgentStore,
} from './agentStore';
import { useProjectStore } from './projectStore';
import { setAccessToken } from '../lib/authSession';

beforeEach(() => {
  setAccessToken('test-token');
});

function sseEnvelope(
  seq: number,
  type: 'turn_started' | 'agent_event' | 'turn_paused' | 'canvas_session_required'
    | 'canvas_run_started' | 'canvas_run_updated' | 'canvas_run_completed' | 'canvas_run_failed'
    | 'turn_completed' | 'turn_failed',
  payload: Record<string, unknown>,
  turnId = 'turn-1',
) {
  return { turnId, seq, type, payload };
}

let testTurnSequence = 0;

function sseBody(events: Array<ReturnType<typeof sseEnvelope>>) {
  const generatedTurnId = `turn-test-${++testTurnSequence}`;
  return events
    .map((event) => event.turnId === 'turn-1' ? { ...event, turnId: generatedTurnId } : event)
    .map((event) => [
      `event: ${event.type}`,
      `data: ${JSON.stringify(event)}`,
      '',
      '',
    ].join('\n'))
    .join('');
}

describe('agentStore createConversation', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useAgentStore.setState({
      activeConversationId: null,
      conversations: [],
      selectConversation: useAgentStore.getInitialState().selectConversation,
    });
    useProjectStore.setState({ currentProject: null });
  });

  it('does not default an omitted orchestration mode to LEGACY', async () => {
    const conversation: AgentConversation = {
      id: 'conversation-1',
      userId: 'user-1',
      projectId: 'project-1',
      title: '新对话',
      status: 'active',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
    });
    const createConversation = vi.spyOn(agentApi, 'createConversation')
      .mockResolvedValue({ data: { data: conversation } } as never);
    const selectConversation = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({ selectConversation });

    await useAgentStore.getState().createConversation();

    expect(createConversation).toHaveBeenCalledWith(
      'project-1',
      '新对话',
      DEFAULT_AGENT_APPROVAL_MODE,
    );
    expect(selectConversation).toHaveBeenCalledWith('conversation-1');
  });

  it('creates the first conversation before sending the first message', async () => {
    const conversation: AgentConversation = {
      id: 'conversation-first',
      userId: 'user-1',
      projectId: 'project-1',
      title: '新对话',
      status: 'active',
      approvalMode: DEFAULT_AGENT_APPROVAL_MODE,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
    });
    const createConversation = vi.spyOn(agentApi, 'createConversation')
      .mockResolvedValue({ data: { data: conversation } } as never);
    const selectConversation = vi.fn(async (id: string) => {
      useAgentStore.setState({ activeConversationId: id });
    });
    useAgentStore.setState({
      activeConversationId: null,
      conversations: [],
      messages: [],
      streaming: false,
      generating: {},
      streamError: null,
      selectConversation,
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: conversation.id }),
      sseEnvelope(2, 'turn_completed', { content: '收到' }),
    ]), {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await useAgentStore.getState().sendMessage('第一条消息');

    expect(createConversation).toHaveBeenCalledWith(
      'project-1',
      '新对话',
      DEFAULT_AGENT_APPROVAL_MODE,
    );
    expect(selectConversation).toHaveBeenCalledWith(conversation.id);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(useAgentStore.getState().streamError).toBeNull();
  });
});

describe('agentStore side chat', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useAgentStore.setState({
      sideChat: {
        open: false,
        loading: false,
        focusToken: 0,
        sourceConversationId: null,
        sourceTitle: '',
        projectId: null,
        conversationId: null,
        error: null,
      },
    });
  });

  it('creates an in-memory temporary branch from the active conversation', async () => {
    const sideConversation: AgentConversation = {
      id: 'side-1',
      userId: 'user-1',
      projectId: 'project-1',
      title: '侧边 · 主对话',
      status: 'temporary',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    const createSideChat = vi.spyOn(agentApi, 'createSideChat')
      .mockResolvedValue({ data: { data: sideConversation } } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      sideChat: {
        open: false,
        loading: false,
        focusToken: 0,
        sourceConversationId: null,
        sourceTitle: '',
        projectId: null,
        conversationId: null,
        error: null,
      },
    });

    await useAgentStore.getState().openSideChat('conversation-1', '主对话');

    expect(createSideChat).toHaveBeenCalledWith('conversation-1');
    expect(useAgentStore.getState().sideChat).toMatchObject({
      open: true,
      loading: false,
      sourceConversationId: 'conversation-1',
      sourceTitle: '主对话',
      projectId: 'project-1',
      conversationId: 'side-1',
      error: null,
    });
  });

  it('retries after temporary-branch creation failed', async () => {
    const sideConversation: AgentConversation = {
      id: 'side-retry',
      userId: 'user-1',
      projectId: 'project-1',
      title: '侧边 · 主对话',
      status: 'temporary',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    const createSideChat = vi.spyOn(agentApi, 'createSideChat')
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ data: { data: sideConversation } } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      conversations: [],
      sideChat: {
        open: false,
        loading: false,
        focusToken: 0,
        sourceConversationId: null,
        sourceTitle: '',
        projectId: null,
        conversationId: null,
        error: null,
      },
    });

    await useAgentStore.getState().openSideChat('conversation-1', '主对话');
    expect(useAgentStore.getState().sideChat.error).toBe('侧边创建失败，请重试');
    await useAgentStore.getState().openSideChat('conversation-1', '主对话');

    expect(createSideChat).toHaveBeenCalledTimes(2);
    expect(useAgentStore.getState().sideChat.conversationId).toBe('side-retry');
    expect(useAgentStore.getState().sideChat.error).toBeNull();
  });

  it('discards a temporary branch created after the panel was closed', async () => {
    const sideConversation: AgentConversation = {
      id: 'side-late',
      userId: 'user-1',
      projectId: 'project-1',
      title: '侧边 · 主对话',
      status: 'temporary',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    let resolveCreate!: (value: unknown) => void;
    const createSideChat = vi.spyOn(agentApi, 'createSideChat')
      .mockReturnValue(new Promise((resolve) => { resolveCreate = resolve; }) as never);
    const closeSideChat = vi.spyOn(agentApi, 'closeSideChat')
      .mockResolvedValue(undefined as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      conversations: [],
      sideChat: {
        open: false,
        loading: false,
        focusToken: 0,
        sourceConversationId: null,
        sourceTitle: '',
        projectId: null,
        conversationId: null,
        error: null,
      },
    });

    const opening = useAgentStore.getState().openSideChat('conversation-1', '主对话');
    await useAgentStore.getState().closeSideChat();
    resolveCreate({ data: { data: sideConversation } });
    await opening;

    expect(createSideChat).toHaveBeenCalledTimes(1);
    expect(closeSideChat).toHaveBeenCalledWith('side-late');
    expect(useAgentStore.getState().sideChat.open).toBe(false);
    expect(useAgentStore.getState().sideChat.conversationId).toBeNull();
  });
});

describe('agentStore approval mode', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useAgentStore.setState({
      activeConversationId: null,
      conversations: [],
      pendingApprovalMode: DEFAULT_AGENT_APPROVAL_MODE,
      streaming: false,
    });
  });

  it('updates the active conversation approval mode', async () => {
    const conversation: AgentConversation = {
      id: 'conversation-1',
      userId: 'user-1',
      projectId: 'project-1',
      title: '方案会话',
      status: 'active',
      approvalMode: 'PLAN_APPROVAL',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    useAgentStore.setState({
      activeConversationId: conversation.id,
      conversations: [conversation],
      pendingApprovalMode: 'PLAN_APPROVAL',
    });
    const update = vi.spyOn(agentApi, 'updateApprovalMode').mockResolvedValue({
      data: { data: { ...conversation, approvalMode: 'REQUEST_APPROVAL' } },
    } as never);

    await useAgentStore.getState().setApprovalMode('REQUEST_APPROVAL');

    expect(update).toHaveBeenCalledWith('conversation-1', 'REQUEST_APPROVAL');
    expect(useAgentStore.getState().pendingApprovalMode).toBe('REQUEST_APPROVAL');
    expect(useAgentStore.getState().conversations[0]?.approvalMode).toBe('REQUEST_APPROVAL');
  });

  it('requires explicit confirmation before enabling full access', async () => {
    const conversation: AgentConversation = {
      id: 'conversation-1',
      userId: 'user-1',
      projectId: 'project-1',
      title: '审批会话',
      status: 'active',
      approvalMode: 'PLAN_APPROVAL',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    useAgentStore.setState({
      activeConversationId: conversation.id,
      conversations: [conversation],
      pendingApprovalMode: 'PLAN_APPROVAL',
    });
    const update = vi.spyOn(agentApi, 'updateApprovalMode').mockResolvedValue({
      data: { data: { ...conversation, approvalMode: 'FULL_ACCESS' } },
    } as never);

    await useAgentStore.getState().setApprovalMode('FULL_ACCESS');

    expect(update).not.toHaveBeenCalled();
    expect(useAgentStore.getState().pendingApprovalMode).toBe('PLAN_APPROVAL');

    await useAgentStore.getState().setApprovalMode('FULL_ACCESS', {
      confirmedFullAccess: true,
    });

    expect(update).toHaveBeenCalledWith('conversation-1', 'FULL_ACCESS');
    expect(useAgentStore.getState().pendingApprovalMode).toBe('FULL_ACCESS');
  });

  it('does not switch while a turn is running', async () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      pendingApprovalMode: 'PLAN_APPROVAL',
      streaming: true,
    });
    const update = vi.spyOn(agentApi, 'updateApprovalMode');

    await useAgentStore.getState().setApprovalMode('REQUEST_APPROVAL');

    expect(update).not.toHaveBeenCalled();
    expect(useAgentStore.getState().pendingApprovalMode).toBe('PLAN_APPROVAL');
  });
});

describe('agentStore background tasks', () => {
  const task = (overrides: Partial<AgentTaskView> = {}): AgentTaskView => ({
    taskId: 'task-1',
    conversationId: 'conversation-1',
    projectId: 'project-1',
    title: '剧情与分镜诊断',
    toolName: 'start_storyboard_production',
    kind: 'long-tool',
    status: 'running',
    stage: 'plot_diagnosis',
    progress: 20,
    resultSummary: '',
    error: '',
    parentTaskId: '',
    anchorMessageId: 'message-1',
    version: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    assetIds: [],
    resultUrls: [],
    slots: [],
    result: '',
    liveText: '',
    liveThinking: '',
    liveStage: '',
    canCancel: true,
    canRetry: false,
    canFollowUp: true,
    ...overrides,
  });

  afterEach(() => {
    useAgentStore.setState({
      tasks: {},
      taskNotices: {},
      taskUnread: {},
      activeConversationId: null,
      sendMessage: useAgentStore.getInitialState().sendMessage,
    });
  });

  it('ignores stale snapshots and creates one completion notice', () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    useAgentStore.getState().upsertTask(task({ version: 2 }));
    useAgentStore.getState().upsertTask(task({ version: 1, status: 'queued' }));
    expect(useAgentStore.getState().tasks['task-1'].version).toBe(2);

    useAgentStore.getState().upsertTask(task({ version: 3, status: 'completed', stage: 'completed' }));
    useAgentStore.getState().upsertTask(task({ version: 3, status: 'completed', stage: 'completed' }));

    expect(useAgentStore.getState().taskNotices['conversation-1']).toHaveLength(1);
  });

  it('does not announce a waiting plot review as a completed task', () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    useAgentStore.getState().upsertTask(task({ version: 1 }));
    useAgentStore.getState().upsertTask(task({
      version: 2,
      status: 'completed',
      stage: 'waiting_review',
      resultSummary: '分镜诊断完成 runid: b66b3892b3ff43d892e2e781bac2a7e4 当前阶段: PLOT_REVIEW',
    }));

    expect(useAgentStore.getState().taskNotices['conversation-1']).toBeUndefined();
  });

  it('refreshes messages when a long task settles without a wakeup event', async () => {
    vi.useFakeTimers();
    const listMessages = vi.spyOn(agentApi, 'listMessagesPage')
      .mockResolvedValue({
        data: {
          data: {
            records: [{
              id: 'm1',
              conversationId: 'conversation-1',
              role: 'assistant',
              content: 'ok',
              createdAt: new Date().toISOString(),
            }],
            nextCursor: null,
            hasMore: false,
          },
        },
      } as never);
    useAgentStore.setState({ activeConversationId: 'conversation-1', streaming: false });

    useAgentStore.getState().upsertTask(task({ version: 1 }));
    useAgentStore.getState().upsertTask(task({
      version: 2,
      status: 'completed',
      stage: 'waiting_review',
    }));
    await vi.runOnlyPendingTimersAsync();

    expect(listMessages).toHaveBeenCalledWith('conversation-1', undefined);
    listMessages.mockRestore();
    vi.useRealTimers();
  });

  it('does not overwrite in-flight streaming messages when a task settles mid-turn', async () => {
    vi.useFakeTimers();
    const listMessages = vi.spyOn(agentApi, 'listMessagesPage')
      .mockResolvedValue({ data: { data: { records: [], nextCursor: null, hasMore: false } } } as never);
    useAgentStore.setState({ activeConversationId: 'conversation-1', streaming: true });

    useAgentStore.getState().upsertTask(task({ version: 1 }));
    useAgentStore.getState().upsertTask(task({
      version: 2,
      status: 'completed',
      stage: 'waiting_review',
    }));
    await vi.runOnlyPendingTimersAsync();

    expect(listMessages).not.toHaveBeenCalled();
    listMessages.mockRestore();
    vi.useRealTimers();
  });

  it('stores completion as unread when another conversation is active', () => {
    useAgentStore.setState({ activeConversationId: 'conversation-2' });
    useAgentStore.getState().upsertTask(task({ version: 1 }));
    useAgentStore.getState().upsertTask(task({ version: 2, status: 'completed' }));

    expect(useAgentStore.getState().taskUnread['conversation-1']).toBe(1);
  });

  it('notifies a terminal storyboard task discovered by fallback polling', () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    useAgentStore.getState().upsertTask(task({
      version: 2,
      status: 'completed',
      result: '- run_id: `6efa645a-724d-4459-be90-35aac504e323`',
    }), { notifyIfTerminal: true });

    expect(useAgentStore.getState().taskNotices['conversation-1']).toHaveLength(1);
    expect(useAgentStore.getState().taskNotices['conversation-1'][0].storyboardRunId)
      .toBe('6efa645a-724d-4459-be90-35aac504e323');
  });

  it('does not auto-route a completed storyboard task into the legacy review flow', async () => {
    vi.useFakeTimers();
    const sendMessage = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      sendMessage,
      streaming: false,
      waitingHumanInput: null,
    });

    useAgentStore.getState().upsertTask(task({
      version: 2,
      status: 'completed',
      stage: 'completed',
      result: '- run_id: `6efa645a-724d-4459-be90-35aac504e323`',
    }));
    await vi.runAllTimersAsync();

    expect(sendMessage).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it('labels partial image batches and keeps slot progress in the task view', () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1' });
    const partial = task({
      status: 'partial',
      version: 2,
      resultUrls: ['/one.png'],
      slots: [{
        index: 0,
        label: '第一张',
        status: 'completed',
        prompt: '第一张',
        url: '/one.png',
        assetId: 'asset-1',
        error: '',
        completedAt: new Date().toISOString(),
      }, {
        index: 1,
        label: '第二张',
        status: 'failed',
        prompt: '第二张',
        url: '',
        assetId: '',
        error: '超时',
        completedAt: new Date().toISOString(),
      }],
    });

    useAgentStore.getState().upsertTask(partial);

    expect(taskStatusLabel(partial)).toBe('部分完成');
    expect(useAgentStore.getState().tasks['task-1'].slots[1].error).toBe('超时');
  });

  it('appends a terminal batch result to its assistant message once', () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      tasks: { 'task-1': task({ version: 1 }) },
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '批量生图任务已提交。',
        createdAt: new Date().toISOString(),
        metadata: {
          kind: 'assistant-reply',
          taskCards: [{ taskId: 'task-1', title: '批量生成 2 张图片', kind: 'image' }],
        },
      }],
    });
    const completed = task({
      version: 2,
      status: 'completed',
      result: '![生成图片](/one.png)\n\n已生成 1 张图片',
    });

    useAgentStore.getState().upsertTask(completed);
    useAgentStore.getState().upsertTask(completed);
    useAgentStore.getState().upsertTask({
      ...completed,
      version: 3,
      result: '![生成图片](/one.png)\n\n![生成图片](/two.png)\n\n已生成 2 张图片',
    });

    const content = useAgentStore.getState().messages[0].content;
    expect(content).toContain('![生成图片](/one.png)');
    expect(content).toContain('![生成图片](/two.png)');
    expect(content.split('![生成图片](/one.png)').length - 1).toBe(1);
    expect(content.split('![生成图片]').length - 1).toBe(2);
  });
});

describe('agentStore message metrics', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    useAgentStore.setState({
      activeConversationId: null,
      messages: [],
      streaming: false,
      generating: {},
      pendingAssistantId: null,
      pendingAttachments: [],
      refImageUrls: [],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
    });
    useProjectStore.setState({ currentProject: null });
  });

  it('merges terminal metrics from message_end into the assistant message', async () => {
    const metrics = {
      inputTokens: 120,
      outputTokens: 30,
      cachedInputTokens: 20,
      totalTokens: 150,
      durationMs: 880,
      modelCalls: 1,
      complete: true,
    };
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: '完成' },
      }),
      sseEnvelope(3, 'turn_completed', { content: '完成', metadata: { metrics } }),
    ]);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
    });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      streaming: false,
      generating: {},
      streamError: null,
      waitingHumanInput: null,
      waitingVideoPlan: null,
      canvasBinding: null,
      canvasExitPending: false,
    });

    await useAgentStore.getState().sendMessage('测试指标');

    expect(useAgentStore.getState().messages.at(-1)?.metadata?.metrics).toEqual(metrics);
  });

  it('takes backend context occupancy from MODEL_CALL_END without summing it', () => {
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      generating: { 'conversation-1': { hint: '', assistantId: 'assistant-1' } },
      streamError: null,
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'agent_event', {
        eventType: 'MODEL_CALL_END',
        data: {
          usage: {
            inputTokens: 900,
            outputTokens: 100,
            cachedTokens: 500,
            contextTokens: 900,
            contextWindowTokens: 128000,
          },
        },
      }),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(2, 'agent_event', {
        eventType: 'MODEL_CALL_END',
        data: {
          usage: { inputTokens: 200, outputTokens: 50, cachedTokens: 200, contextTokens: 200 },
        },
      }),
      context,
    );

    const draft = useAgentStore.getState().generating['conversation-1']?.usageDraft;
    // 计费聚合累加（900 + 200），上下文占用取最大（max(900, 200)），窗口沿用以保证不丢失
    expect(draft?.inputTokens).toBe(1100);
    expect(draft?.cachedInputTokens).toBe(700);
    expect(draft?.contextTokens).toBe(900);
    expect(draft?.contextWindowTokens).toBe(128000);
  });

  it('records the model context window from turn_started before any usage event', () => {
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      generating: {},
      streamError: null,
    });

    applyAgentTurnEnvelope(
      sseEnvelope(
        1,
        'turn_started',
        { conversationId: 'conversation-1', contextWindowTokens: 1000000 },
        'turn-start-window',
      ),
      context,
    );

    expect(useAgentStore.getState().generating['conversation-1']?.contextWindowTokens).toBe(1000000);
  });

  it('keeps attachment metadata on the optimistic user message and send request', async () => {
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'turn_completed', { content: '这是海报' }),
    ]);
    const fetchMock = vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    const attachments = [{
      assetId: 'asset-reference-1',
      url: '/api/files/images/reference.png',
      name: 'reference.png',
      mimeType: 'image/png',
      kind: 'image' as const,
      source: 'upload' as const,
    }, {
      url: '/api/files/videos/clip.mp4',
      name: 'clip.mp4',
      mimeType: 'video/mp4',
      kind: 'video' as const,
      source: 'upload' as const,
    }];
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      streaming: false,
      generating: {},
      pendingAttachments: attachments,
      refImageUrls: ['/api/files/images/reference.png'],
      pickedReferenceVideos: ['/api/files/videos/clip.mp4'],
      pickedReferenceAudios: [],
    });

    await useAgentStore.getState().sendMessage('这是什么');

    const requestBody = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(requestBody.attachments).toEqual(attachments);
    expect(useAgentStore.getState().messages.find((message) => message.role === 'user')?.metadata?.messageInput?.attachments)
      .toEqual(attachments);
  });

  it('persists the uploaded document assetId on the pending attachment', async () => {
    vi.spyOn(agentApi, 'uploadImage').mockResolvedValue({
      data: {
        data: {
          assetId: 'asset-doc-1',
          url: '/api/files/files/brief.md',
          name: 'brief.md',
          mimeType: 'text/markdown',
          sizeBytes: 42,
        },
      },
    } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      pendingAttachments: [],
    });

    await useAgentStore.getState().uploadRefImage(
      new File(['# 标题'], 'brief.md', { type: 'text/markdown' }),
      '剧本参考',
    );

    expect(useAgentStore.getState().pendingAttachments).toEqual([
      expect.objectContaining({
        assetId: 'asset-doc-1',
        url: '/api/files/files/brief.md',
        kind: 'file',
      }),
    ]);
  });
});

describe('agentStore turn replay', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useAgentStore.setState({
      activeConversationId: null,
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
    });
  });

  it('finishes generation when replay reaches turn_completed', async () => {
    vi.spyOn(agentApi, 'listTurnEvents').mockResolvedValue({
      data: {
        data: [{
          turnId: 'turn-replay-terminal',
          seq: 1,
          type: 'turn_completed',
          payload: { content: '最终回复' },
        }],
      },
    } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-replay',
      streaming: true,
      pendingAssistantId: 'assistant-replay',
      generating: {
        'conversation-replay': {
          hint: '',
          turnId: 'turn-replay-terminal',
          assistantId: 'assistant-replay',
          assistantContent: '',
          finished: false,
        },
      },
      messages: [{
        id: 'assistant-replay',
        conversationId: 'conversation-replay',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
    });

    await useAgentStore.getState().replayTurnEvents(
      'conversation-replay',
      'turn-replay-terminal',
      0,
    );

    expect(useAgentStore.getState().streaming).toBe(false);
    expect(useAgentStore.getState().pendingAssistantId).toBeNull();
    expect(useAgentStore.getState().generating['conversation-replay']).toBeUndefined();
  });
});

describe('agentStore detached async tasks', () => {
  const task = (overrides: Partial<AgentTaskView> = {}): AgentTaskView => ({
    taskId: 'task-1',
    conversationId: 'conversation-1',
    projectId: 'project-1',
    title: '剧情与分镜诊断',
    toolName: 'start_storyboard_production',
    kind: 'long-tool',
    status: 'queued',
    stage: 'accepted',
    progress: 0,
    resultSummary: '',
    error: '',
    parentTaskId: '',
    anchorMessageId: '',
    version: 1,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    assetIds: [],
    resultUrls: [],
    slots: [],
    result: '',
    liveText: '',
    liveThinking: '',
    liveStage: '',
    canCancel: true,
    canRetry: false,
    canFollowUp: false,
    ...overrides,
  });

  const resetStore = () => {
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      streaming: false,
      generating: {},
      pendingAssistantId: null,
      streamError: null,
      waitingHumanInput: null,
      waitingVideoPlan: null,
      canvasBinding: null,
      canvasExitPending: false,
      tasks: {},
      agentVideoTask: null,
    });
  };

  const taskAcceptedBody = (
    taskKind: 'long-tool' | 'image-batch' | 'video',
    content: string,
  ) => sseBody([
    sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
    sseEnvelope(2, 'agent_event', {
      eventType: 'TOOL_RESULT_TEXT_DELTA',
      data: {
        toolCallId: 'tool-1',
        toolName: 'start_task',
        delta: JSON.stringify({ taskId: 'task-1', taskKind, message: '任务已受理' }),
      },
    }),
    sseEnvelope(3, 'agent_event', {
      eventType: 'TOOL_RESULT_END',
      data: { toolCallId: 'tool-1', toolName: 'start_task', state: 'SUCCESS' },
    }),
    sseEnvelope(4, 'turn_completed', { content }),
  ]);

  afterEach(() => {
    vi.unstubAllGlobals();
    useAgentStore.getState().stopGeneration();
    useAgentStore.setState({
      activeConversationId: null,
      messages: [],
      streaming: false,
      generating: {},
      pendingAssistantId: null,
      tasks: {},
      agentVideoTask: null,
    });
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('finishes the foreground turn when a long-tool task is accepted', async () => {
    const body = taskAcceptedBody('long-tool', '任务已受理，完成后会展示结果。');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    vi.spyOn(agentApi, 'getTask').mockResolvedValue({ data: { data: task() } } as never);
    resetStore();

    await useAgentStore.getState().sendMessage('开始分镜诊断');

    expect(useAgentStore.getState().streaming).toBe(false);
    expect(useAgentStore.getState().generating['conversation-1']).toBeUndefined();
    expect(useAgentStore.getState().pendingAssistantId).toBeNull();
  });

  it('finishes the foreground turn when an image batch task is accepted', async () => {
    const body = taskAcceptedBody('image-batch', '批量生图任务已受理。');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    vi.spyOn(agentApi, 'getTask').mockResolvedValue({
      data: { data: task({ kind: 'image-batch', title: '生成图片' }) },
    } as never);
    resetStore();

    await useAgentStore.getState().sendMessage('批量生成图片');

    expect(useAgentStore.getState().streaming).toBe(false);
    expect(useAgentStore.getState().generating['conversation-1']).toBeUndefined();
    expect(useAgentStore.getState().pendingAssistantId).toBeNull();
  });

  it('releases the foreground turn when a detached task stream closes without message_end', async () => {
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'agent_event', {
        eventType: 'TOOL_RESULT_TEXT_DELTA',
        data: {
          toolCallId: 'tool-1',
          toolName: 'start_task',
          delta: JSON.stringify({ taskId: 'task-1', taskKind: 'long-tool', message: '任务已受理' }),
        },
      }),
      sseEnvelope(3, 'agent_event', {
        eventType: 'TOOL_RESULT_END',
        data: { toolCallId: 'tool-1', toolName: 'start_task', state: 'SUCCESS' },
      }),
    ]);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    vi.spyOn(agentApi, 'getTask').mockResolvedValue({ data: { data: task() } } as never);
    resetStore();

    await useAgentStore.getState().sendMessage('开始分镜诊断');

    expect(useAgentStore.getState().streaming).toBe(false);
    expect(useAgentStore.getState().generating['conversation-1']).toBeUndefined();
    expect(useAgentStore.getState().pendingAssistantId).toBeNull();
  });

  it('keeps the foreground turn active for video tasks until polling settles', async () => {
    vi.useFakeTimers();
    const body = taskAcceptedBody('video', '视频任务已受理');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    vi.spyOn(agentApi, 'interruptTurn').mockResolvedValue({} as never);
    resetStore();

    await useAgentStore.getState().sendMessage('生成视频');

    expect(useAgentStore.getState().streaming).toBe(true);
    expect(useAgentStore.getState().generating['conversation-1']).toBeDefined();
    expect(useAgentStore.getState().agentVideoTask?.taskId).toBe('task-1');
  });

  it('does not leave an empty assistant bubble when a video task stream closes early', async () => {
    vi.useFakeTimers();
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'agent_event', {
        eventType: 'TOOL_RESULT_TEXT_DELTA',
        data: {
          toolCallId: 'tool-1',
          toolName: 'generate_video',
          delta: JSON.stringify({ taskId: 'task-1', taskKind: 'video', message: '任务已受理' }),
        },
      }),
      sseEnvelope(3, 'agent_event', {
        eventType: 'TOOL_RESULT_END',
        data: { toolCallId: 'tool-1', toolName: 'generate_video', state: 'SUCCESS' },
      }),
    ]);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    resetStore();

    await useAgentStore.getState().sendMessage('生成视频');

    const assistant = [...useAgentStore.getState().messages].reverse()
      .find((message) => message.role === 'assistant');
    expect(assistant?.content).toBe('任务已受理，正在后台生成…');
    expect(assistant?.content).not.toBe('（未收到回复）');
  });

  it('removes the unfinished assistant bubble when the user stops generation', () => {
    const interruptTurn = vi.spyOn(agentApi, 'interruptTurn').mockResolvedValue({} as never);
    resetStore();
    useAgentStore.setState({
      streaming: true,
      pendingAssistantId: 'assistant-pending',
      messages: [
        {
          id: 'user-1',
          conversationId: 'conversation-1',
          role: 'user',
          content: '开始生成',
          createdAt: new Date().toISOString(),
        },
        {
          id: 'assistant-pending',
          conversationId: 'conversation-1',
          role: 'assistant',
          content: '这半截回复不应留在对话中',
          createdAt: new Date().toISOString(),
        },
      ],
      generating: {
        'conversation-1': {
          hint: '',
          turnId: 'turn-running',
          assistantId: 'assistant-pending',
          assistantContent: '这半截回复不应留在对话中',
          finished: false,
        },
      },
    });

    useAgentStore.getState().stopGeneration();

    const state = useAgentStore.getState();
    expect(state.pendingAssistantId).toBeNull();
    expect(state.streaming).toBe(false);
    expect(state.messages.map((message) => message.id)).toEqual(['user-1']);
    expect(interruptTurn).toHaveBeenCalledWith('conversation-1', 'turn-running');
  });

  it('replaces the last user turn and removes its old assistant reply when edited', async () => {
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: '修改后的回复' },
      }),
      sseEnvelope(3, 'turn_completed', { content: '修改后的回复' }),
    ]);
    const fetchMock = vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    resetStore();
    useAgentStore.setState({
      messages: [
        {
          id: 'user-1',
          conversationId: 'conversation-1',
          role: 'user',
          content: '原始问题',
          createdAt: new Date().toISOString(),
        },
        {
          id: 'assistant-old',
          conversationId: 'conversation-1',
          role: 'assistant',
          content: '旧回复',
          createdAt: new Date().toISOString(),
        },
      ],
    });
    await useAgentStore.getState().editAndResend('修改后的问题', 'user-1');

    const state = useAgentStore.getState();
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/conversations/conversation-1/turns/stream'),
      expect.objectContaining({ method: 'POST' }),
    );
    const requestBody = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(requestBody.editMessageId).toBe('user-1');
    expect(state.messages[0].content).toBe('修改后的问题');
    expect(state.messages.some((message) => message.id === 'assistant-old')).toBe(false);
    expect(state.messages.at(-1)?.content).toBe('修改后的回复');
  });

  it('maps an optimistic edit target to the persisted user message before resending', async () => {
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'turn_completed', { content: '已按新内容重发' }),
    ]);
    const fetchMock = vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: {
        data: {
          records: [{
            id: 'user-db',
            conversationId: 'conversation-1',
            role: 'user',
            content: '原始问题',
            createdAt: new Date().toISOString(),
          }],
          nextCursor: null,
          hasMore: false,
        },
      },
    } as never);
    resetStore();
    useAgentStore.setState({
      messages: [
        {
          id: 'tmp-1710000000000',
          conversationId: 'conversation-1',
          role: 'user',
          content: '原始问题',
          createdAt: new Date().toISOString(),
        },
        {
          id: 'assistant-failed',
          conversationId: 'conversation-1',
          role: 'assistant',
          content: '（回复失败）',
          createdAt: new Date().toISOString(),
        },
      ],
    });

    await useAgentStore.getState().editAndResend('修改后的问题', 'tmp-1710000000000');

    const requestBody = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(requestBody.editMessageId).toBe('user-db');
  });

  it('interrupts the active run and sends the queued message as a new turn', async () => {
    const interruptTurn = vi.spyOn(agentApi, 'interruptTurn').mockResolvedValue({} as never);
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: '新回复' },
      }),
      sseEnvelope(3, 'turn_completed', { content: '新回复' }),
    ]);
    const fetchMock = vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    resetStore();
    useAgentStore.setState({
      streaming: true,
      pendingAssistantId: 'assistant-running',
      messages: [
        {
          id: 'user-old',
          conversationId: 'conversation-1',
          role: 'user',
          content: '原问题',
          createdAt: new Date().toISOString(),
        },
        {
          id: 'assistant-running',
          conversationId: 'conversation-1',
          role: 'assistant',
          content: '正在生成',
          createdAt: new Date().toISOString(),
        },
      ],
      generating: {
        'conversation-1': {
          hint: '',
          turnId: 'turn-running',
          assistantId: 'assistant-running',
          assistantContent: '正在生成',
          finished: false,
        },
      },
    });

    await useAgentStore.getState().interruptAndSend('改成新的问题');

    expect(interruptTurn).toHaveBeenCalledWith('conversation-1', 'turn-running');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/conversations/conversation-1/turns/stream'),
      expect.objectContaining({ method: 'POST' }),
    );
    const state = useAgentStore.getState();
    expect(state.messages.some((message) => message.id === 'assistant-running')).toBe(false);
    expect(state.messages.find((message) => message.role === 'user' && message.content === '改成新的问题')).toBeTruthy();
    expect(state.messages.at(-1)?.content).toBe('新回复');
  });
});

describe('AgentScope turn envelope reduction', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    useAgentStore.setState({
      activeConversationId: null,
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
      waitingHumanInput: null,
      streamError: null,
      timelineByConversation: {},
    });
    useProjectStore.setState({ currentProject: null });
  });

  it('buffers gaps and ignores stale or duplicate sequences', () => {
    const sequencer = new AgentTurnEventSequencer();
    const first = sseEnvelope(1, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'a' } }, 'seq-test');
    const second = sseEnvelope(2, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'b' } }, 'seq-test');
    const third = sseEnvelope(3, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'c' } }, 'seq-test');

    expect(sequencer.accept(third)).toEqual([]);
    expect(sequencer.accept(first).map((event) => event.seq)).toEqual([1]);
    expect(sequencer.accept(second).map((event) => event.seq)).toEqual([2, 3]);
    expect(sequencer.accept(second)).toEqual([]);
    expect(sequencer.lastSeq('seq-test')).toBe(3);
  });

  it('labels the active runtime as MoonAgent in the conversation UI', () => {
    const turnId = 'runtime-label';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-1', assistantContent: '' },
      },
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, turnId),
      context,
    );

    expect(useAgentStore.getState().workflowHint).toBe('MoonAgent 正在处理');
  });

  it('keeps the live reasoning summary on finished sentences only', () => {
    const turnId = 'summary-stable';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-1', assistantContent: '' },
      },
    });
    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(2, 'agent_event', { eventType: 'THINKING_BLOCK_DELTA', data: { delta: '先判断技能。' } }, turnId),
      context,
    );
    expect(useAgentStore.getState().generating['conversation-1'].reasoningDraft?.summary)
      .toBe('先判断技能。');

    // 流式半句不能顶替摘要，否则标题会显示 “…This maps” 这种残句
    applyAgentTurnEnvelope(
      sseEnvelope(3, 'agent_event', { eventType: 'THINKING_BLOCK_DELTA', data: { delta: 'This maps' } }, turnId),
      context,
    );
    expect(useAgentStore.getState().generating['conversation-1'].reasoningDraft?.summary)
      .toBe('先判断技能。');
  });

  it('reduces native thinking and text deltas in order', () => {
    const turnId = 'ordered-reduce';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-1', assistantContent: '' },
      },
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(3, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: '结果' } }, turnId),
      context,
    );
    expect(useAgentStore.getState().messages[0].content).toBe('');

    applyAgentTurnEnvelope(
      sseEnvelope(2, 'agent_event', { eventType: 'THINKING_BLOCK_DELTA', data: { delta: '推理' } }, turnId),
      context,
    );

    const state = useAgentStore.getState();
    expect(state.messages[0].content).toBe('结果');
    expect(state.generating['conversation-1'].reasoningDraft?.details).toBe('推理');
    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, turnId),
      context,
    );
    expect(useAgentStore.getState().messages[0].content).toBe('结果');
  });

  it('reduces live agent trace events before turn completion', () => {
    const turnId = 'live-trace';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-1', assistantContent: '' },
      },
    });
    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, turnId),
      context,
    );

    applyAgentTurnEnvelope(
      sseEnvelope(2, 'agent_event', {
        eventType: 'AGENT_TRACE',
        data: {
          traceEvent: {
            id: 'trace-1',
            phase: 'model_reasoning',
            agent: 'moon',
            specialist: 'agentscope',
            status: 'node_started',
            title: '模型推理',
            summary: '',
            startedAt: new Date().toISOString(),
          },
        },
      }, turnId),
      context,
    );

    expect(useAgentStore.getState().generating['conversation-1'].traceDraft?.[0])
      .toMatchObject({ id: 'trace-1', status: 'node_started' });
    expect(useAgentStore.getState().messages[0].metadata?.agentTrace?.[0]?.title)
      .toBe('模型推理');
  });

  it('pauses on turn_paused and resumes with the same turn id', async () => {
    const pauseBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, 'turn-pause'),
      sseEnvelope(2, 'turn_paused', {
        messageId: 'hitl-message',
        content: '请确认',
        hitl: {
          formToken: 'form-1',
          taskId: 'task-1',
          formContent: '请确认',
          actions: [{ id: 'approve', title: '批准' }],
          expirationTime: Date.now() + 60_000,
        },
      }, 'turn-pause'),
    ]);
    const resumeBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, 'turn-resume'),
      sseEnvelope(2, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: '已继续' } }, 'turn-resume'),
      sseEnvelope(3, 'turn_completed', { content: '已继续' }, 'turn-resume'),
    ]);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(pauseBody, { status: 200 }))
      .mockResolvedValueOnce(new Response(resumeBody, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
    });

    await useAgentStore.getState().sendMessage('需要确认');

    expect(useAgentStore.getState().waitingHumanInput?.turnId).toBe('turn-pause');
    expect(useAgentStore.getState().waitingHumanInput?.formToken).toBe('form-1');

    await useAgentStore.getState().submitHumanInput('approve');

    expect(fetchMock.mock.calls[1]?.[0]).toContain('/turns/turn-pause/resume/stream');
    expect(useAgentStore.getState().messages.at(-1)?.content).toBe('已继续');
  });

  it('recovers a resumed turn before leaving a failed placeholder', async () => {
    const pauseBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, 'turn-recover'),
      sseEnvelope(2, 'turn_paused', {
        messageId: 'hitl-recover',
        content: '请确认',
        hitl: {
          formToken: 'form-recover',
          taskId: 'task-recover',
          formContent: '请确认',
          actions: [{ id: 'approve', title: '批准' }],
          expirationTime: Date.now() + 60_000,
        },
      }, 'turn-recover'),
    ]);
    const truncatedResumeBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1', resume: true }, 'turn-recover'),
    ]);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(pauseBody, { status: 200 }))
      .mockResolvedValueOnce(new Response(truncatedResumeBody, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const listTurnEvents = vi.spyOn(agentApi, 'listTurnEvents').mockResolvedValue({
      data: {
        data: [sseEnvelope(3, 'turn_completed', { content: '已恢复' }, 'turn-recover')],
      },
    } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: {
        data: {
          records: [{
            id: 'assistant-recovered',
            conversationId: 'conversation-1',
            role: 'assistant',
            content: '已恢复',
            createdAt: new Date().toISOString(),
            metadata: {},
          }],
          nextCursor: null,
          hasMore: false,
        },
      },
    } as never);
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
    });

    await useAgentStore.getState().sendMessage('需要确认');
    await useAgentStore.getState().submitHumanInput('approve');
    await vi.waitFor(() => {
      expect(listTurnEvents).toHaveBeenCalledWith('conversation-1', 'turn-recover', 2);
      expect(useAgentStore.getState().messages.at(-1)?.content).toBe('已恢复');
    });
    expect(useAgentStore.getState().messages.some((message) => message.content === '（未收到回复）'))
      .toBe(false);
  });

  it('keeps the generated plan when turn_paused appends a HITL card', () => {
    const turnId = 'turn-plan-pause';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-plan' };
    const plan = '## 方案对比\n\nA. 万象汇聚\nB. 分镜隧道\nC. 智能体网络';
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-plan',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: plan,
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-plan', assistantContent: plan },
      },
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_paused', {
        messageId: 'hitl-message',
        content: plan,
        hitl: {
          formToken: 'form-1',
          formContent: '请选择片尾视频的设计方案：',
          actions: [
            { id: 'option-0', title: 'A' },
            { id: 'option-1', title: 'B' },
            { id: 'option-2', title: 'C' },
          ],
          expirationTime: Date.now() + 60_000,
        },
      }, turnId),
      context,
    );

    expect(useAgentStore.getState().messages[0].content).toBe(plan);
    expect(useAgentStore.getState().waitingHumanInput?.formContent)
      .toBe('请选择片尾视频的设计方案：');
  });

  it('maps canvas bind pause to an exact canvas mode binding', () => {
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-canvas' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-canvas',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
    });

    const effects = applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_paused', {
        content: '请打开画布继续',
        hitl: {
          kind: 'canvas-bind',
          formToken: 'canvas-form-1',
          formContent: '请打开画布继续',
          projectId: 'project-1',
          canvasId: 'canvas-9',
          canvasTitle: '画布 9',
          requestedIntent: '生成雪茄房转场',
          picUrls: ['https://example.test/ref.png'],
          assetIds: ['asset-1'],
          actions: [{ id: 'open', title: '打开画布继续' }],
          expirationTime: Date.now() + 60_000,
        },
      }, 'turn-canvas'),
      context,
    );

    expect(effects.canvasModeRequired).toMatchObject({
      conversationId: 'conversation-1',
      turnId: 'turn-canvas',
      projectId: 'project-1',
      canvasId: 'canvas-9',
      canvasTitle: '画布 9',
      requestedIntent: '生成雪茄房转场',
      formToken: 'canvas-form-1',
      picUrls: ['https://example.test/ref.png'],
      assetIds: ['asset-1'],
    });
  });

  it('maps canvas_session_required to the independent canvas session state', () => {
    const effects = applyAgentTurnEnvelope(
      sseEnvelope(1, 'canvas_session_required', {
        sessionId: 'canvas-session-1',
        conversationId: 'conversation-1',
        turnId: 'turn-canvas-session',
        projectId: 'project-1',
        intent: '生成雪茄房转场',
        status: 'pending_selection',
        choices: [
          { canvasId: 'canvas-1', title: '画布 1' },
          { canvasId: 'canvas-2', title: '画布 2' },
        ],
      }, 'turn-canvas-session'),
      { conversationId: 'conversation-1', assistantId: 'assistant-1' },
    );

    expect(effects.canvasSessionRequired).toMatchObject({
      sessionId: 'canvas-session-1',
      turnId: 'turn-canvas-session',
      status: 'pending_selection',
    });
  });

  it('upserts one stable canvas run message without deleting the assistant placeholder', () => {
    const turnId = 'turn-canvas-run';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-canvas-run' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [
        {
          id: 'user-1',
          conversationId: 'conversation-1',
          role: 'user',
          content: '生成画布',
          createdAt: new Date().toISOString(),
        },
        {
          id: 'assistant-canvas-run',
          conversationId: 'conversation-1',
          role: 'assistant',
          content: '',
          createdAt: new Date().toISOString(),
        },
      ],
      canvasSession: null,
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'canvas_run_started', {
        canvasRunId: 'run-1',
        canvasId: 'canvas-1',
        title: '雪茄房转场画布',
        status: 'planning',
        progress: 5,
        nodeCount: 0,
        edgeCount: 0,
        revision: 1,
      }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(2, 'canvas_run_updated', {
        canvasRunId: 'run-1',
        canvasId: 'canvas-1',
        title: '雪茄房转场画布',
        status: 'writing',
        progress: 60,
        nodeCount: 8,
        edgeCount: 9,
        revision: 2,
      }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(3, 'canvas_run_completed', {
        canvasRunId: 'run-1',
        canvasId: 'canvas-1',
        title: '雪茄房转场画布',
        status: 'completed',
        progress: 100,
        nodeCount: 10,
        edgeCount: 11,
        revision: 3,
      }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(4, 'turn_completed', {
        content: '画布链路已生成',
        metadata: { kind: 'assistant-reply' },
      }, turnId),
      context,
    );

    const state = useAgentStore.getState();
    expect(state.messages).toHaveLength(2);
    expect(state.messages[0]).toMatchObject({ id: 'user-1', content: '生成画布' });
    expect(state.messages[1]).toMatchObject({
      id: 'assistant-canvas-run',
      role: 'assistant',
      metadata: {
        kind: 'canvas-run',
        canvasRunId: 'run-1',
        canvasId: 'canvas-1',
        turnId,
        status: 'completed',
        progress: 100,
        nodeCount: 10,
        edgeCount: 11,
        revision: 3,
      },
    });
    expect(state.messages[1].content).toBe('画布链路已生成');
    expect(state.canvasSession).toBeNull();
  });

  it('marks a canvas run failed on canvas_run_failed', () => {
    const turnId = 'turn-canvas-run-failed';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-canvas-failed' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-canvas-failed',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'canvas_run_failed', {
        canvasRunId: 'run-failed',
        canvasId: 'canvas-1',
        status: 'failed',
        progress: 20,
        nodeCount: 2,
        edgeCount: 1,
        revision: 3,
        error: '画布写入失败',
      }, turnId),
      context,
    );

    expect(useAgentStore.getState().messages[0].metadata).toMatchObject({
      kind: 'canvas-run',
      canvasRunId: 'run-failed',
      status: 'failed',
      error: '画布写入失败',
    });
  });

  it('applies completed metadata and failed turn errors directly', () => {
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', assistantId: 'assistant-1' },
      },
      streamError: null,
    });

    const metrics = {
      inputTokens: 4,
      outputTokens: 2,
      cachedInputTokens: 0,
      totalTokens: 6,
      durationMs: 10,
      modelCalls: 1,
      complete: true,
    };
    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_completed', { content: '完成', metadata: { metrics } }, 'turn-complete'),
      context,
    );
    expect(useAgentStore.getState().messages[0].content).toBe('完成');
    expect(useAgentStore.getState().messages[0].metadata?.metrics).toEqual(metrics);

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_failed', { code: '50001', message: '执行失败' }, 'turn-failed'),
      context,
    );
    expect(useAgentStore.getState().streamError).toBe('执行失败');
  });

  it('replays turn events afterSeq and ignores duplicates on a second replay', async () => {
    const turnId = 'replay-turn';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-replay' };
    const replayEvents = [
      sseEnvelope(2, 'agent_event', { eventType: 'THINKING_BLOCK_DELTA', data: { delta: '回放推理' } }, turnId),
      sseEnvelope(3, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: '回放正文' } }, turnId),
      sseEnvelope(4, 'turn_completed', { content: '回放正文' }, turnId),
    ];
    const listTurnEvents = vi.spyOn(agentApi, 'listTurnEvents')
      .mockResolvedValue({ data: { data: replayEvents } } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-replay',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-replay' },
      },
      pendingAssistantId: 'assistant-replay',
    });
    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, turnId),
      context,
    );

    await useAgentStore.getState().replayTurnEvents('conversation-1', turnId, 1);
    expect(listTurnEvents).toHaveBeenCalledWith('conversation-1', turnId, 1);
    expect(useAgentStore.getState().messages[0].content).toBe('回放正文');

    await useAgentStore.getState().replayTurnEvents('conversation-1', turnId, 1);
    expect(useAgentStore.getState().messages[0].content).toBe('回放正文');
  });

  it('restores the canvas session card when replaying a missing final event', async () => {
    const turnId = 'canvas-replay-turn';
    vi.spyOn(agentApi, 'listTurnEvents').mockResolvedValue({
      data: {
        data: [
          sseEnvelope(1, 'canvas_session_required', {
            sessionId: 'canvas-session-replay',
            conversationId: 'conversation-1',
            turnId,
            projectId: 'project-1',
            intent: '生成雪茄房转场',
            status: 'pending_selection',
            choices: [{ canvasId: 'canvas-1', title: '画布 1' }],
          }, turnId),
        ],
      },
    } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      canvasSession: null,
      messages: [{
        id: 'assistant-canvas-replay',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-canvas-replay' },
      },
      pendingAssistantId: 'assistant-canvas-replay',
    });

    await useAgentStore.getState().replayTurnEvents('conversation-1', turnId, 0);

    expect(useAgentStore.getState().canvasSession).toMatchObject({
      sessionId: 'canvas-session-replay',
      status: 'pending_selection',
    });
    expect(useAgentStore.getState().messages).toHaveLength(0);
    expect(useAgentStore.getState().generating['conversation-1']).toBeUndefined();
  });
});
