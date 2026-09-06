import { useEffect, useState } from 'react';
import { Brain, ChevronDown, ChevronRight, Clock } from 'lucide-react';
import type { AgentReasoning, AgentTraceEvent } from '../../api/agent';
import { AgentTraceTimeline } from './AgentTraceTimeline';

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
  const hasContent = !!reasoning || (agentTrace && agentTrace.length > 0);
  useEffect(() => {
    if (!streaming) setExpanded(false);
  }, [streaming]);
  if (!hasContent && !streaming) return null;
  const live = !!streaming;
  const duration = reasoning?.durationMs != null ? formatDuration(reasoning.durationMs) : '';
  const title = live ? '正在思考…' : `已思考${duration ? `（用时 ${duration}）` : ''}`;
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
        <span>{title}</span>
        {live && (
          <span style={{ display: 'inline-flex', gap: 2 }}>
            {[0, 1].map((i) => (
              <span key={i} style={{ width: 3, height: 3, borderRadius: '50%', background: 'var(--color-primary)', animation: 'reasoningDot 1.2s ease-in-out infinite', animationDelay: `${i * 0.18}s` }} />
            ))}
          </span>
        )}
        {showToggle && (expanded ? <ChevronDown size={13} strokeWidth={1.8} /> : <ChevronRight size={13} strokeWidth={1.8} />)}
        {!live && duration && <Clock size={12} strokeWidth={1.8} style={{ color: 'var(--color-muted-soft)' }} />}
      </button>
      <style>{`@keyframes reasoningDot { 0%, 100% { opacity: 0.4; } 50% { opacity: 1; } }`}</style>

      {expanded && (
        <div
          style={{
            marginTop: 8, padding: '10px 12px', borderRadius: 12,
            border: '1px solid var(--color-hairline)', background: 'var(--color-canvas)',
            maxWidth: '100%', overflowWrap: 'anywhere',
          }}
        >
          {reasoning?.summary && (
            <div style={{ fontSize: 13, color: 'var(--color-body-strong)', fontWeight: 500, marginBottom: 6 }}>
              {reasoning.summary}
            </div>
          )}
          {reasoning?.details && (
            <div style={{ fontSize: 12, color: 'var(--color-muted)', lineHeight: 1.7, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 240, overflowY: 'auto' }}>
              <span style={{ color: 'var(--color-muted-soft)', fontSize: 11, letterSpacing: 1, display: 'block', marginBottom: 4 }}>推理详情</span>
              {reasoning.details}
            </div>
          )}
          {agentTrace && agentTrace.length > 0 && <AgentTraceTimeline events={agentTrace} />}
        </div>
      )}
    </div>
  );
}
