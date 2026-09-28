import { useEffect, useRef, useState } from 'react';
import { Check, ChevronDown, ShieldAlert, ShieldCheck, Zap } from 'lucide-react';
import type { AgentApprovalMode } from '../../api/agent';
import { FullAccessConfirmDialog } from './FullAccessConfirmDialog';

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
  const current = OPTIONS.find((option) => option.id === mode) ?? OPTIONS[2];
  const CurrentIcon = current.icon;
  const fullAccessActive = current.id === 'FULL_ACCESS';

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
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
          type="button"
          aria-haspopup="menu"
          aria-expanded={open}
          aria-label={`审批模式：${current.label}`}
          disabled={disabled}
          onClick={() => setOpen((value) => !value)}
          style={{
            height: 36,
            maxWidth: 154,
            padding: '0 9px',
            border: fullAccessActive
              ? '1px solid rgba(234,88,12,0.26)'
              : '1px solid rgba(0,0,0,0.08)',
            borderRadius: 10,
            background: open
              ? 'rgba(65,118,230,0.08)'
              : fullAccessActive ? 'rgba(249,115,22,0.10)' : 'transparent',
            color: disabled
              ? 'rgb(152,158,168)'
              : fullAccessActive ? 'rgb(194,65,12)' : 'rgb(96,100,108)',
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            cursor: disabled ? 'not-allowed' : 'pointer',
            opacity: disabled ? 0.5 : 1,
            minWidth: 0,
          }}
        >
          <CurrentIcon size={15} strokeWidth={2} style={{ flexShrink: 0 }} />
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

        {open && (
          <div
            role="menu"
            aria-label="选择审批模式"
            style={{
              position: 'absolute',
              left: 0,
              bottom: 'calc(100% + 10px)',
              width: 286,
              maxWidth: 'min(286px, calc(100vw - 32px))',
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
                        : selected ? 'rgb(65,118,230)' : 'rgb(96,100,108)',
                    }}
                  />
                  <span style={{ minWidth: 0 }}>
                    <span
                      style={{
                        display: 'block',
                        color: option.id === 'FULL_ACCESS' ? 'rgb(194,65,12)' : 'rgb(15,17,21)',
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
                          : 'rgb(152,158,168)',
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
                          : 'rgb(65,118,230)',
                      }}
                    />
                  ) : <span />}
                </button>
              );
            })}
          </div>
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
