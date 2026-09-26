import { CheckCircle2, X } from 'lucide-react';
import { useAgentStore } from '../../stores/agentStore';
import './AgentTaskCompletionNotices.css';

const EMPTY_NOTICES: import('../../stores/agentStore').AgentTaskNotice[] = [];

export function AgentTaskCompletionNotices({ conversationId }: { conversationId: string | null }) {
  const noticesByConversation = useAgentStore((state) => state.taskNotices);
  const notices = conversationId ? noticesByConversation[conversationId] ?? EMPTY_NOTICES : EMPTY_NOTICES;
  const dismiss = useAgentStore((state) => state.dismissTaskNotice);
  const focusTask = useAgentStore((state) => state.focusTask);
  const openTaskDrawer = useAgentStore((state) => state.openTaskDrawer);

  if (!conversationId || notices.length === 0) return null;

  const open = (taskId: string) => {
    focusTask(taskId);
    dismiss(conversationId, taskId);
    window.setTimeout(() => {
      const target = document.querySelector<HTMLElement>(`[data-task-id="${CSS.escape(taskId)}"]`);
      if (target) {
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        target.classList.add('is-task-focused');
        window.setTimeout(() => target.classList.remove('is-task-focused'), 1800);
      } else {
        openTaskDrawer(taskId);
      }
    }, 0);
  };

  return (
    <div className="agent-task-notice-stack" aria-live="polite">
      {notices.map((notice) => (
        <div className="agent-task-notice" key={notice.taskId}>
          <button type="button" onClick={() => open(notice.taskId)}>
            <CheckCircle2 size={15} />
            <span>
              <strong>{notice.title}</strong>
              <small>任务已完成，查看结果</small>
            </span>
          </button>
          <button
            type="button"
            className="agent-task-notice__close"
            onClick={() => dismiss(conversationId, notice.taskId)}
            aria-label="关闭任务完成提示"
          >
            <X size={13} />
          </button>
        </div>
      ))}
    </div>
  );
}
