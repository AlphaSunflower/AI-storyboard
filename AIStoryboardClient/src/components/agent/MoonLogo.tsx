/**
 * Moon 智能体品牌标识
 *
 * 设计语言：「深空徽章 + 星空轨道」
 * - 深紫黑圆角徽章，暖色月晕从左上打亮，右下角压一点冷调靛蓝，形成冷暖对撞；
 * - 新月由两个圆精确求交（不是手描贝塞尔），两端收尖，保证任意尺寸都不糊；
 * - 一枚 −23° 的轨道椭圆从月球背后穿过，右端落一颗冷青色信号点 —— 这是「智能体」的科技感来源；
 * - 徽章里铺一片星场（17 颗星：8 颗四角星 + 9 颗微光点），小尺寸自动精简为 8 颗四角星，
 *   避免 26px 徽章糊成一片灰点；主星芒仍是画面最亮的一颗。
 *
 * 动效（animated）：
 * - 信号点沿轨道缓慢绕行，转到月球背后时被新月自然遮挡（图形绘制顺序即遮挡关系）；
 * - 星场逐颗以不同周期与相位闪烁，信号点光晕呼吸、主星芒轻微缩放、月晕缓慢明暗；
 * - 自动遵循 prefers-reduced-motion：用户关闭动效时退回静态图标（星场仍在，只是不闪）。
 *
 * 用法：
 *   <MoonLogo size={28} />                      完整徽章（侧栏、登录页）
 *   <MoonLogo size={28} showText animated />    徽章 + 文字 + 动效
 *   <MoonLogo variant="mark" size={20} />       去掉徽章的纯符号（深色胶囊、极小内联）
 *   <MoonLogo variant="mark" tone="brand" />    浅底内联用珊瑚色
 */
import { useEffect, useId, useState } from 'react';

interface MoonLogoProps {
  /** 尺寸（px），控制整体高度，宽度自适应 */
  size?: number;
  /** 是否右侧附带 "Moon 智能体" 文字 */
  showText?: boolean;
  /** 文字颜色，默认 ink */
  textColor?: string;
  /**
   * badge：完整应用图标（默认）
   * mark：透明底纯符号，用于深色胶囊 / 极小内联场景
   */
  variant?: 'badge' | 'mark';
  /** mark 变体取色：light 暖白金（深底），brand 珊瑚（浅底），blue 聊天页品牌蓝 */
  tone?: 'light' | 'brand' | 'blue';
  /** 轨道信号点 / 星芒动效；默认关闭，自动遵守 prefers-reduced-motion */
  animated?: boolean;
  /** className 透传 */
  className?: string;
  /** style 透传 */
  style?: React.CSSProperties;
}

/** 新月：外圆 O(30.6,31.4) r18.8 减去切圆 C(42.2,27.2) r20.6 的圆弧求交结果 */
const CRESCENT_PATH =
  'M 40 47.682 A 18.8 18.8 0 1 1 27.396 12.875 A 20.6 20.6 0 0 0 40 47.682 Z';

/** 四角星（凹边菱形），中心 (46.6,18.2)，尖半径 4.4 */
const SPARK_PATH =
  'M46.6 13.8Q47.7 17.1 51 18.2Q47.7 19.3 46.6 22.6Q45.5 19.3 42.2 18.2Q45.5 17.1 46.6 13.8Z';

/** 同一颗星以 (0,0) 为中心的版本，便于绕自身中心做缩放动画 */
const SPARK_CENTERED_PATH =
  'M0 -4.4Q1.1 -1.1 4.4 0Q1.1 1.1 0 4.4Q-1.1 1.1 -4.4 0Q-1.1 -1.1 0 -4.4Z';

/** mark 变体的四角星，中心 (44.2,19.3)，尖半径 4.1 */
const SPARK_MARK_PATH =
  'M44.2 15.2Q45.2 18.3 48.3 19.3Q45.2 20.3 44.2 23.4Q43.2 20.3 40.1 19.3Q43.2 18.3 44.2 15.2Z';

/** mark 变体的星，以 (0,0) 为中心 */
const SPARK_MARK_CENTERED_PATH =
  'M0 -4.1Q1 -1 4.1 0Q1 1 0 4.1Q-1 1 -4.1 0Q-1 -1 0 -4.1Z';

const MARK_TONES = {
  light: { from: '#FFE9CE', mid: '#F6C79E', to: '#D98F68', spark: '#FFF3E0' },
  brand: { from: '#E49A73', mid: '#CC785C', to: '#9E4A31', spark: '#E8A57F' },
  blue: { from: '#8FA5FF', mid: '#4D6BFE', to: '#2B45C7', spark: '#A9BAFF' },
} as const;

interface StarSpec {
  x: number;
  y: number;
  size: number;
  opacity: number;
  /** 一次明暗循环的秒数 */
  dur: number;
  /** 负延迟 = 从周期中段开始，避免所有星同步闪 */
  delay: number;
  shape: 'spark' | 'dot';
  color: string;
}

/**
 * 徽章内的星场：小四角星 + 微光点，围绕新月分布成一片夜空。
 * 分布避开新月主体（被遮挡的星自然消失），主星芒仍是画面最亮的一颗。
 */
const BADGE_STARS: StarSpec[] = [
  { x: 12.6, y: 10.2, size: 2.3, opacity: 0.72, dur: 5.6, delay: 1.1, shape: 'spark', color: '#FFF6E8' },
  { x: 23.8, y: 8.2, size: 1.5, opacity: 0.55, dur: 4.4, delay: 2.9, shape: 'spark', color: '#FFF6E8' },
  { x: 37.6, y: 9.4, size: 1.9, opacity: 0.62, dur: 4.9, delay: 0.4, shape: 'spark', color: '#FFF6E8' },
  { x: 54.4, y: 12.2, size: 1.6, opacity: 0.52, dur: 5.2, delay: 3.3, shape: 'spark', color: '#DCE9FF' },
  { x: 52.8, y: 39.8, size: 2.2, opacity: 0.66, dur: 6.4, delay: 0.7, shape: 'spark', color: '#FFEFD8' },
  { x: 14.4, y: 52.6, size: 1.9, opacity: 0.56, dur: 5.9, delay: 3.7, shape: 'spark', color: '#FFF6E8' },
  { x: 45.2, y: 49.2, size: 1.6, opacity: 0.5, dur: 4.6, delay: 2.1, shape: 'spark', color: '#FFF6E8' },
  { x: 8.8, y: 43.2, size: 1.4, opacity: 0.44, dur: 4.2, delay: 1.5, shape: 'spark', color: '#DCE9FF' },
  { x: 18.4, y: 5.8, size: 0.75, opacity: 0.6, dur: 3.4, delay: 0.9, shape: 'dot', color: '#FFF6E8' },
  { x: 31.2, y: 6.4, size: 0.7, opacity: 0.5, dur: 4.1, delay: 2.4, shape: 'dot', color: '#FFF6E8' },
  { x: 47.4, y: 8.8, size: 0.7, opacity: 0.55, dur: 3.8, delay: 1.6, shape: 'dot', color: '#FFF6E8' },
  { x: 58.6, y: 22.4, size: 0.65, opacity: 0.45, dur: 3.1, delay: 0.3, shape: 'dot', color: '#DCE9FF' },
  { x: 43.4, y: 33.4, size: 0.8, opacity: 0.55, dur: 4.8, delay: 3.1, shape: 'dot', color: '#FFF6E8' },
  { x: 58.2, y: 39.4, size: 0.7, opacity: 0.5, dur: 3.6, delay: 2.0, shape: 'dot', color: '#FFF6E8' },
  { x: 29.2, y: 57.2, size: 0.8, opacity: 0.5, dur: 3.9, delay: 1.2, shape: 'dot', color: '#FFF6E8' },
  { x: 36.6, y: 55.2, size: 0.6, opacity: 0.4, dur: 5.2, delay: 3.8, shape: 'dot', color: '#FFF6E8' },
  { x: 8.4, y: 22.2, size: 0.65, opacity: 0.45, dur: 4.7, delay: 0.6, shape: 'dot', color: '#FFF6E8' },
];

/** 以 (x,y) 为中心的四角星路径 */
function starPath(x: number, y: number, size: number): string {
  const k = size * 0.26;
  const n = (value: number) => Math.round(value * 1000) / 1000;
  return (
    `M${n(x)} ${n(y - size)}` +
    `Q${n(x + k)} ${n(y - k)} ${n(x + size)} ${n(y)}` +
    `Q${n(x + k)} ${n(y + k)} ${n(x)} ${n(y + size)}` +
    `Q${n(x - k)} ${n(y + k)} ${n(x - size)} ${n(y)}` +
    `Q${n(x - k)} ${n(y - k)} ${n(x)} ${n(y - size)}Z`
  );
}

/** 星场：静态时是固定夜空，animated 时逐颗以不同相位呼吸 */
function MoonStarfield({
  animated,
  glowId,
  size,
}: {
  animated: boolean;
  glowId: string;
  size: number;
}) {
  // 小尺寸下只留四角星、去掉微光点和光晕，避免 26px 徽章糊成一片灰点
  const compact = size < 32;
  const stars = compact ? BADGE_STARS.filter((star) => star.shape === 'spark') : BADGE_STARS;

  return (
    <g data-moon-starfield>
      {stars.map((star, index) => {
        const twinkle = animated ? (
          <animate
            attributeName="opacity"
            values={`${(star.opacity * 0.22).toFixed(2)};${star.opacity};${(star.opacity * 0.22).toFixed(2)}`}
            dur={`${star.dur}s`}
            begin={`-${star.delay}s`}
            repeatCount="indefinite"
          />
        ) : null;

        if (star.shape === 'dot') {
          return (
            <circle
              key={`star-${index}`}
              data-moon-star
              cx={star.x}
              cy={star.y}
              r={star.size}
              fill={star.color}
              opacity={star.opacity}
            >
              {twinkle}
            </circle>
          );
        }

        return (
          <g key={`star-${index}`}>
            {!compact && star.size >= 1.9 && (
              <circle
                cx={star.x}
                cy={star.y}
                r={Math.round(star.size * 2.6 * 100) / 100}
                fill={`url(#${glowId})`}
                opacity={star.opacity * 0.55}
              />
            )}
            <path
              data-moon-star
              d={starPath(star.x, star.y, star.size)}
              fill={star.color}
              opacity={star.opacity}
            >
              {twinkle}
            </path>
          </g>
        );
      })}
    </g>
  );
}

/** 首帧就读取系统设置，避免 reduced-motion 用户先看到一帧动画 */
function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const apply = () => setReduced(query.matches);
    apply();
    query.addEventListener?.('change', apply);
    return () => query.removeEventListener?.('change', apply);
  }, []);

  return reduced;
}

export default function MoonLogo({
  size = 28,
  showText = false,
  textColor = 'var(--color-ink)',
  variant = 'badge',
  tone = 'light',
  animated = false,
  className,
  style,
}: MoonLogoProps) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const prefersReducedMotion = usePrefersReducedMotion();
  const motion = animated && !prefersReducedMotion;

  const tileId = `moonTile-${uid}`;
  const tileLightId = `moonTileLight-${uid}`;
  const coolId = `moonCool-${uid}`;
  const haloId = `moonHalo-${uid}`;
  const moonId = `moonBody-${uid}`;
  const rimId = `moonRim-${uid}`;
  const orbitId = `moonOrbit-${uid}`;
  const nodeId = `moonNode-${uid}`;
  const starGlowId = `moonStarGlow-${uid}`;
  const clipId = `moonClip-${uid}`;
  const markId = `moonMark-${uid}`;
  const markGlowId = `moonMarkGlow-${uid}`;

  return (
    <div
      className={className}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: size * 0.34,
        ...style,
      }}
    >
      {variant === 'mark' ? (
        <svg
          width={size}
          height={size}
          viewBox="0 0 64 64"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          aria-hidden="true"
          focusable="false"
          style={{ flexShrink: 0, display: 'block' }}
        >
          <defs>
            <linearGradient id={markId} x1="20%" y1="0%" x2="76%" y2="100%">
              <stop offset="0%" stopColor={MARK_TONES[tone].from} />
              <stop offset="50%" stopColor={MARK_TONES[tone].mid} />
              <stop offset="100%" stopColor={MARK_TONES[tone].to} />
            </linearGradient>
            <radialGradient id={markGlowId} cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="#FFD9B4" stopOpacity="0.30" />
              <stop offset="100%" stopColor="#FFD9B4" stopOpacity="0" />
            </radialGradient>
          </defs>
          <g transform="translate(1.6 1.6)">
            {motion && (
              <circle cx="31" cy="31" r="27" fill={`url(#${markGlowId})`}>
                <animate
                  attributeName="opacity"
                  values="0.55;1;0.55"
                  dur="6.2s"
                  repeatCount="indefinite"
                />
              </circle>
            )}
            <path d={CRESCENT_PATH} fill={`url(#${markId})`} />
            {motion ? (
              <g transform="translate(44.2 19.3)">
                <path d={SPARK_MARK_CENTERED_PATH} fill={MARK_TONES[tone].spark}>
                  <animate
                    attributeName="opacity"
                    values="0.55;1;0.55"
                    dur="5.2s"
                    repeatCount="indefinite"
                  />
                  <animateTransform
                    attributeName="transform"
                    type="scale"
                    values="0.88;1.08;0.88"
                    dur="5.2s"
                    repeatCount="indefinite"
                  />
                </path>
              </g>
            ) : (
              <path d={SPARK_MARK_PATH} fill={MARK_TONES[tone].spark} />
            )}
          </g>
        </svg>
      ) : (
        <svg
          width={size}
          height={size}
          viewBox="0 0 64 64"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          aria-hidden="true"
          focusable="false"
          style={{ flexShrink: 0, display: 'block' }}
        >
          <defs>
            <linearGradient id={tileId} x1="6%" y1="0%" x2="94%" y2="100%">
              <stop offset="0%" stopColor="#352A4A" />
              <stop offset="44%" stopColor="#1A1429" />
              <stop offset="100%" stopColor="#09080F" />
            </linearGradient>
            <radialGradient id={tileLightId} cx="16%" cy="-4%" r="96%">
              <stop offset="0%" stopColor="#FFD2B0" stopOpacity="0.30" />
              <stop offset="44%" stopColor="#FFD2B0" stopOpacity="0.06" />
              <stop offset="100%" stopColor="#FFD2B0" stopOpacity="0" />
            </radialGradient>
            <radialGradient id={coolId} cx="94%" cy="100%" r="78%">
              <stop offset="0%" stopColor="#6C7BFF" stopOpacity="0.22" />
              <stop offset="100%" stopColor="#6C7BFF" stopOpacity="0" />
            </radialGradient>
            <radialGradient id={haloId} cx="40%" cy="46%" r="58%">
              <stop offset="0%" stopColor="#FFA06C" stopOpacity="0.34" />
              <stop offset="55%" stopColor="#FFA06C" stopOpacity="0.10" />
              <stop offset="100%" stopColor="#FFA06C" stopOpacity="0" />
            </radialGradient>
            <linearGradient id={moonId} x1="20%" y1="0%" x2="76%" y2="100%">
              <stop offset="0%" stopColor="#FFFFFF" />
              <stop offset="34%" stopColor="#FFF1E0" />
              <stop offset="72%" stopColor="#FBD5AE" />
              <stop offset="100%" stopColor="#F0B183" />
            </linearGradient>
            <linearGradient id={rimId} x1="12%" y1="0%" x2="88%" y2="100%">
              <stop offset="0%" stopColor="#FFE6D1" stopOpacity="0.70" />
              <stop offset="34%" stopColor="#FFE6D1" stopOpacity="0.20" />
              <stop offset="100%" stopColor="#FFE6D1" stopOpacity="0.05" />
            </linearGradient>
            <linearGradient id={orbitId} x1="4%" y1="30%" x2="96%" y2="70%">
              <stop offset="0%" stopColor="#B9E9FF" stopOpacity="0.03" />
              <stop offset="34%" stopColor="#B9E9FF" stopOpacity="0.26" />
              <stop offset="70%" stopColor="#B9E9FF" stopOpacity="0.50" />
              <stop offset="100%" stopColor="#B9E9FF" stopOpacity="0.52" />
            </linearGradient>
            <radialGradient id={nodeId} cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="#8CEEFF" stopOpacity="0.55" />
              <stop offset="100%" stopColor="#8CEEFF" stopOpacity="0" />
            </radialGradient>
            <radialGradient id={starGlowId} cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="#FFE9C9" stopOpacity="0.6" />
              <stop offset="100%" stopColor="#FFE9C9" stopOpacity="0" />
            </radialGradient>
            <clipPath id={clipId}>
              <rect x="2" y="2" width="60" height="60" rx="16.4" />
            </clipPath>
          </defs>

          <g clipPath={`url(#${clipId})`}>
            <rect x="2" y="2" width="60" height="60" rx="16.4" fill={`url(#${tileId})`} />
            <rect x="2" y="2" width="60" height="60" rx="16.4" fill={`url(#${tileLightId})`} />
            <rect x="2" y="2" width="60" height="60" rx="16.4" fill={`url(#${coolId})`} />
            <circle cx="30" cy="33" r="28" fill={`url(#${haloId})`}>
              {motion && (
                <animate
                  attributeName="opacity"
                  values="0.78;1;0.78"
                  dur="7.6s"
                  repeatCount="indefinite"
                />
              )}
            </circle>

            <MoonStarfield animated={motion} glowId={starGlowId} size={size} />

            {/* 轨道：从月球背后穿过；信号点沿它绕行，月球会自然遮住背面那一段 */}
            <ellipse
              cx="32"
              cy="32"
              rx="27"
              ry="9.6"
              transform="rotate(-23 32 32)"
              fill="none"
              stroke={`url(#${orbitId})`}
              strokeWidth="1.3"
            />

            {motion ? (
              <g transform="translate(32 32) rotate(-23)">
                {/* 先把圆轨道压成椭圆，再在椭圆空间里旋转；信号点自身反向缩放，保持正圆 */}
                <g transform="scale(1 0.355556)">
                  <g>
                    <animateTransform
                      attributeName="transform"
                      type="rotate"
                      from="0"
                      to="360"
                      dur="26s"
                      repeatCount="indefinite"
                    />
                    <g transform="translate(27 0) scale(1 2.8125)">
                      <circle r="5.5" fill={`url(#${nodeId})`}>
                        <animate
                          attributeName="opacity"
                          values="0.65;1;0.65"
                          dur="3.4s"
                          repeatCount="indefinite"
                        />
                      </circle>
                      <circle
                        r="3.2"
                        fill="none"
                        stroke="#8CEEFF"
                        strokeOpacity="0.30"
                        strokeWidth="0.7"
                      />
                      <circle r="1.75" fill="#8CEEFF">
                        <animate
                          attributeName="r"
                          values="1.6;1.95;1.6"
                          dur="3.4s"
                          repeatCount="indefinite"
                        />
                      </circle>
                    </g>
                  </g>
                </g>
              </g>
            ) : (
              <>
                <circle cx="55.7" cy="27.0" r="5.5" fill={`url(#${nodeId})`} />
                <circle
                  cx="55.7"
                  cy="27.0"
                  r="3.2"
                  fill="none"
                  stroke="#8CEEFF"
                  strokeOpacity="0.30"
                  strokeWidth="0.7"
                />
                <circle cx="55.7" cy="27.0" r="1.75" fill="#8CEEFF" />
              </>
            )}

            {/* 新月本体 + 右上星芒 */}
            <path d={CRESCENT_PATH} fill={`url(#${moonId})`} />
            {motion ? (
              <g transform="translate(46.6 18.2)">
                <path d={SPARK_CENTERED_PATH} fill="#FFF9F0">
                  <animate
                    attributeName="opacity"
                    values="0.55;1;0.55"
                    dur="5.2s"
                    repeatCount="indefinite"
                  />
                  <animateTransform
                    attributeName="transform"
                    type="scale"
                    values="0.88;1.08;0.88"
                    dur="5.2s"
                    repeatCount="indefinite"
                  />
                </path>
              </g>
            ) : (
              <path d={SPARK_PATH} fill="#FFF9F0" />
            )}
          </g>

          <rect
            x="2.55"
            y="2.55"
            width="58.9"
            height="58.9"
            rx="15.9"
            fill="none"
            stroke={`url(#${rimId})`}
            strokeWidth="1.1"
          />
        </svg>
      )}

      {showText && (
        <span
          style={{
            fontSize: Math.round(size * 0.62 * 100) / 100,
            fontWeight: 650,
            color: textColor,
            letterSpacing: '-0.015em',
            whiteSpace: 'nowrap',
            lineHeight: 1,
          }}
        >
          Moon{' '}
          <span style={{ fontWeight: 600, opacity: 0.58 }}>智能体</span>
        </span>
      )}
    </div>
  );
}
