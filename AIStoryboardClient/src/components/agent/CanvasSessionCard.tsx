import { ExternalLink, Frame, X } from 'lucide-react';
import { useAgentStore } from '../../stores/agentStore';

export function CanvasSessionCard() {
  const session = useAgentStore((state) => state.canvasSession);
  const status = useAgentStore((state) => state.canvasSessionStatus);
  const beginOpen = useAgentStore((state) => state.beginCanvasSessionOpen);
  const cancel = useAgentStore((state) => state.cancelCanvasSession);

  if (!session) return null;

  const selecting = session.status === 'pending_selection' && !session.canvasId;
  return (
    <div
      role="region"
      aria-label="画布会话"
      style={{
        margin: '0 16px 12px',
        padding: 14,
        border: '1px solid var(--color-hairline)',
        borderRadius: 8,
        background: 'var(--color-surface-card)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <Frame size={16} strokeWidth={1.8} />
        <strong style={{ fontSize: 14 }}>继续设计画布</strong>
      </div>
      <div style={{ color: 'var(--color-muted)', fontSize: 13, lineHeight: 1.6 }}>
        {session.intent || '打开画布后继续创建和连接设计节点。'}
      </div>

      {selecting ? (
        <div style={{ display: 'grid', gap: 8, marginTop: 12 }}>
          {session.choices.map((choice) => (
            <button
              key={choice.canvasId}
              type="button"
              onClick={() => beginOpen(choice.canvasId, false)}
              style={{ minHeight: 36, display: 'flex', alignItems: 'center', gap: 8, border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'var(--color-panel)', cursor: 'pointer', textAlign: 'left', padding: '7px 10px' }}
            >
              <Frame size={14} strokeWidth={1.8} />
              {choice.title}
            </button>
          ))}
          <button
            type="button"
            onClick={() => beginOpen(undefined, true)}
            style={{ minHeight: 36, border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'var(--color-panel)', cursor: 'pointer', padding: '7px 10px' }}
          >
            新建画布
          </button>
        </div>
      ) : (
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <button
            type="button"
            onClick={() => beginOpen(session.canvasId, false)}
            disabled={!!status}
            style={{ minHeight: 36, display: 'inline-flex', alignItems: 'center', gap: 7, border: 0, borderRadius: 8, background: 'var(--color-primary)', color: 'var(--color-on-primary)', cursor: status ? 'default' : 'pointer', padding: '7px 12px' }}
          >
            <ExternalLink size={14} strokeWidth={1.8} />
            {status || '打开画布继续'}
          </button>
          <button
            type="button"
            onClick={() => void cancel()}
            style={{ minHeight: 36, display: 'inline-flex', alignItems: 'center', gap: 6, border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'var(--color-panel)', cursor: 'pointer', padding: '7px 12px' }}
          >
            <X size={14} strokeWidth={1.8} />
            取消
          </button>
        </div>
      )}
    </div>
  );
}
