import { useAgentStore } from '../../stores/agentStore';

export function AgentFab() {
  const windowOpen = useAgentStore((s) => s.windowOpen);
  const setWindowOpen = useAgentStore((s) => s.setWindowOpen);
  if (windowOpen) return null;
  return (
    <button
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
        transition: 'transform 0.15s',
      }}
      onMouseEnter={(e) => ((e.currentTarget as HTMLElement).style.transform = 'scale(1.06)')}
      onMouseLeave={(e) => ((e.currentTarget as HTMLElement).style.transform = 'scale(1)')}
    >
      ☾
    </button>
  );
}
