import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { GenerationCenterPanel } from './GenerationCenterPanel';

vi.mock('gsap', () => ({
  default: { fromTo: vi.fn() },
}));
vi.mock('@gsap/react', () => ({
  useGSAP: vi.fn(),
}));
vi.mock('../../hooks/useDragSnap', () => ({
  useDragSnap: (onClick: () => void) => ({
    onClick,
    onMouseDown: vi.fn(),
    dragging: false,
    returning: false,
    transform: undefined,
    transition: undefined,
  }),
}));

const task: AgentTaskView = {
  taskId: 'task-1',
  conversationId: 'conversation-1',
  projectId: 'project-1',
  title: '剧情与分镜诊断',
  toolName: 'start_storyboard_production',
  kind: 'long-tool',
  status: 'completed',
  stage: 'completed',
  progress: 100,
  resultSummary: '诊断完成',
  error: '',
  parentTaskId: '',
  anchorMessageId: '',
  version: 2,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  assetIds: [],
  resultUrls: [],
  slots: [],
  result: '',
  canCancel: false,
  canRetry: false,
  canFollowUp: false,
};

describe('GenerationCenterPanel', () => {
  afterEach(() => {
    cleanup();
    useAgentStore.setState({
      tasks: {},
      storyboardVideoQueue: [],
      storyboardImageQueue: [],
      assets: { records: [], total: 0, page: 1, size: 20 },
      activeConversationId: null,
    });
    useProjectStore.setState({
      currentProject: null,
      scenes: [],
      generatingImage: {},
    });
    vi.restoreAllMocks();
  });

  it('keeps agent tasks outside the generation type tabs', async () => {
    vi.spyOn(agentApi, 'listGenerationRecords').mockResolvedValue({
      data: { data: { records: [], total: 0, page: 1, size: 50 } },
    } as never);
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
      scenes: [],
      generatingImage: {},
    });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      tasks: { [task.taskId]: task },
      storyboardVideoQueue: [],
      storyboardImageQueue: [],
      assets: { records: [], total: 0, page: 1, size: 20 },
      loadAssets: vi.fn(),
      openTaskDrawer: vi.fn(),
    });

    render(
      <MemoryRouter>
        <GenerationCenterPanel />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '生成中心' }));
    const tablist = screen.getByRole('tablist');
    expect(within(tablist).getAllByRole('tab')).toHaveLength(4);
    expect(within(tablist).queryByText('智能体任务')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /智能体任务/ }));
    expect(screen.getByText('剧情与分镜诊断')).toBeTruthy();
  });

  it('shows an active agent task in the default generation view', async () => {
    vi.spyOn(agentApi, 'listGenerationRecords').mockResolvedValue({
      data: { data: { records: [], total: 0, page: 1, size: 50 } },
    } as never);
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
      scenes: [],
      generatingImage: {},
    });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      tasks: {
        [task.taskId]: {
          ...task,
          status: 'running',
          stage: 'plot_diagnosis',
          progress: 20,
          canCancel: true,
        },
      },
      storyboardVideoQueue: [],
      storyboardImageQueue: [],
      assets: { records: [], total: 0, page: 1, size: 20 },
      loadAssets: vi.fn(),
      openTaskDrawer: vi.fn(),
    });

    const { container } = render(
      <MemoryRouter>
        <GenerationCenterPanel />
      </MemoryRouter>,
    );

    const view = within(container);
    fireEvent.click(view.getByRole('button', { name: /生成中心/ }));

    expect(view.getByText('剧情与分镜诊断')).toBeTruthy();
    expect(view.getByText('剧情诊断中')).toBeTruthy();
    expect(view.queryByText('当前没有进行中的任务')).toBeNull();
  });

  it('only shows agent tasks from the active conversation', async () => {
    vi.spyOn(agentApi, 'listGenerationRecords').mockResolvedValue({
      data: { data: { records: [], total: 0, page: 1, size: 50 } },
    } as never);
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
      scenes: [],
      generatingImage: {},
    });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      tasks: {
        [task.taskId]: { ...task, status: 'running', stage: 'plot_diagnosis', progress: 20 },
        'task-2': {
          ...task,
          taskId: 'task-2',
          conversationId: 'conversation-2',
          title: '其他对话的任务',
          status: 'running',
          stage: 'plot_diagnosis',
          progress: 30,
        },
      },
      storyboardVideoQueue: [],
      storyboardImageQueue: [],
      assets: { records: [], total: 0, page: 1, size: 20 },
      loadAssets: vi.fn(),
      openTaskDrawer: vi.fn(),
    });

    render(
      <MemoryRouter>
        <GenerationCenterPanel taskConversationId="conversation-1" />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: /生成中心/ }));
    const taskEntry = screen.getByRole('button', { name: /智能体任务/ });
    expect(taskEntry.textContent).toContain('1');
    fireEvent.click(taskEntry);

    expect(screen.getByText('剧情与分镜诊断')).toBeTruthy();
    expect(screen.queryByText('其他对话的任务')).toBeNull();
  });

  it('hides agent tasks while there is no active conversation', async () => {
    vi.spyOn(agentApi, 'listGenerationRecords').mockResolvedValue({
      data: { data: { records: [], total: 0, page: 1, size: 50 } },
    } as never);
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
      scenes: [],
      generatingImage: {},
    });
    useAgentStore.setState({
      activeConversationId: null,
      tasks: { [task.taskId]: task },
      storyboardVideoQueue: [],
      storyboardImageQueue: [],
      assets: { records: [], total: 0, page: 1, size: 20 },
      loadAssets: vi.fn(),
      openTaskDrawer: vi.fn(),
    });

    render(
      <MemoryRouter>
        <GenerationCenterPanel taskConversationId={null} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '生成中心' }));
    fireEvent.click(screen.getByRole('button', { name: /智能体任务/ }));

    expect(screen.getByText('暂无智能体任务')).toBeTruthy();
    expect(screen.queryByText('剧情与分镜诊断')).toBeNull();
  });

  it('can be opened directly on the requested image tab', async () => {
    vi.spyOn(agentApi, 'listGenerationRecords').mockResolvedValue({
      data: { data: { records: [], total: 0, page: 1, size: 50 } },
    } as never);
    useProjectStore.setState({
      currentProject: { id: 'project-1', name: 'Project' } as never,
      scenes: [],
      generatingImage: {},
    });
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      tasks: {},
      storyboardVideoQueue: [],
      storyboardImageQueue: [],
      assets: { records: [], total: 0, page: 1, size: 20 },
      loadAssets: vi.fn(),
      openTaskDrawer: vi.fn(),
    });

    render(
      <MemoryRouter>
        <GenerationCenterPanel open defaultTab="image" />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: /图片/ }).getAttribute('aria-selected')).toBe('true');
    });
  });
});
