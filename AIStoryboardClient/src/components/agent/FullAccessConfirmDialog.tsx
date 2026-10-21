import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { ShieldAlert, Zap } from 'lucide-react';

export function FullAccessConfirmDialog({
  open,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel();
    };
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    cancelRef.current?.focus();
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [open, onCancel]);

  if (!open) return null;

  return createPortal(
    <div
      onPointerDown={(event) => {
        if (event.target === event.currentTarget) onCancel();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 5000,
        padding: 20,
        display: 'grid',
        placeItems: 'center',
        background: 'rgba(15,17,21,0.46)',
        backdropFilter: 'blur(5px)',
      }}
    >
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="full-access-title"
        aria-describedby="full-access-description"
        onPointerDown={(event) => event.stopPropagation()}
        style={{
          width: '100%',
          maxWidth: 430,
          overflow: 'hidden',
          borderRadius: 16,
          border: '1px solid rgba(15,17,21,0.08)',
          background: 'var(--color-panel)',
          boxShadow: '0 24px 64px rgba(15,17,21,0.24)',
        }}
      >
        <div style={{ padding: '20px 22px 18px', display: 'flex', gap: 14 }}>
          <span
            aria-hidden="true"
            style={{
              width: 42,
              height: 42,
              borderRadius: 12,
              display: 'grid',
              placeItems: 'center',
              flexShrink: 0,
              color: 'rgb(234,88,12)',
              background: 'rgba(249,115,22,0.12)',
              border: '1px solid rgba(249,115,22,0.20)',
            }}
          >
            <ShieldAlert size={21} strokeWidth={2} />
          </span>
          <div style={{ minWidth: 0 }}>
            <h2
              id="full-access-title"
              style={{
                margin: 0,
                color: 'var(--color-ink)',
                fontSize: 17,
                lineHeight: 1.35,
                fontWeight: 650,
                letterSpacing: 0,
              }}
            >
              开启完全访问？
            </h2>
            <p
              id="full-access-description"
              style={{
                margin: '7px 0 0',
                color: 'var(--color-muted)',
                fontSize: 13,
                lineHeight: 1.65,
              }}
            >
              将跳过所有人工确认，生成、删除、覆盖和画布执行会连续运行。请仅在信任当前任务时开启。
            </p>
          </div>
        </div>

        <div
          style={{
            margin: '0 22px 18px',
            padding: '11px 12px',
            borderRadius: 10,
            display: 'flex',
            alignItems: 'flex-start',
            gap: 9,
            color: 'rgb(154,52,18)',
            background: 'rgba(249,115,22,0.08)',
            border: '1px solid rgba(249,115,22,0.16)',
            fontSize: 12,
            lineHeight: 1.55,
          }}
        >
          <Zap size={15} strokeWidth={2} style={{ marginTop: 1, flexShrink: 0 }} />
          <span>切换后立即对当前会话生效，本轮运行中的任务不会被中断。</span>
        </div>

        <div
          style={{
            padding: '13px 18px',
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 9,
            borderTop: '1px solid rgba(15,17,21,0.07)',
            background: 'rgb(249,250,251)',
          }}
        >
          <button
            ref={cancelRef}
            type="button"
            onClick={onCancel}
            style={{
              height: 38,
              padding: '0 17px',
              borderRadius: 10,
              border: '1px solid rgba(15,17,21,0.10)',
              background: 'var(--color-panel)',
              color: 'rgb(70,74,82)',
              fontSize: 13,
              fontWeight: 600,
              cursor: 'pointer',
            }}
          >
            取消
          </button>
          <button
            type="button"
            onClick={onConfirm}
            style={{
              height: 38,
              padding: '0 18px',
              borderRadius: 10,
              border: 'none',
              background: 'rgb(234,88,12)',
              color: 'var(--color-on-primary)',
              fontSize: 13,
              fontWeight: 650,
              cursor: 'pointer',
              boxShadow: '0 5px 14px rgba(234,88,12,0.24)',
            }}
            onMouseEnter={(event) => {
              event.currentTarget.style.background = 'rgb(194,65,12)';
            }}
            onMouseLeave={(event) => {
              event.currentTarget.style.background = 'rgb(234,88,12)';
            }}
          >
            确认开启
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}
