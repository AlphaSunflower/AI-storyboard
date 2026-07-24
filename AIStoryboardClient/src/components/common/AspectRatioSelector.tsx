const ratios = ['16:9', '9:16', '2.35:1', '4:3', '1:1'];

export function AspectRatioSelector({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
      {ratios.map((r) => {
        const isSelected = r === value;
        return (
          <button
            key={r}
            type="button"
            onClick={() => onChange(r)}
            style={{
              padding: '3px 10px',
              borderRadius: 'var(--rounded-sm)',
              fontSize: 12,
              font: 'var(--text-caption-upper)',
              border: `1px solid ${isSelected ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
              background: isSelected ? 'var(--color-surface-soft)' : 'white',
              color: isSelected ? 'var(--color-primary)' : 'var(--color-muted)',
              cursor: 'pointer',
              transition: 'border-color 0.15s, color 0.15s',
            }}
          >
            {r}
          </button>
        );
      })}
    </div>
  );
}
