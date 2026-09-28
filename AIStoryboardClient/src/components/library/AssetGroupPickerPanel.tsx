import { useEffect, useMemo, useState } from 'react';
import { Check, Globe2, LoaderCircle } from 'lucide-react';
import { libraryApi, type LibraryAsset } from '../../api/library';
import { assetUrl } from '../../config';

export type AssetGroupKindFilter = 'all' | 'CHARACTER' | 'PROP' | 'SCENE' | 'GENERAL';

interface Props {
  projectId?: string;
  /** 受控选中态：当前已完成选择的资产组 id 列表。 */
  selectedIds: string[];
  /** 单选/多选由调用方决定，面板只负责触发切换。 */
  onToggle: (groupId: string) => void;
}

/**
 * 受控的资产组选择面板：项目/全局资产组查询、人物/道具/场景/通用筛选、卡片选择与缩略图展示。
 * 供 AssetGroupPickerModal（编辑器/旧入口）与 /chat 的统一项目库选择器复用。
 */
export function AssetGroupPickerPanel({ projectId, selectedIds, onToggle }: Props) {
  const [kindFilter, setKindFilter] = useState<AssetGroupKindFilter>('all');
  const [groups, setGroups] = useState<LibraryAsset[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const selected = useMemo(() => new Set(selectedIds), [selectedIds]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    Promise.all([
      projectId
        ? libraryApi.listAssets({ scope: 'PROJECT', projectId, type: 'GROUP', page: 1, size: 100 })
        : Promise.resolve(null),
      libraryApi.listAssets({ scope: 'GLOBAL', type: 'GROUP', page: 1, size: 100 }),
    ]).then(([projectRes, globalRes]) => {
      if (cancelled) return;
      setGroups([
        ...(projectRes?.data.data?.records ?? []),
        ...(globalRes.data.data?.records ?? []),
      ]);
    }).catch((err: unknown) => {
      if (!cancelled) setError(err instanceof Error ? err.message : '加载资产组失败');
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [projectId]);

  const visible = useMemo(() => groups.filter((group) => {
    if (group.status !== 'ACTIVE') return false;
    if (kindFilter === 'all') return true;
    if (kindFilter === 'GENERAL') return !group.aiKind;
    return group.aiKind === kindFilter;
  }), [groups, kindFilter]);

  return (
    <>
      <div style={filters}>
        {(['all', 'CHARACTER', 'PROP', 'SCENE', 'GENERAL'] as const).map((filter) => (
          <button
            key={filter}
            type="button"
            onClick={() => setKindFilter(filter)}
            style={pill(kindFilter === filter)}
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
                <button key={group.id} type="button" onClick={() => onToggle(group.id)} style={card(active)}>
                  <div style={cover}>
                    {group.coverUrl
                      ? <img src={assetUrl(group.coverUrl)} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                      : <Globe2 size={18} style={{ color: 'var(--color-muted)' }} />}
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
    </>
  );
}

function aiKindLabel(kind: NonNullable<LibraryAsset['aiKind']>) {
  if (kind === 'CHARACTER') return '人物';
  if (kind === 'PROP') return '道具';
  return '场景';
}

function filterLabel(filter: AssetGroupKindFilter) {
  if (filter === 'all') return '全部';
  if (filter === 'GENERAL') return '通用';
  return aiKindLabel(filter);
}

const filters: React.CSSProperties = {
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
const pill = (active: boolean): React.CSSProperties => ({
  padding: '6px 12px', borderRadius: 8, border: `1px solid ${active ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
  background: active ? 'var(--color-primary)' : 'white', color: active ? 'white' : 'var(--color-muted)',
  fontSize: 12, cursor: 'pointer',
});
const card = (active: boolean): React.CSSProperties => ({
  position: 'relative', display: 'flex', alignItems: 'center', gap: 10, padding: 9,
  border: `1.5px solid ${active ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
  borderRadius: 11, background: active ? 'var(--color-surface-soft)' : 'white', cursor: 'pointer',
});
