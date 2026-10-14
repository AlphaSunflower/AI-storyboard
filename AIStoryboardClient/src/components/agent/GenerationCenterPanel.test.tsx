import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, type AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { GenerationCenterPanel } from './GenerationCenterPanel';

vi.mock('gsap', () => ({
  default: {
    fromTo: vi.fn(),
    killTweensOf: vi.fn(),
    set: vi.fn(),
    to: vi.fn(),
    timeline: vi.fn(() => ({ to: vi.fn().mockReturnThis() })),
  },
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
  liveText: '',
  liveThinking: '',
  liveStage: '',
  canCancel: false,
  canRetry: false,
  canFollowUp: false,
};

/** 桌面视口：matchMedia 一律不命中窄屏查询。 */
function stubDesktopViewport() {
  vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
    matches: false,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })));
}

/** 窄屏视口：命中 max-width 查询，侧边栏应转为覆盖式抽屉。 */
function stubNarrowViewport() {
  vi.stubGlobal('matchMedia', vi.fn((query: string) => ({
    matches: query.includes('max-width'),
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })));
}

function seedStore() {
  vi.spyOn(agentApi, 'listGenerationRecords').mockResolvedValue({
    data: { data: { records: [], total: 0, page: 1, size: 50 } },
  } as never);
  useProjectStore.setState({
    currentProject: { id: 'project-1', name: 'Project' } as never,
    scenes: [],
    generatingImage: {},
  });
}

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
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('keeps the sidebar out of the accessibility tree until it is opened', () => {
    stubDesktopViewport();
    seedStore();

    const { container } = render(
      <MemoryRouter>
        <GenerationCenterPanel />
      </MemoryRouter>,
    );

    expect(screen.queryByRole('tablist', { name: '生成中心视图' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '生成中心' }));

    expect(screen.getByRole('tablist', { name: '生成中心视图' })).toBeTruthy();
    expect(container.querySelector('.generation-center__sidebar')?.classList.contains('is-open')).toBe(true);
  });

  it('marks the closed launcher as edge-collapsed for hover reveal', () => {
    stubDesktopViewport();
    seedStore();

    const { container } = render(
      <MemoryRouter>
        <GenerationCenterPanel />
      </MemoryRouter>,
    );

    expect(container.querySelector('.generation-center')?.classList.contains('is-collapsed'))
      .toBe(true);
  });

  it('offers the three top segments with records declared as a dialog opener', () => {
    stubDesktopViewport();
    seedStore();

    render(
      <MemoryRouter>
        <GenerationCenterPanel />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '生成中心' }));

    const segments = screen.getByRole('tablist', { name: '生成中心视图' });
    const labels = within(segments).getAllByRole('tab').map((tab) => tab.textContent);
    expect(labels).toHaveLength(3);
    expect(labels[0]).toContain('生成任务');
    expect(labels[1]).toContain('会话资产');
    expect(labels[2]).toContain('生成记录');
    expect(within(segments).getByRole('tab', { name: /生成记录/ }).getAttribute('aria-haspopup'))
      .toBe('dialog');
  });

  it('opens the generation records dialog and bounces the segment back when it closes', () => {
    stubDesktopViewport();
    seedStore();
    const onOpenRecords = vi.fn();

    const { rerender } = render(
      <MemoryRouter>
        <GenerationCenterPanel open onOpenRecords={onOpenRecords} recordsOpen={false} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('tab', { name: /生成记录/ }));

    expect(onOpenRecords).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('tab', { name: /生成记录/ }).getAttribute('aria-selected')).toBe('true');

    rerender(
      <MemoryRouter>
        <GenerationCenterPanel open onOpenRecords={onOpenRecords} recordsOpen />
      </MemoryRouter>,
    );
    rerender(
      <MemoryRouter>
        <GenerationCenterPanel open onOpenRecords={onOpenRecords} recordsOpen={false} />
      </MemoryRouter>,
    );

    expect(screen.getByRole('tab', { name: /生成任务/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('shows an active agent task in the default generation view', async () => {
    stubDesktopViewport();
    seedStore();
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
    stubDesktopViewport();
    seedStore();
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
    stubDesktopViewport();
    seedStore();
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

  it('opens straight onto the session assets with the requested filter selected', async () => {
    stubDesktopViewport();
    seedStore();
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      tasks: {},
      storyboardVideoQueue: [],
      storyboardImageQueue: [],
      assets: { records: [], total: 0, page: 1, size: 20 },
      loadAssets: vi.fn(),
      openTaskDrawer: vi.fn(),
      addRefImageUrls: vi.fn(),
      refImageUrls: [],
    });

    render(
      <MemoryRouter>
        <GenerationCenterPanel open defaultTab="image" />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByRole('tab', { name: '图片' }).getAttribute('aria-selected')).toBe('true');
    });
    expect(screen.getByRole('tab', { name: /会话资产/ }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tablist', { name: '会话资产筛选' })).toBeTruthy();
  });

  it('swaps the asset category from the filter nav', () => {
    stubDesktopViewport();
    seedStore();
    useAgentStore.setState({
      activeConversationId: 'conversation-1',
      tasks: {},
      storyboardVideoQueue: [],
      storyboardImageQueue: [],
      assets: { records: [], total: 0, page: 1, size: 20 },
      loadAssets: vi.fn(),
      openTaskDrawer: vi.fn(),
      addRefImageUrls: vi.fn(),
      refImageUrls: [],
    });

    render(
      <MemoryRouter>
        <GenerationCenterPanel open defaultTab="all" />
      </MemoryRouter>,
    );

    const filter = screen.getByRole('tablist', { name: '会话资产筛选' });
    fireEvent.click(within(filter).getByRole('tab', { name: '分镜' }));

    expect(within(filter).getByRole('tab', { name: '分镜' }).getAttribute('aria-selected')).toBe('true');
    expect(within(filter).getByRole('tab', { name: '全部' }).getAttribute('aria-selected')).toBe('false');
  });

  it('switches back to generation tasks without leaving the assets filter behind', () => {
    stubDesktopViewport();
    seedStore();

    render(
      <MemoryRouter>
        <GenerationCenterPanel open />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('tab', { name: /会话资产/ }));
    expect(screen.getByRole('tablist', { name: '会话资产筛选' })).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: /生成任务/ }));
    expect(screen.queryByRole('tablist', { name: '会话资产筛选' })).toBeNull();
    expect(screen.getByRole('button', { name: /智能体任务/ })).toBeTruthy();
  });

  it('closes the sidebar from its own close button', () => {
    stubDesktopViewport();
    seedStore();
    const onOpenChange = vi.fn();

    render(
      <MemoryRouter>
        <GenerationCenterPanel open onOpenChange={onOpenChange} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '关闭生成中心' }));

    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('adds a dismissing scrim only on narrow viewports', () => {
    stubNarrowViewport();
    seedStore();
    const onOpenChange = vi.fn();

    const { container } = render(
      <MemoryRouter>
        <GenerationCenterPanel open onOpenChange={onOpenChange} />
      </MemoryRouter>,
    );

    const scrim = container.querySelector('.generation-center__scrim');
    expect(scrim).toBeTruthy();
    fireEvent.click(scrim as Element);

    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('does not park the segment on records when the host cannot report the dialog state', () => {
    stubDesktopViewport();
    seedStore();

    render(
      <MemoryRouter>
        <GenerationCenterPanel open onOpenRecords={vi.fn()} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('tab', { name: /生成记录/ }));

    expect(screen.getByRole('tab', { name: /生成任务/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('offsets the sidebar clear of an open app window', () => {
    stubDesktopViewport();
    seedStore();

    const { container } = render(
      <MemoryRouter>
        <GenerationCenterPanel open rightOffset={452} />
      </MemoryRouter>,
    );

    const sidebar = container.querySelector('.generation-center__sidebar') as HTMLElement;
    expect(sidebar.style.getPropertyValue('--generation-center-right')).toBe('452px');
  });

  it('ignores the app window offset on narrow viewports so the drawer stays full width', () => {
    stubNarrowViewport();
    seedStore();

    const { container } = render(
      <MemoryRouter>
        <GenerationCenterPanel open rightOffset={452} />
      </MemoryRouter>,
    );

    const sidebar = container.querySelector('.generation-center__sidebar') as HTMLElement;
    expect(sidebar.style.getPropertyValue('--generation-center-right')).toBe('0px');
  });

  it('hands focus into the sidebar on open and back to the launcher on close', () => {
    stubDesktopViewport();
    seedStore();

    const { rerender } = render(
      <MemoryRouter>
        <GenerationCenterPanel open={false} />
      </MemoryRouter>,
    );

    rerender(
      <MemoryRouter>
        <GenerationCenterPanel open />
      </MemoryRouter>,
    );
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '关闭生成中心' }));

    rerender(
      <MemoryRouter>
        <GenerationCenterPanel open={false} />
      </MemoryRouter>,
    );
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '生成中心' }));
  });

  it('keeps the desktop sidebar scrim-free', () => {
    stubDesktopViewport();
    seedStore();

    const { container } = render(
      <MemoryRouter>
        <GenerationCenterPanel open />
      </MemoryRouter>,
    );

    expect(container.querySelector('.generation-center__scrim')).toBeNull();
  });
});
