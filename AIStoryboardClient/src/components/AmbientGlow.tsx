import { useId } from 'react';

/**
 * AmbientGlow — 页面级氛围光晕（bg-glow overlay）
 *
 * CSS-only、零 JS 状态、零请求：
 * - fixed 铺满视口，z-index 0（低于所有弹层 90+），pointer-events none（不挡任何交互）
 * - 三团品牌色 radial-gradient（珊瑚/暖金/teal）以极低透明度漂移，营造浅色页面上的氛围光
 * - 透明渐变 tint 在内容之上，透明度 5-8%，视觉上只是氛围，不影响阅读与点击
 * - prefers-reduced-motion 降级为静止
 *
 * 用法：挂到页面根容器第一个子元素（Fragment 顶层一次即可，fixed 跟随视口）。
 */
export default function AmbientGlow() {
  const id = useId();
  const glowId = `glow-${id.replace(/[^a-zA-Z0-9]/g, '')}`;

  return (
    <div
      id={glowId}
      aria-hidden
      style={{ position: 'fixed', inset: 0, zIndex: 0, pointerEvents: 'none', overflow: 'hidden' }}
    >
      <style>{`
        #${glowId} > i {
          position: absolute;
          border-radius: 50%;
          filter: blur(90px);
          will-change: transform;
          animation: ${glowId}-drift 26s ease-in-out infinite alternate;
        }
        #${glowId} > i:nth-child(1) {
          width: 46vw; height: 46vw; left: -10vw; top: -14vh;
          background: radial-gradient(circle, rgba(204,120,92,0.09), transparent 62%);
          animation-duration: 30s;
        }
        #${glowId} > i:nth-child(2) {
          width: 40vw; height: 40vw; right: -8vw; top: 4vh;
          background: radial-gradient(circle, rgba(232,165,90,0.08), transparent 62%);
          animation-duration: 34s;
          animation-delay: -8s;
        }
        #${glowId} > i:nth-child(3) {
          width: 44vw; height: 44vw; left: 24vw; bottom: -20vh;
          background: radial-gradient(circle, rgba(93,184,166,0.07), transparent 62%);
          animation-duration: 38s;
          animation-delay: -16s;
        }
        @keyframes ${glowId}-drift {
          0%   { transform: translate3d(0, 0, 0) scale(1); }
          50%  { transform: translate3d(4vw, 3vh, 0) scale(1.08); }
          100% { transform: translate3d(-3vw, -2vh, 0) scale(0.96); }
        }
        @media (prefers-reduced-motion: reduce) {
          #${glowId} > i { animation: none; }
        }
      `}</style>
      <i /><i /><i />
    </div>
  );
}
