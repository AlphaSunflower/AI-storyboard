import { useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { Check, CircleStop, ListTodo, LoaderCircle, X } from 'lucide-react';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { agentApi, type GenerationRecord } from '../../api/agent';
import { useDragSnap } from '../../hooks/useDragSnap';

const STATUS_LABEL: Record<string, string> = {
  queued: '排队中',
  running: '生成中',
  completed: '已完成',
  failed: '生成失败',
  cancelled: '已取消',
};

const ACTIVE = new Set(['queued', 'running']);

/** /chat 右上角任务队列：展示分镜视频、图片队列与编辑器/智能体进行中的生图任务。 */
export function TaskQueuePanel() {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const rowsRef = useRef<HTMLDivElement>(null);
  const queue = useAgentStore((s) => s.storyboardVideoQueue);
  const refetch = useAgentStore((s) => s.refetchStoryboardVideoQueue);
  const cancel = useAgentStore((s) => s.cancelStoryboardVideoQueue);
  const imageQueue = useAgentStore((s) => s.storyboardImageQueue);
  const refetchImage = useAgentStore((s) => s.refetchStoryboardImageQueue);
  const cancelImage = useAgentStore((s) => s.cancelStoryboardImageQueue);
  const projectId = useProjectStore((s) => s.currentProject?.id);
  const scenes = useProjectStore((s) => s.scenes);
  const generatingImage = useProjectStore((s) => s.generatingImage);
  const [activeGenerations, setActiveGenerations] = useState<GenerationRecord[]>([]);
  const drag = useDragSnap(() => {
    if (projectId) { void refetch(projectId); void refetchImage(projectId); }
    setOpen((v) => !v);
  });

  // 有进行中任务时轮询刷进度（5s）
  const activeVideo = queue.filter((t) => ACTIVE.has(t.status));
  const activeImage = imageQueue.filter((t) => ACTIVE.has(t.status));
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

  useEffect(() => {
    if (!projectId) {
      setActiveGenerations([]);
      return;
    }
    let cancelled = false;
    const load = () => {
      agentApi.listGenerationRecords({ status: 'active', page: 1, size: 50 })
        .then((res) => {
          if (cancelled) return;
          setActiveGenerations((res.data.data.records ?? []).filter((record) => record.projectId === projectId));
        })
        .catch(() => { /* 任务列表静默降级到已有队列状态 */ });
    };
    load();
    const timer = window.setInterval(load, 5000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [projectId]);

  // 点击弹窗外（含任务列表按钮本身）关闭；按钮 onMouseDown stopPropagation 由 onClick 自行切换
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  // 下拉面板入场：淡入 + 上浮
  useGSAP(() => {
    if (!open || !panelRef.current) return;
    gsap.fromTo(
      panelRef.current,
      { opacity: 0, y: -6, scale: 0.98 },
      { opacity: 1, y: 0, scale: 1, duration: 0.22, ease: 'power2.out', transformOrigin: 'top right', clearProps: 'transform' }
    );
  }, { dependencies: [open], scope: panelRef, revertOnUpdate: true });

  // 任务行 stagger 浮入（打开 / 数量变化时）
  useGSAP(() => {
    if (!open || !rowsRef.current) return;
    const rows = Array.from(rowsRef.current.children);
    if (rows.length === 0) return;
    gsap.fromTo(
      rows,
      { opacity: 0, y: 6 },
      { opacity: 1, y: 0, duration: 0.24, stagger: 0.04, ease: 'power2.out', clearProps: 'transform' }
    );
  }, { dependencies: [open, activeCount], scope: rowsRef, revertOnUpdate: true });

  useEffect(() => {
    if (!projectId || activeCount === 0) return;
    const timer = setInterval(() => { void refetch(projectId); void refetchImage(projectId); }, 5000);
    return () => clearInterval(timer);
  }, [projectId, activeCount, refetch, refetchImage]);

  // 关闭下拉时也保留队列数据（供分镜行状态复用）
  return (
    <>
      <style>{'@keyframes queue-rotate { to { transform: rotate(360deg); } }'}</style>
      {/* 右下角悬浮「任务列表」入口 + 弹出队列面板（面板从按钮上方展开） */}
      <div ref={containerRef} style={{ position: 'fixed', bottom: 104, right: 20, zIndex: 110, display: 'flex', flexDirection: 'column', alignItems: 'flex-end' }}>
        <button
          type="button"
          onClick={drag.onClick}
          onMouseDown={(e) => { e.stopPropagation(); drag.onMouseDown(e); }}
          title="任务列表"
          onMouseEnter={(e) => { const el = e.currentTarget; el.style.borderColor = 'var(--color-primary)'; el.style.color = 'var(--color-primary)'; el.style.boxShadow = '0 8px 26px rgba(0,0,0,0.16)'; }}
          onMouseLeave={(e) => { const el = e.currentTarget; el.style.borderColor = open ? 'var(--color-primary)' : 'var(--color-hairline)'; el.style.color = open ? 'var(--color-primary)' : 'var(--color-ink)'; el.style.boxShadow = '0 6px 20px rgba(0,0,0,0.12)'; }}
          style={{
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 4,
            width: 76, height: 76, boxSizing: 'border-box',
            border: `1px solid ${open ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
            background: 'white', color: open ? 'var(--color-primary)' : 'var(--color-ink)',
            fontSize: 12, fontWeight: 500, cursor: 'pointer',
            borderRadius: 16, boxShadow: '0 6px 20px rgba(0,0,0,0.12)', position: 'relative',
            transform: drag.transform,
            transition: drag.dragging ? 'none' : 'border-color 0.15s, color 0.15s, box-shadow 0.15s, transform 0.3s ease',
            touchAction: 'none',
          }}
        >
          <ListTodo size={20} strokeWidth={1.8} />
          <span style={{ lineHeight: 1 }}>任务列表</span>
          {activeCount > 0 && (
            <span style={{
              position: 'absolute', top: -2, right: -2, minWidth: 16, height: 16, borderRadius: 8,
              background: '#e2544b', color: 'white', fontSize: 10, lineHeight: '16px', textAlign: 'center', padding: '0 4px',
            }}>{activeCount}</span>
          )}
        </button>
        {open && (
          <div
            ref={panelRef}
            style={{
              position: 'absolute', bottom: 'calc(100% + 8px)', right: 0, width: 320, maxHeight: 420,
              overflowY: 'auto', background: 'white', border: '1px solid rgba(0, 0, 0, 0.08)',
              borderRadius: 12, boxShadow: '0 12px 32px rgba(15, 17, 21, 0.16)', padding: 8, zIndex: 110,
            }}
            role="dialog"
            aria-label="任务列表"
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '6px 8px' }}>
              <strong style={{ fontSize: 13, color: 'rgb(15, 17, 21)' }}>任务列表</strong>
              <small style={{ fontSize: 12, color: 'rgb(152, 158, 168)' }}>
                {activeCount ? `${activeCount} 个进行中` : '空闲'}
              </small>
            </div>
            <div ref={rowsRef} style={{ display: 'flex', flexDirection: 'column' }}>
              {activeVideo.length === 0 && activeRecordVideo.length === 0
                && activeImage.length === 0 && activeGenerationImage.length === 0 ? (
                <div style={{ fontSize: 13, color: 'rgb(152, 158, 168)', padding: '18px 10px', textAlign: 'center' }}>
                  暂无任务
                </div>
              ) : (
                <>
                  {(activeVideo.length > 0 || activeRecordVideo.length > 0) && (
                    <div style={{ padding: '4px 8px 2px', fontSize: 11, color: 'rgb(152, 158, 168)' }}>视频生成</div>
                  )}
                  {activeVideo.map((t) => renderRow(t, '视频', scenes, cancel))}
                  {activeRecordVideo.map((record) => renderRow({
                    id: record.id,
                    sceneId: record.sceneId ?? '',
                    status: record.status,
                    progress: null,
                    error: record.error,
                  }, '视频', scenes, undefined, record.sceneId ? undefined : '智能体视频生成中'))}
                  {activeImage.length > 0 && (
                    <div style={{ padding: '8px 8px 2px', fontSize: 11, color: 'rgb(152, 158, 168)' }}>完善图片</div>
                  )}
                  {activeImage.map((t) => renderRow(t, '图片', scenes, cancelImage))}
                  {activeGenerationImage.length > 0 && (
                    <div style={{ padding: '8px 8px 2px', fontSize: 11, color: 'rgb(152, 158, 168)' }}>生成图片</div>
                  )}
                  {activeRecordImage.map((record) => renderRow({
                    id: record.id,
                    sceneId: record.sceneId ?? '',
                    status: record.status,
                    progress: null,
                    error: record.error,
                  }, '图片', scenes, undefined, record.sceneId ? undefined : '智能体图片生成中'))}
                  {activeDirectImage.map((t) => renderRow(t, '图片', scenes, cancelImage))}
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </>
  );
}

type QueueRow = { id: string; sceneId: string; status: string; progress: number | null; error: string | null };

/** 单条任务行：视频（视频生成）与图片（完善图片）共用。 */
function renderRow(
  t: QueueRow,
  kind: '视频' | '图片',
  scenes: { id: string; sceneNumber?: number }[],
  onCancel?: (id: string) => void,
  titleOverride?: string,
) {
  const active = ACTIVE.has(t.status);
  const sceneNum = scenes.find((s) => s.id === t.sceneId)?.sceneNumber;
  return (
    <div
      key={`${kind}-${t.id}`}
      style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '9px 10px',
        borderRadius: 8, background: active ? 'rgba(65, 118, 230, 0.04)' : 'transparent',
      }}
    >
      <span style={{ display: 'inline-flex', alignItems: 'center', color: t.status === 'completed' ? 'rgb(15, 150, 80)' : t.status === 'failed' ? 'rgb(217, 45, 32)' : 'rgb(65, 118, 230)' }}>
        {t.status === 'completed' ? <Check size={14} />
          : t.status === 'failed' ? <X size={14} />
          : <LoaderCircle size={14} style={active ? { animation: 'queue-rotate 0.8s linear infinite' } : undefined} />}
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <strong style={{ fontSize: 13, color: 'rgb(15, 17, 21)' }}>
            {titleOverride ?? `分镜 ${sceneNum ?? ''} · ${kind}`}
          </strong>
          <small style={{ fontSize: 11, color: 'rgb(96, 100, 108)' }}>{STATUS_LABEL[t.status] ?? t.status}</small>
        </div>
        <div style={{ height: 3, background: 'rgba(0, 0, 0, 0.08)', borderRadius: 2, marginTop: 5, overflow: 'hidden' }}>
          <div style={{ height: '100%', width: `${Math.max(3, t.progress ?? 0)}%`, background: 'rgb(65, 118, 230)', borderRadius: 2, transition: 'width 0.4s' }} />
        </div>
        {t.error && <small style={{ fontSize: 11, color: 'rgb(217, 45, 32)' }}>{t.error}</small>}
      </div>
      {t.status === 'queued' && onCancel && (
        <button
          type="button"
          title="取消生成"
          onClick={() => void onCancel(t.id)}
          style={{ border: 'none', background: 'transparent', color: 'rgb(96, 100, 108)', cursor: 'pointer', padding: 4 }}
        ><CircleStop size={14} /></button>
      )}
      {t.status === 'running' && (
        <span title="上游已受理，无法取消" style={{ fontSize: 10, color: 'rgb(152, 158, 168)', whiteSpace: 'nowrap' }}>无法取消</span>
      )}
    </div>
  );
}
