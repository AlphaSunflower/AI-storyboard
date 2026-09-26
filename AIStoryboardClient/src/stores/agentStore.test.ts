import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentConversation, type AgentTaskView } from '../api/agent';
import { taskStatusLabel, useAgentStore } from './agentStore';
import { useProjectStore } from './projectStore';

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

    expect(createConversation).toHaveBeenCalledWith('project-1', '新对话');
    expect(selectConversation).toHaveBeenCalledWith('conversation-1');
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
    const body = [
      'event: message',
      'data: {"content":"完成"}',
      '',
      'event: message_end',
      `data: ${JSON.stringify({ content: '完成', metrics })}`,
      '',
      '',
    ].join('\n');
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
    const body = [
      'event: task_accepted',
      `data: ${JSON.stringify({ taskId: 'task-1', taskKind: 'long-tool', message: '任务已受理' })}`,
      '',
      'event: message_end',
      `data: ${JSON.stringify({ content: '任务已受理，完成后会展示结果。' })}`,
      '',
      '',
    ].join('\n');
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
    const body = [
      'event: task_accepted',
      `data: ${JSON.stringify({ taskId: 'task-1', taskKind: 'image-batch', message: '任务已受理' })}`,
      '',
      'event: message_end',
      `data: ${JSON.stringify({ content: '批量生图任务已受理。' })}`,
      '',
      '',
    ].join('\n');
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
    const body = [
      'event: task_accepted',
      `data: ${JSON.stringify({ taskId: 'task-1', taskKind: 'long-tool', message: '任务已受理' })}`,
      '',
      '',
    ].join('\n');
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
    const body = [
      'event: task_accepted',
      `data: ${JSON.stringify({ taskId: 'task-1', taskKind: 'video', message: '视频任务已受理' })}`,
      '',
      '',
    ].join('\n');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    vi.spyOn(agentApi, 'interrupt').mockResolvedValue({} as never);
    resetStore();

    await useAgentStore.getState().sendMessage('生成视频');

    expect(useAgentStore.getState().streaming).toBe(true);
    expect(useAgentStore.getState().generating['conversation-1']).toBeDefined();
    expect(useAgentStore.getState().agentVideoTask?.taskId).toBe('task-1');
  });

  it('removes the unfinished assistant bubble when the user stops generation', () => {
    vi.spyOn(agentApi, 'interrupt').mockResolvedValue({} as never);
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
  });

  it('replaces the last user turn and removes its old assistant reply when edited', async () => {
    const body = [
      'event: message',
      'data: {"content":"修改后的回复"}',
      '',
      'event: message_end',
      'data: {"content":"修改后的回复"}',
      '',
      '',
    ].join('\n');
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
      expect.stringContaining('/conversations/conversation-1/messages/user-1/edit-and-resend'),
      expect.objectContaining({ method: 'POST' }),
    );
    expect(state.messages[0].content).toBe('修改后的问题');
    expect(state.messages.some((message) => message.id === 'assistant-old')).toBe(false);
    expect(state.messages.at(-1)?.content).toBe('修改后的回复');
  });

  it('interrupts the active run and sends the queued message as a new turn', async () => {
    const interrupt = vi.spyOn(agentApi, 'interrupt').mockResolvedValue({} as never);
    const body = [
      'event: message',
      'data: {"content":"新回复"}',
      '',
      'event: message_end',
      'data: {"content":"新回复"}',
      '',
      '',
    ].join('\n');
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
          assistantId: 'assistant-running',
          assistantContent: '正在生成',
          finished: false,
        },
      },
    });

    await useAgentStore.getState().interruptAndSend('改成新的问题');

    expect(interrupt).toHaveBeenCalledWith('conversation-1');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/conversations/conversation-1/messages/interrupt-and-send'),
      expect.objectContaining({ method: 'POST' }),
    );
    const state = useAgentStore.getState();
    expect(state.messages.some((message) => message.id === 'assistant-running')).toBe(false);
    expect(state.messages.find((message) => message.role === 'user' && message.content === '改成新的问题')).toBeTruthy();
    expect(state.messages.at(-1)?.content).toBe('新回复');
  });
});
