import { useState } from 'react';
import { CheckCircle2, CircleAlert, LoaderCircle, RotateCcw, Sparkles, Upload, X } from 'lucide-react';
import { useLibraryTaskStore, type LibraryTask, type LibraryTaskStatus } from '../../stores/libraryTaskStore';
import { Tooltip } from '../common/Tooltip';
import './LibraryTaskQueue.css';

const STATUS_LABEL: Record<LibraryTaskStatus, string> = {
  queued: '等待中',
  uploading: '上传中',
  processing: '处理中',
  completed: '已完成',
  failed: '失败',
};

type TaskFilter = 'all' | 'active' | 'failed';

export function LibraryTaskQueue({ onRetry }: { onRetry?: (task: LibraryTask) => void }) {
  const tasks = useLibraryTaskStore((state) => state.tasks);
  const panelOpen = useLibraryTaskStore((state) => state.panelOpen);
  const setPanelOpen = useLibraryTaskStore((state) => state.setPanelOpen);
  const clearFinished = useLibraryTaskStore((state) => state.clearFinished);
  const clearFailed = useLibraryTaskStore((state) => state.clearFailed);
  const [filter, setFilter] = useState<TaskFilter>('all');

  if (!panelOpen) return null;

  const activeCount = tasks.filter((task) => task.status !== 'completed' && task.status !== 'failed').length;
  const failedTasks = tasks.filter((task) => task.status === 'failed');
  const completedCount = tasks.filter((task) => task.status === 'completed').length;
  const filteredTasks = filter === 'all'
    ? tasks
    : filter === 'failed'
      ? failedTasks
      : tasks.filter((task) => task.status !== 'completed' && task.status !== 'failed');
  const visibleTasks = filteredTasks.slice(0, 50);

  return (
    <aside className="library-task-queue" aria-label="素材任务队列">
      <header>
        <div>
          <span>素材任务</span>
          <strong>{activeCount} 项处理中{failedTasks.length > 0 ? ` · ${failedTasks.length} 项失败` : ''}</strong>
        </div>
        <div>
          <Tooltip label="关闭">
            <button type="button" onClick={() => setPanelOpen(false)} aria-label="关闭任务队列">
              <X size={15} />
            </button>
          </Tooltip>
        </div>
      </header>
      <div className="library-task-queue__filters" role="group" aria-label="任务筛选">
        {(['all', 'active', 'failed'] as const).map((value) => (
          <button
            key={value}
            type="button"
            className={filter === value ? 'is-active' : ''}
            onClick={() => setFilter(value)}
          >
            {value === 'all' ? `全部 ${tasks.length}` : value === 'active'
              ? `进行中 ${activeCount}` : `失败 ${failedTasks.length}`}
          </button>
        ))}
        <span className="library-task-queue__spacer" />
        {onRetry && failedTasks.length > 0 && (
          <button
            type="button"
            className="is-retry-all"
            onClick={() => failedTasks.forEach((task) => onRetry(task))}
          >
            重试全部失败
          </button>
        )}
        <button
          type="button"
          onClick={clearFinished}
          disabled={completedCount === 0}
        >
          清理已完成
        </button>
        {filter === 'failed' && (
          <button
            type="button"
            onClick={clearFailed}
            disabled={failedTasks.length === 0}
          >
            清理失败
          </button>
        )}
      </div>
      <div className="library-task-queue__list">
        {visibleTasks.length === 0 ? (
          <div className="library-task-queue__empty">
            {filter === 'failed' ? '没有失败的任务。' : filter === 'active'
              ? '当前没有进行中的任务。' : '上传或导入素材后，任务会显示在这里。'}
          </div>
        ) : visibleTasks.map((task) => (
          <article key={task.id} className={`library-task-row is-${task.status}`}>
            <span className="library-task-row__icon">
              {task.status === 'completed' ? <CheckCircle2 size={15} />
                : task.status === 'failed' ? <CircleAlert size={15} />
                  : task.kind === 'analysis' ? <Sparkles size={15} />
                    : task.status === 'queued' ? <Upload size={15} />
                      : <LoaderCircle size={15} className="is-spinning" />}
            </span>
            <div className="library-task-row__body">
              <div>
                <strong title={task.label}>{task.label}</strong>
                <span>{STATUS_LABEL[task.status]}</span>
              </div>
              {task.batchLabel && <small className="library-task-row__batch">{task.batchLabel}</small>}
              {(task.detail || task.error) && <small>{task.error || task.detail}</small>}
              {task.status !== 'completed' && task.status !== 'failed' && (
                <i><b style={{ width: `${Math.max(0, Math.min(100, task.progress))}%` }} /></i>
              )}
            </div>
            {task.status === 'failed' && onRetry && (
              <button
                type="button"
                className="library-task-row__retry"
                aria-label={`重试 ${task.label}`}
                onClick={() => onRetry(task)}
              >
                <RotateCcw size={13} /> 重试
              </button>
            )}
          </article>
        ))}
      </div>
    </aside>
  );
}
