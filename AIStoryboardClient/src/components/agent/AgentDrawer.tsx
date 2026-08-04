import { useEffect } from 'react';
import { useAgentStore } from '../../stores/agentStore';
import { AgentConversationList } from './AgentConversationList';
import { AgentChatPanel } from './AgentChatPanel';
import { AgentAssetsPanel } from './AgentAssetsPanel';

export function AgentDrawer() {
  const windowOpen = useAgentStore((s) => s.windowOpen);
  const setWindowOpen = useAgentStore((s) => s.setWindowOpen);
  const loadConversations = useAgentStore((s) => s.loadConversations);

  // 打开时加载会话列表
  useEffect(() => {
    if (windowOpen) {
      loadConversations().catch(() => { /* 静默 */ });
    }
  }, [windowOpen, loadConversations]);

  // Esc 关闭
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setWindowOpen(false);
    };
    if (windowOpen) document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [windowOpen, setWindowOpen]);

  if (!windowOpen) return null;

  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 95 }}>
      {/* 遮罩 */}
      <div
        onClick={() => setWindowOpen(false)}
        style={{ position: 'absolute', inset: 0, background: 'rgba(20, 20, 19, 0.25)' }}
      />
      {/* 抽屉 */}
      <div
        style={{
          position: 'absolute',
          top: 0,
          right: 0,
          bottom: 0,
          width: 480,
          maxWidth: '92vw',
          background: 'var(--color-canvas)',
          borderLeft: '1px solid var(--color-hairline)',
          boxShadow: '-8px 0 24px rgba(20, 20, 19, 0.12)',
          display: 'flex',
          animation: 'agentSlideIn 0.2s ease-out',
        }}
      >
        <style>{`@keyframes agentSlideIn { from { transform: translateX(40px); opacity: 0.4; } to { transform: translateX(0); opacity: 1; } }`}</style>
        <AgentConversationList />
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          <AgentChatPanel />
          <AgentAssetsPanel />
        </div>
      </div>
    </div>
  );
}
