import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { Check, ChevronRight, CircleAlert, CircleStop, History, Images, ListTodo, LoaderCircle, X } from 'lucide-react';
import { isActiveTask, taskStatusLabel, useAgentStore } from '../../stores/agentStore';
import { MOBILE_BREAKPOINT } from '../../hooks/useIsMobile';
import { useProjectStore } from '../../stores/projectStore';
import { agentApi, type GenerationRecord } from '../../api/agent';
import { useDragSnap } from '../../hooks/useDragSnap';
import { Tooltip } from '../common/Tooltip';
import { AgentAssetBrowser } from './AgentAssetsPanel';
import { AssetFilterNav } from './AssetFilterNav';
import { RubberSegment, type RubberSegmentItem } from './RubberSegment';
import type { AssetsTab } from './assetBrowserModel';
import { partitionImageQueueTasks } from './generationCenterImageTasks';
import './GenerationCenterPanel.css';

export type GenerationCenterTab = 'active' | 'tasks' | AssetsTab;

/** 停靠侧边栏宽度：宿主按这个值给消息列留白，必须与 CSS 保持一致。 */
export const GENERATION_CENTER_WIDTH = 380;

/** 窄屏断点与页面手机端断点共用，否则 721-768px 会「页面进了手机壳、侧边栏却还是非模态停靠」。 */
const NARROW_VIEWPORT_QUERY = `(max-width: ${MOBILE_BREAKPOINT}px)`;

const STATUS_LABEL: Record<string, string> = {
  queued: '排队中',
  running: '生成中',
  completed: '已完成',
  failed: '生成失败',
  cancelled: '已取消',
};

const ACTIVE_STATUSES = new Set(['queued', 'running']);

type SegmentKey = 'tasks' | 'assets' | 'records';
type TaskView = 'active' | 'tasks';

/** 把外部传入的 defaultTab 拆成「顶部段位 + 段内视图」。 */
function resolveDefaultTab(tab: GenerationCenterTab): {
  segment: SegmentKey;
  taskView: TaskView;
  assetFilter: AssetsTab;
} {
  if (tab === 'active') return { segment: 'tasks', taskView: 'active', assetFilter: 'all' };
  if (tab === 'tasks') return { segment: 'tasks', taskView: 'tasks', assetFilter: 'all' };
  return { segment: 'assets', taskView: 'active', assetFilter: tab };
}

/** jsdom 等宿主可能没有 matchMedia，缺失时按桌面布局处理。 */
function matchesNarrowViewport(): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia(NARROW_VIEWPORT_QUERY).matches;
}

function useNarrowViewport(): boolean {
  const [narrow, setNarrow] = useState(matchesNarrowViewport);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const query = window.matchMedia(NARROW_VIEWPORT_QUERY);
    const sync = () => setNarrow(query.matches);
    sync();
    query.addEventListener('change', sync);
    return () => query.removeEventListener('change', sync);
  }, []);

  return narrow;
}

/**
 * 生成中心：右侧停靠侧边栏，统一查看进行中的生成任务、会话资产和生成记录。
 * 顶部三段（生成任务 / 会话资产 / 生成记录），会话资产再按类型筛选。
 */
export function GenerationCenterPanel({
  onOpenChange,
  open: controlledOpen,
  defaultTab = 'active',
  bottom = 104,
  onOpenRecords,
  taskConversationId,
  recordsOpen,
  rightOffset = 0,
}: {
  onOpenChange?: (open: boolean) => void;
  open?: boolean;
  defaultTab?: GenerationCenterTab;
  bottom?: number;
  onOpenRecords?: () => void;
  /** 传入时会话内只展示该会话的智能体任务；传 null 表示当前没有会话。省略时保持项目范围。 */
  taskConversationId?: string | null;
  /** 生成记录弹窗的开关；宿主传入后，弹窗关闭时记录段会自动回弹到上一段。 */
  recordsOpen?: boolean;
  /** 右侧让位（px）：有 app 子窗口时把侧边栏推到它左边，避免被固定定位的子窗口整条压住。 */
  rightOffset?: number;
}) {
  const navigate = useNavigate();
  const [internalOpen, setInternalOpen] = useState(false);
  const open = controlledOpen ?? internalOpen;
  const [segment, setSegment] = useState<SegmentKey>('tasks');
  const [taskView, setTaskView] = useState<TaskView>('active');
  const [assetFilter, setAssetFilter] = useState<AssetsTab>('all');
  const [recordsPill, setRecordsPill] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const rowsRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const launcherRef = useRef<HTMLButtonElement>(null);
  const hasOpenedRef = useRef(false);
  const narrow = useNarrowViewport();

  const queue = useAgentStore((state) => state.storyboardVideoQueue);
  const refetch = useAgentStore((state) => state.refetchStoryboardVideoQueue);
  const cancel = useAgentStore((state) => state.cancelStoryboardVideoQueue);
  const imageQueue = useAgentStore((state) => state.storyboardImageQueue);
  const refetchImage = useAgentStore((state) => state.refetchStoryboardImageQueue);
  const cancelImage = useAgentStore((state) => state.cancelStoryboardImageQueue);
  const activeConversationId = useAgentStore((state) => state.activeConversationId);
  const agentTasksMap = useAgentStore((state) => state.tasks);
  const openTaskDrawer = useAgentStore((state) => state.openTaskDrawer);
  const projectId = useProjectStore((state) => state.currentProject?.id);
  const scenes = useProjectStore((state) => state.scenes);
  const generatingImage = useProjectStore((state) => state.generatingImage);
  const [activeGenerations, setActiveGenerations] = useState<GenerationRecord[]>([]);

  const setOpen = useCallback((next: boolean | ((value: boolean) => boolean)) => {
    const resolved = typeof next === 'function' ? next(open) : next;
    if (controlledOpen === undefined) setInternalOpen(resolved);
    onOpenChange?.(resolved);
  }, [controlledOpen, onOpenChange, open]);

  const drag = useDragSnap(() => {
    if (projectId) {
      void refetch(projectId);
      void refetchImage(projectId);
    }
    setOpen((value) => !value);
  });

  const activeVideo = queue.filter((task) => ACTIVE_STATUSES.has(task.status));
  const activeImage = imageQueue.filter((task) => ACTIVE_STATUSES.has(task.status));
  const activeImageTasks = partitionImageQueueTasks(activeImage);
  const activeImageSceneIds = new Set(activeImage.map((task) => task.sceneId));
  const activeRecordImage = activeGenerations.filter((record) =>
    record.type === 'image' && (!record.sceneId || !activeImageSceneIds.has(record.sceneId)));
  const activeRecordVideo = activeGenerations.filter((record) => record.type === 'video');
  const activeRecordImageSceneIds = new Set(activeRecordImage.map((record) => record.sceneId).filter(Boolean));
  const activeDirectImage = scenes
    .filter((scene) => generatingImage[scene.id] && !activeRecordImageSceneIds.has(scene.id))
    .map((scene) => ({
      id: `direct-image-${scene.id}`,
      sceneId: scene.id,
      status: 'running',
      progress: null,
      error: null,
    }));
  const activeGenerationImage = [...activeRecordImage, ...activeDirectImage];
  const allAgentTasks = Object.values(agentTasksMap).filter((task) =>
    taskConversationId === undefined || task.conversationId === taskConversationId);
  const activeAgentTasks = allAgentTasks.filter(isActiveTask);
  const generationActiveCount = activeVideo.length + activeRecordVideo.length
    + activeImage.length + activeGenerationImage.length;
  const activeCount = generationActiveCount + activeAgentTasks.length;
  const isGenerating = activeCount > 0;

  // defaultTab 同时决定段位和段内视图，外部每次要求打开时都重新对齐；关闭时清掉记录段占位。
  useEffect(() => {
    if (!open) {
      setRecordsPill(false);
      return;
    }
    const resolved = resolveDefaultTab(defaultTab);
    setSegment(resolved.segment);
    setTaskView(resolved.taskView);
    setAssetFilter(resolved.assetFilter);
    setRecordsPill(false);
  }, [defaultTab, open]);

  // 记录弹窗关掉之后，滑块回弹到真正的段位，避免停在「生成记录」上。
  useEffect(() => {
    if (recordsOpen === false) setRecordsPill(false);
  }, [recordsOpen]);

  useEffect(() => {
    if (!projectId) {
      setActiveGenerations([]);
      return;
    }
    let cancelled = false;
    const load = () => {
      agentApi.listGenerationRecords({ status: 'active', page: 1, size: 50 })
        .then((response) => {
          if (cancelled) return;
          setActiveGenerations((response.data.data.records ?? []).filter((record) => record.projectId === projectId));
        })
        .catch(() => { /* 队列状态仍可独立展示 */ });
    };
    load();
    const timer = window.setInterval(load, 5000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [projectId]);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, setOpen]);

  // 窄屏是全宽覆盖式抽屉，额外锁背景滚动。
  useEffect(() => {
    if (!open || !narrow) return undefined;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previousOverflow;
    };
  }, [open, narrow]);

  // 打开时悬浮球被隐藏，焦点会掉到 body；把焦点收进侧边栏，关闭时再还回悬浮球。
  // 只是恢复焦点归属，不改变用户的操作对象，鼠标用户也不会因此失去已有焦点。
  useEffect(() => {
    if (open) {
      hasOpenedRef.current = true;
      closeButtonRef.current?.focus();
      return;
    }
    if (hasOpenedRef.current) launcherRef.current?.focus();
  }, [open]);

  useGSAP(() => {
    if (!open || segment !== 'tasks' || !rowsRef.current) return;
    const rows = Array.from(rowsRef.current.children);
    if (rows.length === 0) return;
    gsap.fromTo(
      rows,
      { opacity: 0, y: 6 },
      { opacity: 1, y: 0, duration: 0.24, stagger: 0.04, ease: 'power2.out', clearProps: 'transform' }
    );
  }, { dependencies: [open, segment, taskView, activeCount], scope: rowsRef, revertOnUpdate: true });

  useEffect(() => {
    if (!projectId || activeCount === 0) return undefined;
    const timer = window.setInterval(() => {
      void refetch(projectId);
      void refetchImage(projectId);
    }, 5000);
    return () => window.clearInterval(timer);
  }, [activeCount, projectId, refetch, refetchImage]);

  const segments: RubberSegmentItem<SegmentKey>[] = [
    {
      key: 'tasks',
      label: '生成任务',
      icon: <ListTodo size={13} strokeWidth={1.9} />,
      count: activeCount,
    },
    { key: 'assets', label: '会话资产', icon: <Images size={13} strokeWidth={1.9} /> },
    {
      key: 'records',
      label: '生成记录',
      icon: <History size={13} strokeWidth={1.9} />,
      opensDialog: true,
    },
  ];

  const handleSegmentChange = (key: SegmentKey) => {
    if (key === 'records') {
      // 宿主不回报弹窗开关时别把滑块停在记录段，否则会卡在一个没有内容视图的段位上。
      setRecordsPill(recordsOpen !== undefined);
      onOpenRecords?.();
      return;
    }
    setRecordsPill(false);
    setSegment(key);
  };

  const openEditor = () => {
    setOpen(false);
    navigate('/editor');
  };

  const isTaskViewEmpty = activeVideo.length === 0 && activeRecordVideo.length === 0
    && activeImage.length === 0 && activeGenerationImage.length === 0
    && activeAgentTasks.length === 0;

  return (
    <>
      <div
        ref={containerRef}
        className={`generation-center${open ? ' is-open' : ' is-collapsed'}${drag.dragging ? ' is-dragging' : ''}`}
        style={{ bottom }}
      >
        <button
          ref={launcherRef}
          type="button"
          onClick={drag.onClick}
          onMouseDown={(event) => {
            event.stopPropagation();
            drag.onMouseDown(event);
          }}
          className={`generation-center__trigger${isGenerating ? ' is-generating' : ''}${open ? ' is-open' : ''}${drag.dragging ? ' is-dragging' : ''}${drag.returning ? ' is-returning' : ''}`}
          aria-label={isGenerating ? `生成中心，${activeCount} 个任务生成中` : '生成中心'}
          aria-expanded={open}
          style={{ transform: drag.transform, transition: drag.transition }}
        >
          <ListTodo size={20} strokeWidth={1.8} />
          <span className="generation-center__trigger-label">{isGenerating ? '生成中' : '生成中心'}</span>
          {activeCount > 0 && (
            <span className="generation-center__badge" aria-live="polite">{activeCount}</span>
          )}
        </button>
      </div>

      {open && narrow && (
        <div
          className="generation-center__scrim"
          onClick={() => setOpen(false)}
          aria-hidden="true"
        />
      )}

      <aside
        className={`generation-center__sidebar${open ? ' is-open' : ''}`}
        style={{
          '--generation-center-width': `${GENERATION_CENTER_WIDTH}px`,
          '--generation-center-right': `${narrow ? 0 : rightOffset}px`,
        } as React.CSSProperties}
        role="dialog"
        aria-label="生成中心"
        aria-hidden={!open}
      >
        <header className="generation-center__header">
          <div className="generation-center__heading">
            <strong>生成中心</strong>
            <span className="generation-center__status">
              {activeCount ? `${activeCount} 个进行中` : '当前空闲'}
            </span>
          </div>
          <button
            ref={closeButtonRef}
            type="button"
            className="generation-center__close"
            onClick={() => setOpen(false)}
            aria-label="关闭生成中心"
          >
            <X size={16} strokeWidth={2} />
          </button>
        </header>

        <div className="generation-center__segments">
          <RubberSegment
            items={segments}
            value={recordsPill ? 'records' : segment}
            onChange={handleSegmentChange}
            ariaLabel="生成中心视图"
          />
        </div>

        {segment === 'assets' ? (
          <div className="generation-center__filter">
            <AssetFilterNav value={assetFilter} onChange={setAssetFilter} />
          </div>
        ) : null}

        <div className="generation-center__body">
          {segment === 'tasks' ? (
            <>
              <button
                type="button"
                className={`generation-center__agent-entry${taskView === 'tasks' ? ' is-active' : ''}`}
                aria-expanded={taskView === 'tasks'}
                onClick={() => setTaskView((current) => current === 'tasks' ? 'active' : 'tasks')}
              >
                <span className="generation-center__agent-entry-icon">
                  <ListTodo size={15} strokeWidth={1.9} />
                </span>
                <span className="generation-center__agent-entry-copy">
                  <strong>智能体任务</strong>
                  <small>
                    {activeAgentTasks.length > 0
                      ? `${activeAgentTasks.length} 个任务进行中`
                      : '查看后台任务与结果'}
                  </small>
                </span>
                <b>{allAgentTasks.length}</b>
                <ChevronRight size={14} className={taskView === 'tasks' ? 'is-expanded' : undefined} />
              </button>

              <div ref={rowsRef} className="generation-center__list">
                {taskView === 'tasks' ? (
                  allAgentTasks.length === 0 ? (
                    <div className="generation-center__empty">
                      <strong>暂无智能体任务</strong>
                      <span>分镜、批量生成等后台任务会显示在这里</span>
                    </div>
                  ) : allAgentTasks
                    .slice()
                    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
                    .map((task) => renderAgentTaskRow(task, openTaskDrawer))
                ) : isTaskViewEmpty ? (
                  <div className="generation-center__empty">
                    <strong>当前没有进行中的任务</strong>
                    <span>新任务开始后会自动出现在这里</span>
                  </div>
                ) : (
                  <>
                    {activeAgentTasks.length > 0 && <SectionLabel>智能体任务</SectionLabel>}
                    {activeAgentTasks
                      .slice()
                      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
                      .map((task) => renderAgentTaskRow(task, openTaskDrawer))}
                    {(activeVideo.length > 0 || activeRecordVideo.length > 0) && <SectionLabel>视频生成</SectionLabel>}
                    {activeVideo.map((task) => renderRow(task, '视频', scenes, cancel))}
                    {activeRecordVideo.map((record) => renderRow({
                      id: record.id,
                      sceneId: record.sceneId ?? '',
                      status: record.status,
                      progress: null,
                      error: record.error,
                    }, '视频', scenes, undefined, record.sceneId ? undefined : '智能体视频生成中'))}
                    {activeImageTasks.refine.length > 0 && <SectionLabel>完善图片</SectionLabel>}
                    {activeImageTasks.refine.map((task) => renderRow(task, '图片', scenes, cancelImage))}
                    {activeImageTasks.auto.length > 0 && <SectionLabel>自动生成图片</SectionLabel>}
                    {activeImageTasks.auto.map((task) => renderRow(task, '图片', scenes, cancelImage))}
                    {activeGenerationImage.length > 0 && <SectionLabel>生成图片</SectionLabel>}
                    {activeRecordImage.map((record) => renderRow({
                      id: record.id,
                      sceneId: record.sceneId ?? '',
                      status: record.status,
                      progress: null,
                      error: record.error,
                    }, '图片', scenes, undefined, record.sceneId ? undefined : '智能体图片生成中'))}
                    {activeDirectImage.map((task) => renderRow(task, '图片', scenes, cancelImage))}
                  </>
                )}
              </div>
            </>
          ) : (
            <div className="generation-center__assets">
              <AgentAssetBrowser
                tab={assetFilter}
                active={open}
                onOpenEditor={openEditor}
                conversationId={activeConversationId ?? '__no_active_conversation__'}
                /* 侧边栏贴屏幕右缘，预览只能往左展开才不会盖住网格本身。 */
                previewPlacement="left"
              />
            </div>
          )}
        </div>
      </aside>
    </>
  );
}

function renderAgentTaskRow(
  task: import('../../api/agent').AgentTaskView,
  onOpen: (taskId: string) => void,
) {
  const active = isActiveTask(task);
  return (
    <button
      key={`agent-${task.taskId}`}
      type="button"
      className="generation-center__row is-clickable"
      onClick={() => onOpen(task.taskId)}
    >
      <span className={`generation-center__row-icon is-${task.status}`}>
        {task.status === 'completed' ? <Check size={14} />
          : task.status === 'partial' ? <CircleAlert size={14} />
          : task.status === 'failed' || task.status === 'cancelled' ? <X size={14} />
            : <LoaderCircle size={14} className={active ? 'is-spinning' : undefined} />}
      </span>
      <div className="generation-center__row-copy">
        <div>
          <strong>{task.title}</strong>
          <small>{taskStatusLabel(task)}</small>
        </div>
        <span className="generation-center__progress">
          <i style={{ width: `${Math.max(3, task.progress ?? 0)}%` }} />
        </span>
        {task.resultSummary && <small>{task.resultSummary}</small>}
        {task.error && <small className="generation-center__error">{task.error}</small>}
      </div>
      <span className="generation-center__locked">查看</span>
    </button>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return <div className="generation-center__section-label">{children}</div>;
}

type QueueRow = {
  id: string;
  sceneId: string;
  status: string;
  progress: number | null;
  error: string | null;
};

function renderRow(
  task: QueueRow,
  kind: '视频' | '图片',
  scenes: { id: string; sceneNumber?: number }[],
  onCancel?: (id: string) => void,
  titleOverride?: string,
) {
  const active = ACTIVE_STATUSES.has(task.status);
  const sceneNumber = scenes.find((scene) => scene.id === task.sceneId)?.sceneNumber;
  return (
    <div key={`${kind}-${task.id}`} className="generation-center__row">
      <span className={`generation-center__row-icon is-${task.status}`}>
        {task.status === 'completed' ? <Check size={14} />
          : task.status === 'failed' ? <X size={14} />
            : <LoaderCircle size={14} className={active ? 'is-spinning' : undefined} />}
      </span>
      <div className="generation-center__row-copy">
        <div>
          <strong>{titleOverride ?? `分镜 ${sceneNumber ?? ''} · ${kind}`}</strong>
          <small>{STATUS_LABEL[task.status] ?? task.status}</small>
        </div>
        <span className="generation-center__progress">
          <i style={{ width: `${Math.max(3, task.progress ?? 0)}%` }} />
        </span>
        {task.error && <small className="generation-center__error">{task.error}</small>}
      </div>
      {task.status === 'queued' && onCancel && (
        <Tooltip label="取消生成">
        <button type="button" aria-label="取消生成" onClick={() => void onCancel(task.id)}>
          <CircleStop size={14} />
        </button>
        </Tooltip>
      )}
      {task.status === 'running' && <span className="generation-center__locked">无法取消</span>}
    </div>
  );
}
