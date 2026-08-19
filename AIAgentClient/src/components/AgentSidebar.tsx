import { useAgentStore } from '../stores/agentStore';

export function AgentSidebar() {
  const {
    activeModal, setActiveModal,
    historyExpanded, setHistoryExpanded,
    conversations, activeConversationId, selectConversation,
    createConversation, deleteConversation,
    waitingHumanInput, streaming,
    projectId,
  } = useAgentStore();

  const activeConvs = conversations.filter((c) => c.status !== 'archived');

  return (
    <div
      className="flex flex-col"
      style={{
        width: 220,
        minWidth: 220,
        borderRight: '1px solid var(--color-hairline)',
        background: 'var(--color-surface-soft)',
      }}
    >
      {/* Logo */}
      <div className="px-4 py-3 font-semibold text-sm" style={{ color: 'var(--color-ink)' }}>
        ☾ AI Agent
      </div>

      {/* 新对话 */}
      <button
        onClick={() => createConversation()}
        disabled={!projectId}
        className="mx-3 mb-2 px-3 py-2 text-sm text-left rounded-md transition-colors"
        style={{
          background: 'var(--color-primary)',
          color: 'var(--color-on-primary)',
          opacity: projectId ? 1 : 0.5,
        }}
      >
        ➕ 新对话
      </button>

      {/* 分镜（外链） */}
      <button
        onClick={() => window.open('/storyboard', '_blank')}
        className="mx-3 mb-1 px-3 py-2 text-sm text-left rounded-md transition-colors hover:opacity-80"
        style={{ background: 'transparent', color: 'var(--color-body)' }}
      >
        🎬 分镜
      </button>

      {/* 资产 */}
      <button
        onClick={() => setActiveModal(activeModal === 'assets' ? null : 'assets')}
        className="mx-3 mb-1 px-3 py-2 text-sm text-left rounded-md transition-colors hover:opacity-80"
        style={{
          background: activeModal === 'assets' ? 'var(--color-surface-card)' : 'transparent',
          color: 'var(--color-body)',
        }}
      >
        🖼️ 资产
      </button>

      {/* 项目 */}
      <button
        onClick={() => setActiveModal(activeModal === 'project' ? null : 'project')}
        className="mx-3 mb-1 px-3 py-2 text-sm text-left rounded-md transition-colors hover:opacity-80"
        style={{
          background: activeModal === 'project' ? 'var(--color-surface-card)' : 'transparent',
          color: 'var(--color-body)',
        }}
      >
        📁 项目
      </button>

      {/* 分隔线 */}
      <div className="mx-3 my-2" style={{ borderTop: '1px solid var(--color-hairline)' }} />

      {/* 历史（折叠展开） */}
      <button
        onClick={() => setHistoryExpanded(!historyExpanded)}
        className="mx-3 mb-1 px-3 py-2 text-sm text-left rounded-md transition-colors hover:opacity-80"
        style={{ background: 'transparent', color: 'var(--color-body)' }}
      >
        💬 历史 {historyExpanded ? '▾' : '▸'}
      </button>

      {historyExpanded && (
        <div className="flex-1 overflow-y-auto mx-3 mb-1">
          {activeConvs.length === 0 && (
            <p className="px-2 py-1 text-xs" style={{ color: 'var(--color-muted)' }}>暂无对话</p>
          )}
          {activeConvs.map((c) => (
            <div
              key={c.id}
              onClick={() => {
                if (waitingHumanInput || streaming) return;
                selectConversation(c.id);
              }}
              className="px-2 py-2 rounded-md cursor-pointer mb-1 group"
              style={{
                background: c.id === activeConversationId ? 'var(--color-surface-card)' : 'transparent',
                opacity: waitingHumanInput && c.id !== activeConversationId ? 0.5 : 1,
              }}
            >
              <div
                className="text-xs truncate"
                style={{
                  color: 'var(--color-ink)',
                  fontWeight: c.id === activeConversationId ? 600 : 400,
                }}
              >
                {c.title}
              </div>
              <button
                onClick={(e) => { e.stopPropagation(); deleteConversation(c.id); }}
                className="text-xs opacity-0 group-hover:opacity-100 transition-opacity mt-1"
                style={{ color: 'var(--color-muted)', background: 'none', border: 'none', cursor: 'pointer' }}
              >
                🗑️
              </button>
            </div>
          ))}
        </div>
      )}

      {/* 底部设置 */}
      <div className="mt-auto">
        <button
          onClick={() => setActiveModal(activeModal === 'settings' ? null : 'settings')}
          className="mx-3 mb-3 px-3 py-2 text-sm text-left rounded-md transition-colors hover:opacity-80 w-[calc(100%-24px)]"
          style={{
            background: activeModal === 'settings' ? 'var(--color-surface-card)' : 'transparent',
            color: 'var(--color-body)',
          }}
        >
          ⚙️ 设置
        </button>
      </div>
    </div>
  );
}
