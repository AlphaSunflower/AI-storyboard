import { useCallback, useEffect, useMemo, useState } from 'react';
import { Globe2, Image as ImageIcon, Plus, Video } from 'lucide-react';
import { listGlobalAssets, listProjectAssets, listProjects, saveAssetToLibrary } from '../services/projectStorage';
import { OPERATION_LABELS, operationLabel } from '../canvas/operationLabels';
import type { ImageOperation, PersistedAsset } from '../types';

interface AssetLibraryProps {
  projectId: string;
  onNotify?: (message: string) => void;
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

const shortId = (id?: string) => (id ? `${id.slice(0, 8)}…` : '—');

const FILTER_TYPES: Array<'all' | ImageOperation['type']> = ['all', 'import', 'crop', 'split', 'pixels', 'annotation', 'flatten', 'remote'];

function AssetCard({ asset, inGlobal, onAdd }: { asset: PersistedAsset; inGlobal: boolean; onAdd: (id: string) => void }) {
  const thumb = useObjectUrl(asset.thumbnailBlob);
  const isVideo = asset.kind === 'video';
  return (
    <article style={card}>
      <div style={{ position: 'relative', height: 120, borderRadius: 8, overflow: 'hidden', background: 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {thumb ? <img src={thumb} alt={asset.name} style={{ width: '100%', height: '100%', objectFit: 'cover' }} /> : <ImageIcon size={24} style={{ opacity: 0.4 }} />}
        {isVideo && <span style={{ position: 'absolute', top: 6, right: 6, background: 'rgba(0,0,0,0.6)', borderRadius: 4, padding: '2px 5px' }}><Video size={12} /></span>}
        <span style={{ position: 'absolute', bottom: 6, left: 6, fontSize: 10, opacity: 0.75, background: 'rgba(0,0,0,0.55)', padding: '2px 5px', borderRadius: 4 }}>{operationLabel(asset.operation?.type) ?? '导入'}</span>
      </div>
      <strong style={{ display: 'block', margin: '8px 0 2px', fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{asset.name}</strong>
      <small style={{ opacity: 0.55, fontSize: 11 }}>{asset.width}×{asset.height}{asset.kind === 'video' && asset.durationSeconds ? ` · ${asset.durationSeconds}s` : ''}</small>
      <dl style={{ margin: '6px 0 0', fontSize: 10, opacity: 0.5, lineHeight: 1.5 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between' }}><dt>项目</dt><dd>{shortId(asset.projectId)}</dd></div>
        <div style={{ display: 'flex', justifyContent: 'space-between' }}><dt>画布</dt><dd>{shortId(asset.canvasId)}</dd></div>
        <div style={{ display: 'flex', justifyContent: 'space-between' }}><dt>时间</dt><dd>{new Date(asset.createdAt).toLocaleString('zh-CN', { hour12: false })}</dd></div>
      </dl>
      {!inGlobal && (
        <button style={addButton} onClick={() => onAdd(asset.id)}><Plus size={12} /> 添加到总资产库</button>
      )}
      {inGlobal && (
        <div style={{ marginTop: 6, fontSize: 10, opacity: 0.55, display: 'flex', alignItems: 'center', gap: 4 }}><Globe2 size={12} /> 跨项目可用</div>
      )}
    </article>
  );
}

export function AssetLibrary({ projectId, onNotify }: AssetLibraryProps) {
  const [tab, setTab] = useState<'project' | 'global'>('project');
  const [filter, setFilter] = useState<'all' | ImageOperation['type']>('all');
  const [projectAssets, setProjectAssets] = useState<PersistedAsset[]>([]);
  const [globalAssets, setGlobalAssets] = useState<PersistedAsset[]>([]);
  const [projectTitles, setProjectTitles] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const [project, global, projects] = await Promise.all([listProjectAssets(projectId), listGlobalAssets(), listProjects()]);
      setProjectAssets(project);
      setGlobalAssets(global);
      const titles: Record<string, string> = {};
      projects.forEach((item) => { titles[item.id] = item.title; });
      setProjectTitles(titles);
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { void reload(); }, [reload]);

  const addToGlobal = async (assetId: string) => {
    try {
      await saveAssetToLibrary(assetId);
      onNotify?.('已添加到总资产库');
      await reload();
    } catch (error) {
      onNotify?.(error instanceof Error ? error.message : '添加失败');
    }
  };

  const assets = tab === 'project' ? projectAssets : globalAssets;
  const filtered = assets.filter((asset) => filter === 'all' || asset.operation?.type === filter);
  const groupedByProject = useMemo(() => {
    const groups: Record<string, PersistedAsset[]> = {};
    filtered.forEach((asset) => {
      const key = asset.projectId ?? 'unknown';
      (groups[key] ??= []).push(asset);
    });
    return groups;
  }, [filtered]);

  const assetGrid = (items: PersistedAsset[]) => (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 14 }}>
      {items.map((asset) => <AssetCard key={asset.id} asset={asset} inGlobal={tab === 'global'} onAdd={(id) => void addToGlobal(id)} />)}
    </div>
  );

  return (
    <section style={{ padding: '20px 0' }}>
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        <button onClick={() => setTab('project')} style={tab === 'project' ? tabActive : tabInactive}>项目资产库</button>
        <button onClick={() => setTab('global')} style={tab === 'global' ? tabActive : tabInactive}>全局总资产库</button>
      </div>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 16 }}>
        {FILTER_TYPES.map((type) => (
          <button key={type} onClick={() => setFilter(type)} style={filter === type ? chipActive : chipInactive}>
            {type === 'all' ? '全部' : OPERATION_LABELS[type]}
          </button>
        ))}
      </div>
      <p style={{ opacity: 0.55, fontSize: 13, margin: '0 0 16px' }}>
        {tab === 'project' ? '本项目导入与生成的所有素材（默认绑定项目）。' : '跨项目共享的素材，均可追溯其来源项目。'}
      </p>
      {loading ? (
        <div style={{ padding: 32, textAlign: 'center', opacity: 0.55 }}>正在读取素材…</div>
      ) : filtered.length ? (
        tab === 'global' ? (
          <div>
            {Object.entries(groupedByProject).map(([projectIdKey, items]) => (
              <section key={projectIdKey} style={{ marginBottom: 24 }}>
                <h3 style={{ fontSize: 14, margin: '0 0 12px', opacity: 0.85 }}>{projectIdKey === 'unknown' ? '未知来源' : (projectTitles[projectIdKey] ?? shortId(projectIdKey))}</h3>
                {assetGrid(items)}
              </section>
            ))}
          </div>
        ) : (
          assetGrid(filtered)
        )
      ) : (
        <div style={{ textAlign: 'center', padding: 48, borderRadius: 14, border: '1px dashed rgba(255,255,255,0.16)', opacity: 0.6 }}>
          {assets.length ? '没有匹配当前筛选的素材。' : tab === 'project' ? '还没有素材，导入图片或让 AI 生成后会自动进入这里。' : '还没有跨项目素材，在项目资产库中点击「添加到总资产库」。'}
        </div>
      )}
    </section>
  );
}

const card: React.CSSProperties = { padding: 12, borderRadius: 12, border: '1px solid rgba(255,255,255,0.12)', background: 'rgba(255,255,255,0.03)' };
const addButton: React.CSSProperties = { marginTop: 8, display: 'inline-flex', alignItems: 'center', gap: 4, padding: '5px 8px', borderRadius: 6, border: '1px solid rgba(215,255,84,0.4)', background: 'transparent', color: '#d7ff54', cursor: 'pointer', fontSize: 11 };
const tabActive: React.CSSProperties = { padding: '8px 14px', borderRadius: 8, border: 'none', background: '#d7ff54', color: '#1c1e22', cursor: 'pointer', fontSize: 13, fontWeight: 600 };
const tabInactive: React.CSSProperties = { padding: '8px 14px', borderRadius: 8, border: '1px solid rgba(255,255,255,0.14)', background: 'transparent', color: 'inherit', cursor: 'pointer', fontSize: 13 };
const chipActive: React.CSSProperties = { padding: '4px 10px', borderRadius: 999, border: '1px solid rgba(215,255,84,0.6)', background: 'rgba(215,255,84,0.12)', color: '#d7ff54', cursor: 'pointer', fontSize: 12 };
const chipInactive: React.CSSProperties = { padding: '4px 10px', borderRadius: 999, border: '1px solid rgba(255,255,255,0.14)', background: 'transparent', color: 'inherit', cursor: 'pointer', fontSize: 12 };
