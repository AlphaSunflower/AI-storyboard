import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, ChevronDown, ShieldAlert, ShieldCheck, Zap } from 'lucide-react';
import type { AgentApprovalMode } from '../../api/agent';
import { FullAccessConfirmDialog } from './FullAccessConfirmDialog';
import { useAbovePopoverPlacement } from './abovePopover';

const OPTIONS: Array<{
  id: AgentApprovalMode;
  label: string;
  description: string;
  icon: typeof ShieldCheck;
}> = [
  {
    id: 'REQUEST_APPROVAL',
    label: '逐项批准',
    description: '每次高风险操作都需要你明确允许',
    icon: ShieldAlert,
  },
  {
    id: 'FULL_ACCESS',
    label: '完全访问',
    description: '自动连续执行，不再暂停确认',
    icon: Zap,
  },
  {
    id: 'PLAN_APPROVAL',
    label: '方案批准',
    description: '先展示执行方案，批准后按方案生成',
    icon: ShieldCheck,
  },
];

export function ApprovalModeSelector({
  mode,
  disabled = false,
  onChange,
}: {
  mode: AgentApprovalMode;
  disabled?: boolean;
  onChange: (
    mode: AgentApprovalMode,
    options?: { confirmedFullAccess?: boolean },
  ) => void;
}) {
  const [open, setOpen] = useState(false);
  const [confirmFullAccess, setConfirmFullAccess] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  // 浮层挂到 body：停靠态浮窗 overflow: hidden 会把卡片内的上弹浮层裁掉
  const placement = useAbovePopoverPlacement(open, buttonRef, { gap: 10, width: 286 });
  const menuWidth = placement?.width ?? 286;
  const current = OPTIONS.find((option) => option.id === mode) ?? OPTIONS[2];
  const CurrentIcon = current.icon;
  const fullAccessActive = current.id === 'FULL_ACCESS';

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      const target = event.target as Node;
      if (rootRef.current?.contains(target)) return;
      if (menuRef.current?.contains(target)) return;
      setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  useEffect(() => {
    if (disabled) {
      setOpen(false);
      setConfirmFullAccess(false);
    }
  }, [disabled]);

  const choose = (next: AgentApprovalMode) => {
    setOpen(false);
    if (next === mode) return;
    if (next === 'FULL_ACCESS') {
      setConfirmFullAccess(true);
      return;
    }
    onChange(next);
  };

  return (
    <>
      <div ref={rootRef} style={{ position: 'relative', minWidth: 0 }}>
        <button
          ref={buttonRef}
          type="button"
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label={`审批模式：${current.label}`}
          disabled={disabled}
          onClick={() => setOpen((value) => !value)}
          style={{
            height: 34,
            maxWidth: 154,
            padding: '0 9px',
            border: '1px solid rgba(0,0,0,0.09)',
            borderRadius: 8,
            background: open
              ? 'color-mix(in srgb, var(--color-primary) 7%, transparent)'
              : 'transparent',
            color: disabled
              ? 'var(--color-muted-soft)'
              : 'var(--color-muted)',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            cursor: disabled ? 'not-allowed' : 'pointer',
            opacity: disabled ? 0.5 : 1,
            minWidth: 0,
          }}
        >
          <CurrentIcon
            size={15}
            strokeWidth={2}
            style={{ flexShrink: 0, color: fullAccessActive ? '#B45309' : undefined }}
          />
          <span
            style={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            {current.label}
          </span>
          <ChevronDown size={14} strokeWidth={2} style={{ flexShrink: 0 }} />
        </button>

        {open && placement && createPortal(
          <div
            ref={menuRef}
            role="menu"
            aria-label="选择审批模式"
            style={{
              position: 'fixed',
              left: placement.left,
              bottom: placement.bottom,
              width: menuWidth,
              maxWidth: 'min(286px, calc(100vw - 32px))',
              maxHeight: placement.maxHeight,
              overflowY: 'auto',
              padding: 6,
              borderRadius: 14,
              border: '1px solid rgba(0,0,0,0.08)',
              background: 'white',
              boxShadow: '0 12px 36px rgba(15,17,21,0.16)',
              zIndex: 1200,
            }}
          >
            {OPTIONS.map((option) => {
              const Icon = option.icon;
              const selected = option.id === mode;
              return (
                <button
                  key={option.id}
                type="button"
                role="menuitemradio"
                aria-checked={selected}
                className={`approval-mode-option${option.id === 'FULL_ACCESS' ? ' is-full-access' : ''}${selected ? ' is-selected' : ''}`}
                  onClick={() => choose(option.id)}
                  style={{
                    width: '100%',
                    border: 'none',
                    borderRadius: 10,
                    padding: '9px 10px',
                    display: 'grid',
                    gridTemplateColumns: '22px 1fr 18px',
                    alignItems: 'center',
                    gap: 8,
                    textAlign: 'left',
                    cursor: 'pointer',
                  }}
                >
                  <Icon
                    size={17}
                    strokeWidth={2}
                    style={{
                      color: option.id === 'FULL_ACCESS'
                        ? 'rgb(234,88,12)'
                        : selected ? 'var(--color-primary)' : 'var(--color-muted)',
                    }}
                  />
                  <span style={{ minWidth: 0 }}>
                    <span
                      style={{
                        display: 'block',
                        color: option.id === 'FULL_ACCESS' ? 'rgb(194,65,12)' : 'var(--color-ink)',
                        fontSize: 13,
                        fontWeight: 600,
                        lineHeight: 1.35,
                      }}
                    >
                      {option.label}
                    </span>
                    <span
                      style={{
                        display: 'block',
                        marginTop: 2,
                        color: option.id === 'FULL_ACCESS'
                          ? 'rgb(194,88,46)'
                          : 'var(--color-muted-soft)',
                        fontSize: 11,
                        lineHeight: 1.35,
                      }}
                    >
                      {option.description}
                    </span>
                  </span>
                  {selected ? (
                    <Check
                      size={16}
                      strokeWidth={2.4}
                      style={{
                        color: option.id === 'FULL_ACCESS'
                          ? 'rgb(234,88,12)'
                          : 'var(--color-primary)',
                      }}
                    />
                  ) : <span />}
                </button>
              );
            })}
          </div>,
          document.body,
        )}
      </div>
      <FullAccessConfirmDialog
        open={confirmFullAccess}
        onCancel={() => setConfirmFullAccess(false)}
        onConfirm={() => {
          setConfirmFullAccess(false);
          onChange('FULL_ACCESS', { confirmedFullAccess: true });
        }}
      />
    </>
  );
}
