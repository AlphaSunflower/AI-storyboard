import { FileText, Frame, PenLine } from 'lucide-react';
import type { ChatAppKey } from './chatAppDefinitions';

export type CanvasModePhase = 'natural' | 'binding' | 'locked' | 'exit_pending';

export function ChatAppActions({
  onOpen,
  canvasPhase = 'natural',
  onExitCanvas,
  onCancelCanvas,
  compact = false,
}: {
  onOpen: (key: ChatAppKey) => void;
  canvasPhase?: CanvasModePhase;
  onExitCanvas?: () => void;
  onCancelCanvas?: () => void;
  compact?: boolean;
}) {
  const canvasAction = canvasPhase === 'binding'
    ? { label: '取消进入画布', disabled: false, onClick: onCancelCanvas }
    : canvasPhase === 'exit_pending'
      ? { label: '本轮结束后离开', disabled: true, onClick: undefined }
      : canvasPhase === 'locked'
        ? { label: '离开画布编辑模式', disabled: false, onClick: onExitCanvas }
        : { label: '画布', disabled: false, onClick: undefined };
  const items = [
    {
      key: 'canvas' as const,
      label: canvasAction.label,
      icon: <Frame size={15} strokeWidth={1.8} />,
      disabled: canvasAction.disabled,
      onClick: canvasAction.onClick,
    },
    { key: 'editor' as const, label: '编辑器', icon: <PenLine size={15} strokeWidth={1.8} />, disabled: false, onClick: undefined },
    { key: 'docs' as const, label: '使用文档', icon: <FileText size={15} strokeWidth={1.8} />, disabled: false, onClick: undefined },
  ];

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          disabled={item.disabled}
          onClick={() => item.onClick ? item.onClick() : onOpen(item.key)}
          title={item.label}
          style={appButtonStyle(compact, item.disabled)}
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

function appButtonStyle(compact: boolean, disabled = false): React.CSSProperties {
  return {
    border: 'none',
    background: 'transparent',
    color: 'var(--color-muted)',
    fontSize: compact ? 12 : 14,
    cursor: disabled ? 'not-allowed' : 'pointer',
    opacity: disabled ? 0.55 : 1,
    padding: compact ? '6px 7px' : '6px 9px',
    borderRadius: 8,
    display: 'flex',
    alignItems: 'center',
    gap: 4,
    whiteSpace: 'nowrap',
    flexShrink: 0,
  };
}
