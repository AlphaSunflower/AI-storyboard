import { afterEach, describe, expect, it, vi } from 'vitest';
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

function sseEnvelope(
  seq: number,
  type: 'turn_started' | 'agent_event' | 'turn_paused' | 'turn_completed' | 'turn_failed',
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
    const listMessages = vi.spyOn(agentApi, 'listMessages')
      .mockResolvedValue({ data: { data: [] } } as never);
    useAgentStore.setState({ activeConversationId: 'conversation-1', streaming: false });

    useAgentStore.getState().upsertTask(task({ version: 1 }));
    useAgentStore.getState().upsertTask(task({
      version: 2,
      status: 'completed',
      stage: 'waiting_review',
    }));
    await vi.runOnlyPendingTimersAsync();

    expect(listMessages).toHaveBeenCalledWith('conversation-1');
    listMessages.mockRestore();
    vi.useRealTimers();
  });

  it('does not overwrite in-flight streaming messages when a task settles mid-turn', async () => {
    vi.useFakeTimers();
    const listMessages = vi.spyOn(agentApi, 'listMessages')
      .mockResolvedValue({ data: { data: [] } } as never);
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
});
