import { useAgentStore } from '../../stores/agentStore';
import type { SuggestedAction } from '../../api/agent';

/** 建议动作按钮：点击后以 routingHint 重新发送给智能体。 */
export function SuggestedActions({ actions }: { actions?: SuggestedAction[] }) {
  const sendMessage = useAgentStore((s) => s.sendMessage);
  const activeConversationId = useAgentStore((s) => s.activeConversationId);
  const readOnly = useAgentStore((s) => {
    const conversation = s.conversations.find((item) => item.id === s.activeConversationId);
    return conversation?.status === 'archived' || conversation?.orchestrationMode === 'LEGACY';
  });
  if (readOnly || !activeConversationId) return null;
  if (!actions || actions.length === 0) return null;

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 10, maxWidth: '100%' }}>
      {actions.map((a) => (
        <button
          key={a.id}
          onClick={() => void sendMessage(a.title, { routingHint: a.routingHint })}
          disabled={readOnly}
          style={{
            padding: '7px 14px', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-pill)',
            background: 'var(--color-surface-card)', color: 'var(--color-body-strong)',
            fontSize: 13, cursor: 'pointer', whiteSpace: 'nowrap',
          }}
        >
          {a.title}
        </button>
      ))}
    </div>
  );
}
