import { useState } from 'react';
import {
  CheckCircle2,
  CircleAlert,
  CircleStop,
  Clock3,
  LoaderCircle,
  MessageSquarePlus,
  RotateCcw,
  XCircle,
} from 'lucide-react';
import { agentApi } from '../../api/agent';
import { storyboardRunIdFromTask, useAgentStore, taskStatusLabel } from '../../stores/agentStore';
import './AgentTaskCard.css';

export function AgentTaskCard({ taskId }: { taskId: string }) {
  const task = useAgentStore((state) => state.tasks[taskId]);
  const openTaskDrawer = useAgentStore((state) => state.openTaskDrawer);
  const cancelTask = useAgentStore((state) => state.cancelTask);
  const retryTask = useAgentStore((state) => state.retryTask);
  const followUpTask = useAgentStore((state) => state.followUpTask);
  const [followUpOpen, setFollowUpOpen] = useState(false);
  const [instruction, setInstruction] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [approvingPlot, setApprovingPlot] = useState(false);

  if (!task) {
    return (
      <div className="agent-task-card is-missing" data-task-id={taskId}>
        <Clock3 size={15} />
        <span>任务信息同步中</span>
      </div>
    );
  }

  const active = task.status === 'queued' || task.status === 'running'
    || task.status === 'waiting_parent' || task.status === 'cancelling';
  const progress = Math.max(0, Math.min(100, task.progress ?? 0));
  const plotReviewRunId = task.status === 'completed' && task.stage === 'waiting_review'
    ? storyboardRunIdFromTask(task) : '';
  const StatusIcon = task.status === 'completed' ? CheckCircle2
    : task.status === 'partial' ? CircleAlert
    : task.status === 'failed' || task.status === 'cancelled' ? XCircle
    : LoaderCircle;

  const submitFollowUp = async () => {
    if (!instruction.trim() || submitting) return;
    setSubmitting(true);
    try {
      await followUpTask(task.taskId, instruction.trim());
      setInstruction('');
      setFollowUpOpen(false);
    } finally {
      setSubmitting(false);
    }
  };

  const approvePlot = async () => {
    if (!plotReviewRunId || approvingPlot) return;
    setApprovingPlot(true);
    try {
      const response = await agentApi.approveStoryboard(plotReviewRunId, {
        stage: 'PLOT_REVIEW',
        decision: 'APPROVE',
      });
      if (response.data.data.taskId) openTaskDrawer(response.data.data.taskId);
    } finally {
      setApprovingPlot(false);
    }
  };

  return (
    <div
      className={`agent-task-card is-${task.status}`}
      data-task-id={task.taskId}
      role="group"
      aria-label={`后台任务：${task.title}`}
    >
      <button
        type="button"
        className="agent-task-card__main"
        onClick={() => openTaskDrawer(task.taskId)}
      >
        <span className="agent-task-card__icon">
          <StatusIcon size={17} strokeWidth={1.9} className={active ? 'is-spinning' : undefined} />
        </span>
        <span className="agent-task-card__copy">
          <strong>{task.title}</strong>
          <span>{taskStatusLabel(task)}</span>
          {task.resultSummary && <small>{task.resultSummary}</small>}
        </span>
        <span className="agent-task-card__version">v{task.version}</span>
      </button>

      {progress > 0 && progress < 100 && (
        <div className="agent-task-card__progress" aria-hidden="true">
          <span style={{ width: `${progress}%` }} />
        </div>
      )}

      <div className="agent-task-card__actions">
        {plotReviewRunId && (
          <button
            type="button"
            className="is-primary"
            disabled={approvingPlot}
            onClick={() => void approvePlot()}
            title="确认剧情并继续生成分镜"
          >
            {approvingPlot ? <LoaderCircle size={13} className="is-spinning" /> : <CheckCircle2 size={13} />}
            {approvingPlot ? '提交中' : '确认剧情'}
          </button>
        )}
        {task.canCancel && (
          <button type="button" onClick={() => void cancelTask(task.taskId)} title="取消任务">
            <CircleStop size={13} />
            取消
          </button>
        )}
        {task.canRetry && (
          <button type="button" onClick={() => void retryTask(task.taskId)} title="重试任务">
            <RotateCcw size={13} />
            {task.status === 'partial' ? '重试失败项' : '重试'}
          </button>
        )}
        {task.canFollowUp && (
          <button type="button" onClick={() => setFollowUpOpen((value) => !value)} title="补充要求">
            <MessageSquarePlus size={13} />
            补充要求
          </button>
        )}
      </div>

      {followUpOpen && (
        <div className="agent-task-card__followup">
          <textarea
            value={instruction}
            onChange={(event) => setInstruction(event.target.value)}
            placeholder="说明需要补充或修改的内容"
            rows={3}
          />
          <div>
            <button type="button" onClick={() => setFollowUpOpen(false)}>取消</button>
            <button
              type="button"
              className="is-primary"
              disabled={!instruction.trim() || submitting}
              onClick={() => void submitFollowUp()}
            >
              {submitting ? '提交中' : '加入任务队列'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
