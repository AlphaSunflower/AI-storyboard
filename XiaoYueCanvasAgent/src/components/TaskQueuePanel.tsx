import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, CircleStop, Clock3, ListTodo, LoaderCircle, X } from 'lucide-react';
import { useCanvasStore } from '../store/canvasStore';
import { cancelCanvasGeneration } from '../services/jobRunner';
import { generationRecordProgress, selectActiveGenerationRecords } from '../canvas/generationRecords';
import { multiAngleLabel } from '../canvas/multiAngle';
import { usePresence } from '../hooks/usePresence';
import type { GenerationRecord } from '../types';

const STATUS_LABEL: Record<GenerationRecord['status'], string> = {
  queued: '排队中',
  running: '生成中',
  completed: '已完成',
  failed: '失败',
  cancelled: '已取消',
  interrupted: '已中断',
};

const actionLabel: Record<string, string> = {
  'image.generate': '生成图片',
  'image.redraw': '图片重绘',
  'video.generate': '生成视频',
  'storyboard.plan': '分镜规划',
  'storyboard.outline': '剧情与伪分镜',
  'storyboard.finalize': '正式分镜生成',
  'agent.request': '设计助理',
};

export const TaskQueuePanel = memo(function TaskQueuePanel() {
  const [open, setOpen] = useState(false);
  const panel = usePresence(open, { duration: 140 });
  const [notice, setNotice] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<Set<string>>(new Set());
  const rootRef = useRef<HTMLDivElement>(null);
  const records = useCanvasStore((state) => state.generationRecords);
  const nodes = useCanvasStore((state) => state.nodes);
  const active = useMemo(
    () => selectActiveGenerationRecords(records).sort((left, right) => left.startedAt - right.startedAt),
    [records],
  );

  useEffect(() => {
    if (!open) return;
    const close = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', close, true);
    return () => document.removeEventListener('pointerdown', close, true);
  }, [open]);

  const cancel = async (record: GenerationRecord) => {
    setCancelling((current) => new Set(current).add(record.id));
    try {
      await cancelCanvasGeneration(record.id);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '取消失败');
      window.setTimeout(() => setNotice(null), 2500);
    } finally {
      setCancelling((current) => {
        const next = new Set(current);
        next.delete(record.id);
        return next;
      });
    }
  };

  const nodeLabel = (nodeId: string) => nodes.find((node) => node.id === nodeId)?.data.label ?? '画布任务';
  return (
    <div ref={rootRef} className="task-queue-shell">
      <button className="canvas-quick-action" aria-label="任务队列" aria-expanded={open}
        onClick={() => setOpen((value) => !value)}>
        <ListTodo size={18} />
        {active.length > 0 && <span className="canvas-quick-action__badge">{active.length}</span>}
      </button>
      {panel.mounted && (
        <div className="task-queue-panel motion-presence" data-present={panel.present} role="dialog" aria-label="任务队列">
          <header className="task-queue-panel__head">
            <div><strong>任务队列</strong><small>{active.length ? `${active.length} 个真实任务执行中` : '当前空闲'}</small></div>
            <button aria-label="关闭任务队列" onClick={() => setOpen(false)}><X size={15} /></button>
          </header>
          {notice && <div className="task-queue-panel__notice"><AlertTriangle size={13} />{notice}</div>}
          <div className="task-queue-panel__list">
            {active.length === 0 && (
              <div className="task-queue-panel__empty">
                <Clock3 size={20} />
                <strong>没有进行中的任务</strong>
                <span>完成的生成会进入生成记录。</span>
              </div>
            )}
            {active.map((record) => {
              const progress = generationRecordProgress(record, nodes);
              const materializing = record.materializationStatus === 'materializing';
              const capabilityId = typeof record.params.capabilityId === 'string' ? record.params.capabilityId : '';
              const operationLabel = multiAngleLabel(record.params.multiAngleMode)
                ?? actionLabel[capabilityId]
                ?? record.actionId;
              return (
                <article key={record.id} className={`task-queue-item is-${record.status}`}>
                  <span className="task-queue-item__icon"><LoaderCircle className="is-spinning" size={14} /></span>
                  <div className="task-queue-item__body">
                    <div className="task-queue-item__title">
                      <strong>{nodeLabel(record.producerNodeId)} · {operationLabel}</strong>
                      <small>{materializing ? '结果落地中' : STATUS_LABEL[record.status]}</small>
                    </div>
                    <span className="task-queue-item__prompt">{record.effectivePrompt || record.prompt || '未填写提示词'}</span>
                    <i className="task-queue-item__bar"><em style={{ width: `${Math.max(3, progress * 100)}%` }} /></i>
                  </div>
                  <button className="task-queue-item__cancel" title="取消生成" aria-label={`取消${nodeLabel(record.producerNodeId)}的生成任务`}
                    disabled={cancelling.has(record.id)} onClick={() => void cancel(record)}>
                    <CircleStop size={15} />
                  </button>
                </article>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
});
