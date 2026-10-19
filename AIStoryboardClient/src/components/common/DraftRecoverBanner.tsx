import { History } from 'lucide-react';

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
        padding: '7px 20px',
        background: 'var(--color-primary-tint)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 16,
        borderBottom: '1px solid var(--color-hairline)',
        flexShrink: 0,
      }}
    >
      <span
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 7,
          minWidth: 0,
          fontSize: 12.5,
          color: 'var(--color-body)',
        }}
      >
        <History size={14} strokeWidth={1.9} color="var(--color-primary-active)" />
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          有未完成的草稿「<strong style={{ fontWeight: 600, color: 'var(--color-ink)' }}>{projectName}</strong>」
        </span>
      </span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
        <button type="button" className="btn btn--ghost btn--sm" onClick={onDismiss}>
          忽略
        </button>
        <button
          type="button"
          className="btn btn--sm"
          onClick={onRecover}
          style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)', borderColor: 'var(--color-primary)' }}
        >
          继续编辑
        </button>
      </div>
    </div>
  );
}
