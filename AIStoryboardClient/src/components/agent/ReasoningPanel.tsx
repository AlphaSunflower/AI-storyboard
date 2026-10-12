import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import type { AgentReasoning, AgentTraceEvent } from '../../api/agent';
import { AgentTraceTimeline } from './AgentTraceTimeline';
import { usePresence } from '../../hooks/usePresence';
import LatticeLoader from '../ui/LatticeLoader';

/** 用时格式化：ms →  X.X 秒 / X 分 Y 秒 */
function formatDuration(ms?: number): string {
  if (ms == null) return '';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s < 10 ? s.toFixed(1) : Math.round(s)} 秒`;
  return `${Math.floor(s / 60)} 分 ${Math.round(s % 60)} 秒`;
}

/**
 * 推理过程面板：实时阶段格纹动画 + 「思考中」+ 计时，message_end 后冻结为「已思考」+ 用时。
 * 展开后分段展示 reasoning 摘要/详情 与 agentTrace 交接轨迹（不嵌套卡片）。
 */
export function ReasoningPanel({ reasoning, agentTrace, streaming }: {
  reasoning?: AgentReasoning;
  agentTrace?: AgentTraceEvent[];
  streaming?: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const panelPresence = usePresence(expanded, 220);
  const hasContent = !!reasoning || (agentTrace && agentTrace.length > 0);
  useEffect(() => {
    setExpanded(!!streaming);
  }, [streaming]);
  if (!hasContent && !streaming) return null;
  const live = !!streaming;
  // 结束后只有拿到后端耗时才显示计时；否则保持「已思考」不带时间，避免出现 0.0s。
  const durationMs = live ? undefined : reasoning?.durationMs;
  const hasDuration = durationMs != null;
  const duration = formatDuration(durationMs);
  const inkColor = live ? 'var(--color-primary)' : 'var(--color-muted)';
  const announce = live
    ? '思考中'
    : hasDuration
      ? `已思考，用时 ${duration}`
      : '已思考';
  const showToggle = hasContent;

  return (
    <div style={{ marginBottom: 10, maxWidth: '100%' }}>
      <button
        onClick={() => showToggle && setExpanded(!expanded)}
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          border: 'none', background: 'transparent', cursor: showToggle ? 'pointer' : 'default',
          padding: 0, color: inkColor,
          fontSize: 12, lineHeight: 1.4, textAlign: 'left',
        }}
      >
        {/* 格纹动画只是状态指示，无障碍名称由下面的 sr-only 文案提供 */}
        <span aria-hidden="true" style={{ display: 'inline-flex', flexShrink: 0 }}>
          <LatticeLoader
            status={live ? 'working' : 'done'}
            label="思考中"
            doneLabel="已思考"
            pattern="orbit"
            grid={3}
            shape="round"
            color={inkColor}
            doneColor="var(--color-success)"
            cellSize={5}
            gap={2}
            fontSize={12}
            step={90}
            idleOpacity={0.15}
            glow={false}
            showTimer={live || hasDuration}
            elapsed={hasDuration ? durationMs / 1000 : undefined}
          />
        </span>
        <span className="sr-only">{announce}</span>
        {showToggle && (
          <span className={`reasoning-chevron${expanded ? ' is-open' : ''}`}>
            {expanded ? <ChevronDown size={13} strokeWidth={1.8} /> : <ChevronRight size={13} strokeWidth={1.8} />}
          </span>
        )}
      </button>

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
              {reasoning?.details && (
                <div
                  className="reasoning-detail-item"
                  style={{ '--item-index': 0 } as React.CSSProperties}
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
