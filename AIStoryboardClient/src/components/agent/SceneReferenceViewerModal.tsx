import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Eye, Image as ImageIcon, LoaderCircle, Package, Trash2, X } from 'lucide-react';
import { assetApi } from '../../api/assets';
import { sceneApi } from '../../api/scenes';
import type { SceneReferenceAsset } from '../../api/projects';
import { assetUrl } from '../../config';
import { DS } from './ChatComposer';

interface AssetRef {
  id: string;
  name: string;
  type: string;
  image?: string;
}

interface Props {
  open: boolean;
  sceneId: string;
  onClose: () => void;
  onChanged: () => void | Promise<void>;
}

export function SceneReferenceViewerModal({ open, sceneId, onClose, onChanged }: Props) {
  const [references, setReferences] = useState<SceneReferenceAsset[]>([]);
  const [assets, setAssets] = useState<AssetRef[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [preview, setPreview] = useState<{ url: string; type: 'image' | 'video' } | null>(null);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [refsRes, assetsRes] = await Promise.all([
        sceneApi.listReferences(sceneId),
        assetApi.listSceneAssets(sceneId),
      ]);
      setReferences(refsRes.data.data ?? []);
      const byId = new Map<string, AssetRef>();
      for (const asset of [...(assetsRes.data.data?.imageAssets ?? []), ...(assetsRes.data.data?.videoAssets ?? [])]) {
        byId.set(asset.id, {
          id: asset.id,
          name: asset.name,
          type: asset.type,
          image: asset.images?.[0]?.url ? assetUrl(asset.images[0].url) : undefined,
        });
      }
      setAssets([...byId.values()]);
    } catch (err) {
      setError(err instanceof Error ? err.message : '读取参考物失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (open) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, sceneId]);

  if (!open) return null;

  const uploads = references.filter((ref) => (ref.sourceType ?? 'upload') === 'upload');
  const outputs = references.filter((ref) => ref.sourceType === 'output' || ref.sourceType === 'scene_image');

  const removeReference = async (ref: SceneReferenceAsset) => {
    try {
      await sceneApi.deleteReference(ref.id);
      await load();
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : '移除素材失败');
    }
  };

  const removeAsset = async (asset: AssetRef) => {
    try {
      const res = await assetApi.listSceneAssets(sceneId);
      const imageIds = (res.data.data?.imageAssets ?? []).filter((item) => item.id !== asset.id).map((item) => item.id);
      const videoIds = (res.data.data?.videoAssets ?? []).filter((item) => item.id !== asset.id).map((item) => item.id);
      await assetApi.setSceneAssets(sceneId, imageIds, videoIds);
      await load();
      await onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : '移除资产失败');
    }
  };

  return createPortal(
    <div style={overlay} onPointerDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={dialog} onPointerDown={(e) => e.stopPropagation()}>
        <header style={header}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
            <Eye size={16} color={DS.brand} />
            <strong style={{ fontSize: 15 }}>查看参考物</strong>
          </div>
          <button type="button" onClick={onClose} style={iconButton}><X size={17} /></button>
        </header>
        <div style={body}>
          {loading ? <div style={empty}><LoaderCircle size={18} className="is-spinning" /> 读取中…</div> : (
            <>
              <ReferenceGroup
                title="用户上传图片"
                icon={<ImageIcon size={13} />}
                empty="尚未直接上传图片"
                items={uploads}
                onPreview={(ref) => setPreview({ url: ref.url, type: ref.type === 'video' ? 'video' : 'image' })}
                onRemove={(ref) => void removeReference(ref)}
              />
              <ReferenceGroupAssets assets={assets} onPreview={(url) => setPreview({ url, type: 'image' })} onRemove={(asset) => void removeAsset(asset)} />
              <ReferenceGroup
                title="上传的产出素材"
                icon={<FolderIcon />}
                empty="尚未选择产出素材"
                items={outputs}
                onPreview={(ref) => setPreview({ url: ref.url, type: ref.type === 'video' ? 'video' : 'image' })}
                onRemove={(ref) => void removeReference(ref)}
              />
            </>
          )}
          {error && <div style={errorBar}>{error}</div>}
        </div>
      </div>
      {preview && (
        <div style={previewOverlay} onPointerDown={() => setPreview(null)}>
          {preview.type === 'video'
            ? <video src={assetUrl(preview.url)} controls autoPlay style={previewMedia} onPointerDown={(e) => e.stopPropagation()} />
            : <img src={assetUrl(preview.url)} alt="" style={previewMedia} onPointerDown={(e) => e.stopPropagation()} />}
        </div>
      )}
    </div>,
    document.body,
  );
}

function ReferenceGroup({ title, icon, empty, items, onPreview, onRemove }: {
  title: string;
  icon: React.ReactNode;
  empty: string;
  items: SceneReferenceAsset[];
  onPreview: (ref: SceneReferenceAsset) => void;
  onRemove: (ref: SceneReferenceAsset) => void;
}) {
  return (
    <section style={{ marginBottom: 18 }}>
      <div style={groupTitle}>{icon}{title} <span style={count}>{items.length}</span></div>
      {items.length === 0 ? <div style={emptyText}>{empty}</div> : (
        <div style={grid}>
          {items.map((ref) => (
            <div key={ref.id} style={mediaCard}>
              {ref.type === 'video'
                ? <video src={assetUrl(ref.url)} muted style={media} />
                : <img src={assetUrl(ref.url)} alt="" style={media} />}
              <div style={actions}>
                <button type="button" title="查看" onClick={() => onPreview(ref)} style={actionButton}><Eye size={13} /></button>
                <button type="button" title="移除" onClick={() => onRemove(ref)} style={{ ...actionButton, color: '#d92d20' }}><Trash2 size={13} /></button>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function ReferenceGroupAssets({ assets, onPreview, onRemove }: {
  assets: AssetRef[];
  onPreview: (url: string) => void;
  onRemove: (asset: AssetRef) => void;
}) {
  return (
    <section style={{ marginBottom: 18 }}>
      <div style={groupTitle}><Package size={13} />上传资产 <span style={count}>{assets.length}</span></div>
      {assets.length === 0 ? <div style={emptyText}>尚未关联 AI 资产</div> : (
        <div style={grid}>
          {assets.map((asset) => (
            <div key={asset.id} style={{ ...mediaCard, padding: 10 }}>
              {asset.image
                ? <img src={asset.image} alt="" style={{ ...media, borderRadius: 8 }} />
                : <div style={{ ...media, borderRadius: 8, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Package size={18} /></div>}
              <div style={{ marginTop: 6, fontSize: 12, color: DS.ink }}>{asset.name}</div>
              <div style={{ fontSize: 11, color: DS.textCaption }}>{asset.type}</div>
              <div style={{ ...actions, position: 'static', marginTop: 6 }}>
                {asset.image && <button type="button" title="查看" onClick={() => onPreview(asset.image!)} style={actionButton}><Eye size={13} /></button>}
                <button type="button" title="解除关联" onClick={() => onRemove(asset)} style={{ ...actionButton, color: '#d92d20' }}><Trash2 size={13} /></button>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function FolderIcon() {
  return <Package size={13} />;
}

const overlay: React.CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1450, background: 'rgba(15,17,21,0.46)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
};
const dialog: React.CSSProperties = {
  width: '100%', maxWidth: 700, maxHeight: '80vh', background: 'white', borderRadius: 16,
  display: 'flex', flexDirection: 'column', overflow: 'hidden', boxShadow: '0 18px 48px rgba(15,17,21,0.24)',
};
const header: React.CSSProperties = {
  padding: '13px 18px', borderBottom: `1px solid ${DS.border}`,
  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
};
const body: React.CSSProperties = { flex: 1, minHeight: 0, overflowY: 'auto', padding: 16 };
const groupTitle: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 5, fontSize: 13, fontWeight: 600,
  color: DS.textSecondary, marginBottom: 8,
};
const count: React.CSSProperties = {
  marginLeft: 3, padding: '0 6px', borderRadius: 9, background: DS.hover,
  color: DS.textCaption, fontSize: 11,
};
const emptyText: React.CSSProperties = { fontSize: 12, color: DS.textCaption, padding: '8px 0' };
const grid: React.CSSProperties = {
  display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(100px, 1fr))', gap: 9,
};
const mediaCard: React.CSSProperties = {
  position: 'relative', overflow: 'hidden', borderRadius: 10,
  border: `1px solid ${DS.border}`, background: 'white',
};
const media: React.CSSProperties = {
  width: '100%', aspectRatio: '1', objectFit: 'cover', display: 'block', background: DS.hover,
};
const actions: React.CSSProperties = {
  position: 'absolute', left: 4, right: 4, bottom: 4, display: 'flex',
  justifyContent: 'flex-end', gap: 4,
};
const actionButton: React.CSSProperties = {
  width: 25, height: 25, border: 'none', borderRadius: 7, background: 'rgba(255,255,255,0.92)',
  color: DS.textSecondary, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  cursor: 'pointer', boxShadow: '0 1px 4px rgba(0,0,0,0.12)',
};
const iconButton: React.CSSProperties = {
  border: 'none', background: 'transparent', color: DS.textCaption, cursor: 'pointer', padding: 4,
};
const errorBar: React.CSSProperties = {
  marginTop: 12, padding: '8px 10px', color: '#d92d20', background: 'rgba(217,45,32,0.06)',
  borderRadius: 8, fontSize: 12,
};
const previewOverlay: React.CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 1500, background: 'rgba(0,0,0,0.82)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
};
const previewMedia: React.CSSProperties = {
  maxWidth: '92vw', maxHeight: '88vh', borderRadius: 10,
};
const empty: React.CSSProperties = {
  minHeight: 180, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
  color: DS.textCaption, fontSize: 13,
};
