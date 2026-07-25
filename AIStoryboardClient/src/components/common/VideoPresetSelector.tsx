import { VIDEO_PRESETS } from '../../config';

interface VideoPresetSelectorProps {
  value: string;
  onChange: (value: string) => void;
}

export function VideoPresetSelector({ value, onChange }: VideoPresetSelectorProps) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      style={{
        width: '100%',
        fontSize: 12,
        padding: '8px 10px',
        borderRadius: 'var(--rounded-sm)',
        border: '1px solid var(--color-hairline)',
        background: 'white',
        color: 'var(--color-ink)',
        boxSizing: 'border-box' as React.CSSProperties['boxSizing'],
        fontFamily: 'inherit',
        outline: 'none',
        cursor: 'pointer',
        appearance: 'auto' as React.CSSProperties['appearance'],
        paddingRight: 28,
      }}
    >
      {VIDEO_PRESETS.map((p) => (
        <option key={p.value} value={p.value}>
          {p.label}
        </option>
      ))}
    </select>
  );
}
