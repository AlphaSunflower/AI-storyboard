import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../../stores/agentStore';
import { AgentConversationList } from './AgentConversationList';
import { AgentChatPanel } from './AgentChatPanel';
import { Moon, X, Maximize } from 'lucide-react';

/**
 * /editor 的 Moon 智能体窗口：固定宽度圆角浮动卡片（宽度与旧 62vw 抽屉一致）。
 * 内容为原生聊天组件（AgentConversationList + AgentChatPanel），与编辑器共享同一套 Zustand store，
 * 因此 agent 生成的分镜/图/视频能实时回写编辑器、互斥判定（sceneCount 对比）也生效——不再用 iframe（iframe 是独立 JS 世界，store 隔离）。
 */
export function MoonAgentWindow() {
  const navigate = useNavigate();
  const windowOpen = useAgentStore((s) => s.windowOpen);
  const setWindowOpen = useAgentStore((s) => s.setWindowOpen);
  const loadConversations = useAgentStore((s) => s.loadConversations);

  // 延迟卸载：mounted 控制 DOM 是否存在；closing 表示正在播放退场动画
  const [mounted, setMounted] = useState(false);
  const [closing, setClosing] = useState(false);
  const cardRef = useRef<HTMLDivElement>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  // 会话栏宽度（可拖拽调整）
  const [convWidth, setConvWidth] = useState(200);
  const handleConvDrag = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = convWidth;
    const onMouseMove = (ev: MouseEvent) => setConvWidth(Math.min(360, Math.max(180, startW + ev.clientX - startX)));
    const onMouseUp = () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
  }, [convWidth]);

  useEffect(() => {
    if (windowOpen) { setMounted(true); setClosing(false); loadConversations().catch(() => { /* 静默 */ }); }
  }, [windowOpen, loadConversations]);
  useEffect(() => {
    if (!windowOpen && mounted && !closing) setClosing(true);
  }, [windowOpen, mounted, closing]);

  // 入场
  useGSAP(() => {
    if (mounted && !closing) {
      gsap.fromTo(cardRef.current, { opacity: 0, y: 12 }, { opacity: 1, y: 0, duration: 0.28, ease: 'back.out(1.2)', onComplete: () => gsap.set(cardRef.current, { clearProps: 'transform' }) });
    }
  }, { dependencies: [mounted], scope: rootRef, revertOnUpdate: true });
  // 退场
  useGSAP(() => {
    if (closing && mounted) {
      gsap.to(cardRef.current, { opacity: 0, y: 12, duration: 0.22, ease: 'power2.in', onComplete: () => { setMounted(false); setClosing(false); } });
    }
  }, { dependencies: [closing], scope: rootRef, revertOnUpdate: true });

  // Esc 关闭
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') setWindowOpen(false); };
    if (windowOpen) document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [windowOpen, setWindowOpen]);

  if (!mounted) return null;

  return (
    <div ref={rootRef} style={{ position: 'fixed', inset: 0, zIndex: 95, pointerEvents: 'none' }}>
      {/* 浮动卡片：宽度与旧 62vw 抽屉保持一致 */}
      <div
        ref={cardRef}
        style={{
          position: 'absolute', top: 10, right: 12, bottom: 10,
          width: '62vw', minWidth: 480, maxWidth: 'calc(100vw - 24px)',
          background: 'var(--color-canvas)', borderRadius: 16, overflow: 'hidden',
          boxShadow: '0 16px 48px rgba(20, 20, 19, 0.18)', border: '1px solid var(--color-hairline)',
          display: 'flex', flexDirection: 'column', pointerEvents: 'auto',
        }}
      >
        {/* 窗口顶栏：标题 + 关闭 */}
        <div
          style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '8px 10px 8px 12px', borderBottom: '1px solid var(--color-hairline)',
            flexShrink: 0, background: 'var(--color-surface-soft)',
          }}
        >
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, fontWeight: 600, color: 'var(--color-ink)' }}>
            <button
              onClick={() => { setWindowOpen(false); navigate('/chat'); }}
              title="全屏对话"
              style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'rgb(65, 118, 230)', padding: '2px 4px', borderRadius: 6, display: 'inline-flex', alignItems: 'center' }}
              onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(65, 118, 230, 0.08)'; }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
            ><Maximize size={14} strokeWidth={1.8} /></button>
            <Moon size={13} strokeWidth={1.8} /> Moon 智能体
          </span>
          <button
            onClick={() => setWindowOpen(false)}
            title="关闭"
            style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--color-muted)', padding: '4px 6px', borderRadius: 8, display: 'flex', alignItems: 'center' }}
          ><X size={15} strokeWidth={2} /></button>
        </div>

        {/* 主体：会话栏 + 聊天 */}
        <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
          <div style={{ width: convWidth, flexShrink: 0, display: 'flex', flexDirection: 'column', background: 'var(--color-surface-soft)' }}>
            <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
              <AgentConversationList width={convWidth} hideHeader />
            </div>
          </div>
          <div
            onMouseDown={handleConvDrag}
            style={{ width: 4, cursor: 'col-resize', background: 'transparent', transition: 'background 0.15s', flexShrink: 0 }}
            onMouseEnter={(e) => { (e.target as HTMLElement).style.background = 'var(--color-primary)'; }}
            onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
          />
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
            <AgentChatPanel />
          </div>
        </div>
      </div>
    </div>
  );
}
