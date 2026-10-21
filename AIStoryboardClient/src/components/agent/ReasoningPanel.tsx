import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import type { AgentReasoning, AgentTraceEvent } from '../../api/agent';
import { AgentTraceTimeline } from './AgentTraceTimeline';
import { usePresence } from '../../hooks/usePresence';
import { useWorkStepDisplay } from '../../stores/accountSettingsStore';
import { agentChatTokens as DS } from './agentChatTokens';
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
export function ReasoningPanel({ reasoning, agentTrace, streaming, loadDetails }: {
  reasoning?: AgentReasoning;
  agentTrace?: AgentTraceEvent[];
  streaming?: boolean;
  /**
   * 按需拉「思考全文 + 执行轨迹」。
   *
   * <p>列表接口为了瘦身只回 `reasoning.detailsAvailable` / `traceAvailable` 标记
   * （一条思考全文 10 KB 级、轨迹 6 KB 级，而它们只在展开这个面板时才看得到）。
   * 展开时再拉一次；失败只记录一次错误态并提供「重试」，不会无限重拉、也不会一直停在加载中。
   */
  loadDetails?: () => Promise<{ reasoningDetails?: string; agentTrace?: AgentTraceEvent[] }>;
}) {
  const [expanded, setExpanded] = useState(false);
  const [fetched, setFetched] = useState<{ reasoningDetails?: string; agentTrace?: AgentTraceEvent[] } | null>(null);
  const [loadingDetails, setLoadingDetails] = useState(false);
  const [detailsFailed, setDetailsFailed] = useState(false);
  /** 在途请求去重：同一个面板同时只允许一个明细请求。 */
  const detailsRequestRef = useRef(false);
  const display = useWorkStepDisplay();
  const panelPresence = usePresence(expanded, 220);
  const hasContent = !!reasoning
    || (display !== 'thinking' && (!!agentTrace && agentTrace.length > 0 || !!loadDetails));
  useEffect(() => {
    setExpanded(!!streaming);
  }, [streaming]);
  /**
   * 展开且列表里没带明细 → 拉一次（组件内缓存，切走再回来会重拉，可接受）。
   *
   * <p>注意：不能把 loading 状态写进下面 effect 的依赖里。effect 内 setLoading(true) 会立刻
   * 触发自身清理、把 `cancelled` 置为 true，于是请求成功/失败都写不回状态，面板会永远停在
   * 「思考内容加载中…」。这里用 ref 做在途去重，失败只落一次错误态，不自动重试。
   */
  const requestDetails = useCallback(() => {
    if (!loadDetails || detailsRequestRef.current || fetched) return;
    detailsRequestRef.current = true;
    setLoadingDetails(true);
    setDetailsFailed(false);
    void loadDetails()
      .then((details) => setFetched(details ?? {}))
      .catch(() => setDetailsFailed(true))
      .finally(() => {
        detailsRequestRef.current = false;
        setLoadingDetails(false);
      });
  }, [loadDetails, fetched]);
  useEffect(() => {
    if (expanded) requestDetails();
  }, [expanded, requestDetails]);

  const detailsText = reasoning?.details || fetched?.reasoningDetails;
  const trace = (agentTrace && agentTrace.length > 0) ? agentTrace : fetched?.agentTrace;
  if (!hasContent && !streaming) return null;
  const live = !!streaming;
  // 结束后只有拿到后端耗时才显示计时；否则保持「已思考」不带时间，避免出现 0.0s。
  const durationMs = live ? undefined : reasoning?.durationMs;
  const hasDuration = durationMs != null;
  const duration = formatDuration(durationMs);
  // /chat 的品牌语言是 DS 蓝：思考中要用和工具调用、状态胶囊同一种蓝，
  // 否则蓝色对话里会冒出一个珊瑚色的「思考中」。
  const inkColor = live ? DS.brand : DS.textSecondary;
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
                border: `1px solid ${DS.border}`, background: DS.surfaceSoft,
                maxWidth: '100%', overflowWrap: 'anywhere',
              }}
            >
              {detailsText ? (
                <div
                  className="reasoning-detail-item"
                  style={{ '--item-index': 0 } as React.CSSProperties}
                >
                  <div style={{ fontSize: 12, color: DS.textSecondary, lineHeight: 1.7, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 240, overflowY: 'auto' }}>
                    <span style={{ color: DS.textSecondary, fontSize: 11, letterSpacing: 1, display: 'block', marginBottom: 4 }}>推理详情</span>
                    {detailsText}
                  </div>
                </div>
              ) : loadingDetails ? (
                <div style={{ fontSize: 12, color: DS.textSecondary, padding: '2px 0 4px' }}>
                  思考内容加载中…
                </div>
              ) : detailsFailed ? (
                <div style={{ fontSize: 12, color: DS.textSecondary, padding: '2px 0 4px' }}>
                  思考详情加载失败，
                  <button
                    type="button"
                    onClick={requestDetails}
                    style={{
                      border: 'none', background: 'transparent', padding: 0, cursor: 'pointer',
                      color: DS.brand, font: 'inherit',
                    }}
                  >
                    重试
                  </button>
                </div>
              ) : null}
              {display !== 'thinking' && trace && trace.length > 0 && (
                <AgentTraceTimeline events={trace} />
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
