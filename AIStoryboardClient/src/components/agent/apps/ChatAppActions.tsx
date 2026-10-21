import { Frame, Library, PenLine } from 'lucide-react';
import { Tooltip, TooltipGroup } from '../../common/Tooltip';
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
      shortcut: canvasPhase === 'natural' ? 'Ctrl/Cmd+Shift+8' : undefined,
      keyshortcuts: canvasPhase === 'natural' ? 'Control+Shift+8 Meta+Shift+8' : undefined,
    },
    { key: 'editor' as const, label: '编辑器', icon: <PenLine size={15} strokeWidth={1.8} />, disabled: false, onClick: undefined, shortcut: 'Ctrl/Cmd+Shift+7', keyshortcuts: 'Control+Shift+7 Meta+Shift+7' },
    { key: 'library' as const, label: '项目库', icon: <Library size={15} strokeWidth={1.8} />, disabled: false, onClick: undefined, shortcut: 'Ctrl/Cmd+Shift+9', keyshortcuts: 'Control+Shift+9 Meta+Shift+9' },
  ];

  return (
    <TooltipGroup>
      <div
        className="chat-app-actions"
        style={{
          display: 'flex', alignItems: 'center', gap: 2,
          padding: 2, border: '1px solid var(--color-hairline)',
          borderRadius: 8, background: 'var(--color-panel)', boxShadow: 'var(--shadow-xs)',
        }}
      >
        {items.map((item) => (
          <Tooltip key={item.key} label={item.label} shortcut={item.shortcut}>
            <button
              type="button"
              disabled={item.disabled}
              aria-keyshortcuts={item.keyshortcuts}
              onClick={() => item.onClick ? item.onClick() : onOpen(item.key)}
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
          </Tooltip>
        ))}
      </div>
    </TooltipGroup>
  );
}

function appButtonStyle(compact: boolean, disabled = false): React.CSSProperties {
  return {
    border: 'none',
    background: 'transparent',
    color: 'var(--color-muted)',
    fontSize: compact ? 11.5 : 12.5,
    cursor: disabled ? 'not-allowed' : 'pointer',
    opacity: disabled ? 0.55 : 1,
    height: 26,
    padding: compact ? '0 7px' : '0 9px',
    borderRadius: 6,
    display: 'flex',
    alignItems: 'center',
    gap: 4,
    whiteSpace: 'nowrap',
    flexShrink: 0,
  };
}
