import { Route, Users } from 'lucide-react';
import { useAgentStore, type AgentScopeTimelineEntry } from '../../stores/agentStore';

function statusLabel(status: string) {
  switch (status) {
    case 'node_started': return '进行中';
    case 'node_running': return '执行中';
    case 'node_completed': return '已完成';
    case 'node_failed': return '失败';
    default: return status || '处理中';
  }
}

function kindLabel(kind: AgentScopeTimelineEntry['kind']) {
  if (kind === 'plan') return '计划';
  if (kind === 'subagent') return '子智能体';
  return '产物';
}

export function AgentScopeTimeline() {
  const conversationId = useAgentStore((state) => state.activeConversationId);
  const entries = useAgentStore((state) => conversationId
    ? state.timelineByConversation[conversationId] ?? []
    : []);
  const visible = entries
    .filter((entry) => entry.kind === 'plan' || entry.kind === 'subagent')
    .sort((left, right) => left.seq - right.seq);

  if (visible.length === 0) return null;

  return (
    <section
      aria-label="MoonAgent 执行时间线"
      style={{
        margin: '8px 0 14px',
        padding: '12px 14px',
        border: '1px solid var(--color-hairline)',
        borderRadius: 8,
        background: 'white',
        boxShadow: '0 1px 3px rgba(16, 24, 40, 0.05)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 10 }}>
        <Route size={14} strokeWidth={1.8} color="var(--color-primary)" />
        <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--color-muted)', letterSpacing: 0.4 }}>
          MoonAgent 执行
        </span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {visible.map((entry, index) => {
          const completed = entry.status === 'node_completed';
          const failed = entry.status === 'node_failed';
          const Icon = entry.kind === 'subagent' ? Users : Route;
          return (
            <div
              key={entry.id}
              style={{
                display: 'grid',
                gridTemplateColumns: '18px minmax(0, 1fr) auto',
                gap: 8,
                padding: '8px 0',
                borderTop: index === 0 ? 'none' : '1px solid var(--color-hairline)',
              }}
            >
              <Icon
                size={14}
                strokeWidth={1.8}
                color={failed ? 'var(--color-error)' : completed ? 'var(--color-success)' : 'var(--color-primary)'}
                style={{ marginTop: 2 }}
              />
              <div style={{ minWidth: 0 }}>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--color-muted-soft)' }}>
                    {kindLabel(entry.kind)}
                  </span>
                  {entry.agent && (
                    <span style={{ fontSize: 10, color: 'var(--color-muted-soft)', overflowWrap: 'anywhere' }}>
                      {entry.agent}
                    </span>
                  )}
                </div>
                <div style={{ marginTop: 2, fontSize: 13, fontWeight: 600, color: 'var(--color-ink)', overflowWrap: 'anywhere' }}>
                  {entry.title}
                </div>
                {entry.summary && (
                  <div style={{ marginTop: 2, fontSize: 11, lineHeight: 1.5, color: 'var(--color-muted)', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                    {entry.summary}
                  </div>
                )}
              </div>
              <span
                style={{
                  alignSelf: 'start',
                  padding: '1px 6px',
                  borderRadius: 5,
                  fontSize: 10,
                  fontWeight: 700,
                  whiteSpace: 'nowrap',
                  color: failed ? 'var(--color-error)' : completed ? 'var(--color-success)' : 'var(--color-primary)',
                  background: failed ? '#fff1f0' : completed ? '#edf8f1' : '#eff6ff',
                }}
              >
                {statusLabel(entry.status)}
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}
