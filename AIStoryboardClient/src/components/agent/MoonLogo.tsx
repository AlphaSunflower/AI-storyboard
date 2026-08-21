/**
 * Moon 智能体 SVG Logo
 * 几何新月 + 星芒，渐变色 coral→暖金
 * 用法：<MoonLogo size={28} /> 或 <MoonLogo size={120} showText />
 */
import { useId } from 'react';

interface MoonLogoProps {
  /** 尺寸（px），控制整体高度，宽度自适应 */
  size?: number;
  /** 是否右侧附带 "Moon 智能体" 文字 */
  showText?: boolean;
  /** 文字颜色，默认 ink */
  textColor?: string;
  /** className 透传 */
  className?: string;
  /** style 透传 */
  style?: React.CSSProperties;
}

export default function MoonLogo({
  size = 28,
  showText = false,
  textColor = 'var(--color-ink)',
  className,
  style,
}: MoonLogoProps) {
  const gradId = useId();
  const glowId = useId();

  return (
    <div
      className={className}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: size * 0.36,
        ...style,
      }}
    >
      <svg
        width={size}
        height={size}
        viewBox="0 0 28 28"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        style={{ flexShrink: 0 }}
      >
        <defs>
          {/* 新月渐变：coral → 暖金 */}
          <linearGradient id={gradId} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#cc785c" />
            <stop offset="100%" stopColor="#e8a97b" />
          </linearGradient>
          {/* 柔光滤镜 */}
          <filter id={glowId} x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur in="SourceGraphic" stdDeviation="0.8" />
          </filter>
        </defs>

        {/* 柔光底层（营造月晕） */}
        <path
          d="M18.5 3.5C12.5 3.5 7.5 8.5 7.5 14.5C7.5 20.5 12.5 25 18.5 25C14 25 10 21.5 10 14.5C10 7.5 14 4 18.5 3.5Z"
          fill="#cc785c"
          opacity="0.15"
          filter={`url(#${glowId})`}
        />

        {/* 新月主体：两个圆的差集 */}
        <path
          d="M20 2C12.82 2 7 7.82 7 15C7 22.18 12.82 28 20 28C15.03 28 11 23.97 11 19C11 11.27 15.03 4 20 2Z"
          fill={`url(#${gradId})`}
        />

        {/* 新月内部高光 */}
        <path
          d="M16.5 6.5C13.5 8.5 12 11.5 12 15C12 19.5 14.5 23 17.5 24.5C15 23 13.5 20 13.5 16.5C13.5 11 15.5 7.5 16.5 6.5Z"
          fill="white"
          opacity="0.2"
        />

        {/* 星芒 1（大） */}
        <g transform="translate(22.5, 5.5) rotate(15)">
          <line x1="0" y1="-2.2" x2="0" y2="2.2" stroke="#e8a97b" strokeWidth="0.9" strokeLinecap="round" />
          <line x1="-2.2" y1="0" x2="2.2" y2="0" stroke="#e8a97b" strokeWidth="0.9" strokeLinecap="round" />
          <line x1="-1.4" y1="-1.4" x2="1.4" y2="1.4" stroke="#e8a97b" strokeWidth="0.6" strokeLinecap="round" opacity="0.7" />
          <line x1="1.4" y1="-1.4" x2="-1.4" y2="1.4" stroke="#e8a97b" strokeWidth="0.6" strokeLinecap="round" opacity="0.7" />
        </g>

        {/* 星芒 2（小） */}
        <g transform="translate(25, 11) rotate(-10)">
          <line x1="0" y1="-1.3" x2="0" y2="1.3" stroke="#cc785c" strokeWidth="0.7" strokeLinecap="round" />
          <line x1="-1.3" y1="0" x2="1.3" y2="0" stroke="#cc785c" strokeWidth="0.7" strokeLinecap="round" />
        </g>

        {/* 星芒 3（微小粒子） */}
        <circle cx="19.5" cy="4" r="0.5" fill="#e8a97b" opacity="0.6" />
      </svg>

      {showText && (
        <span
          style={{
            fontSize: size * 0.64,
            fontWeight: 700,
            color: textColor,
            letterSpacing: '-0.02em',
            whiteSpace: 'nowrap',
            lineHeight: 1,
          }}
        >
          Moon 智能体
        </span>
      )}
    </div>
  );
}
