import { memo, useState } from 'react';
import { AlertTriangle, Check, CircleStop, ListTodo, LoaderCircle, X } from 'lucide-react';
import { useStoryboardVideoStore, selectVideoTasks } from '../store/storyboardVideoStore';
import { useCanvasStore } from '../store/canvasStore';
import { cancelStoryboardVideo } from '../services/storyboardVideo';
import type { StoryboardVideoStatus } from '../types';

const STATUS_LABEL: Record<StoryboardVideoStatus, string> = {
  idle: '待生成',
  queued: '排队中',
  running: '生成中',
  completed: '已完成',
  failed: '生成失败',
  cancelled: '已取消',
  cancelling: '取消中',
};

const isActive = (status: StoryboardVideoStatus) =>
  status === 'queued' || status === 'running' || status === 'cancelling';

export const TaskQueuePanel = memo(function TaskQueuePanel() {
  const [open, setOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const tasks = useStoryboardVideoStore((state) => state.tasks);
  const clearFinished = useStoryboardVideoStore((state) => state.clearFinished);
  const nodes = useCanvasStore((state) => state.nodes);
  const all = selectVideoTasks(tasks);
  const activeCount = all.filter((task) => isActive(task.status)).length;
  const finished = all.filter((task) => !isActive(task.status));

  const cancel = async (queueId: string) => {
    try {
      await cancelStoryboardVideo(queueId);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '取消失败');
      window.setTimeout(() => setNotice(null), 2500);
    }
  };

  const nodeLabel = (nodeId: string) => nodes.find((node) => node.id === nodeId)?.data.label ?? '分镜';

  return (
    <>
      <button className="task-queue-trigger" aria-label="任务队列" aria-expanded={open}
        onClick={() => setOpen((value) => !value)}>
        <ListTodo size={18} />
        {activeCount > 0 && <span className="task-queue-trigger__badge">{activeCount}</span>}
      </button>
      {open && (
        <div className="task-queue-panel" role="dialog" aria-label="任务队列">
          <header className="task-queue-panel__head">
            <strong>任务队列</strong>
            <span>{activeCount ? `${activeCount} 个进行中` : '空闲'}</span>
            <button aria-label="清空已完成" disabled={finished.length === 0} onClick={clearFinished}>清空</button>
            <button aria-label="关闭任务队列" onClick={() => setOpen(false)}><X size={15} /></button>
          </header>
          {notice && <div className="task-queue-panel__notice"><AlertTriangle size={13} />{notice}</div>}
          <div className="task-queue-panel__list">
            {all.length === 0 && <p className="task-queue-panel__empty">暂无任务</p>}
            {all.map((task) => {
              const active = isActive(task.status);
              return (
                <article key={task.queueId} className={`task-queue-item is-${task.status}`}>
                  <span className="task-queue-item__icon">
                    {task.status === 'completed' ? <Check size={14} />
                      : task.status === 'failed' ? <AlertTriangle size={14} />
                      : <LoaderCircle className={active ? 'is-spinning' : ''} size={14} />}
                  </span>
                  <div className="task-queue-item__body">
                    <div className="task-queue-item__title">
                      <strong>{nodeLabel(task.nodeId)} · 镜头{task.frameIndex + 1}</strong>
                      <small>{STATUS_LABEL[task.status]}</small>
                    </div>
                    <span className="task-queue-item__prompt">{task.prompt}</span>
                    <i className="task-queue-item__bar"><em style={{ width: `${Math.max(3, task.progress * 100)}%` }} /></i>
                  </div>
                  {active && (
                    <button className="task-queue-item__cancel" title="取消生成" aria-label={`取消镜头${task.frameIndex + 1}的视频生成`}
                      disabled={task.status === 'cancelling'} onClick={() => void cancel(task.queueId)}>
                      <CircleStop size={15} />
                    </button>
                  )}
                </article>
              );
            })}
          </div>
        </div>
      )}
    </>
  );
});
