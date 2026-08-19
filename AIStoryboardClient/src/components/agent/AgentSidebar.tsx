import { useAgentStore } from '../../stores/agentStore';

const NAV_ITEMS = [
  { icon: '➕', label: '新对话', action: 'new' as const },
  { icon: '🎬', label: '分镜', action: 'storyboard' as const },
  { icon: '🖼️', label: '资产', action: 'assets' as const },
  { icon: '📁', label: '项目', action: 'project' as const },
  { icon: '💬', label: '历史', action: 'history' as const },
];

export function AgentSidebar() {
  const createConversation = useAgentStore((s) => s.createConversation);
  const setActiveModal = useAgentStore((s) => s.setActiveModal);
  const historyExpanded = useAgentStore((s) => s.historyExpanded);
  const setHistoryExpanded = useAgentStore((s) => s.setHistoryExpanded);
  const conversations = useAgentStore((s) => s.conversations);
  const activeConversationId = useAgentStore((s) => s.activeConversationId);
  const selectConversation = useAgentStore((s) => s.selectConversation);

  const handleClick = (action: string) => {
    switch (action) {
      case 'new':
        createConversation();
        break;
      case 'history':
        setHistoryExpanded(!historyExpanded);
        break;
      default:
        setActiveModal(action as 'storyboard' | 'assets' | 'project');
    }
  };

  const recentConversations = conversations.slice(0, 20);

  return (
    <aside
      className="w-60 shrink-0 flex flex-col h-full border-r"
      style={{
        backgroundColor: 'var(--color-surface-card)',
        borderColor: 'var(--color-hairline)',
      }}
    >
      {/* Logo */}
      <div className="px-4 py-4 border-b" style={{ borderColor: 'var(--color-hairline)' }}>
        <span className="text-sm font-semibold" style={{ color: 'var(--color-ink)', fontFamily: 'var(--font-body)' }}>
          AI Storyboard
        </span>
      </div>

      {/* Nav items */}
      <nav className="flex-1 overflow-y-auto px-2 py-2">
        {NAV_ITEMS.map((item, i) => (
          <div key={item.action}>
            {i === 4 && <div className="border-t my-1" style={{ borderColor: 'var(--color-hairline)' }} />}
            <button
              onClick={() => handleClick(item.action)}
              className="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left hover:bg-black/5 transition-colors"
              style={{ color: 'var(--color-ink)', fontFamily: 'var(--font-body)' }}
            >
              <span>{item.icon}</span>
              <span>{item.label}</span>
            </button>
            {item.action === 'history' && historyExpanded && (
              <div className="ml-3 mt-1 space-y-0.5">
                {recentConversations.map((c) => (
                  <button
                    key={c.id}
                    onClick={() => selectConversation(c.id)}
                    className={`w-full text-left px-2 py-1.5 rounded-md text-xs truncate hover:bg-black/5 transition-colors ${
                      c.id === activeConversationId ? 'bg-black/10 font-medium' : ''
                    }`}
                    style={{ color: 'var(--color-ink)', fontFamily: 'var(--font-body)' }}
                  >
                    {c.title || '新对话'}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
      </nav>

      {/* Bottom settings */}
      <div className="px-2 py-2 border-t" style={{ borderColor: 'var(--color-hairline)' }}>
        <button
          onClick={() => setActiveModal('settings')}
          className="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left hover:bg-black/5 transition-colors"
          style={{ color: 'var(--color-ink)', fontFamily: 'var(--font-body)' }}
        >
          <span>⚙️</span>
          <span>设置</span>
        </button>
      </div>
    </aside>
  );
}
