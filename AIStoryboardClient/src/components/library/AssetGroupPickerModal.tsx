import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Boxes, Check, Globe2, LoaderCircle, X } from 'lucide-react';
import { libraryApi, type LibraryAsset } from '../../api/library';
import { assetUrl } from '../../config';

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
  const [kindFilter, setKindFilter] = useState<'all' | 'CHARACTER' | 'PROP' | 'SCENE' | 'GENERAL'>('all');
  const [groups, setGroups] = useState<LibraryAsset[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set(selectedIds));
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const selectedKey = selectedIds.join('\u0000');

  useEffect(() => {
    if (!open) return;
    setSelected(new Set(selectedIds));
    setKindFilter('all');
    setError('');
  }, [open, selectedKey]);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    Promise.all([
      projectId
        ? libraryApi.listAssets({ scope: 'PROJECT', projectId, type: 'GROUP', page: 1, size: 100 })
        : Promise.resolve(null),
      libraryApi.listAssets({ scope: 'GLOBAL', type: 'GROUP', page: 1, size: 100 }),
    ]).then(([projectRes, globalRes]) => {
      if (!cancelled) {
        setGroups([
          ...(projectRes?.data.data?.records ?? []),
          ...(globalRes.data.data?.records ?? []),
        ]);
      }
    }).catch((err: unknown) => {
      if (!cancelled) setError(err instanceof Error ? err.message : '加载资产组失败');
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [open, projectId]);

  const visible = useMemo(() => groups.filter((group) => {
    if (group.status !== 'ACTIVE') return false;
    if (kindFilter === 'all') return true;
    if (kindFilter === 'GENERAL') return !group.aiKind;
    return group.aiKind === kindFilter;
  }), [groups, kindFilter]);

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

        <div style={tabs}>
          {(['all', 'CHARACTER', 'PROP', 'SCENE', 'GENERAL'] as const).map((filter) => (
            <button
              key={filter}
              type="button"
              onClick={() => setKindFilter(filter)}
              style={scopeButton(kindFilter === filter)}
            >
              {filterLabel(filter)}
            </button>
          ))}
        </div>

        <div style={body}>
          {loading ? (
            <div style={empty}><LoaderCircle className="is-spinning" size={18} /> 正在加载资产组…</div>
          ) : visible.length === 0 ? (
            <div style={empty}>暂无资产组</div>
          ) : (
            <div style={grid}>
              {visible.map((group) => {
                const active = selected.has(group.id);
                return (
                  <button key={group.id} type="button" onClick={() => toggle(group.id)} style={card(active)}>
                    <div style={cover}>
                      {group.coverUrl
                        ? <img src={assetUrl(group.coverUrl)} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                        : <Boxes size={22} color="var(--color-muted)" />}
                    </div>
                    <div style={{ minWidth: 0, flex: 1, textAlign: 'left' }}>
                      <strong style={ellipsis}>{group.name}</strong>
                      <small style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--color-muted)', marginTop: 3 }}>
                        {group.scope === 'GLOBAL' && <Globe2 size={11} />}
                        {group.aiKind ? aiKindLabel(group.aiKind) : '通用素材组'}
                      </small>
                    </div>
                    {active && <span style={check}><Check size={12} /></span>}
                  </button>
                );
              })}
            </div>
          )}
          {error && <div style={{ color: 'var(--color-error)', fontSize: 12, marginTop: 10 }}>{error}</div>}
        </div>

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

function aiKindLabel(kind: NonNullable<LibraryAsset['aiKind']>) {
  if (kind === 'CHARACTER') return '人物';
  if (kind === 'PROP') return '道具';
  return '场景';
}

function filterLabel(filter: 'all' | 'CHARACTER' | 'PROP' | 'SCENE' | 'GENERAL') {
  if (filter === 'all') return '全部';
  if (filter === 'GENERAL') return '通用';
  return aiKindLabel(filter);
}

const overlay: React.CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1500, padding: 16,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
  background: 'rgba(15,17,21,0.46)',
};
const dialog: React.CSSProperties = {
  width: '100%', maxWidth: 680, maxHeight: '82dvh', display: 'flex', flexDirection: 'column',
  overflow: 'hidden', borderRadius: 16, background: 'white',
  boxShadow: '0 18px 48px rgba(15,17,21,0.24)',
};
const header: React.CSSProperties = {
  padding: '14px 18px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  borderBottom: '1px solid var(--color-hairline)',
};
const tabs: React.CSSProperties = {
  padding: '10px 18px', display: 'flex', gap: 8, borderBottom: '1px solid var(--color-hairline)',
};
const body: React.CSSProperties = { flex: 1, minHeight: 0, overflowY: 'auto', padding: 16 };
const grid: React.CSSProperties = { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(250px, 1fr))', gap: 10 };
const empty: React.CSSProperties = {
  minHeight: 180, display: 'flex', alignItems: 'center', justifyContent: 'center',
  gap: 8, color: 'var(--color-muted)', fontSize: 13,
};
const cover: React.CSSProperties = {
  width: 52, height: 52, flexShrink: 0, overflow: 'hidden', borderRadius: 9,
  display: 'grid', placeItems: 'center', background: 'var(--color-surface-soft)',
};
const ellipsis: React.CSSProperties = {
  display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 13,
};
const check: React.CSSProperties = {
  width: 20, height: 20, borderRadius: 10, display: 'grid', placeItems: 'center',
  color: 'white', background: 'var(--color-primary)',
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
  background: 'white', color: 'var(--color-body)', fontSize: 13, cursor: 'pointer',
};
const primaryButton: React.CSSProperties = {
  padding: '8px 16px', borderRadius: 10, border: 'none',
  background: 'var(--color-primary)', color: 'white', fontSize: 13, cursor: 'pointer',
};
const scopeButton = (active: boolean): React.CSSProperties => ({
  padding: '6px 12px', borderRadius: 8, border: `1px solid ${active ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
  background: active ? 'var(--color-primary)' : 'white', color: active ? 'white' : 'var(--color-muted)',
  fontSize: 12, cursor: 'pointer',
});
const card = (active: boolean): React.CSSProperties => ({
  position: 'relative', display: 'flex', alignItems: 'center', gap: 10, padding: 9,
  border: `1.5px solid ${active ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
  borderRadius: 11, background: active ? 'var(--color-surface-soft)' : 'white', cursor: 'pointer',
});
