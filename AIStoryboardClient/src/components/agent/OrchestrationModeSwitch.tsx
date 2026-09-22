import { useRef } from 'react';
import { useAgentStore } from '../../stores/agentStore';

/**
 * Debug-only engine selector. A conversation is locked to one engine; selecting
 * a different engine for a non-empty conversation opens a new conversation.
 */
export function OrchestrationModeSwitch({ compact = false }: { compact?: boolean }) {
  const conversations = useAgentStore((s) => s.conversations);
  const activeConversationId = useAgentStore((s) => s.activeConversationId);
  const switchMode = useAgentStore((s) => s.switchOrchestrationMode);
  const busy = useAgentStore((s) => s.streaming || !!s.waitingHumanInput || !!s.waitingVideoPlan || !!s.agentVideoTask);
  const current = conversations.find((conversation) => conversation.id === activeConversationId);
  const mode = current?.orchestrationMode ?? 'LEGACY';
  const enabled = current?.debugComparisonEnabled !== false;
  const switchingRef = useRef(false);
  if (!enabled) return null;

  const switchOnce = async (value: 'LEGACY' | 'UNIFIED_LOOP') => {
    if (switchingRef.current || busy) return;
    switchingRef.current = true;
    try {
      await switchMode(value);
    } finally {
      switchingRef.current = false;
    }
  };

  const button = (value: 'LEGACY' | 'UNIFIED_LOOP', label: string) => (
    <button
      key={value}
      type="button"
      disabled={busy}
      onClick={() => void switchOnce(value)}
      title={busy ? '本轮或待确认任务结束后再切换' : value === mode ? `当前${label}` : `切换到${label}`}
      style={{
        border: 'none',
        borderRadius: 7,
        padding: compact ? '3px 7px' : '4px 9px',
        background: value === mode ? 'rgba(204,120,92,0.12)' : 'transparent',
        color: value === mode ? '#cc785c' : 'var(--color-muted)',
        fontSize: compact ? 11 : 12,
        fontWeight: value === mode ? 650 : 500,
        cursor: busy ? 'not-allowed' : 'pointer',
        opacity: busy ? 0.55 : 1,
      }}
    >
      {label}
    </button>
  );

  return (
    <div
      role="group"
      aria-label="编排引擎"
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 2,
        padding: 2,
        border: '1px solid var(--color-hairline)',
        borderRadius: 9,
        background: 'var(--color-canvas)',
        flexShrink: 0,
      }}
    >
      {button('LEGACY', '旧版')}
      {button('UNIFIED_LOOP', '新版')}
    </div>
  );
}
