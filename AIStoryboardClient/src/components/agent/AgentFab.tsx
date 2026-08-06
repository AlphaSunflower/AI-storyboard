import { useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../../stores/agentStore';

export function AgentFab() {
  const windowOpen = useAgentStore((s) => s.windowOpen);
  const setWindowOpen = useAgentStore((s) => s.setWindowOpen);
  const fabRef = useRef<HTMLButtonElement>(null);

  // 悬浮球入场：弹性放大 + 轻微呼吸脉动
  useGSAP(() => {
    if (!fabRef.current) return;
    gsap.fromTo(
      fabRef.current,
      { scale: 0, opacity: 0 },
      { scale: 1, opacity: 1, duration: 0.5, ease: 'back.out(2)' }
    );
    gsap.to(fabRef.current, {
      scale: 1.06,
      duration: 1.6,
      yoyo: true,
      repeat: -1,
      ease: 'sine.inOut',
      delay: 0.6,
    });
  }, { scope: fabRef });

  if (windowOpen) return null;
  return (
    <button
      ref={fabRef}
      onClick={() => setWindowOpen(true)}
      title="Moon 智能体"
      style={{
        position: 'fixed',
        right: 24,
        bottom: 24,
        width: 52,
        height: 52,
        borderRadius: '50%',
        border: 'none',
        background: 'var(--color-primary)',
        color: '#fff',
        fontSize: 22,
        cursor: 'pointer',
        boxShadow: '0 4px 16px rgba(204, 120, 92, 0.45)',
        zIndex: 90,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        transformOrigin: 'center',
      }}
    >
      ☾
    </button>
  );
}
