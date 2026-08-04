import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';

export function AgentAssetsPanel() {
  const { assets, loadAssets, deleteAsset } = useAgentStore();
  if (!assets || assets.records.length === 0) return null;

  const totalPages = Math.max(1, Math.ceil(assets.total / assets.size));

  return (
    <div style={{ borderTop: '1px solid var(--color-hairline)', background: 'white', maxHeight: 200, overflowY: 'auto', padding: '10px 14px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)', textTransform: 'uppercase', letterSpacing: 1 }}>
          生成资产（{assets.total}）
        </span>
        <span style={{ fontSize: 11, color: 'var(--color-muted-soft)' }}>
          {assets.page} / {totalPages}
        </span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(64px, 1fr))', gap: 8 }}>
        {assets.records.map((a) => (
          <div key={a.id} style={{ position: 'relative', aspectRatio: '1', borderRadius: 8, overflow: 'hidden', background: 'var(--color-surface-soft)' }}>
            {a.type === 'video' ? (
              <video src={assetUrl(a.url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} muted />
            ) : (
              <img src={assetUrl(a.url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            )}
            <button
              onClick={() => { if (window.confirm('删除该资产？')) deleteAsset(a.id); }}
              title="删除"
              style={{
                position: 'absolute', top: 2, right: 2, width: 18, height: 18,
                border: 'none', borderRadius: '50%', background: 'rgba(198, 69, 69, 0.9)',
                color: 'white', fontSize: 10, cursor: 'pointer', lineHeight: 1,
              }}
            >×</button>
          </div>
        ))}
      </div>
      {totalPages > 1 && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, marginTop: 8 }}>
          <button disabled={assets.page <= 1} onClick={() => loadAssets(assets.page - 1)} style={{ fontSize: 11, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '2px 10px', background: 'white', cursor: 'pointer' }}>上一页</button>
          <button disabled={assets.page >= totalPages} onClick={() => loadAssets(assets.page + 1)} style={{ fontSize: 11, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '2px 10px', background: 'white', cursor: 'pointer' }}>下一页</button>
        </div>
      )}
    </div>
  );
}
