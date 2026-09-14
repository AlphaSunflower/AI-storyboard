import { FileText, Frame, PenLine } from 'lucide-react';
import type { ChatAppKey } from './chatAppDefinitions';

export function ChatAppActions({
  onOpen,
  compact = false,
}: {
  onOpen: (key: ChatAppKey) => void;
  compact?: boolean;
}) {
  const items = [
    { key: 'canvas' as const, label: '画布', icon: <Frame size={15} strokeWidth={1.8} /> },
    { key: 'editor' as const, label: '编辑器', icon: <PenLine size={15} strokeWidth={1.8} /> },
    { key: 'docs' as const, label: '使用文档', icon: <FileText size={15} strokeWidth={1.8} /> },
  ];

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          onClick={() => onOpen(item.key)}
          title={item.label}
          style={appButtonStyle(compact)}
          onMouseEnter={(event) => {
            event.currentTarget.style.background = 'var(--color-surface-soft)';
            event.currentTarget.style.color = 'var(--color-primary)';
          }}
          onMouseLeave={(event) => {
            event.currentTarget.style.background = 'transparent';
            event.currentTarget.style.color = 'var(--color-muted)';
          }}
        >
          {item.icon}
          <span>{item.label}</span>
        </button>
      ))}
    </div>
  );
}

function appButtonStyle(compact: boolean): React.CSSProperties {
  return {
    border: 'none',
    background: 'transparent',
    color: 'var(--color-muted)',
    fontSize: compact ? 12 : 14,
    cursor: 'pointer',
    padding: compact ? '6px 7px' : '6px 9px',
    borderRadius: 8,
    display: 'flex',
    alignItems: 'center',
    gap: 4,
    whiteSpace: 'nowrap',
    flexShrink: 0,
  };
}
