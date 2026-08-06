import { useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../../stores/agentStore';
import { AgentConversationList } from './AgentConversationList';
import { AgentChatPanel } from './AgentChatPanel';

export function AgentDrawer() {
  const windowOpen = useAgentStore((s) => s.windowOpen);
  const setWindowOpen = useAgentStore((s) => s.setWindowOpen);
  const loadConversations = useAgentStore((s) => s.loadConversations);

  // 延迟卸载：mounted 控制 DOM 是否存在；closing 表示正在播放退场动画
  const [mounted, setMounted] = useState(false);
  const [closing, setClosing] = useState(false);
  const drawerRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  // 打开时挂载 + 加载会话列表
  useEffect(() => {
    if (windowOpen) {
      setMounted(true);
      setClosing(false);
      loadConversations().catch(() => { /* 静默 */ });
    }
  }, [windowOpen, loadConversations]);

  // 关闭请求：标记 closing，由退场 useGSAP 播放动画后卸载
  useEffect(() => {
    if (!windowOpen && mounted && !closing) {
      setClosing(true);
    }
  }, [windowOpen, mounted, closing]);

  // 入场动画（仅在挂载时播放一次；closing 变化不会触发 revert）
  useGSAP(() => {
    if (mounted && !closing) {
      gsap.fromTo(overlayRef.current, { opacity: 0 }, { opacity: 1, duration: 0.2, ease: 'power2.out' });
      gsap.fromTo(
        drawerRef.current,
        { x: 80, opacity: 0 },
        {
          x: 0, opacity: 1, duration: 0.34, ease: 'back.out(1.2)',
          onComplete: () => {
            // 清除残留 transform：drawer 作为 containing block 会让内部 fixed 灯箱（ImagePreviewModal）错位
            gsap.set(drawerRef.current, { clearProps: 'transform' });
          },
        }
      );
    }
  }, { dependencies: [mounted], scope: rootRef, revertOnUpdate: true });

  // 退场动画（closing 触发一次；播放完后卸载）
  useGSAP(() => {
    if (closing && mounted) {
      gsap.to(overlayRef.current, { opacity: 0, duration: 0.22, ease: 'power2.in' });
      gsap.to(drawerRef.current, {
        x: 80,
        opacity: 0,
        duration: 0.26,
        ease: 'power2.in',
        onComplete: () => {
          setMounted(false);
          setClosing(false);
        },
      });
    }
  }, { dependencies: [closing], scope: rootRef, revertOnUpdate: true });

  // Esc 关闭
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setWindowOpen(false);
    };
    if (windowOpen) document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [windowOpen, setWindowOpen]);

  if (!mounted) return null;

  return (
    <div ref={rootRef} style={{ position: 'fixed', inset: 0, zIndex: 95 }}>
      {/* 遮罩 */}
      <div
        ref={overlayRef}
        onClick={() => setWindowOpen(false)}
        style={{ position: 'absolute', inset: 0, background: 'rgba(20, 20, 19, 0.25)' }}
      />
      {/* 抽屉 */}
      <div
        ref={drawerRef}
        style={{
          position: 'absolute',
          top: 0,
          right: 0,
          bottom: 0,
          width: '50vw',
          minWidth: 420,
          maxWidth: '100vw',
          background: 'var(--color-canvas)',
          borderLeft: '1px solid var(--color-hairline)',
          boxShadow: '-8px 0 24px rgba(20, 20, 19, 0.12)',
          display: 'flex',
        }}
      >
        <AgentConversationList />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          <AgentChatPanel />
        </div>
      </div>
    </div>
  );
}
