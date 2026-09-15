import { useCallback, useEffect, useState } from 'react';
import { Image as ImageIcon, Layers, X } from 'lucide-react';
import { listProjectAssets } from '../services/projectStorage';
import { operationLabel } from '../canvas/operationLabels';
import type { PersistedAsset } from '../types';

export const ASSET_DRAG_MIME = 'application/x-morrow-asset';

interface AssetDrawerProps {
  projectId: string;
  onClose: () => void;
}

const useObjectUrl = (blob: Blob | undefined) => {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!blob) { setUrl(null); return; }
    const next = URL.createObjectURL(blob);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [blob]);
  return url;
};

function DrawerAssetCard({ asset }: { asset: PersistedAsset }) {
  const thumb = useObjectUrl(asset.thumbnailBlob);
  return (
    <div
      draggable
      onDragStart={(event) => {
        event.dataTransfer.setData(ASSET_DRAG_MIME, asset.id);
        event.dataTransfer.effectAllowed = 'copy';
      }}
      style={card}
      title={`拖到画布上放置 ${asset.name}`}
    >
      <div style={{ height: 72, borderRadius: 6, overflow: 'hidden', background: 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {thumb ? <img src={thumb} alt={asset.name} draggable={false} style={{ width: '100%', height: '100%', objectFit: 'cover' }} /> : <ImageIcon size={20} style={{ opacity: 0.4 }} />}
      </div>
      <div style={{ marginTop: 5, minWidth: 0 }}>
        <strong style={{ display: 'block', fontSize: 11, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{asset.name}</strong>
        <small style={{ opacity: 0.5, fontSize: 10 }}>{operationLabel(asset.operation?.type) ?? '导入'}</small>
      </div>
    </div>
  );
}

export function AssetDrawer({ projectId, onClose }: AssetDrawerProps) {
  const [assets, setAssets] = useState<PersistedAsset[]>([]);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    setLoading(true);
    try { setAssets(await listProjectAssets(projectId)); }
    finally { setLoading(false); }
  }, [projectId]);

  useEffect(() => { void reload(); }, [reload]);

  return (
    <aside className="asset-drawer-panel" style={drawer} aria-label="资产库抽屉">
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Layers size={16} /><strong style={{ fontSize: 14 }}>资产库</strong></div>
        <button onClick={onClose} aria-label="关闭资产库" style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', padding: 4 }}><X size={16} /></button>
      </header>
      <p style={{ opacity: 0.55, fontSize: 12, margin: '0 0 12px' }}>拖拽素材到画布上放置。</p>
      {loading ? (
        <div style={{ padding: 24, textAlign: 'center', opacity: 0.55, fontSize: 13 }}>正在读取素材…</div>
      ) : assets.length ? (
        <div className="asset-drawer-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))', gap: 10 }}>
          {assets.map((asset) => <DrawerAssetCard key={asset.id} asset={asset} />)}
        </div>
      ) : (
        <div style={{ padding: 24, textAlign: 'center', opacity: 0.55, fontSize: 13 }}>还没有素材。导入图片或生成后会自动进入这里。</div>
      )}
    </aside>
  );
}

const drawer: React.CSSProperties = {
  position: 'absolute', left: 16, top: 16, bottom: 16, width: 260,
  padding: 16, boxSizing: 'border-box', zIndex: 40,
  background: 'rgba(24, 26, 30, 0.96)', borderRadius: 14,
  border: '1px solid rgba(255,255,255,0.12)', boxShadow: '0 12px 40px rgba(0,0,0,0.4)',
  overflowY: 'auto',
};
const card: React.CSSProperties = { padding: 6, borderRadius: 8, border: '1px solid rgba(255,255,255,0.1)', background: 'rgba(255,255,255,0.03)', cursor: 'grab' };
