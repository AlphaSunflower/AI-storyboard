import { useAgentStore, type HumanInputInfo } from '../../stores/agentStore';

export function HumanInputCard({ info }: { info: HumanInputInfo }) {
  const submitHumanInput = useAgentStore((s) => s.submitHumanInput);
  const streaming = useAgentStore((s) => s.streaming);
  const expired = info.expirationTime > 0 && Date.now() / 1000 > info.expirationTime;

  return (
    <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 10 }}>
      <div style={{ maxWidth: '82%', padding: 12, borderRadius: 12, background: 'white', border: '1px solid var(--color-hairline)', boxShadow: '0 2px 8px rgba(20,20,19,0.06)' }}>
        <div style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 6, letterSpacing: 1 }}>需要您确认</div>
        <div style={{ fontSize: 13, color: 'var(--color-ink)', lineHeight: 1.6, marginBottom: 10, whiteSpace: 'pre-wrap' }}>
          {info.formContent || '请确认是否继续？'}
        </div>
        {expired ? (
          <div style={{ fontSize: 12, color: 'var(--color-warning)' }}>确认已过期，请重新发起对话</div>
        ) : (
          <div style={{ display: 'flex', gap: 8 }}>
            {info.actions.map((a) => (
              <button
                key={a.id}
                disabled={streaming}
                onClick={() => submitHumanInput(a.id)}
                style={{
                  padding: '6px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-primary)', color: 'white', fontSize: 13,
                  cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
                }}
              >
                {a.title}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
