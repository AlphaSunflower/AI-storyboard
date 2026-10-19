import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_AGENT_APPROVAL_MODE,
  agentApi,
  type AgentAttachment,
  type AgentConversation,
  type AgentTaskView,
  type AgentTurnEventType,
} from '../api/agent';
import {
  AgentTurnEventSequencer,
  TURN_GAP_MAX_ATTEMPTS,
  TURN_GAP_TIMEOUT_MS,
  applyAgentTurnEnvelope,
  clearTurnGapRecovery,
  taskStatusLabel,
  turnGapRecoverySnapshot,
  turnGapTimeoutForAttempt,
  useAgentStore,
} from './agentStore';
import { dedupeAttachments, attachmentKey } from '../lib/attachmentDedupe';
import { collectRenderIntents, parseRenderIntent } from '../lib/renderIntent';
import { useProjectStore } from './projectStore';
import {
  DEFAULT_ACCOUNT_SETTINGS,
  useAccountSettingsStore,
} from './accountSettingsStore';
import { readTurnLastSeq } from './turnResume';
import { setAccessToken } from '../lib/authSession';
import { defaultGenerationPreferences } from '../lib/generationPreferences';

beforeEach(() => {
  setAccessToken('test-token');
});

afterEach(() => {
  useAccountSettingsStore.setState({ settings: DEFAULT_ACCOUNT_SETTINGS });
});

function sseEnvelope(
  seq: number,
  type: AgentTurnEventType,
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
      pendingApprovalMode: DEFAULT_AGENT_APPROVAL_MODE,
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

  it('keeps the account-level FULL_ACCESS default for a new conversation', async () => {
    useAccountSettingsStore.setState({
      settings: { ...DEFAULT_ACCOUNT_SETTINGS, defaultApprovalMode: 'FULL_ACCESS' },
      loaded: true,
    });
    const conversation: AgentConversation = {
      id: 'conversation-full',
      userId: 'user-1',
      projectId: 'project-1',
      title: '新对话',
      status: 'active',
      approvalMode: 'FULL_ACCESS',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
    });
    const createConversation = vi.spyOn(agentApi, 'createConversation')
      .mockResolvedValue({ data: { data: conversation } } as never);
    useAgentStore.setState({ selectConversation: vi.fn().mockResolvedValue(undefined) });

    useAgentStore.getState().newConversation();
    expect(useAgentStore.getState().pendingApprovalMode).toBe('FULL_ACCESS');

    await useAgentStore.getState().createConversation();

    expect(createConversation).toHaveBeenCalledWith('project-1', '新对话', 'FULL_ACCESS');
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
      canvasEditContext: null,
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

  it('inserts a steered user message before the streaming assistant bubble exactly once', () => {
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-1',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: '2026-10-17T00:00:00.000Z',
      }],
      generating: {},
    });
    const turnId = 'turn-steer-test';
    const envelope = (seq: number) => sseEnvelope(
      seq,
      'USER_MESSAGE',
      {
        messageId: 'steer-1',
        content: '改成暖色',
        createdAt: '2026-10-17T00:00:01.000Z',
      },
      turnId,
    );

    applyAgentTurnEnvelope(envelope(1), context);
    // 同一条事件重放（重连/补偿）不得插出第二个气泡
    applyAgentTurnEnvelope(envelope(2), context);

    const messages = useAgentStore.getState().messages;
    expect(messages.map((message) => message.id)).toEqual(['steer-1', 'assistant-1']);
    expect(messages[0].metadata?.steer).toBe(true);
    expect(messages[0].metadata?.turnId).toBe(turnId);
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

  it('keeps uploaded canvas media refs relative for MoonAgent validation', async () => {
    vi.spyOn(agentApi, 'uploadImage').mockResolvedValue({
      data: {
        data: {
          assetId: 'asset-img-1',
          url: '/api/files/images/ref.png?token=1.abc',
          name: 'ref.png',
          mimeType: 'image/png',
          sizeBytes: 10,
        },
      },
    } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      pendingAttachments: [],
      refImageUrls: [],
      canvasEditContext: {
        mode: 'canvas-edit',
        projectId: 'project-1',
        canvasId: 'canvas-1',
        mediaRefs: [],
      },
    });

    await useAgentStore.getState().uploadRefImage(
      new File(['x'], 'ref.png', { type: 'image/png' }),
    );

    // 绝对化会让 origin 变成浏览器地址，被 MoonAgent 判成「媒体地址不在允许的服务范围内」
    expect(useAgentStore.getState().canvasEditContext?.mediaRefs).toEqual([
      expect.objectContaining({
        kind: 'image',
        url: '/api/files/images/ref.png?token=1.abc',
        source: 'upload',
      }),
    ]);
  });

  it('clears consumed canvas media from the composer right after sending', async () => {
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'turn_completed', { content: '好的' }),
    ]);
    const fetchMock = vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      streaming: false,
      generating: {},
      pendingAttachments: [{
        assetId: 'asset-white-1',
        url: '/api/files/images/white.png',
        name: 'white.png',
        mimeType: 'image/png',
        kind: 'image' as const,
        source: 'upload' as const,
      }],
      refImageUrls: ['/api/files/images/white.png'],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
      canvasEditContext: {
        mode: 'canvas-edit',
        projectId: 'project-1',
        canvasId: 'canvas-1',
        mediaRefs: [{
          kind: 'image' as const,
          url: '/api/files/images/white.png',
          name: 'white.png',
          source: 'upload' as const,
        }],
      },
    });

    await useAgentStore.getState().sendMessage('把这些白膜变成真实场景');

    // 本轮请求仍带上了画布媒体
    const requestBody = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(requestBody.canvasContext.mediaRefs).toHaveLength(1);
    // 但输入框上方立刻清空：不再每轮重复挂载、也不遮挡智能体输出
    const after = useAgentStore.getState();
    expect(after.canvasEditContext?.mediaRefs).toEqual([]);
    expect(after.pendingAttachments).toEqual([]);
    expect(after.refImageUrls).toEqual([]);
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

  it('replays a paused turn through the same checkpoint recovery path as a live stream', async () => {
    const turnId = 'turn-replay-paused';
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({
      data: { data: null },
    } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({
      data: {
        data: {
          turnId,
          formToken: 'form-replayed',
          taskId: '',
          formContent: '请确认回放卡片',
          actions: [{ id: 'approve', title: '批准' }],
          expirationTime: Date.now() / 1000 + 60,
        },
      },
    } as never);
    vi.spyOn(agentApi, 'listTurnEvents').mockResolvedValue({
      data: {
        data: [{
          turnId,
          seq: 1,
          type: 'turn_paused',
          payload: {
            messageId: 'hitl-replayed',
            content: '请确认回放卡片',
            hitl: {
              formToken: 'form-replayed',
              formContent: '请确认回放卡片',
              actions: [{ id: 'approve', title: '批准' }],
            },
          },
        }],
      },
    } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-replay',
      messages: [],
      generating: {
        'conversation-replay': {
          hint: '',
          turnId,
          assistantId: 'assistant-replay',
          assistantContent: '',
        },
      },
      streaming: false,
      pendingAssistantId: 'assistant-replay',
      waitingHumanInput: null,
    });

    await useAgentStore.getState().replayTurnEvents('conversation-replay', turnId, 0);

    expect(useAgentStore.getState().waitingHumanInput?.formToken).toBe('form-replayed');
  });

  it('applies a replayed task-accepted side effect to the assistant card', async () => {
    const turnId = 'turn-replay-task';
    const task: AgentTaskView = {
      taskId: 'task-replayed',
      conversationId: 'conversation-replay',
      projectId: 'project-1',
      title: '生成剧情稿',
      toolName: 'start_storyboard',
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
    };
    vi.spyOn(agentApi, 'listTurnEvents').mockResolvedValue({
      data: {
        data: [
          sseEnvelope(1, 'turn_started', {
            conversationId: 'conversation-replay',
          }, turnId),
          sseEnvelope(2, 'TOOL_RESULT_TEXT_DELTA', {
            toolCallId: 'tool-task',
            toolName: 'start_storyboard',
            delta: JSON.stringify({
              taskId: task.taskId,
              taskKind: 'long-tool',
              message: '任务已受理',
            }),
          }, turnId),
          sseEnvelope(3, 'TOOL_RESULT_END', {
            toolCallId: 'tool-task',
            toolName: 'start_storyboard',
            state: 'SUCCESS',
          }, turnId),
        ],
      },
    } as never);
    vi.spyOn(agentApi, 'getTask').mockResolvedValue({ data: { data: task } } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-replay',
      messages: [{
        id: 'assistant-replay',
        conversationId: 'conversation-replay',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-replay': {
          hint: '',
          turnId,
          assistantId: 'assistant-replay',
        },
      },
      pendingAssistantId: 'assistant-replay',
      tasks: {},
    });

    await useAgentStore.getState().replayTurnEvents('conversation-replay', turnId, 0);

    expect(useAgentStore.getState().messages[0].metadata?.taskCards?.[0]?.taskId)
      .toBe(task.taskId);
    expect(useAgentStore.getState().tasks[task.taskId]?.taskId).toBe(task.taskId);
  });

  it('restores only the latest resumed phase after a refresh', async () => {
    const turnId = 'turn-resume-refresh';
    const events = [
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-refresh' }, turnId),
      sseEnvelope(2, 'turn_paused', {
        messageId: 'old-hitl',
        content: '旧方案待确认',
        hitl: {
          formToken: 'old-form',
          formContent: '旧方案待确认',
          actions: [{ id: 'approve', title: '批准' }],
        },
      }, turnId),
      sseEnvelope(3, 'turn_started', {
        conversationId: 'conversation-refresh',
        resume: true,
      }, turnId),
      sseEnvelope(4, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: 'resumed text' },
      }, turnId),
    ];
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: {
        data: { records: [], nextCursor: null, hasMore: false },
      },
    } as never);
    vi.spyOn(agentApi, 'listMessages').mockResolvedValue({
      data: { data: [] },
    } as never);
    vi.spyOn(agentApi, 'listTasks').mockResolvedValue({
      data: {
        data: {
          records: [],
          total: 0,
          page: 1,
          size: 100,
        },
      },
    } as never);
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({
      data: { data: null },
    } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({
      data: { data: null },
    } as never);
    vi.spyOn(agentApi, 'getRuntimeStatus').mockResolvedValue({
      data: {
        data: {
          activeTurn: {
            turnId,
            status: 'running',
          },
        },
      },
    } as never);
    const listTurnEvents = vi.spyOn(agentApi, 'listTurnEvents')
      .mockImplementation(async (_conversationId, _turnId, afterSeq = 0) => ({
        data: {
          data: events.filter((event) => event.seq > afterSeq),
        },
      } as never));
    useAgentStore.setState({
      activeConversationId: null,
      conversations: [],
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
      waitingHumanInput: null,
    });

    await useAgentStore.getState().selectConversation('conversation-refresh');

    await vi.waitFor(() => {
      expect(listTurnEvents).toHaveBeenCalledWith('conversation-refresh', turnId, 0);
      expect(useAgentStore.getState().messages.some(
        (message) => message.content === 'resumed text',
      )).toBe(true);
    });
    expect(useAgentStore.getState().waitingHumanInput).toBeNull();
    useAgentStore.setState({ activeConversationId: null });
  });
});

describe('agentStore 输入框上方只保留还没发出去的素材', () => {
  const pendingKey = 'moon.agent.pendingAttachments.conversation-1';

  const mockConversationLoad = (records: unknown[] = []) => {
    vi.spyOn(agentApi, 'getConversation').mockResolvedValue({
      data: { data: { conversation: { canvasContext: null } } },
    } as never);
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: { data: { records, nextCursor: null, hasMore: false } },
    } as never);
    vi.spyOn(agentApi, 'listMessages').mockResolvedValue({ data: { data: [] } } as never);
    vi.spyOn(agentApi, 'listTasks').mockResolvedValue({
      data: { data: { records: [], total: 0, page: 1, size: 100 } },
    } as never);
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({
      data: { data: null },
    } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({
      data: { data: null },
    } as never);
    vi.spyOn(agentApi, 'getRuntimeStatus').mockResolvedValue({
      data: { data: { activeTurn: null } },
    } as never);
  };

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    window.localStorage.clear();
    useAgentStore.setState({
      activeConversationId: null,
      refImageUrls: [],
      pendingAttachments: [],
    });
  });

  it('发送后清空输入框上方素材，并清掉本地快照（刷新不会复活）', async () => {
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'turn_completed', { content: '好的' }),
    ]);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    const attachment = {
      url: '/api/files/images/a.png',
      name: 'a.png',
      mimeType: 'image/png',
      kind: 'image' as const,
      source: 'upload' as const,
    };
    window.localStorage.setItem(pendingKey, JSON.stringify([attachment]));
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      streaming: false,
      generating: {},
      refImageUrls: ['/api/files/images/a.png'],
      pendingAttachments: [attachment],
      pickedReferenceVideos: [],
      pickedReferenceAudios: [],
      canvasEditContext: null,
    });

    await useAgentStore.getState().sendMessage('按这张图生成');

    const after = useAgentStore.getState();
    // 已发出的素材不再挂在输入框上方（历史仍在会话消息里），也不会在刷新后作为「待发」复活
    expect(after.refImageUrls).toEqual([]);
    expect(after.pendingAttachments).toEqual([]);
    expect(window.localStorage.getItem(pendingKey)).toBeNull();
  });

  it('刷新只恢复还没发出去的附件，不把历史消息里的已发送图片带回来', async () => {
    // 历史消息带有附件（已发送），localStorage 里没有未发送快照
    mockConversationLoad([{
      id: 'm1',
      conversationId: 'conversation-1',
      role: 'user',
      content: '按这张图生成',
      createdAt: new Date().toISOString(),
      metadata: {
        messageInput: {
          attachments: [{
            url: '/api/files/images/a.png',
            name: 'a.png',
            mimeType: 'image/png',
            kind: 'image',
          }],
        },
      },
    }]);

    await useAgentStore.getState().selectConversation('conversation-1');

    const state = useAgentStore.getState();
    expect(state.messages.some((message) => message.id === 'm1')).toBe(true);
    expect(state.refImageUrls).toEqual([]);
    expect(state.pendingAttachments).toEqual([]);
  });

  it('加了但还没发出去的素材，刷新后仍会回到输入框', async () => {
    window.localStorage.setItem(pendingKey, JSON.stringify([{
      url: '/api/files/images/unsent.png',
      name: 'unsent.png',
      mimeType: 'image/png',
      kind: 'image',
    }]));
    mockConversationLoad();

    await useAgentStore.getState().selectConversation('conversation-1');

    const state = useAgentStore.getState();
    expect(state.refImageUrls).toEqual(['/api/files/images/unsent.png']);
    expect(state.pendingAttachments).toHaveLength(1);
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

  it('keeps the streamed reply and appends an ellipsis when the user stops generation', () => {
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
          content: '这半截回复要留在对话中',
          createdAt: new Date().toISOString(),
        },
      ],
      generating: {
        'conversation-1': {
          hint: '',
          turnId: 'turn-running',
          assistantId: 'assistant-pending',
          assistantContent: '这半截回复要留在对话中',
          finished: false,
        },
      },
    });

    useAgentStore.getState().stopGeneration();

    const state = useAgentStore.getState();
    expect(state.pendingAssistantId).toBeNull();
    expect(state.streaming).toBe(false);
    // 停止不再清空智能体已经发出来的文字：气泡保留，正文以「…」收尾
    expect(state.messages.map((message) => message.id)).toEqual(['user-1', 'assistant-pending']);
    expect(state.messages[1].content).toBe('这半截回复要留在对话中…');
    expect(state.messages[1].metadata?.interrupted).toBe(true);
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

  it('interrupts the active run, keeps the partial reply and then sends the queued message', async () => {
    const encoder = new TextEncoder();
    const frame = (event: {
      turnId: string; seq: number; type: string; payload: Record<string, unknown>;
    }) => encoder.encode(`event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
    let runningController: ReadableStreamDefaultController<Uint8Array> | null = null;
    // 正在生成的那一轮：turn_started + 一段正文，然后停在流里等打断
    const runningStream = new ReadableStream<Uint8Array>({
      start(controller) {
        runningController = controller;
        controller.enqueue(encoder.encode([
          'event: turn_started',
          `data: ${JSON.stringify({ turnId: 'turn-running', seq: 1, type: 'turn_started', payload: { conversationId: 'conversation-1' } })}`,
          '', '',
        ].join('\n')));
        controller.enqueue(encoder.encode([
          'event: agent_event',
          `data: ${JSON.stringify({ turnId: 'turn-running', seq: 2, type: 'agent_event', payload: { eventType: 'TEXT_BLOCK_DELTA', data: { delta: '正在生成' } } })}`,
          '', '',
        ].join('\n')));
      },
    });
    const queuedBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: '新回复' },
      }),
      sseEnvelope(3, 'turn_completed', { content: '新回复' }),
    ]);
    let runningStreamServed = false;
    // 每次调用都新建 Response：同一个 Response 实例被第二次读取会因 body 已锁定而报错
    const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/conversations/conversation-1/turns/stream')) {
        if (!runningStreamServed) {
          runningStreamServed = true;
          return new Response(runningStream, {
            status: 200,
            headers: { 'Content-Type': 'text/event-stream' },
          });
        }
        return new Response(queuedBody, {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        });
      }
      // 对账/标题补齐等旁路请求：直接失败，测试只关心 turn 流
      return new Response('{}', { status: 500, headers: { 'Content-Type': 'application/json' } });
    });
    vi.stubGlobal('fetch', fetchMock);
    // 打断成功：后端把这一轮收成终态（40904）并结束流
    const interruptTurn = vi.spyOn(agentApi, 'interruptTurn').mockImplementation(async () => {
      runningController?.enqueue(frame({
        turnId: 'turn-running',
        seq: 3,
        type: 'turn_failed',
        payload: { code: '40904', message: '生成已停止', interrupted: true, content: '正在生成…' },
      }));
      runningController?.close();
      return {} as never;
    });
    resetStore();

    const running = useAgentStore.getState().sendMessage('原问题');
    await vi.waitFor(() => {
      expect(useAgentStore.getState().generating['conversation-1']?.turnId).toBe('turn-running');
    });

    const delivered = await useAgentStore.getState().interruptAndSend('改成新的问题');
    await running;

    expect(delivered).toBe(true);
    expect(interruptTurn).toHaveBeenCalledWith('conversation-1', 'turn-running');
    // 打断后才把新消息作为下一轮发出去（而不是抢跑撞会话锁）：turns/stream 恰好两次
    const streamCalls = () => fetchMock.mock.calls
      .filter(([url]) => String(url).includes('/conversations/conversation-1/turns/stream'));
    await vi.waitFor(() => { expect(streamCalls()).toHaveLength(2); });
    expect(JSON.parse(String(streamCalls()[0]?.[1]?.body)).content).toBe('原问题');
    expect(JSON.parse(String(streamCalls()[1]?.[1]?.body)).content).toBe('改成新的问题');
    expect(streamCalls()[1]?.[1]).toEqual(expect.objectContaining({ method: 'POST' }));
    await vi.waitFor(() => {
      expect(useAgentStore.getState().messages.at(-1)?.content).toBe('新回复');
    });
    const state = useAgentStore.getState();
    // 被打断的正文留在对话里，以「…」收尾
    const interrupted = state.messages.find((message) => message.metadata?.interrupted);
    expect(interrupted?.content).toBe('正在生成…');
    expect(state.streamError).toBeNull();
    expect(state.messages.find((message) => message.role === 'user' && message.content === '改成新的问题')).toBeTruthy();
  });

  it('keeps the input text when the interrupt never settles', async () => {
    vi.useFakeTimers();
    vi.spyOn(agentApi, 'interruptTurn').mockResolvedValue({} as never);
    resetStore();
    // generating 里有活跃 turn 但没有真正的流：打断永远等不到终态
    useAgentStore.setState({
      streaming: true,
      pendingAssistantId: 'assistant-running',
      messages: [
        { id: 'user-1', conversationId: 'conversation-1', role: 'user', content: '原问题', createdAt: new Date().toISOString() },
        { id: 'assistant-running', conversationId: 'conversation-1', role: 'assistant', content: '半截', createdAt: new Date().toISOString() },
      ],
      generating: {
        'conversation-1': {
          hint: '', turnId: 'turn-running', assistantId: 'assistant-running', assistantContent: '半截', finished: false,
        },
      },
    });

    const pending = useAgentStore.getState().interruptAndSend('打断超时的消息');
    await vi.advanceTimersByTimeAsync(21_000);
    const delivered = await pending;

    // 没发出去时调用方要能把内容还给用户；被截断的正文仍然保留
    expect(delivered).toBe(false);
    expect(useAgentStore.getState().messages.find((m) => m.metadata?.interrupted)?.content).toBe('半截…');
    vi.useRealTimers();
  });

  it('treats a server-side stop (40904) as a stop, not an error, and freezes the text', async () => {
    const body = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: '被别处停掉的一半' },
      }),
      sseEnvelope(3, 'turn_failed', { code: '40904', message: '生成已停止', interrupted: true }),
    ]);
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    resetStore();

    await useAgentStore.getState().sendMessage('开始生成');

    const state = useAgentStore.getState();
    const assistant = state.messages.find((message) => message.role === 'assistant');
    expect(assistant?.content).toBe('被别处停掉的一半…');
    expect(assistant?.metadata?.interrupted).toBe(true);
    // 用户自己要停的操作不能弹错误条
    expect(state.streamError).toBeNull();
  });

  it('keeps the interrupted reply when the message list is refreshed before the server persisted it', async () => {
    resetStore();
    useAgentStore.setState({
      streaming: true,
      pendingAssistantId: 'assistant-running',
      messages: [
        {
          id: 'user-1', conversationId: 'conversation-1', role: 'user',
          content: '原问题', createdAt: new Date(Date.now() - 60_000).toISOString(),
        },
        {
          id: 'assistant-running', conversationId: 'conversation-1', role: 'assistant',
          content: '这半截回复', createdAt: new Date().toISOString(),
        },
      ],
      generating: {
        'conversation-1': {
          hint: '', turnId: 'turn-running', assistantId: 'assistant-running',
          assistantContent: '这半截回复', finished: false,
        },
      },
    });
    useAgentStore.getState().stopGeneration();

    // 服务端消息列表里还没有这条被截断的回复（打断落库还没完成）
    const serverRecords = [{
      id: 'user-db', conversationId: 'conversation-1', role: 'user',
      content: '原问题', createdAt: new Date(Date.now() - 60_000).toISOString(),
    }];
    vi.spyOn(agentApi, 'getConversation').mockResolvedValue({
      data: { data: { conversation: { canvasContext: null } } },
    } as never);
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: { data: { records: serverRecords, nextCursor: null, hasMore: false } },
    } as never);
    vi.spyOn(agentApi, 'listMessages').mockResolvedValue({ data: { data: serverRecords } } as never);
    vi.spyOn(agentApi, 'listTasks').mockResolvedValue({
      data: { data: { records: [], total: 0, page: 1, size: 100 } },
    } as never);
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'getRuntimeStatus').mockResolvedValue({ data: { data: {} } } as never);

    await useAgentStore.getState().selectConversation('conversation-1');

    const kept = useAgentStore.getState().messages.find((message) => message.metadata?.interrupted);
    expect(kept?.content).toBe('这半截回复…');
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
    expect(useAgentStore.getState().generating['conversation-1'].reasoningDraft?.activity)
      .toBe('先判断技能。This maps');
    expect(useAgentStore.getState().workflowHint)
      .toBe('正在思考：先判断技能。This maps');
  });

  it('keeps a visible boundary between multiple thinking blocks', () => {
    const turnId = 'thinking-blocks';
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

    applyAgentTurnEnvelope(sseEnvelope(1, 'turn_started', {}, turnId), context);
    applyAgentTurnEnvelope(sseEnvelope(2, 'THINKING_BLOCK_START', {}, turnId), context);
    applyAgentTurnEnvelope(sseEnvelope(3, 'THINKING_BLOCK_DELTA', { delta: 'first' }, turnId), context);
    applyAgentTurnEnvelope(sseEnvelope(4, 'THINKING_BLOCK_END', {}, turnId), context);
    applyAgentTurnEnvelope(sseEnvelope(5, 'THINKING_BLOCK_START', {}, turnId), context);
    applyAgentTurnEnvelope(sseEnvelope(6, 'THINKING_BLOCK_DELTA', { delta: 'second' }, turnId), context);

    expect(useAgentStore.getState().generating['conversation-1'].reasoningDraft?.details)
      .toBe('first\n\nsecond');
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

  it('reduces native thinking deltas carried as top-level turn events', () => {
    const turnId = 'native-thinking';
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
      sseEnvelope(2, 'THINKING_BLOCK_DELTA', { delta: '实时推理' }, turnId),
      context,
    );

    expect(useAgentStore.getState().generating['conversation-1'].reasoningDraft?.details)
      .toBe('实时推理');
    expect(useAgentStore.getState().messages[0].metadata?.reasoning?.details)
      .toBe('实时推理');
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
    expect(useAgentStore.getState().messages[0].metadata?.hitl?.formToken).toBe('form-1');
    expect(useAgentStore.getState().waitingHumanInput?.formContent)
      .toBe('请选择片尾视频的设计方案：');
  });

  /**
   * 回归：HITL 暂停轮的工具明细只活在 `generating.toolDrafts` 里，而流一结束（暂停即结束）
   * `generating` 就被删除。若不把草稿折进暂停卡片 metadata，工具调用会在暂停瞬间被吞掉，
   * 只有刷新（服务端 metadata 回放）才重新出现——正是「一开始有的，后面就被吞了」。
   */
  it('暂停轮把直播工具草稿折进卡片 metadata，流结束后仍可渲染', () => {
    const turnId = 'turn-pause-tools';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-pause-tools' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-pause-tools',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-pause-tools' },
      },
      streaming: true,
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'agent_event', {
        eventType: 'TOOL_RESULT_END',
        data: {
          toolName: 'run_python_script',
          toolCallId: 'call-pause',
          state: 'SUCCESS',
          delta: 'done',
        },
      }, turnId),
      context,
    );

    applyAgentTurnEnvelope(
      sseEnvelope(2, 'turn_paused', {
        messageId: 'hitl-pause-tools',
        content: '请确认',
        hitl: {
          formToken: 'form-pause-tools',
          formContent: '请确认',
          actions: [{ id: 'approve', title: '批准' }],
          expirationTime: Date.now() + 60_000,
        },
      }, turnId),
      context,
    );

    // 流的收尾（sendMessage/submitHumanInput 的 finally）会删除本轮的 generating 快照
    useAgentStore.setState((state) => {
      const generating = { ...state.generating };
      delete generating['conversation-1'];
      return { generating, streaming: false };
    });

    const paused = useAgentStore.getState().messages[0];
    expect(paused.id).toBe('hitl-pause-tools');
    expect(paused.metadata?.hitl?.formToken).toBe('form-pause-tools');
    expect(paused.metadata?.toolDrafts?.['call-pause']?.name).toBe('run_python_script');
    expect(paused.metadata?.toolDrafts?.['call-pause']?.status).toBe('node_completed');
  });

  it('暂停轮缺 payload.metadata 时用直播草稿补齐 renders', () => {
    const turnId = 'turn-pause-renders';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-pause-renders' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-pause-renders',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': { hint: '', turnId, assistantId: 'assistant-pause-renders' },
      },
      streaming: true,
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'agent_event', {
        eventType: 'TOOL_RESULT_END',
        data: {
          toolName: 'query_table',
          toolCallId: 'call-render-pause',
          state: 'SUCCESS',
          delta: 'done',
          render: { kind: 'table', id: 'render-pause', status: 'completed', items: [{ 名称: '甲' }], version: 1 },
        },
      }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(2, 'turn_paused', {
        messageId: 'hitl-pause-renders',
        content: '请确认',
        hitl: {
          formToken: 'form-pause-renders',
          formContent: '请确认',
          actions: [{ id: 'approve', title: '批准' }],
          expirationTime: Date.now() + 60_000,
        },
      }, turnId),
      context,
    );

    const metadata = useAgentStore.getState().messages[0].metadata;
    expect(metadata?.toolDrafts?.['call-render-pause']?.render?.kind).toBe('table');
    expect(metadata?.renders?.map((intent) => intent.kind)).toEqual(['table']);
  });

  it('端到端：暂停轮的流结束后工具调用仍留在卡片上（刷新前后一致）', async () => {
    const turnId = 'turn-pause-tools-e2e';
    const pauseBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, turnId),
      sseEnvelope(2, 'agent_event', {
        eventType: 'TOOL_RESULT_END',
        data: {
          toolName: 'web_search',
          toolCallId: 'call-e2e',
          state: 'SUCCESS',
          delta: '{"ok":true}',
        },
      }, turnId),
      sseEnvelope(3, 'turn_paused', {
        messageId: 'hitl-e2e',
        content: '请确认',
        hitl: {
          formToken: 'form-e2e',
          formContent: '请确认',
          actions: [{ id: 'approve', title: '批准' }],
          expirationTime: Date.now() + 60_000,
        },
      }, turnId),
    ]);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(pauseBody, { status: 200 })));
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      conversations: [],
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
      refImageUrls: [],
      pickedAssetIds: [],
    });

    await useAgentStore.getState().sendMessage('搜一下 GitHub Trending');

    const paused = useAgentStore.getState().messages.find((message) => message.metadata?.hitl);
    expect(paused?.metadata?.toolDrafts?.['call-e2e']?.name).toBe('web_search');
    expect(paused?.metadata?.toolDrafts?.['call-e2e']?.status).toBe('node_completed');
    // 直播快照已被回收：卡片自身的 metadata 是唯一渲染来源
    expect(useAgentStore.getState().generating['conversation-1']).toBeUndefined();
  });

  it('does not copy the HITL prompt into an empty assistant placeholder body', () => {
    const turnId = 'turn-pause-empty-body';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-empty' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-empty',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': {
          hint: '',
          turnId,
          assistantId: 'assistant-empty',
          assistantContent: '',
        },
      },
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_paused', {
        messageId: 'hitl-empty-body',
        content: '',
        hitl: {
          formToken: 'form-empty-body',
          formContent: '这张卡片不应复制到正文占位',
          actions: [{ id: 'approve', title: '批准' }],
          expirationTime: Date.now() + 60_000,
        },
      }, turnId),
      context,
    );

    expect(useAgentStore.getState().messages[0].content).toBe('');
    expect(useAgentStore.getState().waitingHumanInput?.formContent)
      .toBe('这张卡片不应复制到正文占位');
  });

  it('keeps a submitted form token across refresh before the checkpoint claim lands', async () => {
    const formToken = 'form-in-flight';
    const pending = {
      turnId: 'turn-in-flight',
      formToken,
      taskId: '',
      formContent: '请选择',
      actions: [{ id: 'approve', title: '批准' }],
      expirationTime: Date.now() / 1000 + 60,
    };
    const pauseBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, 'turn-in-flight'),
      sseEnvelope(2, 'turn_paused', {
        messageId: 'hitl-in-flight',
        content: '请选择',
        hitl: pending,
      }, 'turn-in-flight'),
    ]);
    const resumeBody = sseBody([
      sseEnvelope(3, 'turn_started', {
        conversationId: 'conversation-1',
        resume: true,
      }, 'turn-in-flight'),
    ]);
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(pauseBody, { status: 200 }))
      .mockResolvedValueOnce(new Response(resumeBody, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({ data: { data: pending } } as never);
    vi.spyOn(agentApi, 'getRuntimeStatus').mockResolvedValue({ data: { data: { activeTurn: null } } } as never);
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: { data: { records: [], nextCursor: null, hasMore: false } },
    } as never);
    sessionStorage.clear();
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
      waitingHumanInput: null,
    });

    await useAgentStore.getState().sendMessage('需要确认');
    await useAgentStore.getState().submitHumanInput('approve');

    useAgentStore.setState({ messages: [], waitingHumanInput: null });
    await useAgentStore.getState().recoverPendingCheckpoint('conversation-1');

    expect(useAgentStore.getState().waitingHumanInput).toBeNull();
  });

  it('rolls back a HITL submission that fails before server acceptance', async () => {
    const formToken = 'form-retry-after-failure';
    const pending = {
      turnId: 'turn-retry-after-failure',
      formToken,
      taskId: '',
      formContent: '请选择方案',
      actions: [{ id: 'approve', title: '批准' }],
      expirationTime: Date.now() / 1000 + 60,
    };
    const pauseBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, pending.turnId),
      sseEnvelope(2, 'turn_paused', {
        messageId: 'hitl-retry',
        content: '请选择方案',
        hitl: pending,
      }, pending.turnId),
    ]);
    const failureBody = [
      'event: turn_failed',
      `data: ${JSON.stringify({
        turnId: '',
        seq: 0,
        type: 'turn_failed',
        payload: { code: '40901', message: '当前对话正在处理中，请稍候' },
      })}`,
      '',
      '',
    ].join('\n');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(pauseBody, { status: 200 }))
      .mockResolvedValueOnce(new Response(failureBody, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({ data: { data: pending } } as never);
    const persistedPendingCard = {
      id: 'hitl-retry',
      conversationId: 'conversation-1',
      role: 'assistant' as const,
      content: '请选择方案',
      createdAt: new Date().toISOString(),
      metadata: {
        kind: 'hitl' as const,
        hitl: {
          formContent: '请选择方案',
          actions: [{ id: 'approve', title: '批准' }],
          pending: true,
          formToken,
          messageId: 'hitl-retry',
        },
      },
    };
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: { data: { records: [persistedPendingCard], nextCursor: null, hasMore: false } },
    } as never);
    vi.spyOn(agentApi, 'listMessages').mockResolvedValue({
      data: { data: [persistedPendingCard] },
    } as never);
    vi.spyOn(agentApi, 'getRuntimeStatus').mockResolvedValue({
      data: { data: { activeTurn: null } },
    } as never);
    vi.spyOn(agentApi, 'listTurnEvents').mockResolvedValue({
      data: { data: [] },
    } as never);
    sessionStorage.clear();
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
      waitingHumanInput: null,
      streamError: null,
    });

    await useAgentStore.getState().sendMessage('需要确认');
    await useAgentStore.getState().submitHumanInput('approve');

    await vi.waitFor(() => {
      const card = useAgentStore.getState().messages.find(
        (message) => message.metadata?.hitl?.formToken === formToken,
      );
      expect(card?.metadata?.hitl?.pending).toBe(true);
      expect(card?.metadata?.hitl?.selectedActionId).toBeUndefined();
    });
    expect(useAgentStore.getState().waitingHumanInput?.formToken).toBe(formToken);
  });

  it('rolls back a video plan submission that fails before server acceptance', async () => {
    const planToken = 'video-retry-after-failure';
    const pending = {
      turnId: 'turn-video-retry',
      formToken: planToken,
      taskId: '',
      formContent: '视频方案待确认',
      kind: 'video-plan',
      message: '镜头缓慢推进',
      duration: 8,
      picUrl: '/api/files/images/ref.png',
      actions: [
        { id: 'generate_video', title: '开始生成' },
        { id: 'refine', title: '继续完善' },
      ],
      expirationTime: Date.now() / 1000 + 60,
    };
    const pauseBody = sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }, pending.turnId),
      sseEnvelope(2, 'turn_paused', {
        messageId: 'video-plan-retry',
        content: '',
        hitl: pending,
      }, pending.turnId),
    ]);
    const failureBody = [
      'event: turn_failed',
      `data: ${JSON.stringify({
        turnId: '',
        seq: 0,
        type: 'turn_failed',
        payload: { code: '40901', message: '当前对话正在处理中，请稍候' },
      })}`,
      '',
      '',
    ].join('\n');
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(pauseBody, { status: 200 }))
      .mockResolvedValueOnce(new Response(failureBody, { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({ data: { data: pending } } as never);
    const persistedVideoCard = {
      id: 'video-plan-retry',
      conversationId: 'conversation-1',
      role: 'assistant' as const,
      content: '',
      createdAt: new Date().toISOString(),
      metadata: {
        kind: 'video-plan' as const,
        hitl: {
          formContent: '视频方案待确认',
          actions: pending.actions,
          pending: true,
          formToken: planToken,
          messageId: 'video-plan-retry',
        },
        videoPlan: {
          message: '镜头缓慢推进',
          duration: 8,
          picUrl: '/api/files/images/ref.png',
          actions: pending.actions,
          formToken: planToken,
          messageId: 'video-plan-retry',
        },
      },
    };
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: { data: { records: [persistedVideoCard], nextCursor: null, hasMore: false } },
    } as never);
    vi.spyOn(agentApi, 'listMessages').mockResolvedValue({
      data: { data: [persistedVideoCard] },
    } as never);
    vi.spyOn(agentApi, 'getRuntimeStatus').mockResolvedValue({
      data: { data: { activeTurn: null } },
    } as never);
    vi.spyOn(agentApi, 'listTurnEvents').mockResolvedValue({
      data: { data: [] },
    } as never);
    sessionStorage.clear();
    useProjectStore.setState({ currentProject: { id: 'project-1', name: 'Project' } as never });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
      waitingHumanInput: null,
      waitingVideoPlan: null,
      streamError: null,
    });

    await useAgentStore.getState().sendMessage('生成视频');
    await useAgentStore.getState().submitVideoPlan('generate_video');

    await vi.waitFor(() => {
      expect(useAgentStore.getState().waitingVideoPlan?.planToken).toBe(planToken);
      const card = useAgentStore.getState().messages.find(
        (message) => message.metadata?.hitl?.formToken === planToken,
      );
      expect(card?.metadata?.hitl?.pending).toBe(true);
      expect(card?.metadata?.hitl?.selectedActionId).toBeUndefined();
    });
  });

  it('does not restore a pending card for an already submitted form token', async () => {
    const formToken = 'form-submitted';
    const pending = {
      turnId: 'turn-1',
      formToken,
      taskId: '',
      formContent: '请选择',
      actions: [{ id: 'option-0', title: '选项 A' }],
      expirationTime: Date.now() / 1000 + 60,
    };
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({ data: { data: pending } } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'hitl-message',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '请选择',
        createdAt: new Date().toISOString(),
        metadata: {
          kind: 'hitl',
          hitl: {
            formToken,
            formContent: '请选择',
            actions: [{ id: 'option-0', title: '选项 A' }],
            pending: false,
            selectedActionId: 'option-0',
          },
        },
      }],
      waitingHumanInput: pending,
    });

    await useAgentStore.getState().recoverPendingCheckpoint('conversation-1');

    expect(useAgentStore.getState().waitingHumanInput).toBeNull();
  });

  it('does not duplicate a persisted submitted card while replaying turn_paused', () => {
    const turnId = 'turn-replay-hitl';
    const messageId = 'message-hitl-1';
    const context = { conversationId: 'conversation-1', assistantId: 'restored-assistant' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: messageId,
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '执行方案待确认',
        createdAt: new Date().toISOString(),
        metadata: {
          kind: 'hitl',
          hitl: {
            formToken: 'form-1',
            formContent: '执行方案待确认',
            actions: [{ id: 'approve', title: '按方案执行' }],
            pending: false,
            selectedActionId: 'approve',
          },
        },
      }, {
        id: 'restored-assistant',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: {
        'conversation-1': {
          hint: '',
          turnId,
          assistantId: 'restored-assistant',
          assistantContent: '',
        },
      },
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_paused', {
        messageId,
        content: '执行方案待确认',
        hitl: {
          formToken: 'form-1',
          formContent: '执行方案待确认',
          actions: [{ id: 'approve', title: '按方案执行' }],
        },
      }, turnId),
      context,
    );

    const state = useAgentStore.getState();
    expect(state.messages.filter((message) => message.id === messageId)).toHaveLength(1);
    expect(state.waitingHumanInput).toBeNull();
  });

  it('clears a replayed HITL wait when the same turn resumes', () => {
    const turnId = 'turn-resume-clear';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-1' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      waitingHumanInput: {
        turnId,
        formToken: 'form-1',
        taskId: '',
        formContent: '请确认',
        actions: [{ id: 'approve', title: '批准' }],
        expirationTime: Date.now() + 60_000,
      },
      waitingVideoPlan: {
        planToken: 'video-1',
        message: '视频方案',
        duration: 8,
        picUrl: '',
        actions: [],
      },
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
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1', resume: true }, turnId),
      context,
    );

    expect(useAgentStore.getState().waitingHumanInput).toBeNull();
    expect(useAgentStore.getState().waitingVideoPlan).toBeNull();
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

describe('agentStore 生成偏好下发', () => {
  const customPreferences = {
    auto: false,
    ignoreGenerationPlan: false,
    image: { model: 'doubao-seedream-4-0-250828', aspectRatio: '16:9', clarity: '2K', clarityParam: 'size' as const },
    video: { model: '', aspectRatio: '', clarity: '', clarityParam: '' as const },
  };

  const prepare = () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'turn_completed', { content: '收到' }),
    ]), {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      conversations: [],
      messages: [],
      streaming: false,
      generating: {},
      waitingHumanInput: null,
      waitingVideoPlan: null,
      agentVideoTask: null,
      canvasBinding: null,
      canvasExitPending: false,
      streamError: null,
    });
    return fetchMock;
  };

  const requestBody = (fetchMock: ReturnType<typeof vi.fn>) =>
    JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body)) as Record<string, unknown>;

  afterEach(() => {
    vi.restoreAllMocks();
    useAgentStore.setState({ generationPreferences: defaultGenerationPreferences() });
  });

  it('自定义模式随请求下发比例与清晰度', async () => {
    const fetchMock = prepare();
    useAgentStore.setState({ generationPreferences: customPreferences });

    await useAgentStore.getState().sendMessage('来张图');

    expect(requestBody(fetchMock).generationPreferences).toEqual({
      auto: false,
      ignoreGenerationPlan: false,
      image: { model: 'doubao-seedream-4-0-250828', aspectRatio: '16:9', size: '2K' },
      video: {},
    });
  });

  it('自动且不忽略方案时不带该字段，忽略方案时只带开关', async () => {
    const autoFetch = prepare();
    useAgentStore.setState({ generationPreferences: defaultGenerationPreferences() });
    await useAgentStore.getState().sendMessage('来张图');
    expect(requestBody(autoFetch).generationPreferences).toBeUndefined();

    const ignoreFetch = prepare();
    useAgentStore.setState({
      generationPreferences: { ...defaultGenerationPreferences(), ignoreGenerationPlan: true },
    });
    await useAgentStore.getState().sendMessage('来张图');
    expect(requestBody(ignoreFetch).generationPreferences).toEqual({
      auto: true,
      ignoreGenerationPlan: true,
      image: {},
      video: {},
    });
  });
});

describe('agentStore seq 缺口主动补偿', () => {
  let listTurnEvents: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.useFakeTimers();
    listTurnEvents = vi.spyOn(agentApi, 'listTurnEvents')
      .mockResolvedValue({ data: { data: [] } } as never);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [],
      generating: {},
      streaming: true,
      streamError: null,
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    clearTurnGapRecovery();
    useAgentStore.setState({
      activeConversationId: null,
      messages: [],
      generating: {},
      streaming: false,
      streamError: null,
    });
  });

  it('缺口超过超时时间才触发一次 afterSeq 补偿，且不会轮询成死循环', async () => {
    const turnId = 'turn-gap-timeout';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-gap' };
    useAgentStore.setState({
      messages: [{
        id: 'assistant-gap',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: { 'conversation-1': { hint: '', turnId, assistantId: 'assistant-gap' } },
    });

    // seq=1 到达，seq=2 丢失，seq=3 先到 → sequencer 只放行 1，缺口停在 2。
    applyAgentTurnEnvelope(
      sseEnvelope(1, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'a' } }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(3, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'c' } }, turnId),
      context,
    );

    // 缺口未超时前不补拉（不猜序号、不提前放行）
    await vi.advanceTimersByTimeAsync(TURN_GAP_TIMEOUT_MS - 1);
    expect(listTurnEvents).not.toHaveBeenCalled();
    expect(useAgentStore.getState().messages[0].content).toBe('a');

    // 超时后正好补拉一次（afterSeq 取 sequencer.lastSeq = 1，仍由 store 内部统一给出）
    await vi.advanceTimersByTimeAsync(1);
    expect(listTurnEvents).toHaveBeenCalledTimes(1);
    expect(listTurnEvents).toHaveBeenCalledWith('conversation-1', turnId, 1);

    // 缺口仍在：再来一次乱序事件才会按退避补第二次
    applyAgentTurnEnvelope(
      sseEnvelope(3, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'c' } }, turnId),
      context,
    );
    await vi.advanceTimersByTimeAsync(turnGapTimeoutForAttempt(1));
    expect(listTurnEvents).toHaveBeenCalledTimes(2);

    applyAgentTurnEnvelope(
      sseEnvelope(3, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'c' } }, turnId),
      context,
    );
    await vi.advanceTimersByTimeAsync(turnGapTimeoutForAttempt(2));
    expect(listTurnEvents).toHaveBeenCalledTimes(TURN_GAP_MAX_ATTEMPTS);
    expect(turnGapRecoverySnapshot(turnId).attempts).toBe(TURN_GAP_MAX_ATTEMPTS);

    // 到达上限后彻底停止：新的乱序事件也不再排期补偿
    applyAgentTurnEnvelope(
      sseEnvelope(3, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'c' } }, turnId),
      context,
    );
    await vi.advanceTimersByTimeAsync(turnGapTimeoutForAttempt(5));
    expect(listTurnEvents).toHaveBeenCalledTimes(TURN_GAP_MAX_ATTEMPTS);

    // 整个过程中没有猜测序号：seq=3 的正文始终没有被提前放行
    expect(useAgentStore.getState().messages[0].content).toBe('a');
  });

  it('补偿事件补齐缺口后归并放行且停止补偿', async () => {
    const turnId = 'turn-gap-heal';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-heal' };
    useAgentStore.setState({
      messages: [{
        id: 'assistant-heal',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: { 'conversation-1': { hint: '', turnId, assistantId: 'assistant-heal' } },
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'a' } }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(3, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'c' } }, turnId),
      context,
    );
    expect(useAgentStore.getState().messages[0].content).toBe('a');

    // 补偿拉回缺失的 seq=2：必须按序补进正文，然后才放行已到达的 seq=3
    listTurnEvents.mockResolvedValue({
      data: {
        data: [sseEnvelope(2, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'b' } }, turnId)],
      },
    } as never);
    await vi.advanceTimersByTimeAsync(TURN_GAP_TIMEOUT_MS);

    expect(listTurnEvents).toHaveBeenCalledTimes(1);
    expect(useAgentStore.getState().messages[0].content).toBe('abc');
    expect(turnGapRecoverySnapshot(turnId).scheduled).toBe(false);

    // 缺口已补齐：后续再推进时间不再产生补偿拉取
    await vi.advanceTimersByTimeAsync(TURN_GAP_TIMEOUT_MS * 4);
    expect(listTurnEvents).toHaveBeenCalledTimes(1);
  });

  it('正常按序到达时不安排任何补偿', async () => {
    const turnId = 'turn-no-gap';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-ok' };
    useAgentStore.setState({
      messages: [{
        id: 'assistant-ok',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: { 'conversation-1': { hint: '', turnId, assistantId: 'assistant-ok' } },
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'a' } }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(2, 'agent_event', { eventType: 'TEXT_BLOCK_DELTA', data: { delta: 'b' } }, turnId),
      context,
    );

    expect(turnGapRecoverySnapshot(turnId).scheduled).toBe(false);
    await vi.advanceTimersByTimeAsync(TURN_GAP_TIMEOUT_MS * 3);
    expect(listTurnEvents).not.toHaveBeenCalled();
    expect(useAgentStore.getState().messages[0].content).toBe('ab');
  });
});

describe('产物卡片幂等（assetId）', () => {
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

  it('按 assetId 去重并给出稳定 key，缺 assetId 时退化到 url', () => {
    const attachments: AgentAttachment[] = [
      { url: '/api/files/a.png', name: 'a.png', mimeType: 'image/png', kind: 'image', assetId: 'asset-1' },
      // 同一资产的不同 url（重新签名 / CDN 换链）不能变成第二张卡
      { url: '/api/files/a-signed.png', name: 'a.png', mimeType: 'image/png', kind: 'image', assetId: 'asset-1' },
      { url: '/api/files/b.png', name: 'b.png', mimeType: 'image/png', kind: 'image', assetId: 'asset-2' },
      // 无 assetId：按 url 去重
      { url: '/api/files/c.md', name: 'c.md', mimeType: 'text/markdown', kind: 'file' },
      { url: '/api/files/c.md', name: 'c.md', mimeType: 'text/markdown', kind: 'file' },
    ];

    const deduped = dedupeAttachments(attachments);

    expect(deduped).toHaveLength(3);
    expect(deduped.map((item) => item.url)).toEqual([
      '/api/files/a.png',
      '/api/files/b.png',
      '/api/files/c.md',
    ]);
    expect(attachmentKey(deduped[0], 0)).toBe('asset-asset-1');
    expect(attachmentKey(deduped[2], 2)).toBe('url-/api/files/c.md');
  });

  it('重放同一个 terminal envelope 不会复制或重排产物卡片', () => {
    const turnId = 'turn-attachments';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-attachments' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-attachments',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: { 'conversation-1': { hint: '', turnId, assistantId: 'assistant-attachments' } },
    });

    const sandboxAttachments = [
      { url: '/api/files/a.png', name: 'a.png', mimeType: 'image/png', kind: 'image', assetId: 'asset-1' },
      { url: '/api/files/a-signed.png', name: 'a.png', mimeType: 'image/png', kind: 'image', assetId: 'asset-1' },
      { url: '/api/files/b.md', name: 'b.md', mimeType: 'text/markdown', kind: 'file', assetId: 'asset-2' },
    ];

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_completed', { content: '产物已发布', metadata: { sandboxAttachments } }, turnId),
      context,
    );
    expect(dedupeAttachments(useAgentStore.getState().messages[0].metadata?.sandboxAttachments ?? []))
      .toHaveLength(2);

    // 同一 turn 再重放一遍（afterSeq 补偿 / 刷新后的恢复轮询）：顺序与数量都不变
    useAgentStore.setState({
      generating: { 'conversation-1': { hint: '', turnId, assistantId: 'assistant-attachments' } },
    });
    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_completed', { content: '产物已发布', metadata: { sandboxAttachments } }, turnId),
      context,
    );

    const replayed = dedupeAttachments(
      useAgentStore.getState().messages[0].metadata?.sandboxAttachments ?? [],
    );
    expect(replayed.map((item) => attachmentKey(item, 0))).toEqual(['asset-asset-1', 'asset-asset-2']);
  });
});

describe('空 formToken 的 HITL 卡片重放后不可点击', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useAgentStore.setState({
      activeConversationId: null,
      messages: [],
      generating: {},
      streaming: false,
      waitingHumanInput: null,
      waitingVideoPlan: null,
      hitlVideoPlan: {},
      pendingAssistantId: null,
    });
  });

  const replayPaused = (hitl: Record<string, unknown>, turnId: string) => {
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-blank' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      waitingHumanInput: null,
      messages: [{
        id: 'assistant-blank',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: { 'conversation-1': { hint: '', turnId, assistantId: 'assistant-blank' } },
    });
    applyAgentTurnEnvelope(
      sseEnvelope(1, 'turn_paused', { messageId: 'hitl-blank', content: '请确认', hitl }, turnId),
      context,
    );
    return useAgentStore.getState();
  };

  it('formToken 为空字符串时不装配待确认状态，并标记卡片为只读', () => {
    const state = replayPaused({
      formToken: '',
      formContent: '请确认是否继续',
      actions: [{ id: 'approve', title: '批准' }],
      expirationTime: Date.now() + 60_000,
    }, 'turn-blank-token');

    expect(state.waitingHumanInput).toBeNull();
    expect(state.waitingVideoPlan).toBeNull();
    expect(state.messages[0].metadata?.hitl?.inactive).toBe(true);
    expect(state.messages[0].metadata?.hitl?.formToken).toBe('');
  });

  it('formToken 全为空白字符时同样不可交互', () => {
    const state = replayPaused({
      formToken: '   ',
      formContent: '请确认是否继续',
      actions: [{ id: 'approve', title: '批准' }],
    }, 'turn-whitespace-token');

    expect(state.waitingHumanInput).toBeNull();
    expect(state.messages[0].metadata?.hitl?.inactive).toBe(true);
  });

  it('有 formToken 的卡片行为不变：仍然装配待确认状态且可交互', () => {
    const state = replayPaused({
      formToken: 'form-live',
      formContent: '请确认是否继续',
      actions: [{ id: 'approve', title: '批准' }],
      expirationTime: Date.now() + 60_000,
    }, 'turn-live-token');

    expect(state.waitingHumanInput?.formToken).toBe('form-live');
    expect(state.messages[0].metadata?.hitl?.inactive).toBeUndefined();
  });
});

describe('render 意图分派', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useAgentStore.setState({ activeConversationId: null, generating: {}, messages: [], streaming: false });
  });

  it('缺 kind 的载荷不算渲染意图', () => {
    expect(parseRenderIntent({ id: 'x', items: [] })).toBeNull();
    expect(parseRenderIntent('table')).toBeNull();
    expect(parseRenderIntent(null)).toBeNull();
  });

  it('按 id 去重同时保留未知 kind', () => {
    const intents = collectRenderIntents([
      { kind: 'table', id: 'r1', items: [{ a: 1 }], version: 1 },
      { kind: 'table', id: 'r1', items: [{ a: 1 }], version: 2 },
      { kind: 'timeline-future', id: 'r2', items: [] },
      { kind: 'list', id: 'r3', items: ['一'] },
    ], []);

    // 去重按 id；排序把带 version 的意图排在后面（version 越大越新），未知 kind 原样保留
    expect(intents.map((intent) => intent.kind)).toEqual(['timeline-future', 'list', 'table']);
    expect(intents).toHaveLength(3);
    expect(intents[2].version).toBe(1);
  });

  it('合并 metadata.renders 与工具草稿里的 render 并去重', () => {
    const merged = collectRenderIntents(
      [{ kind: 'gallery', id: 'g1', items: [{ url: '/a.png' }] }],
      [{ kind: 'table', id: 't1', items: [] }, { kind: 'gallery', id: 'g1', items: [{ url: '/a.png' }] }],
    );

    // 结果 metadata 在前（服务端权威），草稿里的重复项被去掉，只会各出现一次
    expect(merged.map((intent) => intent.id)).toEqual(['g1', 't1']);
    expect(merged).toHaveLength(2);
  });

  it('工具结果携带 render 时落进草稿，终态 metadata 汇总 renders', () => {
    const turnId = 'turn-render-intent';
    const context = { conversationId: 'conversation-1', assistantId: 'assistant-render' };
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      messages: [{
        id: 'assistant-render',
        conversationId: 'conversation-1',
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
      generating: { 'conversation-1': { hint: '', turnId, assistantId: 'assistant-render' } },
    });

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'agent_event', {
        eventType: 'TOOL_RESULT_END',
        data: {
          toolName: 'run_python_script',
          toolCallId: 'call-1',
          state: 'SUCCESS',
          delta: 'done',
          render: { kind: 'table', id: 'render-1', status: 'completed', items: [{ 名称: 'A', 数量: 1 }], version: 1 },
        },
      }, turnId),
      context,
    );

    const draft = useAgentStore.getState().generating['conversation-1']?.toolDrafts?.['call-1'];
    expect(draft?.name).toBe('run_python_script');
    expect(draft?.render?.kind).toBe('table');

    applyAgentTurnEnvelope(
      sseEnvelope(2, 'turn_completed', { content: '完成' }, turnId),
      context,
    );

    const metadata = useAgentStore.getState().messages[0].metadata;
    expect(metadata?.renders?.map((intent) => intent.kind)).toEqual(['table']);
    expect(metadata?.toolDrafts?.['call-1']?.status).toBe('node_completed');
  });
});

/**
 * WS4：刷新后不再"全量重放 + 1s 轮询"，而是补完历史后接到 turn 的直播续传端点上
 * （`GET /turns/{turnId}/stream?afterSeq=N`）；端点不可用时回退到既有轮询回放。
 */
describe('agentStore turn 事件续传（刷新恢复）', () => {
  const conversationId = 'conversation-live-resume';

  const resetStore = () => {
    useAgentStore.setState({
      activeConversationId: null,
      conversations: [],
      messages: [],
      generating: {},
      streaming: false,
      pendingAssistantId: null,
      tasks: {},
      waitingHumanInput: null,
    });
    window.sessionStorage.clear();
  };

  const stubConversationShell = (turnId: string) => {
    vi.spyOn(agentApi, 'getConversation').mockResolvedValue({
      data: { data: { conversation: { canvasContext: null } } },
    } as never);
    vi.spyOn(agentApi, 'listMessagesPage').mockResolvedValue({
      data: { data: { records: [], nextCursor: null, hasMore: false } },
    } as never);
    vi.spyOn(agentApi, 'listMessages').mockResolvedValue({ data: { data: [] } } as never);
    vi.spyOn(agentApi, 'listTasks').mockResolvedValue({
      data: { data: { records: [], total: 0, page: 1, size: 100 } },
    } as never);
    vi.spyOn(agentApi, 'getPendingCanvasSession').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'getPendingCheckpoint').mockResolvedValue({ data: { data: null } } as never);
    vi.spyOn(agentApi, 'getRuntimeStatus').mockResolvedValue({
      data: { data: { activeTurn: { turnId, status: 'running' } } },
    } as never);
  };

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    resetStore();
  });

  it('冷启动补齐历史后，从历史末序号接上直播且不重复应用', async () => {
    const turnId = 'turn-live-attach';
    stubConversationShell(turnId);
    const listTurnEvents = vi.spyOn(agentApi, 'listTurnEvents').mockResolvedValue({
      data: {
        data: [
          sseEnvelope(1, 'turn_started', { conversationId }, turnId),
          sseEnvelope(2, 'agent_event', {
            eventType: 'TEXT_BLOCK_DELTA',
            data: { delta: '头' },
          }, turnId),
        ],
      },
    } as never);
    const fetchMock = vi.fn().mockResolvedValue(new Response(sseBody([
      sseEnvelope(3, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: '尾' },
      }, turnId),
    ]), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }));
    vi.stubGlobal('fetch', fetchMock);
    resetStore();

    await useAgentStore.getState().selectConversation(conversationId);

    await vi.waitFor(() => {
      const assistant = useAgentStore.getState().messages.find((message) => message.role === 'assistant');
      expect(assistant?.content ?? '').toBe('头尾');
    });
    expect(listTurnEvents).toHaveBeenCalledWith(conversationId, turnId, 0);
    // 直播起点 = 已经补过的历史末序号（2），不是"要跳过多少历史"的游标（0）。
    expect(String(fetchMock.mock.calls[0]?.[0]))
      .toContain(`/turns/${turnId}/stream?afterSeq=2`);
    // 历史只补一次：文本不会因为直播重放历史而重复。
    const assistant = useAgentStore.getState().messages.find((message) => message.role === 'assistant');
    expect(assistant?.content ?? '').not.toContain('头头');
    // 本用例故意不给终态：直播事件到手即止，断开后由 afterSeq 补偿接续。
    useAgentStore.setState({ activeConversationId: null });
  });

  it('续传端点不可用时回退到轮询回放（并从历史末序号继续）', async () => {
    const turnId = 'turn-live-fallback';
    stubConversationShell(turnId);
    const listTurnEvents = vi.spyOn(agentApi, 'listTurnEvents').mockImplementation(
      async (_id, _turn, afterSeq = 0) => ({
        data: {
          data: afterSeq >= 2
            ? [sseEnvelope(3, 'turn_completed', { content: '完成' }, turnId)]
            : [
              sseEnvelope(1, 'turn_started', { conversationId }, turnId),
              sseEnvelope(2, 'agent_event', {
                eventType: 'TEXT_BLOCK_DELTA',
                data: { delta: '历史' },
              }, turnId),
            ],
        },
      } as never),
    );
    const fetchMock = vi.fn().mockResolvedValue(new Response('not found', { status: 404 }));
    vi.stubGlobal('fetch', fetchMock);
    resetStore();

    await useAgentStore.getState().selectConversation(conversationId);

    await vi.waitFor(() => {
      expect(listTurnEvents).toHaveBeenCalledWith(conversationId, turnId, 2);
    });
    expect(listTurnEvents).toHaveBeenCalledWith(conversationId, turnId, 0);
    expect(String(fetchMock.mock.calls[0]?.[0]))
      .toContain(`/turns/${turnId}/stream?afterSeq=2`);
    // 终态收尾：续传游标被清理，该 turn 不会再被续传。
    await vi.waitFor(() => {
      expect(readTurnLastSeq(conversationId, turnId)).toBe(0);
    });
    useAgentStore.setState({ activeConversationId: null });
  });

  it('直播事件乱序到达时按 seq 等待缺口，补齐后按序应用', async () => {
    const turnId = 'turn-live-gap';
    stubConversationShell(turnId);
    vi.spyOn(agentApi, 'listTurnEvents').mockImplementation(
      async (_id, _turn, afterSeq = 0) => ({
        data: {
          data: [
            sseEnvelope(1, 'turn_started', { conversationId }, turnId),
            sseEnvelope(2, 'agent_event', {
              eventType: 'TEXT_BLOCK_DELTA',
              data: { delta: 'a' },
            }, turnId),
          ].filter((event) => event.seq > afterSeq),
        },
      } as never),
    );
    // 故意先发 seq 4 再发 seq 3：缺口必须被等待，不能把 4 提前应用。
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(sseBody([
      sseEnvelope(4, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: 'd' },
      }, turnId),
      sseEnvelope(3, 'agent_event', {
        eventType: 'TEXT_BLOCK_DELTA',
        data: { delta: 'c' },
      }, turnId),
    ]), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })));
    resetStore();

    await useAgentStore.getState().selectConversation(conversationId);

    await vi.waitFor(() => {
      const assistant = useAgentStore.getState().messages.find((message) => message.role === 'assistant');
      // 4 先到但被缺口挡住，3 补齐后才按 3→4 应用（否则会得到 'adc'）。
      expect(assistant?.content ?? '').toBe('acd');
    });
    useAgentStore.setState({ activeConversationId: null });
  });

  it('canvas_run_* 透传 canvasPatch / canvasPatchOverflow，且不被后续事件抹掉', () => {
    const conversationIdForPatch = 'conversation-canvas-patch';
    const turnId = 'turn-canvas-patch';
    const assistantId = 'assistant-canvas-patch';
    const patch = {
      nodes: [{ id: 'node-1', type: 'imageGeneration' }],
      edges: [{ id: 'edge-1', source: 'node-1', target: 'node-2' }],
    };
    useAgentStore.setState({
      activeConversationId: conversationIdForPatch,
      messages: [{
        id: assistantId,
        conversationId: conversationIdForPatch,
        role: 'assistant',
        content: '',
        createdAt: new Date().toISOString(),
      }],
    });
    const context = { conversationId: conversationIdForPatch, assistantId };

    applyAgentTurnEnvelope(
      sseEnvelope(1, 'canvas_run_started', { canvasRunId: 'run-1', canvasId: 'canvas-1' }, turnId),
      context,
    );
    applyAgentTurnEnvelope(
      sseEnvelope(2, 'canvas_run_updated', {
        canvasRunId: 'run-1',
        canvasId: 'canvas-1',
        canvasPatch: patch,
        canvasPatchOverflow: false,
      }, turnId),
      context,
    );

    let metadata = useAgentStore.getState().messages
      .find((message) => message.id === assistantId)?.metadata;
    expect(metadata?.canvasPatch).toEqual(patch);
    expect(metadata?.canvasPatchOverflow).toBe(false);

    // 后续事件不带补丁字段：已有值保留（与 nodeCount / revision 等字段同一风格）。
    applyAgentTurnEnvelope(
      sseEnvelope(3, 'canvas_run_started', { canvasRunId: 'run-1', canvasId: 'canvas-1' }, turnId),
      context,
    );
    metadata = useAgentStore.getState().messages
      .find((message) => message.id === assistantId)?.metadata;
    expect(metadata?.canvasPatch).toEqual(patch);
    expect(metadata?.canvasPatchOverflow).toBe(false);

    // 类型不对（字符串）不写入，也不覆盖已有值。
    applyAgentTurnEnvelope(
      sseEnvelope(4, 'canvas_run_updated', {
        canvasRunId: 'run-1',
        canvasId: 'canvas-1',
        canvasPatch: 'oops',
        canvasPatchOverflow: 'yes',
      }, turnId),
      context,
    );
    metadata = useAgentStore.getState().messages
      .find((message) => message.id === assistantId)?.metadata;
    expect(metadata?.canvasPatch).toEqual(patch);
    expect(metadata?.canvasPatchOverflow).toBe(false);
  });
});

describe('agentStore @ 引用资料', () => {
  const sceneReference = {
    kind: 'scene' as const,
    id: 'scene-3',
    token: '@分镜3',
    name: '分镜3',
    sceneNumber: 3,
  };
  const conversationReference = {
    kind: 'conversation' as const,
    id: 'conversation-9',
    token: '@对话《分镜重做》',
    name: '对话《分镜重做》',
  };

  const prepare = () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(sseBody([
      sseEnvelope(1, 'turn_started', { conversationId: 'conversation-1' }),
      sseEnvelope(2, 'turn_completed', { content: '收到' }),
    ]), {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      conversations: [],
      messages: [],
      streaming: false,
      generating: {},
      pendingAssistantId: null,
      waitingHumanInput: null,
      waitingVideoPlan: null,
      agentVideoTask: null,
      canvasBinding: null,
      canvasExitPending: false,
      streamError: null,
      pendingReferences: [],
      pendingAttachments: [],
    });
    return fetchMock;
  };

  const requestBody = (fetchMock: ReturnType<typeof vi.fn>) =>
    JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body)) as Record<string, unknown>;

  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    window.localStorage.clear();
  });

  it('按 kind:id 去重，同名不同类互不影响', () => {
    const store = useAgentStore.getState();
    store.addPendingReferences([sceneReference]);
    store.addPendingReferences([sceneReference, conversationReference]);

    expect(useAgentStore.getState().pendingReferences).toEqual([
      sceneReference,
      conversationReference,
    ]);
  });

  it('引用目录按会话持久化，切换会话时按会话恢复', () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1', pendingReferences: [] });
    useAgentStore.getState().addPendingReferences([sceneReference]);

    const persisted = window.localStorage.getItem('moon.agent.pendingReferences.conversation-1');
    expect(persisted).toContain('@分镜3');
  });

  it('移除与清空都同步更新本地存储', () => {
    useAgentStore.setState({ activeConversationId: 'conversation-1', pendingReferences: [] });
    const store = useAgentStore.getState();
    store.addPendingReferences([sceneReference, conversationReference]);
    store.removePendingReference('scene', 'scene-3');
    expect(useAgentStore.getState().pendingReferences).toEqual([conversationReference]);

    useAgentStore.getState().clearPendingReferences();
    expect(useAgentStore.getState().pendingReferences).toEqual([]);
    expect(window.localStorage.getItem('moon.agent.pendingReferences.conversation-1')).toBeNull();
  });

  it('发送时把正文里还存在的引用随请求下发（不依赖调用方显式传参，停止并发送同一条路径）', async () => {
    const fetchMock = prepare();
    useAgentStore.getState().addPendingReferences([sceneReference]);

    // 只传正文、不传 opts.references：模拟 interruptAndSend 的发送路径
    await useAgentStore.getState().sendMessage('把 @分镜3 的镜头拉近一点');

    expect(requestBody(fetchMock).references).toEqual([
      { ...sceneReference },
    ]);
  });

  it('正文里没有标记的引用不下发（用户删掉标记=撤销引用）', async () => {
    const fetchMock = prepare();
    useAgentStore.getState().addPendingReferences([sceneReference, conversationReference]);

    await useAgentStore.getState().sendMessage('什么都不引用了');

    expect(requestBody(fetchMock).references).toEqual([]);
  });

  it('用户消息 metadata 落库引用清单，刷新后气泡仍能渲染标记', async () => {
    prepare();
    useAgentStore.getState().addPendingReferences([sceneReference]);

    await useAgentStore.getState().sendMessage('看看 @分镜3');

    const userMessage = useAgentStore.getState().messages
      .find((message) => message.role === 'user');
    expect(userMessage?.metadata?.messageInput?.references).toEqual([{ ...sceneReference }]);
  });

  it('编辑重发：引用以编辑后的正文为准——删掉标记的引用不复活', async () => {
    const fetchMock = prepare();
    useAgentStore.setState({
      messages: [{
        id: 'user-1',
        conversationId: 'conversation-1',
        role: 'user',
        content: '看看 @分镜3',
        createdAt: new Date().toISOString(),
        metadata: { messageInput: { references: [sceneReference, conversationReference] } },
      }],
    });

    await useAgentStore.getState().editAndResend('什么都不引用了', 'user-1');

    // 显式空数组 = 明确告诉后端「改后不再引用」，后端据此不再从 previousInput 恢复
    expect(requestBody(fetchMock).references).toEqual([]);
  });

  it('编辑重发：正文里仍在的标记继续带走引用', async () => {
    const fetchMock = prepare();
    useAgentStore.setState({
      messages: [{
        id: 'user-1',
        conversationId: 'conversation-1',
        role: 'user',
        content: '看看 @分镜3',
        createdAt: new Date().toISOString(),
        metadata: { messageInput: { references: [sceneReference, conversationReference] } },
      }],
    });

    await useAgentStore.getState().editAndResend('还是只看 @分镜3', 'user-1');

    expect(requestBody(fetchMock).references).toEqual([{ ...sceneReference }]);
  });
});


