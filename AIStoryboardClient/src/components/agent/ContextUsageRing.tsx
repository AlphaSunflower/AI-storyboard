import { useMemo, useState } from 'react';
import { agentChatTokens as T } from './agentChatTokens';
import { contextUsageRatio, formatTokenCount } from './contextWindow';

interface ContextUsageRingProps {
  /** 已占用的上下文 token（最近一轮输入 + 流式累积）。 */
  usedTokens: number;
  /** 当前模型的上下文窗口 token。 */
  limitTokens: number;
  /** 其中命中缓存前缀的 input token（input 的子集，不重复计入占用）。 */
  cachedTokens?: number;
  /** 环直径，默认 28。 */
  size?: number;
  /** 只读会话：保留展示，禁用悬浮详情。 */
  disabled?: boolean;
  /** 组成明细卡是否展开（由父级统一管理，保证同时只开一张卡）。 */
  open?: boolean;
  /** 其他卡片展开时抑制悬浮提示，避免两者叠在一起。 */
  suppressTip?: boolean;
  /** 点击环切换明细卡。 */
  onToggle?: () => void;
}

/** 占用等级 → 语义色（常态品牌蓝 / 偏高琥珀 / 近满危险红）。 */
function ringColorFor(ratio: number): string {
  if (ratio >= 0.9) return T.danger;
  if (ratio >= 0.7) return T.warning;
  return T.brand;
}

/**
 * 输入框右下角「上下文占用」圆圈：常驻显示占用比例，悬浮弹出深色详情卡
 * （占比 / 剩余 / 已用 token 与总量）；点击切换「上下文组成」三色明细卡。
 */
export function ContextUsageRing({
  usedTokens,
  limitTokens,
  cachedTokens = 0,
  size = 28,
  disabled = false,
  open = false,
  suppressTip = false,
  onToggle,
}: ContextUsageRingProps) {
  const [hovered, setHovered] = useState(false);
  const ratio = contextUsageRatio(usedTokens, limitTokens);
  const percent = Math.round(ratio * 100);
  const color = ringColorFor(ratio);
  const tipVisible = hovered && !disabled && !open && !suppressTip;
  const interactive = !!onToggle && !disabled;

  // 由 size 推导航办几何：stroke 随尺寸微调，保证 20~36px 都不糊边。
  const stroke = size <= 24 ? 2.6 : size >= 32 ? 3.4 : 3;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const center = size / 2;
  const dashOffset = circumference * (1 - ratio);
  const title = useMemo(() => {
    if (!Number.isFinite(limitTokens) || limitTokens <= 0) return '上下文占用';
    return `上下文占用 ${percent}%（${formatTokenCount(usedTokens)} / ${formatTokenCount(limitTokens)}）`;
  }, [limitTokens, percent, usedTokens]);

  return (
    <div
      className="chat-context-ring-anchor"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div
        role="meter"
        aria-label={title}
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        {...(interactive ? { 'aria-expanded': open } : {})}
        tabIndex={disabled ? -1 : 0}
        onClick={interactive ? onToggle : undefined}
        onKeyDown={interactive
          ? (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                onToggle?.();
              }
            }
          : undefined}
        onFocus={() => setHovered(true)}
        onBlur={() => setHovered(false)}
        style={{
          position: 'relative', width: size, height: size, flexShrink: 0,
          display: 'grid', placeItems: 'center',
          cursor: disabled ? 'default' : interactive ? 'pointer' : 'help',
          outlineColor: T.brand,
        }}
      >
        <svg
          width={size}
          height={size}
          viewBox={`0 0 ${size} ${size}`}
          aria-hidden="true"
          style={{ position: 'absolute', inset: 0, transform: 'rotate(-90deg)' }}
        >
          <circle
            cx={center}
            cy={center}
            r={radius}
            fill="none"
            stroke={`color-mix(in srgb, var(--color-primary) 18%, transparent)`}
            strokeWidth={stroke}
          />
          <circle
            cx={center}
            cy={center}
            r={radius}
            fill="none"
            stroke={color}
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={dashOffset}
            style={{ transition: 'stroke-dashoffset 0.35s ease, stroke 0.2s ease' }}
          />
        </svg>
        <span
          style={{
            position: 'relative',
            fontSize: 9,
            lineHeight: 1,
            fontWeight: 600,
            color: T.textSecondary,
            fontVariantNumeric: 'tabular-nums',
          }}
        >{percent >= 100 ? '99+' : `${percent}%`}</span>
      </div>

      {tipVisible && (
        <div
          className={`chat-context-tip${tipVisible ? ' is-open' : ''}`}
          style={{
            position: 'absolute',
            bottom: 'calc(100% + 10px)',
            right: 0,
            zIndex: 1200,
            minWidth: 152,
            padding: '8px 12px',
            borderRadius: 12,
            background: 'var(--color-surface-dark)',
            border: `1px solid ${T.borderStrong}`,
            boxShadow: '0 8px 24px rgba(15, 17, 21, 0.28)',
            textAlign: 'center',
            pointerEvents: 'none',
            whiteSpace: 'nowrap',
            display: 'flex',
            flexDirection: 'column',
            gap: 2,
          }}
        >
          <div style={{ fontSize: 12, lineHeight: 1.3, color: T.onDarkSoft }}>背景信息窗口：</div>
          <div style={{ fontSize: 13, lineHeight: 1.35, fontWeight: 600, color: T.onDark }}>
            {percent}% 已用（剩余 {Math.max(0, 100 - percent)}%）
          </div>
          <div style={{ fontSize: 13, lineHeight: 1.35, color: T.onDark }}>
            已用 {formatTokenCount(usedTokens)} 标记，共 {formatTokenCount(limitTokens)}
          </div>
          {cachedTokens > 0 && (
            <div style={{ fontSize: 12, lineHeight: 1.35, color: T.onDarkSoft }}>
              其中缓存命中 {formatTokenCount(cachedTokens)}
            </div>
          )}
          <span
            aria-hidden="true"
            style={{
              position: 'absolute', top: '100%', right: size / 2 - 5, width: 10, height: 10,
              background: 'var(--color-surface-dark)', transform: 'translateY(-5px) rotate(45deg)', borderRadius: 2,
            }}
          />
        </div>
      )}

    </div>
  );
}
