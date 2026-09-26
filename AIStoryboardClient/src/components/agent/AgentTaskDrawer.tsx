import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  Activity,
  CheckCircle2,
  CircleAlert,
  CircleStop,
  LoaderCircle,
  RotateCcw,
  Wrench,
  X,
  XCircle,
} from 'lucide-react';
import { taskStatusLabel, useAgentStore } from '../../stores/agentStore';
import type { AgentTaskSlotView } from '../../api/agent';
import { AgentToolAuditPanel } from './AgentToolAuditPanel';
import './AgentTaskDrawer.css';

function formatTime(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
}

function displayOrder(slots: AgentTaskSlotView[]): AgentTaskSlotView[] {
  return [...slots].sort((left, right) => {
    const leftTime = left.completedAt ? Date.parse(left.completedAt) : Number.POSITIVE_INFINITY;
    const rightTime = right.completedAt ? Date.parse(right.completedAt) : Number.POSITIVE_INFINITY;
    return leftTime - rightTime || left.index - right.index;
  });
}

export function AgentTaskDrawer() {
  const taskId = useAgentStore((state) => state.taskDrawerId);
  const task = useAgentStore((state) => state.tasks[taskId ?? '']);
  const close = useAgentStore((state) => state.closeTaskDrawer);
  const cancelTask = useAgentStore((state) => state.cancelTask);
  const retryTask = useAgentStore((state) => state.retryTask);
  const [view, setView] = useState<'detail' | 'audit'>('detail');

  useEffect(() => {
    setView('detail');
  }, [taskId]);

  if (!taskId || !task) return null;
  const slots = displayOrder(task.slots ?? []);

  return (
    <div className="agent-task-drawer__backdrop" role="presentation" onMouseDown={close}>
      <aside
        className="agent-task-drawer"
        role="dialog"
        aria-modal="true"
        aria-label={`任务详情：${task.title}`}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="agent-task-drawer__header">
          <div>
            <span>后台任务</span>
            <h2>{task.title}</h2>
          </div>
          <button type="button" onClick={close} aria-label="关闭任务详情">
            <X size={17} />
          </button>
        </header>

        <div className="agent-task-drawer__tabs" role="tablist" aria-label="任务详情视图">
          <button
            type="button"
            role="tab"
            aria-selected={view === 'detail'}
            className={view === 'detail' ? 'is-active' : undefined}
            onClick={() => setView('detail')}
          >
            <Wrench size={14} />
            任务详情
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={view === 'audit'}
            className={view === 'audit' ? 'is-active' : undefined}
            onClick={() => setView('audit')}
          >
            <Activity size={14} />
            工具审计
          </button>
        </div>

        {view === 'detail' ? (
          <>
        <div className="agent-task-drawer__status">
          <strong>{taskStatusLabel(task)}</strong>
          <span>{task.progress == null ? '进度未知' : `${task.progress}%`}</span>
        </div>

        <dl className="agent-task-drawer__meta">
          <div><dt>任务 ID</dt><dd><code>{task.taskId}</code></dd></div>
          <div><dt>阶段</dt><dd>{task.stage || '-'}</dd></div>
          <div><dt>创建时间</dt><dd>{formatTime(task.createdAt)}</dd></div>
          <div><dt>更新时间</dt><dd>{formatTime(task.updatedAt)}</dd></div>
          {task.parentTaskId && <div><dt>父任务</dt><dd><code>{task.parentTaskId}</code></dd></div>}
        </dl>

        {task.error && (
          <section className="agent-task-drawer__error">
            <h3>失败原因</h3>
            <p>{task.error}</p>
          </section>
        )}

        {task.kind === 'image-batch' && slots.length > 0 && (
          <section className="agent-task-drawer__slots">
            <h3>图片槽位</h3>
            <div className="agent-task-drawer__slot-list">
              {slots.map((slot) => {
                const Icon = slot.status === 'completed' ? CheckCircle2
                  : slot.status === 'failed' ? XCircle
                    : slot.status === 'cancelled' ? CircleAlert
                      : LoaderCircle;
                return (
                  <article key={slot.index} className={`agent-task-slot is-${slot.status}`}>
                    <span className="agent-task-slot__icon">
                      <Icon size={14} className={slot.status === 'running' ? 'is-spinning' : undefined} />
                    </span>
                    <div className="agent-task-slot__body">
                      <strong>{slot.label || `#${slot.index + 1}`} · {slot.prompt || '未命名提示词'}</strong>
                      {slot.url && <img src={slot.url} alt={`生成图片 ${slot.index + 1}`} loading="lazy" />}
                      {slot.error && <small>{slot.error}</small>}
                    </div>
                  </article>
                );
              })}
            </div>
          </section>
        )}

        <section className="agent-task-drawer__result">
          <h3>结果</h3>
          {task.result
            ? <ReactMarkdown remarkPlugins={[remarkGfm]}>{task.result}</ReactMarkdown>
            : <p>{task.resultSummary || '任务尚未产生结果。'}</p>}
        </section>

        <footer className="agent-task-drawer__actions">
          {task.canCancel && (
            <button type="button" onClick={() => void cancelTask(task.taskId)}>
              <CircleStop size={14} />
              取消任务
            </button>
          )}
          {task.canRetry && (
            <button type="button" onClick={() => void retryTask(task.taskId)}>
              <RotateCcw size={14} />
              重试任务
            </button>
          )}
        </footer>
          </>
        ) : (
          <AgentToolAuditPanel
            taskId={task.taskId}
            conversationId={task.conversationId}
            refreshToken={task.version}
          />
        )}
      </aside>
    </div>
  );
}
