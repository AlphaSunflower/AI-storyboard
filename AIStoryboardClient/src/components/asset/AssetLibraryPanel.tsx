import { useCallback, useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import Carousel, { type CarouselItem } from '../Carousel';
import { assetApi, type Asset, type AssetType } from '../../api/assets';
import { assetUrl } from '../../config';
import { useProjectStore } from '../../stores/projectStore';

const TYPE_LABEL: Record<AssetType, string> = { character: '人物', prop: '道具', scene: '场景' };
const TYPES: { value: AssetType; label: string }[] = [
  { value: 'character', label: '人物' },
  { value: 'prop', label: '道具' },
  { value: 'scene', label: '场景' },
];

// ── styles（设计 token：暖色编辑风，radius 8/12，珊瑚主色） ──
const overlayStyle: React.CSSProperties = {
  position: 'fixed', inset: 0, background: 'rgba(20, 20, 19, 0.4)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
};
const panelStyle: React.CSSProperties = {
  width: 'min(920px, 92vw)', height: 'min(640px, 86vh)',
  background: 'var(--color-canvas)', borderRadius: 'var(--rounded-lg)',
  boxShadow: '0 12px 48px rgba(20, 20, 19, 0.22)', display: 'flex', flexDirection: 'column',
  overflow: 'hidden',
};
const inputStyle: React.CSSProperties = {
  width: '100%', padding: '8px 12px', border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)', font: 'var(--text-body-sm)', color: 'var(--color-ink)',
  outline: 'none', boxSizing: 'border-box', background: 'white',
};
const primaryBtnStyle: React.CSSProperties = {
  padding: '8px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
  background: 'var(--color-primary)', color: 'white', font: 'var(--text-caption)', cursor: 'pointer',
};
const ghostBtnStyle: React.CSSProperties = {
  padding: '5px 10px', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
  background: 'white', color: 'var(--color-muted)', font: 'var(--text-caption)', cursor: 'pointer',
};

export function AssetLibraryPanel({ onClose }: { onClose: () => void }) {
  const currentProject = useProjectStore((s) => s.currentProject);
  const selectedSceneId = useProjectStore((s) => s.selectedSceneId);

  const [assets, setAssets] = useState<Asset[]>([]);
  const [activeType, setActiveType] = useState<'all' | AssetType>('all');
  const [loading, setLoading] = useState(false);

  const [showCreate, setShowCreate] = useState(false);
  const [createType, setCreateType] = useState<AssetType>('character');
  const [createName, setCreateName] = useState('');
  const [createDesc, setCreateDesc] = useState('');
  const [createGlobal, setCreateGlobal] = useState(false);

  const [detail, setDetail] = useState<Asset | null>(null);
  const [sceneAssetIds, setSceneAssetIds] = useState<Set<string>>(new Set());

  const panelRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await assetApi.list(currentProject?.id);
      setAssets(res.data.data || []);
    } catch {
      /* 列表拉取失败静默，展示空态 */
    }
    setLoading(false);
  }, [currentProject?.id]);

  useEffect(() => { void load(); }, [load]);

  // 当前分镜已关联资产（供「关联本镜」切换态）
  useEffect(() => {
    if (!selectedSceneId) { setSceneAssetIds(new Set()); return; }
    assetApi.listSceneAssets(selectedSceneId)
      .then((res) => setSceneAssetIds(new Set((res.data.data || []).map((a) => a.id))))
      .catch(() => setSceneAssetIds(new Set()));
  }, [selectedSceneId]);

  // 入场动画：遮罩淡入 + 面板 back.out 弹入
  useGSAP(() => {
    gsap.fromTo(overlayRef.current, { opacity: 0 }, { opacity: 1, duration: 0.2, ease: 'power2.out' });
    gsap.fromTo(panelRef.current, { scale: 0.96, y: 16, opacity: 0 },
      { scale: 1, y: 0, opacity: 1, duration: 0.34, ease: 'back.out(1.2)' });
  }, { scope: panelRef });

  const filtered = activeType === 'all' ? assets : assets.filter((a) => a.type === activeType);

  const handleCreate = async () => {
    if (!createName.trim()) return;
    try {
      await assetApi.create({
        type: createType,
        name: createName.trim(),
        description: createDesc.trim() || undefined,
        projectId: createGlobal ? null : (currentProject?.id ?? null),
      });
      setCreateName(''); setCreateDesc(''); setShowCreate(false);
      await load();
    } catch {
      /* 创建失败静默 */
    }
  };

  const refreshDetail = async (assetId: string) => {
    const res = await assetApi.list(currentProject?.id);
    const updated = (res.data.data || []).find((a) => a.id === assetId) ?? null;
    setDetail(updated);
    await load();
  };

  const handleUpload = async (assetId: string, files: FileList | null) => {
    if (!files || files.length === 0) return;
    try {
      for (const file of Array.from(files)) await assetApi.uploadImage(assetId, file);
      await refreshDetail(assetId);
    } catch {
      /* 上传失败静默 */
    }
  };

  const handleDelete = async (asset: Asset) => {
    if (!window.confirm(`删除资产「${asset.name}」？其图片与分镜关联将一并删除。`)) return;
    try {
      await assetApi.delete(asset.id);
      setDetail(null);
      await load();
    } catch {
      /* 删除失败静默 */
    }
  };

  const handleDeleteImage = async (assetId: string, imageId: string) => {
    try {
      await assetApi.deleteImage(assetId, imageId);
      await refreshDetail(assetId);
    } catch {
      /* 删除失败静默 */
    }
  };

  const toggleAssociate = async (asset: Asset) => {
    if (!selectedSceneId) return;
    const next = new Set(sceneAssetIds);
    if (next.has(asset.id)) next.delete(asset.id); else next.add(asset.id);
    setSceneAssetIds(next);
    try {
      await assetApi.setSceneAssets(selectedSceneId, Array.from(next));
    } catch {
      /* 关联失败静默 */
    }
  };

  const carouselItems = (a: Asset): CarouselItem[] =>
    a.images.map((img, i) => ({
      id: i,
      title: `图 ${i + 1}`,
      content: (
        <img
          src={assetUrl(img.url)}
          alt={`${a.name} 图${i + 1}`}
          style={{ width: '100%', height: 220, objectFit: 'contain', borderRadius: 'var(--rounded-md)' }}
        />
      ),
    }));

  return (
    <div ref={overlayRef} style={overlayStyle} onClick={onClose}>
      <div ref={panelRef} style={panelStyle} onClick={(e) => e.stopPropagation()}>
        {/* 头部 */}
        <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--color-hairline)', display: 'flex', alignItems: 'center', gap: 12 }}>
          <h2 style={{ margin: 0, font: 'var(--text-title-md)', color: 'var(--color-ink)', flex: 1 }}>AI 资产库</h2>
          {['all', 'character', 'prop', 'scene'].map((t) => (
            <button
              key={t}
              onClick={() => setActiveType(t as 'all' | AssetType)}
              style={{
                ...ghostBtnStyle,
                background: activeType === t ? 'var(--color-surface-card)' : 'white',
                color: activeType === t ? 'var(--color-ink)' : 'var(--color-muted)',
                fontWeight: activeType === t ? 600 : 400,
              }}
            >
              {t === 'all' ? '全部' : TYPE_LABEL[t as AssetType]}
            </button>
          ))}
          <button style={primaryBtnStyle} onClick={() => setShowCreate((v) => !v)}>＋ 新建资产</button>
          <button style={{ ...ghostBtnStyle, border: 'none', fontSize: 16 }} onClick={onClose}>✕</button>
        </div>

        {/* 新建资产表单 */}
        {showCreate && (
          <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--color-hairline)', background: 'var(--color-surface-card)' }}>
            <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
              {TYPES.map((t) => (
                <button
                  key={t.value}
                  onClick={() => setCreateType(t.value)}
                  style={{
                    ...ghostBtnStyle,
                    background: createType === t.value ? 'var(--color-primary)' : 'white',
                    color: createType === t.value ? 'white' : 'var(--color-muted)',
                    borderColor: createType === t.value ? 'var(--color-primary)' : 'var(--color-hairline)',
                  }}
                >
                  {t.label}
                </button>
              ))}
              <label style={{ font: 'var(--text-caption)', color: 'var(--color-muted)', display: 'flex', alignItems: 'center', gap: 4 }}>
                <input type="checkbox" checked={createGlobal} onChange={(e) => setCreateGlobal(e.target.checked)} />
                全局（跨项目复用）
              </label>
            </div>
            <div style={{ display: 'flex', gap: 10, marginTop: 10 }}>
              <input style={{ ...inputStyle, maxWidth: 200 }} placeholder="名称（如 阿伟）" value={createName} onChange={(e) => setCreateName(e.target.value)} />
              <input style={{ ...inputStyle, flex: 1 }} placeholder="文字约束（外貌/外观/构成描述，生成时注入）" value={createDesc} onChange={(e) => setCreateDesc(e.target.value)} />
              <button style={primaryBtnStyle} disabled={!createName.trim()} onClick={handleCreate}>创建</button>
            </div>
          </div>
        )}

        {/* 资产网格 */}
        <div style={{ flex: 1, overflowY: 'auto', padding: 16, display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 14, alignContent: 'start' }}>
          {loading && <p style={{ color: 'var(--color-muted-soft)', font: 'var(--text-body-sm)' }}>加载中…</p>}
          {!loading && filtered.length === 0 && (
            <p style={{ color: 'var(--color-muted-soft)', font: 'var(--text-body-sm)', gridColumn: '1 / -1', textAlign: 'center', padding: 48 }}>
              暂无资产，点「＋ 新建资产」创建人物/道具/场景卡
            </p>
          )}
          {filtered.map((a) => {
            const cover = a.images[0];
            const linked = selectedSceneId ? sceneAssetIds.has(a.id) : false;
            return (
              <div
                key={a.id}
                style={{
                  background: 'white', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-lg)',
                  overflow: 'hidden', display: 'flex', flexDirection: 'column',
                }}
              >
                <div
                  onClick={() => setDetail(a)}
                  style={{ cursor: 'pointer', height: 120, background: 'var(--color-surface-card)', display: 'flex', alignItems: 'center', justifyContent: 'center', position: 'relative' }}
                >
                  {cover ? (
                    <img src={assetUrl(cover.url)} alt={a.name} style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
                  ) : (
                    <span style={{ color: 'var(--color-muted-soft)', font: 'var(--text-caption)' }}>无图</span>
                  )}
                  {a.images.length > 1 && (
                    <span style={{ position: 'absolute', top: 6, right: 6, background: 'rgba(20,20,19,0.65)', color: 'white', fontSize: 11, padding: '2px 6px', borderRadius: 999 }}>
                      {a.images.length} 张
                    </span>
                  )}
                </div>
                <div style={{ padding: '8px 10px', flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <span style={{ font: 'var(--text-caption)', fontWeight: 600, color: 'var(--color-ink)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.name}</span>
                    <span style={{ fontSize: 10, color: 'var(--color-primary)', background: 'var(--color-surface-card)', padding: '2px 6px', borderRadius: 999, whiteSpace: 'nowrap' }}>
                      {TYPE_LABEL[a.type]}{a.projectId ? '' : ' · 全局'}
                    </span>
                  </div>
                  <p style={{ margin: '4px 0 0', font: 'var(--text-caption)', color: 'var(--color-muted)', fontSize: 12, lineHeight: 1.4, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                    {a.description || '（无文字约束）'}
                  </p>
                </div>
                <div style={{ display: 'flex', gap: 6, padding: '8px 10px', borderTop: '1px solid var(--color-hairline-soft)' }}>
                  <label style={{ ...ghostBtnStyle, flex: 1, textAlign: 'center', cursor: 'pointer', margin: 0 }}>
                    📷 传图
                    <input type="file" accept="image/*" multiple hidden onChange={(e) => { void handleUpload(a.id, e.target.files); e.target.value = ''; }} />
                  </label>
                  <button
                    style={{ ...ghostBtnStyle, flex: 1, borderColor: selectedSceneId ? (linked ? 'var(--color-primary)' : 'var(--color-hairline)') : 'var(--color-hairline)', color: linked ? 'var(--color-primary)' : 'var(--color-muted)', background: linked ? 'var(--color-surface-card)' : 'white' }}
                    onClick={() => toggleAssociate(a)}
                    disabled={!selectedSceneId}
                    title={selectedSceneId ? '关联到当前分镜' : '先在预览面板选中一个分镜'}
                  >
                    {linked ? '✓ 已关联' : '关联本镜'}
                  </button>
                  <button style={ghostBtnStyle} onClick={() => handleDelete(a)} title="删除资产">🗑</button>
                </div>
              </div>
            );
          })}
        </div>

        {/* 资产详情（reactbits Carousel 多图轮播） */}
        {detail && (
          <div style={{ ...overlayStyle, zIndex: 210 }} onClick={() => setDetail(null)}>
            <div
              style={{ width: 420, background: 'white', borderRadius: 'var(--rounded-lg)', padding: 20, boxShadow: '0 12px 48px rgba(20,20,19,0.25)' }}
              onClick={(e) => e.stopPropagation()}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                <h3 style={{ margin: 0, font: 'var(--text-title-md)', color: 'var(--color-ink)', flex: 1 }}>{detail.name}</h3>
                <span style={{ fontSize: 11, color: 'var(--color-primary)', background: 'var(--color-surface-card)', padding: '2px 8px', borderRadius: 999 }}>
                  {TYPE_LABEL[detail.type]}
                </span>
                <button style={{ ...ghostBtnStyle, border: 'none', fontSize: 16 }} onClick={() => setDetail(null)}>✕</button>
              </div>
              <p style={{ margin: '0 0 12px', font: 'var(--text-body-sm)', color: 'var(--color-muted)' }}>{detail.description || '（无文字约束）'}</p>
              {detail.images.length > 0 ? (
                <Carousel baseWidth={380} items={carouselItems(detail)} />
              ) : (
                <div style={{ height: 120, background: 'var(--color-surface-card)', borderRadius: 'var(--rounded-md)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--color-muted-soft)', font: 'var(--text-caption)' }}>
                  暂无图片，上传一张作为该资产参考图
                </div>
              )}
              <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
                <label style={{ ...ghostBtnStyle, cursor: 'pointer' }}>
                  📷 上传图片
                  <input type="file" accept="image/*" multiple hidden onChange={(e) => { void handleUpload(detail.id, e.target.files); e.target.value = ''; }} />
                </label>
                {detail.images.map((img) => (
                  <button key={img.id} style={ghostBtnStyle} onClick={() => handleDeleteImage(detail.id, img.id)} title="删除这张图">🗑 图</button>
                ))}
                <button style={{ ...ghostBtnStyle, color: 'var(--color-error)' }} onClick={() => handleDelete(detail)}>删除资产</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
