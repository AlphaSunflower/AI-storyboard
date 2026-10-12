import { CheckCircle2, CircleAlert, LoaderCircle, Sparkles, Upload, X } from 'lucide-react';
import { useLibraryTaskStore, type LibraryTaskStatus } from '../../stores/libraryTaskStore';
import { Tooltip } from '../common/Tooltip';
import './LibraryTaskQueue.css';

const STATUS_LABEL: Record<LibraryTaskStatus, string> = {
  queued: '等待中',
  uploading: '上传中',
  processing: '处理中',
  completed: '已完成',
  failed: '失败',
};

export function LibraryTaskQueue() {
  const tasks = useLibraryTaskStore((state) => state.tasks);
  const panelOpen = useLibraryTaskStore((state) => state.panelOpen);
  const setPanelOpen = useLibraryTaskStore((state) => state.setPanelOpen);
  const clearFinished = useLibraryTaskStore((state) => state.clearFinished);

  if (!panelOpen) return null;

  const visibleTasks = tasks.slice(0, 30);
  return (
    <aside className="library-task-queue" aria-label="素材任务队列">
      <header>
        <div>
          <span>素材任务</span>
          <strong>{tasks.filter((task) => task.status !== 'completed').length} 项处理中</strong>
        </div>
        <div>
          <button type="button" onClick={clearFinished} disabled={!tasks.some((task) => task.status === 'completed')}>
            清理已完成
          </button>
          <Tooltip label="关闭">
          <button type="button" onClick={() => setPanelOpen(false)} aria-label="关闭任务队列">
            <X size={15} />
          </button>
          </Tooltip>
        </div>
      </header>
      <div className="library-task-queue__list">
        {visibleTasks.length === 0 ? (
          <div className="library-task-queue__empty">上传或导入素材后，任务会显示在这里。</div>
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
              {task.detail && <small>{task.error || task.detail}</small>}
              {task.status !== 'completed' && task.status !== 'failed' && (
                <i><b style={{ width: `${Math.max(0, Math.min(100, task.progress))}%` }} /></i>
              )}
            </div>
          </article>
        ))}
      </div>
    </aside>
  );
}
