import { useEffect, useState } from 'react';
import { Brain, ChevronDown, ChevronRight, Clock } from 'lucide-react';
import type { AgentReasoning, AgentTraceEvent } from '../../api/agent';
import { AgentTraceTimeline } from './AgentTraceTimeline';
import { usePresence } from '../../hooks/usePresence';

/** 用时格式化：ms →  X.X 秒 / X 分 Y 秒 */
function formatDuration(ms?: number): string {
  if (ms == null) return '';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s < 10 ? s.toFixed(1) : Math.round(s)} 秒`;
  return `${Math.floor(s / 60)} 分 ${Math.round(s % 60)} 秒`;
}

/**
 * 推理过程面板：实时阶段显示「正在思考…」，message_end 后默认折叠显示「已思考（用时 X 秒）」。
 * 展开后分段展示 reasoning 摘要/详情 与 agentTrace 交接轨迹（不嵌套卡片）。
 */
export function ReasoningPanel({ reasoning, agentTrace, streaming }: {
  reasoning?: AgentReasoning;
  agentTrace?: AgentTraceEvent[];
  streaming?: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const panelPresence = usePresence(expanded, 220);
  const hasContent = !!reasoning || (agentTrace && agentTrace.length > 0);
  useEffect(() => {
    setExpanded(!!streaming);
  }, [streaming]);
  useEffect(() => {
    if (!streaming) return;
    const startedAt = Date.now();
    setElapsedSeconds(0);
    const timer = window.setInterval(() => {
      setElapsedSeconds(Math.max(0, Math.floor((Date.now() - startedAt) / 1000)));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [streaming]);
  if (!hasContent && !streaming) return null;
  const live = !!streaming;
  const duration = reasoning?.durationMs != null ? formatDuration(reasoning.durationMs) : '';
  const liveSummary = reasoning?.summary?.trim();
  const liveActivity = reasoning?.activity?.trim();
  const liveClock = elapsedSeconds >= 60
    ? `${Math.floor(elapsedSeconds / 60)}分${elapsedSeconds % 60}秒`
    : `${elapsedSeconds}秒`;
  const title = live
    ? (liveActivity || liveSummary
        ? `正在思考 ${liveClock}：${liveActivity || liveSummary}`
        : `正在思考 ${liveClock}…`)
    : `已思考${duration ? `（用时 ${duration}）` : ''}`;
  const showToggle = hasContent;

  return (
    <div style={{ marginBottom: 10, maxWidth: '100%' }}>
      <button
        onClick={() => showToggle && setExpanded(!expanded)}
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          border: 'none', background: 'transparent', cursor: showToggle ? 'pointer' : 'default',
          padding: 0, color: live ? 'var(--color-primary)' : 'var(--color-muted)',
          fontSize: 12, lineHeight: 1.4, textAlign: 'left',
        }}
      >
        <Brain size={14} strokeWidth={1.8} style={{ flexShrink: 0, color: live ? 'var(--color-primary)' : 'var(--color-muted)' }} />
        <span
          key={live ? 'thinking' : 'thought'}
          className="reasoning-title-swap"
          style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 'min(70vw, 520px)' }}
        >
          {title}
        </span>
        {live && (
          <span style={{ display: 'inline-flex', gap: 2 }}>
            {[0, 1].map((i) => (
              <span key={i} style={{ width: 3, height: 3, borderRadius: '50%', background: 'var(--color-primary)', animation: 'reasoningDot 1.2s ease-in-out infinite', animationDelay: `${i * 0.18}s` }} />
            ))}
          </span>
        )}
        {showToggle && (
          <span className={`reasoning-chevron${expanded ? ' is-open' : ''}`}>
            {expanded ? <ChevronDown size={13} strokeWidth={1.8} /> : <ChevronRight size={13} strokeWidth={1.8} />}
          </span>
        )}
        {live && <Clock size={12} strokeWidth={1.8} style={{ color: 'var(--color-primary)' }} />}
        {!live && duration && <Clock size={12} strokeWidth={1.8} style={{ color: 'var(--color-muted-soft)' }} />}
      </button>
      <style>{`@keyframes reasoningDot { 0%, 100% { opacity: 0.4; } 50% { opacity: 1; } }`}</style>

      {panelPresence.mounted && (
        <div
          className={`reasoning-expand${panelPresence.visible ? ' is-open' : ''}${panelPresence.closing ? ' is-closing' : ''}`}
        >
          <div className="reasoning-expand__inner">
            <div
              style={{
                marginTop: 8, padding: '10px 12px', borderRadius: 12,
                border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)',
                maxWidth: '100%', overflowWrap: 'anywhere',
              }}
            >
              {reasoning?.summary && (
                <div className="reasoning-detail-item" style={{ '--item-index': 0 } as React.CSSProperties}>
                  <div style={{ fontSize: 13, color: 'var(--color-body-strong)', fontWeight: 500, marginBottom: 6 }}>
                    {reasoning.summary}
                  </div>
                </div>
              )}
              {reasoning?.details && (
                <div
                  className="reasoning-detail-item"
                  style={{ '--item-index': 1 } as React.CSSProperties}
                >
                  <div style={{ fontSize: 12, color: 'var(--color-muted)', lineHeight: 1.7, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 240, overflowY: 'auto' }}>
                    <span style={{ color: 'var(--color-muted-soft)', fontSize: 11, letterSpacing: 1, display: 'block', marginBottom: 4 }}>推理详情</span>
                    {reasoning.details}
                  </div>
                </div>
              )}
              {agentTrace && agentTrace.length > 0 && <AgentTraceTimeline events={agentTrace} />}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
