import type { ReactElement } from 'react';
import WarmTooltip, {
  WarmTooltipGroup,
  type WarmTooltipGroupProps,
  type WarmTooltipSide,
} from '../ui/WarmTooltip';
import './Tooltip.css';

export type TooltipSide = WarmTooltipSide;

export interface TooltipProps {
  /** 标签文案，保持一句短语 */
  label: string;
  side?: TooltipSide;
  /** 传给触发器包裹层（需要用 .tooltip-fill 之类接管布局时用） */
  className?: string;
  disabled?: boolean;
  children: ReactElement<Record<string, unknown>>;
}

/** 应用侧统一观感：深墨底 + 浅色字，颜色全部走 tokens.css */
const DEFAULTS = {
  surfaceColor: 'var(--color-ink)',
  inkColor: 'var(--color-on-dark)',
  size: 'sm' as const,
  radius: 8,
  gap: 6,
  arrow: true,
  popDuration: 160,
  popScale: 0.94,
  popBlur: 4,
  // 贴边自动翻面，避免提示被裁到视口外
  autoSide: true,
  // 不画冷启动引线；触摸长按 800ms 才吞掉点击，降低移动端误吞概率
  showFuse: false,
  longPress: 800,
};

// 暖机 tooltip：同一 TooltipGroup 内第一个标签冷启动等 400ms，
// 之后 300ms 暖窗内移到相邻触发器立即展开。
export function Tooltip({ label, side = 'top', className, disabled, children }: TooltipProps) {
  return (
    <WarmTooltip
      content={label}
      side={side}
      className={className}
      disabled={disabled}
      {...DEFAULTS}
    >
      {children}
    </WarmTooltip>
  );
}

/** 把一组相关触发器（工具条、动作行）包进同一个暖机上下文 */
export function TooltipGroup({ children, ...rest }: WarmTooltipGroupProps) {
  return <WarmTooltipGroup {...rest}>{children}</WarmTooltipGroup>;
}
