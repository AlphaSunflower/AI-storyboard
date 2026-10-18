import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentAsset, type AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
import { AgentTaskCard } from './AgentTaskCard';

const storyboardTask: AgentTaskView = {
  taskId: 'task-1',
  conversationId: 'conversation-1',
  projectId: 'project-1',
  title: '剧情生成、诊断与分镜',
  toolName: 'start_storyboard_production',
  kind: 'long-tool',
  status: 'completed',
  stage: 'completed',
  progress: 100,
  resultSummary: '分镜已完成',
  result: '- run_id: `6efa645a-724d-4459-be90-35aac504e323`',
  liveText: '',
  liveThinking: '',
  liveStage: '',
  error: '',
  parentTaskId: '',
  anchorMessageId: 'message-1',
  version: 2,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  assetIds: [],
  resultUrls: [],
  slots: [],
  canCancel: false,
  canRetry: false,
  canFollowUp: false,
};

describe('AgentTaskCard', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      tasks: {},
      sendMessage: useAgentStore.getInitialState().sendMessage,
      openTaskDrawer: useAgentStore.getInitialState().openTaskDrawer,
    });
    vi.restoreAllMocks();
  });

  it('opens the task drawer instead of routing a completed storyboard into review', () => {
    const openTaskDrawer = vi.fn();
    const sendMessage = vi.fn().mockResolvedValue(undefined);
    useAgentStore.setState({
      tasks: { [storyboardTask.taskId]: storyboardTask },
      openTaskDrawer,
      sendMessage,
    });

    render(<AgentTaskCard taskId={storyboardTask.taskId} />);
    fireEvent.click(screen.getByText(storyboardTask.title).closest('button')!);

    expect(openTaskDrawer).toHaveBeenCalledWith(storyboardTask.taskId);
    expect(sendMessage).not.toHaveBeenCalled();
  });

  it('renders the stage-A shot outline before asking for approval', async () => {
    const plotReviewTask: AgentTaskView = {
      ...storyboardTask,
      status: 'completed',
      stage: 'waiting_review',
    };
    vi.spyOn(agentApi, 'getStoryboardRun').mockResolvedValue({
      data: {
        data: {
          runId: '6efa645a-724d-4459-be90-35aac504e323',
          conversationId: 'conversation-1',
          stage: 'PLOT_REVIEW',
          version: 1,
          originalScript: '雨夜，主角走进旧仓库。',
          selectedScript: '雨夜里，一个人走进旧仓库，最后停在窗边。',
          expansionScript: '',
          pseudoShots: [
            {
              shotId: 'shot-1', segmentId: 'outline', shotOrdinal: 1,
              narrativePurpose: '', characters: ['主角'], location: '旧仓库',
              time: '夜', conflict: '', action: '主角推门进入仓库，雨水顺着衣角滴落',
              emotion: '', continuity: [], sourceExcerpt: [],
              referenceAssetIds: ['asset-1', 'asset-2'],
            },
            {
              shotId: 'shot-2', segmentId: 'outline', shotOrdinal: 2,
              narrativePurpose: '', characters: ['主角'], location: '窗边',
              time: '夜', conflict: '', action: '主角停在窗前，抬手未触碰玻璃',
              emotion: '', continuity: [], sourceExcerpt: [], referenceAssetIds: [],
            },
          ],
          finalShots: [],
          error: '',
        },
      },
    } as never);
    useAgentStore.setState({
      tasks: { [plotReviewTask.taskId]: plotReviewTask },
      openTaskDrawer: vi.fn(),
      sendMessage: vi.fn(),
    });

    render(<AgentTaskCard taskId={plotReviewTask.taskId} />);

    // 梗概 + 每一镜的画面都要能看到，才能「看过再批」
    expect(await screen.findByText(/主角推门进入仓库/)).toBeTruthy();
    expect(screen.getByText(/主角停在窗前/)).toBeTruthy();
    expect(screen.getByText(/雨夜里，一个人走进旧仓库/)).toBeTruthy();
    expect(screen.getByLabelText('镜头大纲')).toBeTruthy();
    expect(screen.getByRole('button', { name: '确认剧情' })).toBeTruthy();
  });

  it('edits one outline line in place and saves the draft with the current version', async () => {
    const plotReviewTask: AgentTaskView = {
      ...storyboardTask,
      status: 'completed',
      stage: 'waiting_review',
    };
    const run = {
      runId: '6efa645a-724d-4459-be90-35aac504e323',
      conversationId: 'conversation-1',
      stage: 'PLOT_REVIEW',
      version: 3,
      originalScript: '雨夜，主角走进旧仓库。',
      selectedScript: '雨夜里，一个人走进旧仓库，停在窗边。',
      expansionScript: '',
      pseudoShots: [
        {
          shotId: 'shot-1', segmentId: 'outline', shotOrdinal: 1,
          narrativePurpose: '', characters: ['主角'], location: '旧仓库',
          time: '夜', conflict: '', action: '第一镜画面',
          emotion: '', continuity: [], sourceExcerpt: [], referenceAssetIds: [],
        },
        {
          shotId: 'shot-2', segmentId: 'outline', shotOrdinal: 2,
          narrativePurpose: '', characters: ['主角'], location: '窗边',
          time: '夜', conflict: '', action: '第二镜画面',
          emotion: '', continuity: [], sourceExcerpt: [], referenceAssetIds: [],
        },
      ],
      finalShots: [],
      error: '',
    };
    vi.spyOn(agentApi, 'getStoryboardRun').mockResolvedValue({ data: { data: run } } as never);
    const save = vi.spyOn(agentApi, 'saveStoryboardDraft').mockImplementation(
      (async (_runId: string, payload: { pseudoShots: typeof run.pseudoShots }) => ({
        data: { data: { ...run, version: run.version + 1, pseudoShots: payload.pseudoShots } },
      })) as never,
    );
    useAgentStore.setState({
      tasks: { [plotReviewTask.taskId]: plotReviewTask },
      openTaskDrawer: vi.fn(),
      sendMessage: vi.fn(),
    });

    render(<AgentTaskCard taskId={plotReviewTask.taskId} />);

    fireEvent.click(await screen.findByRole('button', { name: '编辑第 2 镜画面' }));
    fireEvent.change(screen.getByLabelText('编辑第 2 镜画面'), {
      target: { value: '改过之后的第二镜' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(save).toHaveBeenCalled());
    const [, payload] = (save.mock.calls[0] as unknown) as [string, {
      expectedVersion: number;
      pseudoShots: { action: string }[];
    }];
    // 整份草稿按 version 原子提交，未编辑的行必须原样带回
    expect(payload.expectedVersion).toBe(3);
    expect(payload.pseudoShots[0].action).toBe('第一镜画面');
    expect(payload.pseudoShots[1].action).toBe('改过之后的第二镜');
    // 改后的文本现在会出现两处（大纲行 + 变更审阅列表），所以按「至少一处」断言
    expect((await screen.findAllByText('改过之后的第二镜')).length).toBeGreaterThan(0);
  });

  it('lists changed outline lines with before/after and can revert them', async () => {
    const plotReviewTask: AgentTaskView = {
      ...storyboardTask,
      status: 'completed',
      stage: 'waiting_review',
    };
    const run = {
      runId: '6efa645a-724d-4459-be90-35aac504e323',
      conversationId: 'conversation-1',
      stage: 'PLOT_REVIEW',
      version: 3,
      originalScript: '',
      selectedScript: '雨夜梗概',
      expansionScript: '',
      pseudoShots: [
        {
          shotId: 'shot-1', segmentId: 'outline', shotOrdinal: 1,
          narrativePurpose: '', characters: [], location: '旧仓库',
          time: '夜', conflict: '', action: '原始画面一',
          emotion: '', continuity: [], sourceExcerpt: [], referenceAssetIds: [],
        },
      ],
      finalShots: [],
      error: '',
    };
    vi.spyOn(agentApi, 'getStoryboardRun').mockResolvedValue({ data: { data: run } } as never);
    const save = vi.spyOn(agentApi, 'saveStoryboardDraft').mockImplementation(
      (async (_runId: string, payload: { pseudoShots: typeof run.pseudoShots }) => ({
        data: { data: { ...run, version: run.version + 1, pseudoShots: payload.pseudoShots } },
      })) as never,
    );
    useAgentStore.setState({
      tasks: { [plotReviewTask.taskId]: plotReviewTask },
      openTaskDrawer: vi.fn(),
      sendMessage: vi.fn(),
    });

    render(<AgentTaskCard taskId={plotReviewTask.taskId} />);

    // 改一句 → 变更列表出现 before/after
    fireEvent.click(await screen.findByRole('button', { name: '编辑第 1 镜画面' }));
    fireEvent.change(screen.getByLabelText('编辑第 1 镜画面'), { target: { value: '改后的画面一' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    const changes = await screen.findByLabelText('本次修改');
    expect(changes.textContent).toContain('第 1 镜');
    expect(changes.textContent).toContain('原始画面一');
    expect(changes.textContent).toContain('改后的画面一');

    // 撤销 → 再次提交「改前」的值，且变更条目消失
    save.mockClear();
    fireEvent.click(screen.getByRole('button', { name: '撤销' }));

    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    const [, revertPayload] = (save.mock.calls[0] as unknown) as [string, {
      pseudoShots: { action: string }[];
    }];
    expect(revertPayload.pseudoShots[0].action).toBe('原始画面一');
    await waitFor(() => expect(screen.queryByLabelText('本次修改')).toBeNull());
    expect(await screen.findByText('原始画面一')).toBeTruthy();
  });

  it('renders persisted change history from the server', async () => {
    const plotReviewTask: AgentTaskView = {
      ...storyboardTask,
      status: 'completed',
      stage: 'waiting_review',
    };
    const run = {
      runId: '6efa645a-724d-4459-be90-35aac504e323',
      conversationId: 'conversation-1',
      stage: 'PLOT_REVIEW',
      version: 4,
      originalScript: '',
      selectedScript: '雨夜梗概',
      expansionScript: '',
      pseudoShots: [
        {
          shotId: 'shot-1', segmentId: 'outline', shotOrdinal: 1,
          narrativePurpose: '', characters: [], location: '旧仓库',
          time: '夜', conflict: '', action: '已被智能体改过的画面',
          emotion: '', continuity: [], sourceExcerpt: [], referenceAssetIds: [],
        },
      ],
      finalShots: [],
      error: '',
    };
    vi.spyOn(agentApi, 'getStoryboardRun').mockResolvedValue({ data: { data: run } } as never);
    vi.spyOn(agentApi, 'getStoryboardChanges').mockResolvedValue({
      data: {
        data: [
          {
            runId: run.runId, shotId: 'shot-1', shotOrdinal: 1, kind: 'UPDATE',
            source: 'agent', field: '画面',
            before: '智能体改前的画面', after: '已被智能体改过的画面',
            at: '2026-10-17T17:00:00+08:00',
          },
        ],
      },
    } as never);
    useAgentStore.setState({
      tasks: { [plotReviewTask.taskId]: plotReviewTask },
      openTaskDrawer: vi.fn(),
      sendMessage: vi.fn(),
    });

    render(<AgentTaskCard taskId={plotReviewTask.taskId} />);

    // 落库的改动在刷新后依然可见，并标明来源与字段
    const history = await screen.findByLabelText('改动记录');
    expect(history.textContent).toContain('第 1 镜 · 画面');
    expect(history.textContent).toContain('智能体改前的画面');
    expect(history.textContent).toContain('已被智能体改过的画面');
    expect(history.textContent).toContain('智能体');
    // 历史记录不给"撤销"按钮（撤销只针对本次会话的改动）
    expect(history.querySelector('button')).toBeNull();
  });

  it('hides the plot approval button after a successful confirmation', async () => {
    const plotReviewTask: AgentTaskView = {
      ...storyboardTask,
      status: 'completed',
      stage: 'waiting_review',
    };
    vi.spyOn(agentApi, 'getStoryboardRun').mockResolvedValue({ data: { data: null } } as never);
    const approve = vi.spyOn(agentApi, 'approveStoryboard')
      .mockResolvedValue({ data: { data: { taskId: 'task-2', stage: 'PLOT_REVIEW' } } } as never);
    const openTaskDrawer = vi.fn();
    useAgentStore.setState({
      tasks: { [plotReviewTask.taskId]: plotReviewTask },
      openTaskDrawer,
      sendMessage: vi.fn(),
    });

    render(<AgentTaskCard taskId={plotReviewTask.taskId} />);
    fireEvent.click(screen.getByRole('button', { name: '确认剧情' }));

    await waitFor(() => {
      expect(approve).toHaveBeenCalledWith('6efa645a-724d-4459-be90-35aac504e323', {
        stage: 'PLOT_REVIEW',
        decision: 'APPROVE',
      });
    });
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '确认剧情' })).toBeNull();
    });
  });

  it('offers open and download actions for document task results', () => {
    const documentAsset: AgentAsset = {
      id: 'document-1',
      conversationId: 'conversation-1',
      type: 'document',
      url: '/api/files/reports/summary.md',
      prompt: null,
      model: null,
      status: 'completed',
      taskId: storyboardTask.taskId,
      error: null,
      createdAt: new Date().toISOString(),
      resultUrls: '["/api/files/reports/summary.md"]',
    };
    useAgentStore.setState({
      tasks: {
        [storyboardTask.taskId]: {
          ...storyboardTask,
          assetIds: [documentAsset.id],
          resultUrls: [documentAsset.url],
        },
      },
      assets: {
        records: [documentAsset],
        total: 1,
        documentTotal: 1,
        page: 1,
        size: 20,
      },
    });

    render(<AgentTaskCard taskId={storyboardTask.taskId} />);

    fireEvent.click(screen.getByRole('button', { name: /打开 summary.md/ }));
    expect(useDocumentPreviewStore.getState().attachment?.url).toBe(documentAsset.url);
    expect(screen.getByRole('link', { name: /下载 summary.md/ })).toBeTruthy();
  });
});
