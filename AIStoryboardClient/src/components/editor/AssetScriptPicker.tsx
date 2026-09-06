import { useEffect, useState } from 'react';
import { assetApi, type Asset } from '../../api/assets';
import { assetUrl } from '../../config';

const ASSET_TYPE_LABEL: Record<string, string> = { character: '人物', prop: '道具', scene: '场景' };

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 12,
  color: 'var(--color-muted)',
  marginBottom: 4,
  font: 'var(--text-caption-upper)',
};

/**
 * 资产生成选择器：从资产库（项目 + 全局）勾选资产，注入分镜生成作为固定设定。
 * 受控组件：value=选中资产 id 列表，onChange 透传。
 */
export function AssetScriptPicker({ projectId, value = [], onChange }: { projectId?: string; value?: string[]; onChange: (ids: string[]) => void }) {
  const [open, setOpen] = useState(false);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!projectId) { setAssets([]); return; }
    let alive = true;
    assetApi.list(projectId).then((res) => {
      if (alive) { setAssets(res.data.data || []); setLoaded(true); }
    }).catch(() => { if (alive) { setAssets([]); setLoaded(true); } });
    return () => { alive = false; };
  }, [projectId]);

  const toggle = (id: string) => {
    onChange(value.includes(id) ? value.filter((x) => x !== id) : [...value, id]);
  };

  return (
    <div>
      <label style={labelStyle}>资产生成（可选）</label>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button
          type="button"
          onClick={() => setOpen(true)}
          onMouseEnter={(e) => { if (!value.length) e.currentTarget.style.borderColor = 'var(--color-primary)'; e.currentTarget.style.background = 'var(--color-surface-soft)'; }}
          onMouseLeave={(e) => { e.currentTarget.style.borderColor = value.length ? 'var(--color-primary)' : 'var(--color-hairline)'; e.currentTarget.style.background = value.length ? 'var(--color-surface-card)' : 'white'; }}
          style={{
            flex: 1,
            padding: '8px 10px',
            borderRadius: 'var(--rounded-md)',
            border: `1px solid ${value.length ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
            background: value.length ? 'var(--color-surface-card)' : 'white',
            color: 'var(--color-body)',
            fontSize: 13,
            cursor: 'pointer',
            textAlign: 'left',
          }}
        >
          {value.length ? `已选 ${value.length} 个资产` : '选择资产约束分镜…'}
        </button>
        {value.length > 0 && (
          <button type="button" onClick={() => onChange([])} onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--color-error)'; }} onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--color-muted)'; }} style={{ padding: '4px 8px', fontSize: 12, border: 'none', background: 'none', color: 'var(--color-muted)', cursor: 'pointer', borderRadius: 6 }}>清空</button>
        )}
      </div>

      {open && (
        <div
          style={{
            position: 'fixed', inset: 0, background: 'rgba(20, 20, 19, 0.4)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
          }}
          onClick={() => setOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              width: 'min(520px, 92vw)', maxHeight: '70vh', display: 'flex', flexDirection: 'column',
              background: 'var(--color-canvas)', borderRadius: 'var(--rounded-lg)',
              boxShadow: '0 12px 48px rgba(20, 20, 19, 0.22)', overflow: 'hidden',
            }}
          >
            <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--color-hairline)', display: 'flex', alignItems: 'center', gap: 12 }}>
              <span style={{ flex: 1, font: 'var(--text-title-md)', color: 'var(--color-ink)' }}>选择要约束分镜的资产</span>
              <button
                onClick={() => setOpen(false)}
                onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = 'var(--color-primary-active)'; }}
                onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'var(--color-primary)'; }}
                style={{ padding: '6px 16px', border: 'none', borderRadius: 'var(--rounded-md)', background: 'var(--color-primary)', color: 'white', fontSize: 13, cursor: 'pointer' }}
              >
                确定
              </button>
            </div>
            <div style={{ flex: 1, overflowY: 'auto', padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
              {!loaded && <p style={{ color: 'var(--color-muted)', fontSize: 13, textAlign: 'center', padding: 32 }}>加载中…</p>}
              {loaded && assets.length === 0 && (
                <p style={{ color: 'var(--color-muted)', fontSize: 13, textAlign: 'center', padding: 32 }}>
                  暂无资产 —— 请先在「AI 资产库」创建人物/道具/场景
                </p>
              )}
              {assets.map((a) => {
                const checked = value.includes(a.id);
                const cover = a.images[0];
                return (
                  <div
                    key={a.id}
                    onClick={() => toggle(a.id)}
                    onMouseEnter={(e) => { if (!checked) e.currentTarget.style.background = 'var(--color-surface-soft)'; }}
                    onMouseLeave={(e) => { e.currentTarget.style.background = checked ? 'var(--color-surface-card)' : 'white'; }}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 10, padding: '8px 10px',
                      background: checked ? 'var(--color-surface-card)' : 'white',
                      border: `1px solid ${checked ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
                      borderRadius: 'var(--rounded-md)', cursor: 'pointer',
                    }}
                  >
                    <div style={{ width: 36, height: 36, flexShrink: 0, borderRadius: 'var(--rounded-md)', background: 'var(--color-surface-card)', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}>
                      {cover ? (
                        <img src={assetUrl(cover.url)} alt={a.name} style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
                      ) : (
                        <span style={{ color: 'var(--color-muted-soft)', fontSize: 10 }}>无图</span>
                      )}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <span style={{ font: 'var(--text-body-sm)', fontWeight: 600, color: 'var(--color-ink)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.name}</span>
                        <span style={{ fontSize: 10, color: 'var(--color-primary)', background: 'var(--color-surface-card)', padding: '2px 6px', borderRadius: 999, flexShrink: 0 }}>
                          {ASSET_TYPE_LABEL[a.type] || a.type}{a.projectId ? '' : '·全局'}
                        </span>
                      </div>
                      {a.description && <p style={{ margin: '2px 0 0', font: 'var(--text-caption)', color: 'var(--color-muted)', fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.description}</p>}
                    </div>
                    <span style={{ width: 20, height: 20, flexShrink: 0, borderRadius: 999, border: `1px solid ${checked ? 'var(--color-primary)' : 'var(--color-hairline)'}`, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, color: checked ? 'white' : 'transparent', background: checked ? 'var(--color-primary)' : 'transparent' }}>✓</span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
