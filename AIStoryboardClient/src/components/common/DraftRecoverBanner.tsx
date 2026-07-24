export function DraftRecoverBanner({
  projectName,
  onRecover,
  onDismiss,
}: {
  projectName: string;
  onRecover: () => void;
  onDismiss: () => void;
}) {
  return (
    <div
      style={{
        padding: '10px var(--space-lg)',
        background: 'var(--color-surface-card)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        borderBottom: '1px solid var(--color-hairline)',
        flexShrink: 0,
      }}
    >
      <span style={{ font: 'var(--text-body-sm)', color: 'var(--color-body-strong)' }}>
        检测到未保存的草稿：<strong>{projectName}</strong>
      </span>
      <div style={{ display: 'flex', gap: 8 }}>
        <button
          onClick={onRecover}
          style={{
            padding: '4px 16px',
            height: 30,
            borderRadius: 'var(--rounded-md)',
            fontSize: 12,
            fontWeight: 500,
            background: 'var(--color-primary)',
            color: 'var(--color-on-primary)',
            border: 'none',
            cursor: 'pointer',
          }}
        >
          恢复
        </button>
        <button
          onClick={onDismiss}
          style={{
            padding: '4px 16px',
            height: 30,
            borderRadius: 'var(--rounded-md)',
            fontSize: 12,
            background: 'transparent',
            color: 'var(--color-muted)',
            border: '1px solid var(--color-hairline)',
            cursor: 'pointer',
          }}
        >
          忽略
        </button>
      </div>
    </div>
  );
}
