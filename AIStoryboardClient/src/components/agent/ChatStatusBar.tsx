import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Database, Timer } from 'lucide-react';
import { useAgentStore } from '../../stores/agentStore';
import { agentChatTokens as T } from './agentChatTokens';
import { formatApproxTokenCount, formatTokenCount } from './contextWindow';
import { summarizeConversationStatus } from './contextStatus';
import { ContextUsageRing } from './ContextUsageRing';
import { resolveCardAnchor, type CardAnchor } from './statusCardAnchor';
import './ChatStatusBar.css';

type StatusCard = 'runs' | 'tokens' | 'context';

/** 千分位完整数值，供深色卡片展示（无数据时为 0）。 */
function fullNumber(value: number): string {
  return Math.round(Math.max(0, value)).toLocaleString('en-US');
}

/** 毫秒 → 紧凑耗时文案：1.2s / 12.4s / 1m08s。 */
function formatDuration(ms: number): string {
  const safe = Math.max(0, ms);
  if (safe <= 0) return '0s';
  if (safe < 60_000) {
    const seconds = safe / 1000;
    return `${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)}s`;
  }
  const totalSeconds = Math.round(safe / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}m${String(seconds).padStart(2, '0')}s`;
}

function CardRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="chat-status-card__row">
      <span className="chat-status-card__label">{label}</span>
      <span className="chat-status-card__value">{value}</span>
    </div>
  );
}

/** 后端未上报该指标时显示占位，避免把「缺失」误读成 0。 */
function durationOrDash(ms: number): string {
  return ms > 0 ? formatDuration(ms) : '暂无';
}

/**
 * /chat 底部状态栏：替代「内容由 AI 生成，请仔细甄别」。
 *
 * 三组从左到右——轮/步·输出速度、累计 token·缓存命中、上下文占用环；
 * 每组点击展开上浮深色卡片（同时只开一张，Esc / 点击外部关闭）。
 * 无任何真实用量数据（新会话）时回退为免责声明文案。
 */
export function ChatStatusBar({ compact = false, disabled = false, collapsed = false }: {
  compact?: boolean;
  disabled?: boolean;
  /** 输入卡收缩态：状态栏整块收起，输入卡下沉到原位置 */
  collapsed?: boolean;
}) {
  const messages = useAgentStore((state) => state.messages);
  const activeConversationId = useAgentStore((state) => state.activeConversationId);
  const liveMetrics = useAgentStore(
    (state) => state.generating[activeConversationId ?? '']?.usageDraft,
  );
  const liveWindow = useAgentStore(
    (state) => state.generating[activeConversationId ?? '']?.contextWindowTokens,
  );
  const streaming = useAgentStore((state) => state.streaming);
  const [openCard, setOpenCard] = useState<StatusCard | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const [anchor, setAnchor] = useState<CardAnchor | null>(null);

  const status = useMemo(
    () => summarizeConversationStatus(messages, { metrics: liveMetrics, contextWindowTokens: liveWindow }),
    [messages, liveMetrics, liveWindow],
  );

  /** 贴近状态栏向上展开：浮动层挂到 body，避免被浮窗的 overflow 裁切。 */
  const measure = useCallback((): CardAnchor | null => {
    const node = rootRef.current;
    if (!node) return null;
    return resolveCardAnchor(node.getBoundingClientRect(), {
      width: window.innerWidth,
      height: window.innerHeight,
    });
  }, []);

  useEffect(() => {
    if (!status.hasData) setOpenCard(null);
  }, [status.hasData]);

  // 收缩时状态栏被收起，浮层必须一起收掉，避免只剩一张悬空卡片
  useEffect(() => {
    if (collapsed) setOpenCard(null);
  }, [collapsed]);

  useEffect(() => {
    if (!openCard) return;
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (rootRef.current?.contains(target)) return;
      if (cardRef.current?.contains(target)) return;
      setOpenCard(null);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpenCard(null);
    };
    /** 滚动即收起浮层；卡片自身内部滚动不算。 */
    const dismissOnScroll = (event: Event) => {
      if (cardRef.current?.contains(event.target as Node)) return;
      setOpenCard(null);
    };
    const onWheel = (event: WheelEvent) => dismissOnScroll(event);
    const onScroll = (event: Event) => {
      // 流式回复会自动把消息列滚到底，那种滚动不该把用户刚打开的卡收掉
      if (streaming) return;
      dismissOnScroll(event);
    };
    const reposition = () => setAnchor(measure());
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('wheel', onWheel, { capture: true, passive: true });
    document.addEventListener('touchmove', dismissOnScroll, { capture: true, passive: true });
    document.addEventListener('scroll', onScroll, { capture: true });
    window.addEventListener('resize', reposition);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('wheel', onWheel, { capture: true });
      document.removeEventListener('touchmove', dismissOnScroll, { capture: true });
      document.removeEventListener('scroll', onScroll, { capture: true });
      window.removeEventListener('resize', reposition);
    };
  }, [openCard, measure, streaming]);

  if (!status.hasData) {
    return (
      <div className={`chat-status-bar is-empty${compact ? ' is-compact' : ''}`}>
        内容由 AI 生成，请仔细甄别
      </div>
    );
  }

  const toggle = (card: StatusCard) => () => {
    if (openCard === card) {
      setOpenCard(null);
      return;
    }
    setAnchor(measure());
    setOpenCard(card);
  };
  const cacheHitPercent = Math.round(status.cacheHitRatio * 100);
  const speed = Math.round(status.tokensPerSecond);
  const breakdown = status.breakdown;
  const segments = breakdown
    ? [
        { key: 'system', label: '系统提示词', value: breakdown.systemPromptTokens, color: T.segSystem },
        { key: 'tools', label: '工具定义', value: breakdown.toolDefinitionTokens, color: T.segTools },
        { key: 'messages', label: '对话消息', value: breakdown.messageTokens, color: T.segMessages },
      ].filter((segment) => segment.value > 0)
    : [];
  const contextPercent = status.contextWindowTokens > 0
    ? Math.round(Math.min(1, status.contextTokens / status.contextWindowTokens) * 100)
    : 0;

  const card = openCard && anchor
    ? createPortal(
        <div
          ref={cardRef}
          className={`chat-status-card is-${openCard}`}
          role="dialog"
          aria-label={openCard === 'runs' ? '运行统计' : openCard === 'tokens' ? 'Token 用量' : '上下文组成明细'}
          style={{
            position: 'fixed',
            bottom: anchor.bottom,
            maxHeight: anchor.maxHeight,
            ...(openCard === 'runs' ? { left: anchor.left } : { right: anchor.right }),
          }}
        >
          {openCard === 'runs' && (
            <>
              <div className="chat-status-card__title">运行统计</div>
              <CardRow label="轮次" value={`${status.rounds} 轮`} />
              <CardRow label="总步数" value={`${status.steps} 步`} />
              <CardRow label="模型用时" value={durationOrDash(status.modelDurationMs)} />
              <CardRow label="工具用时" value={durationOrDash(status.toolDurationMs)} />
              <CardRow label="输出速度" value={`${speed} tok/s`} />
            </>
          )}
          {openCard === 'tokens' && (
            <>
              <div className="chat-status-card__title">
                <span className="chat-status-card__heading">
                  <Database size={13} strokeWidth={1.8} aria-hidden="true" />
                  Token 用量
                </span>
                <span className="chat-status-card__value">{fullNumber(status.totalTokens)} tok</span>
              </div>
              <CardRow label="缓存命中" value={`${cacheHitPercent}%`} />
              <CardRow label="未缓存输入" value={`${fullNumber(status.uncachedInputTokens)} tok`} />
              <CardRow label="缓存读取" value={`${fullNumber(status.cachedInputTokens)} tok`} />
              <CardRow label="输出" value={`${fullNumber(status.outputTokens)} tok`} />
            </>
          )}
          {openCard === 'context' && (
            <>
              <div className="chat-status-card__title">
                <span>
                  上下文已用 <b>{contextPercent}%</b>
                </span>
                <span className="chat-status-card__value">
                  ~{formatApproxTokenCount(status.contextTokens)} / {formatApproxTokenCount(status.contextWindowTokens)}
                </span>
              </div>
              <div className="chat-context-detail__track">
                <div
                  className="chat-context-detail__fill"
                  style={{ width: `${Math.min(100, contextPercent)}%` }}
                >
                  {segments.map((segment) => (
                    <div
                      key={segment.key}
                      data-segment={segment.key}
                      style={{ flexGrow: segment.value, background: segment.color }}
                    />
                  ))}
                </div>
              </div>
              {segments.length > 0 ? segments.map((segment) => (
                <div key={segment.key} className="chat-status-card__row">
                  <span className="chat-status-card__legend">
                    <span
                      aria-hidden="true"
                      className="chat-status-card__swatch"
                      style={{ background: segment.color }}
                    />
                    {segment.label}
                  </span>
                  <span className="chat-status-card__value">~{formatApproxTokenCount(segment.value)}</span>
                </div>
              )) : (
                <div className="chat-status-card__empty">暂无明细</div>
              )}
            </>
          )}
        </div>,
        document.body,
      )
    : null;

  return (
    <div
      ref={rootRef}
      className={`chat-status-bar${compact ? ' is-compact' : ''}`}
    >
      <div className="chat-status-bar__row">
        <button
          type="button"
          className={`chat-status-bar__group${openCard === 'runs' ? ' is-open' : ''}`}
          aria-expanded={openCard === 'runs'}
          onClick={toggle('runs')}
        >
          <Timer size={13} strokeWidth={1.8} aria-hidden="true" />
          <span className="chat-status-bar__strong">{status.rounds} 轮 {status.steps} 步</span>
          <span className="chat-status-bar__speed">
            <span className="chat-status-bar__sep" aria-hidden="true">·</span>
            <span>{speed} tok/s</span>
          </span>
        </button>

        <span className="chat-status-bar__mid" aria-hidden="true">·</span>

        <button
          type="button"
          className={`chat-status-bar__group${openCard === 'tokens' ? ' is-open' : ''}`}
          aria-expanded={openCard === 'tokens'}
          onClick={toggle('tokens')}
        >
          <Database size={13} strokeWidth={1.8} aria-hidden="true" />
          <span className="chat-status-bar__strong">{formatTokenCount(status.totalTokens)} tok</span>
          <span className="chat-status-bar__sep" aria-hidden="true">·</span>
          <span>缓存命中 {cacheHitPercent}%</span>
        </button>

        <span className="chat-status-bar__mid" aria-hidden="true">·</span>

        <ContextUsageRing
          usedTokens={status.contextTokens}
          limitTokens={status.contextWindowTokens}
          cachedTokens={status.contextCachedInputTokens}
          disabled={disabled}
          open={openCard === 'context'}
          suppressTip={openCard !== null && openCard !== 'context'}
          onToggle={toggle('context')}
        />
      </div>

      {card}
    </div>
  );
}
