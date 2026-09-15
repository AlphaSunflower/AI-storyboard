import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { Check, CircleStop, Film, History, Images, LayoutList, ListTodo, LoaderCircle, X } from 'lucide-react';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { agentApi, type GenerationRecord } from '../../api/agent';
import { useDragSnap } from '../../hooks/useDragSnap';
import {
  AgentAssetBrowser,
} from './AgentAssetsPanel';
import { getAgentAssetCounts, type AssetsTab } from './assetBrowserModel';
import { partitionImageQueueTasks } from './generationCenterImageTasks';
import './GenerationCenterPanel.css';

type GenerationCenterTab = 'active' | AssetsTab;

const STATUS_LABEL: Record<string, string> = {
  queued: '排队中',
  running: '生成中',
  completed: '已完成',
  failed: '生成失败',
  cancelled: '已取消',
};

const ACTIVE_STATUSES = new Set(['queued', 'running']);

/**
 * 生成中心：统一查看进行中的生成任务、图片、视频和分镜素材。
 */
export function GenerationCenterPanel({
  onOpenChange,
  bottom = 104,
  onOpenRecords,
}: {
  onOpenChange?: (open: boolean) => void;
  bottom?: number;
  onOpenRecords?: () => void;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<GenerationCenterTab>('active');
  const containerRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const rowsRef = useRef<HTMLDivElement>(null);
  const queue = useAgentStore((state) => state.storyboardVideoQueue);
  const refetch = useAgentStore((state) => state.refetchStoryboardVideoQueue);
  const cancel = useAgentStore((state) => state.cancelStoryboardVideoQueue);
  const imageQueue = useAgentStore((state) => state.storyboardImageQueue);
  const refetchImage = useAgentStore((state) => state.refetchStoryboardImageQueue);
  const cancelImage = useAgentStore((state) => state.cancelStoryboardImageQueue);
  const assets = useAgentStore((state) => state.assets);
  const loadAssets = useAgentStore((state) => state.loadAssets);
  const activeConversationId = useAgentStore((state) => state.activeConversationId);
  const projectId = useProjectStore((state) => state.currentProject?.id);
  const scenes = useProjectStore((state) => state.scenes);
  const generatingImage = useProjectStore((state) => state.generatingImage);
  const [activeGenerations, setActiveGenerations] = useState<GenerationRecord[]>([]);

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
  const activeCount = activeVideo.length + activeRecordVideo.length + activeImage.length + activeGenerationImage.length;
  const isGenerating = activeCount > 0;
  const assetCounts = getAgentAssetCounts(assets);

  useEffect(() => {
    onOpenChange?.(open);
  }, [onOpenChange, open]);

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
    if (open && activeConversationId) void loadAssets(1);
  }, [activeConversationId, loadAssets, open]);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  useGSAP(() => {
    if (!open || !panelRef.current) return;
    gsap.fromTo(
      panelRef.current,
      { opacity: 0, y: -6, scale: 0.98 },
      {
        opacity: 1,
        y: 0,
        scale: 1,
        duration: 0.22,
        ease: 'power2.out',
        transformOrigin: 'top right',
        clearProps: 'transform',
      }
    );
  }, { dependencies: [open], scope: panelRef, revertOnUpdate: true });

  useGSAP(() => {
    if (!open || tab !== 'active' || !rowsRef.current) return;
    const rows = Array.from(rowsRef.current.children);
    if (rows.length === 0) return;
    gsap.fromTo(
      rows,
      { opacity: 0, y: 6 },
      { opacity: 1, y: 0, duration: 0.24, stagger: 0.04, ease: 'power2.out', clearProps: 'transform' }
    );
  }, { dependencies: [open, tab, activeCount], scope: rowsRef, revertOnUpdate: true });

  useEffect(() => {
    if (!projectId || activeCount === 0) return;
    const timer = window.setInterval(() => {
      void refetch(projectId);
      void refetchImage(projectId);
    }, 5000);
    return () => window.clearInterval(timer);
  }, [activeCount, projectId, refetch, refetchImage]);

  const tabs: { key: GenerationCenterTab; label: string; icon: React.ReactNode; count: number }[] = [
    { key: 'active', label: '生成中', icon: <ListTodo size={13} strokeWidth={1.9} />, count: activeCount },
    { key: 'image', label: '图片', icon: <Images size={13} strokeWidth={1.9} />, count: assetCounts.image },
    { key: 'video', label: '视频', icon: <Film size={13} strokeWidth={1.9} />, count: assetCounts.video },
    {
      key: 'storyboard',
      label: '分镜',
      icon: <LayoutList size={13} strokeWidth={1.9} />,
      count: activeConversationId
        ? scenes.filter((scene) => scene.conversationId === activeConversationId).length
        : 0,
    },
  ];

  const openEditor = () => {
    setOpen(false);
    navigate('/editor');
  };

  return (
    <div ref={containerRef} className="generation-center" style={{ bottom }}>
      <button
        type="button"
        onClick={drag.onClick}
        onMouseDown={(event) => {
          event.stopPropagation();
          drag.onMouseDown(event);
        }}
        className={`generation-center__trigger${isGenerating ? ' is-generating' : ''}${open ? ' is-open' : ''}${drag.dragging ? ' is-dragging' : ''}${drag.returning ? ' is-returning' : ''}`}
        title={isGenerating ? `生成中心，${activeCount} 个任务生成中` : '生成中心'}
        aria-label={isGenerating ? `生成中心，${activeCount} 个任务生成中` : '生成中心'}
        style={{ transform: drag.transform, transition: drag.transition }}
      >
        <ListTodo size={20} strokeWidth={1.8} />
        <span className="generation-center__trigger-label">{isGenerating ? '生成中' : '生成中心'}</span>
        {activeCount > 0 && (
          <span className="generation-center__badge" aria-live="polite">{activeCount}</span>
        )}
      </button>

      {open && (
        <div ref={panelRef} className="generation-center__panel" role="dialog" aria-label="生成中心">
          <div className="generation-center__header">
            <strong>生成中心</strong>
            <span className="generation-center__status">{activeCount ? `${activeCount} 个进行中` : '当前空闲'}</span>
            <button
              type="button"
              className="generation-center__records"
              onClick={onOpenRecords}
              title="生成记录"
            >
              <History size={13} strokeWidth={1.9} />
              生成记录
            </button>
          </div>

          <div className="generation-center__tabs" role="tablist" aria-label="生成中心视图">
            {tabs.map((item) => (
              <button
                key={item.key}
                type="button"
                role="tab"
                aria-selected={tab === item.key}
                className={tab === item.key ? 'is-active' : undefined}
                onClick={() => setTab(item.key)}
              >
                {item.icon}
                <span>{item.label}</span>
                <b>{item.count}</b>
              </button>
            ))}
          </div>

          {tab === 'active' ? (
            <div ref={rowsRef} className="generation-center__active-list">
              {activeVideo.length === 0 && activeRecordVideo.length === 0
                && activeImage.length === 0 && activeGenerationImage.length === 0 ? (
                <div className="generation-center__empty">
                  <strong>当前没有进行中的任务</strong>
                  <span>新任务开始后会自动出现在这里</span>
                </div>
              ) : (
                <>
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
          ) : (
            <div className="generation-center__assets">
              <AgentAssetBrowser
                tab={tab}
                active={open}
                onOpenEditor={openEditor}
                conversationId={activeConversationId ?? '__no_active_conversation__'}
              />
            </div>
          )}
        </div>
      )}
    </div>
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
        <button type="button" title="取消生成" onClick={() => void onCancel(task.id)}>
          <CircleStop size={14} />
        </button>
      )}
      {task.status === 'running' && <span className="generation-center__locked">无法取消</span>}
    </div>
  );
}
