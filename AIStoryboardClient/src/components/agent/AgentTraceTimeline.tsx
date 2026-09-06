import { ChevronRight } from 'lucide-react';
import type { AgentTraceEvent } from '../../api/agent';

/** 交接轨迹时间线：Moon → 分镜专家 → 审查专家 等阶段，左点右文，不嵌套卡片。 */
export function AgentTraceTimeline({ events }: { events: AgentTraceEvent[] }) {
  if (!events || events.length === 0) return null;
  const chain = events.map((e) => e.specialist || e.agent || '').filter(Boolean).join(' → ');

  return (
    <div style={{ marginTop: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--color-muted-soft)', letterSpacing: 1, marginBottom: 8 }}>
        <ChevronRight size={12} strokeWidth={1.8} />
        <span>Agent 轨迹{chain ? ` · ${chain}` : ''}</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {events.map((ev, i) => {
          const ok = ev.status === 'completed' || ev.status === 'success';
          return (
            <div key={ev.id || i} style={{ display: 'flex', gap: 10, position: 'relative', paddingBottom: i < events.length - 1 ? 12 : 0 }}>
              {i < events.length - 1 && (
                <div style={{ position: 'absolute', left: 5, top: 16, bottom: -4, width: 1, background: 'var(--color-hairline)' }} />
              )}
              <div
                style={{
                  width: 11, height: 11, borderRadius: '50%', flexShrink: 0, marginTop: 4,
                  background: i === 0 ? 'var(--color-primary)' : ok ? 'var(--color-success)' : 'var(--color-surface-cream-strong)',
                  border: '1px solid var(--color-hairline)',
                }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                  <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-ink)' }}>{ev.agent || 'Agent'}</span>
                  {ev.specialist && <span style={{ fontSize: 11, color: 'var(--color-muted)' }}>{ev.specialist}</span>}
                  {ev.phase && <span style={{ fontSize: 11, color: 'var(--color-muted-soft)' }}>{ev.phase}</span>}
                  <span
                    style={{
                      fontSize: 11, padding: '1px 6px', borderRadius: 999,
                      background: ok ? 'rgba(93,184,114,0.12)' : 'rgba(212,160,23,0.12)',
                      color: ok ? 'var(--color-success)' : 'var(--color-warning)',
                    }}
                  >
                    {ev.status}
                  </span>
                </div>
                {ev.title && <div style={{ fontSize: 13, color: 'var(--color-body)', fontWeight: 500, marginTop: 2 }}>{ev.title}</div>}
                {ev.summary && (
                  <div style={{ fontSize: 12, color: 'var(--color-muted)', marginTop: 2, whiteSpace: 'pre-wrap', wordBreak: 'break-word', overflowWrap: 'anywhere' }}>
                    {ev.summary}
                  </div>
                )}
                {(ev.currentIntent || ev.targetIntent) && (
                  <div style={{ fontSize: 11, color: 'var(--color-muted-soft)', marginTop: 2 }}>
                    {ev.currentIntent && <>意图: {ev.currentIntent}</>}
                    {ev.currentIntent && ev.targetIntent && <> → </>}
                    {ev.targetIntent && <>{ev.targetIntent}</>}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
