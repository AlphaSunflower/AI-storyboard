import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { AssetGroupPickerPanel } from './AssetGroupPickerPanel';

interface Props {
  open: boolean;
  projectId?: string;
  multiple?: boolean;
  selectedIds?: string[];
  title?: string;
  purpose?: 'image' | 'video';
  onClose: () => void;
  onConfirm: (groupIds: string[]) => void | Promise<void>;
}

const EMPTY_IDS: string[] = [];

export function AssetGroupPickerModal({
  open,
  projectId,
  multiple = true,
  selectedIds = EMPTY_IDS,
  title = '选择资产组',
  onClose,
  onConfirm,
}: Props) {
  const [selected, setSelected] = useState<Set<string>>(new Set(selectedIds));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const selectedKey = selectedIds.join('\u0000');

  useEffect(() => {
    if (!open) return;
    setSelected(new Set(selectedIds));
    setError('');
  }, [open, selectedKey]);

  if (!open) return null;

  const toggle = (id: string) => {
    setSelected((current) => {
      if (!multiple) return current.has(id) ? new Set() : new Set([id]);
      const next = new Set(current);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const confirm = async () => {
    setSaving(true);
    setError('');
    try {
      await onConfirm([...selected]);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存资产组失败');
    } finally {
      setSaving(false);
    }
  };

  return createPortal(
    <div style={overlay} onPointerDown={(event) => {
      if (event.target === event.currentTarget && !saving) onClose();
    }}>
      <div role="dialog" aria-modal="true" style={dialog} onPointerDown={(event) => event.stopPropagation()}>
        <header style={header}>
          <div>
            <strong style={{ fontSize: 16 }}>{title}</strong>
            <div style={{ marginTop: 3, fontSize: 12, color: 'var(--color-muted)' }}>
              关联时冻结当前快照，组更新后需手动刷新
            </div>
          </div>
          <button type="button" onClick={onClose} disabled={saving} style={iconButton} aria-label="关闭">
            <X size={18} />
          </button>
        </header>

        <AssetGroupPickerPanel
          projectId={projectId}
          selectedIds={[...selected]}
          onToggle={toggle}
        />

        {error && (
          <div style={{ padding: '0 18px 10px', color: 'var(--color-error)', fontSize: 12 }}>{error}</div>
        )}

        <footer style={footer}>
          <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>已选 {selected.size} 个资产组</span>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" onClick={onClose} disabled={saving} style={ghostButton}>取消</button>
            <button type="button" onClick={() => void confirm()} disabled={saving} style={primaryButton}>
              {saving ? '保存中…' : multiple ? '确认关联' : '确认选择'}
            </button>
          </div>
        </footer>
      </div>
    </div>,
    document.body,
  );
}

const overlay: React.CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1500, padding: 16,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  background: 'rgba(15,17,21,0.46)',
};
const dialog: React.CSSProperties = {
  width: '100%', maxWidth: 680, maxHeight: '82dvh', display: 'flex', flexDirection: 'column',
  overflow: 'hidden', borderRadius: 16, background: 'var(--color-panel)',
  boxShadow: '0 18px 48px rgba(15,17,21,0.24)',
};
const header: React.CSSProperties = {
  padding: '14px 18px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  borderBottom: '1px solid var(--color-hairline)',
};
const footer: React.CSSProperties = {
  padding: '12px 18px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  borderTop: '1px solid var(--color-hairline)',
};
const iconButton: React.CSSProperties = {
  border: 'none', background: 'transparent', color: 'var(--color-muted)', cursor: 'pointer', padding: 4,
};
const ghostButton: React.CSSProperties = {
  padding: '8px 16px', borderRadius: 10, border: '1px solid var(--color-hairline)',
  background: 'var(--color-panel)', color: 'var(--color-body)', fontSize: 13, cursor: 'pointer',
};
const primaryButton: React.CSSProperties = {
  padding: '8px 16px', borderRadius: 10, border: 'none',
  background: 'var(--color-primary)', color: 'var(--color-on-primary)', fontSize: 13, cursor: 'pointer',
};
