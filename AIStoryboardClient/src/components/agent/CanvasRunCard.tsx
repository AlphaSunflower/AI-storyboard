import { AlertTriangle, CheckCircle2, Clock3, ExternalLink, Frame, LoaderCircle } from 'lucide-react';
import type { AgentCanvasRunStatus, AgentMessageMetadata } from '../../api/agent';
import { useProjectStore } from '../../stores/projectStore';
import { useChatAppOpener } from './apps/ChatAppsContext';
import './CanvasRunCard.css';

const STATUS_LABELS: Record<AgentCanvasRunStatus, string> = {
  queued: '等待编排',
  planning: '正在规划',
  running: '正在编排',
  writing: '正在写入画布',
  completed: '编排完成',
  failed: '编排失败',
};

function progressPercent(progress?: number): number | null {
  if (progress === undefined || !Number.isFinite(progress)) return null;
  const percent = progress > 0 && progress <= 1 ? progress * 100 : progress;
  return Math.max(0, Math.min(100, Math.round(percent)));
}

export function CanvasRunCard({
  metadata,
  conversationId,
}: {
  metadata: AgentMessageMetadata;
  conversationId?: string;
}) {
  const openChatApp = useChatAppOpener();
  const currentProjectId = useProjectStore((state) => state.currentProject?.id);
  const status = metadata.status ?? 'queued';
  const progress = progressPercent(metadata.progress);
  const projectId = metadata.projectId || currentProjectId;
  const title = metadata.title?.trim() || '画布编排';
  const counts = `${metadata.nodeCount ?? 0} 节点 · ${metadata.edgeCount ?? 0} 连线`;
  const canOpen = Boolean(openChatApp && conversationId && projectId && metadata.canvasId);
  const StatusIcon = status === 'completed'
    ? CheckCircle2
    : status === 'failed'
      ? AlertTriangle
      : status === 'queued'
        ? Clock3
        : LoaderCircle;

  const openCanvas = () => {
    if (!canOpen || !openChatApp || !conversationId || !projectId || !metadata.canvasId) return;
    openChatApp('canvas', {
      canvasMode: {
        conversationId,
        projectId,
        parentOrigin: window.location.origin,
        canvasId: metadata.canvasId,
      },
    });
  };

  return (
    <section className="canvas-run-card" aria-label="画布编排任务">
      <div className="canvas-run-card__header">
        <span className="canvas-run-card__icon" aria-hidden="true"><Frame size={16} strokeWidth={1.9} /></span>
        <div className="canvas-run-card__heading">
          <strong>{title}</strong>
          <span className={`canvas-run-card__status is-${status}`}>
        <StatusIcon className={status === 'planning' || status === 'running' || status === 'writing' ? 'is-spinning' : ''} size={13} strokeWidth={1.9} />
            {STATUS_LABELS[status]}
          </span>
        </div>
        {progress !== null && (
          <span className="canvas-run-card__percent">{progress}%</span>
        )}
      </div>

      {(progress !== null || status === 'planning' || status === 'running' || status === 'writing') && (
        <div className="canvas-run-card__progress" aria-label="画布编排进度">
          <span style={{ width: `${progress ?? 8}%` }} />
        </div>
      )}

      <div className="canvas-run-card__meta">
        <span>{counts}</span>
        {metadata.revision !== undefined && <span>Revision {metadata.revision}</span>}
      </div>

      {metadata.error && (
        <div className="canvas-run-card__error" role="alert">
          <AlertTriangle size={14} strokeWidth={1.9} />
          <span>{metadata.error}</span>
        </div>
      )}

      <div className="canvas-run-card__actions">
        <button
          type="button"
          aria-label="打开画布"
          disabled={!canOpen}
          onClick={openCanvas}
        >
          <ExternalLink size={14} strokeWidth={1.9} />
          打开画布
        </button>
      </div>
    </section>
  );
}
