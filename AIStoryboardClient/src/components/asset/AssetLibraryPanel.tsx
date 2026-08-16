import { useCallback, useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import DepthCarousel from '../DepthCarousel';
import { assetApi, type Asset, type AssetType } from '../../api/assets';
import { assetUrl } from '../../config';
import { useProjectStore } from '../../stores/projectStore';
import './AssetLibraryPanel.css';

const TYPE_LABEL: Record<AssetType, string> = { character: '人物', prop: '道具', scene: '场景' };
const TYPES: { value: AssetType; label: string }[] = [
  { value: 'character', label: '人物' },
  { value: 'prop', label: '道具' },
  { value: 'scene', label: '场景' },
];

// ── 设计 token 样式（暖色编辑风） ──
const overlayStyle: React.CSSProperties = {
  position: 'fixed', inset: 0, background: 'rgba(20, 20, 19, 0.4)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
};
const panelStyle: React.CSSProperties = {
  width: 'min(980px, 94vw)', height: 'min(680px, 88vh)',
  background: 'var(--color-canvas)', borderRadius: 'var(--rounded-lg)',
  boxShadow: '0 12px 48px rgba(20, 20, 19, 0.22)', display: 'flex', flexDirection: 'column', overflow: 'hidden',
};
const inputStyle: React.CSSProperties = {
  width: '100%', padding: '8px 12px', border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)', font: 'var(--text-body-sm)', color: 'var(--color-ink)',
  outline: 'none', boxSizing: 'border-box', background: 'white',
};
const primaryBtn: React.CSSProperties = {
  padding: '8px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
  background: 'var(--color-primary)', color: 'white', font: 'var(--text-caption)', cursor: 'pointer',
};
const ghostBtn: React.CSSProperties = {
  padding: '5px 10px', border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
  background: 'white', color: 'var(--color-muted)', font: 'var(--text-caption)', cursor: 'pointer',
};

export function AssetLibraryPanel({ onClose, mode = 'manage' }: { onClose: () => void; mode?: 'manage' | 'pick' }) {
  const currentProject = useProjectStore((s) => s.currentProject);
  const selectedSceneId = useProjectStore((s) => s.selectedSceneId);

  const [assets, setAssets] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(false);
  const [typeFilter, setTypeFilter] = useState<'all' | AssetType>('all');
  const [view, setView] = useState<'list' | 'workbench'>('list');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [sceneAssetIds, setSceneAssetIds] = useState<Set<string>>(new Set());

  // 新建弹窗
  const [createOpen, setCreateOpen] = useState(false);
  const [createType, setCreateType] = useState<AssetType>('character');
  const [createName, setCreateName] = useState('');
  const [createDesc, setCreateDesc] = useState('');
  const [createGlobal, setCreateGlobal] = useState(false);
  const [createFiles, setCreateFiles] = useState<{ file: File; url: string }[]>([]);

  // 编辑弹窗
  const [editTarget, setEditTarget] = useState<Asset | null>(null);
  const [editName, setEditName] = useState('');
  const [editDesc, setEditDesc] = useState('');

  const panelRef = useRef<HTMLDivElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  const previewRef = useRef<HTMLDivElement>(null);

  const selected = assets.find((a) => a.id === selectedId) ?? null;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await assetApi.list(currentProject?.id);
      setAssets(res.data.data || []);
    } catch {
      /* 拉取失败静默 */
    }
    setLoading(false);
  }, [currentProject?.id]);

  useEffect(() => { void load(); }, [load]);

  // 当前分镜已关联资产（pick 模式勾选态）
  useEffect(() => {
    if (!selectedSceneId) { setSceneAssetIds(new Set()); return; }
    assetApi.listSceneAssets(selectedSceneId)
      .then((res) => setSceneAssetIds(new Set((res.data.data || []).map((a) => a.id))))
      .catch(() => setSceneAssetIds(new Set()));
  }, [selectedSceneId]);

  // 入场动画
  useGSAP(() => {
    gsap.fromTo(overlayRef.current, { opacity: 0 }, { opacity: 1, duration: 0.2, ease: 'power2.out' });
    gsap.fromTo(panelRef.current, { scale: 0.96, y: 16, opacity: 0 }, { scale: 1, y: 0, opacity: 1, duration: 0.34, ease: 'back.out(1.2)' });
  }, { scope: panelRef });

  // 工作台预览面板淡入
  useGSAP(() => {
    if (view === 'workbench' && previewRef.current) {
      gsap.fromTo(previewRef.current, { opacity: 0, x: 16 }, { opacity: 1, x: 0, duration: 0.3, ease: 'power2.out' });
    }
  }, { dependencies: [view], scope: panelRef });

  const filtered = typeFilter === 'all' ? assets : assets.filter((a) => a.type === typeFilter);

  const openWorkbench = (a: Asset) => { setSelectedId(a.id); setCurrentIndex(0); setView('workbench'); };

  const handleCreate = async () => {
    if (!createName.trim()) return;
    try {
      const res = await assetApi.create({
        type: createType, name: createName.trim(), description: createDesc.trim() || undefined,
        projectId: createGlobal ? null : (currentProject?.id ?? null),
      });
      const id = res.data.data?.id;
      if (id && createFiles.length > 0) {
        for (const p of createFiles) await assetApi.uploadImage(id, p.file);
      }
      createFiles.forEach((p) => URL.revokeObjectURL(p.url));
      setCreateOpen(false);
      setCreateName(''); setCreateDesc(''); setCreateFiles([]);
      await load();
    } catch {
      /* 创建失败静默 */
    }
  };

  const removeCreateFile = (i: number) => {
    const p = createFiles[i];
    if (p) URL.revokeObjectURL(p.url);
    setCreateFiles((prev) => prev.filter((_, idx) => idx !== i));
  };

  const handleEdit = async () => {
    if (!editTarget || !editName.trim()) return;
    try {
      await assetApi.update(editTarget.id, { name: editName.trim(), description: editDesc.trim() || undefined });
      setEditTarget(null);
      await load();
    } catch {
      /* 编辑失败静默 */
    }
  };

  const handleUpload = async (assetId: string, files: FileList | null) => {
    if (!files || files.length === 0) return;
    try {
      for (const f of Array.from(files)) await assetApi.uploadImage(assetId, f);
      await load();
    } catch {
      /* 上传失败静默 */
    }
  };

  const handleDelete = async (a: Asset) => {
    if (!window.confirm(`删除资产「${a.name}」？其图片与分镜关联将一并删除。`)) return;
    try {
      await assetApi.delete(a.id);
      if (selectedId === a.id) { setSelectedId(null); setView('list'); }
      await load();
    } catch {
      /* 删除失败静默 */
    }
  };

  const handleDeleteImage = async (assetId: string, imageId: string) => {
    try {
      await assetApi.deleteImage(assetId, imageId);
      const res = await assetApi.list(currentProject?.id);
      const data = res.data.data || [];
      setAssets(data);
      const updated = data.find((a) => a.id === assetId);
      setCurrentIndex((i) => Math.min(i, Math.max(0, (updated?.images.length ?? 1) - 1)));
    } catch {
      /* 删除失败静默 */
    }
  };

  const toggleAssociate = async (a: Asset) => {
    if (!selectedSceneId) return;
    const next = new Set(sceneAssetIds);
    if (next.has(a.id)) next.delete(a.id); else next.add(a.id);
    setSceneAssetIds(next);
    try {
      await assetApi.setSceneAssets(selectedSceneId, Array.from(next));
    } catch {
      /* 关联失败静默 */
    }
  };

  // ── 横向卡片（一行一个资产：图在左，名称/文字约束居中，操作在右） ──
  const renderCard = (a: Asset, compact: boolean) => {
    const cover = a.images[0];
    const linked = mode === 'pick' && sceneAssetIds.has(a.id);
    const active = a.id === selectedId;
    const thumb = compact ? 44 : 56;
    return (
      <div
        key={a.id}
        className={`asset-row${active && mode === 'manage' ? ' is-active' : ''}`}
        onClick={() => (mode === 'pick' ? toggleAssociate(a) : openWorkbench(a))}
        style={{
          display: 'flex', alignItems: 'center', gap: 12, padding: '10px 12px',
          background: linked ? 'var(--color-surface-card)' : 'white',
          border: `1px solid ${linked ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
          borderRadius: 'var(--rounded-md)', marginBottom: compact ? 8 : 0,
        }}
      >
        <div style={{ width: thumb, height: thumb, flexShrink: 0, borderRadius: 'var(--rounded-md)', background: 'var(--color-surface-card)', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', position: 'relative' }}>
          {cover ? (
            <img src={assetUrl(cover.url)} alt={a.name} style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
          ) : (
            <span style={{ color: 'var(--color-muted-soft)', font: 'var(--text-caption)' }}>无图</span>
          )}
          {linked && (
            <span style={{ position: 'absolute', top: 0, left: 0, background: 'var(--color-primary)', color: 'white', width: 18, height: 18, borderRadius: 999, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11 }}>✓</span>
          )}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ font: 'var(--text-body-sm)', fontWeight: 600, color: 'var(--color-ink)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{a.name}</span>
            <span style={{ fontSize: 10, color: 'var(--color-primary)', background: 'var(--color-surface-card)', padding: '2px 6px', borderRadius: 999, whiteSpace: 'nowrap', flexShrink: 0 }}>
              {TYPE_LABEL[a.type]}{a.projectId ? '' : '·全局'}
            </span>
            {a.images.length > 1 && <span style={{ fontSize: 10, color: 'var(--color-muted-soft)', flexShrink: 0 }}>{a.images.length} 图</span>}
          </div>
          <p style={{ margin: '2px 0 0', font: 'var(--text-caption)', color: 'var(--color-muted)', fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {a.description || '（无文字约束）'}
          </p>
        </div>
        {!compact && mode === 'manage' && (
          <div style={{ display: 'flex', gap: 6, flexShrink: 0 }} onClick={(e) => e.stopPropagation()}>
            <label style={{ ...ghostBtn, cursor: 'pointer' }}>
              📷 上传
              <input type="file" accept="image/*" multiple hidden onChange={(e) => { void handleUpload(a.id, e.target.files); e.target.value = ''; }} />
            </label>
            <button style={ghostBtn} onClick={() => { setEditTarget(a); setEditName(a.name); setEditDesc(a.description); }}>✏️ 编辑</button>
            <button style={ghostBtn} onClick={() => handleDelete(a)}>🗑 删除</button>
          </div>
        )}
      </div>
    );
  };

  const typeFilterRow = (
    <div style={{ display: 'flex', gap: 8 }}>
      {(['all', 'character', 'prop', 'scene'] as const).map((t) => (
        <button key={t} onClick={() => setTypeFilter(t)}
          style={{ ...ghostBtn, background: typeFilter === t ? 'var(--color-surface-card)' : 'white', color: typeFilter === t ? 'var(--color-ink)' : 'var(--color-muted)', fontWeight: typeFilter === t ? 600 : 400 }}>
          {t === 'all' ? '全部' : TYPE_LABEL[t]}
        </button>
      ))}
    </div>
  );

  // ── PICK 模式：仅竖向卡片网格 + 勾选切换关联 + 完成 ──
  if (mode === 'pick') {
    return (
      <div ref={overlayRef} style={overlayStyle} onClick={onClose}>
        <div ref={panelRef} style={panelStyle} onClick={(e) => e.stopPropagation()}>
          <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--color-hairline)', display: 'flex', alignItems: 'center', gap: 12 }}>
            <h2 style={{ margin: 0, font: 'var(--text-title-md)', color: 'var(--color-ink)', flex: 1 }}>选择要关联的资产</h2>
            {typeFilterRow}
            <button style={primaryBtn} onClick={onClose}>完成</button>
          </div>
          <div style={{ flex: 1, overflowY: 'auto', padding: 16, display: 'grid', gridTemplateColumns: '1fr', gap: 14, alignContent: 'start' }}>
            {loading && <p style={{ color: 'var(--color-muted-soft)', font: 'var(--text-body-sm)' }}>加载中…</p>}
            {!loading && filtered.length === 0 && (
              <p style={{ color: 'var(--color-muted-soft)', font: 'var(--text-body-sm)', gridColumn: '1 / -1', textAlign: 'center', padding: 48 }}>
                暂无资产——先到「🧩 资产库」创建人物/道具/场景卡
              </p>
            )}
            {filtered.map((a) => renderCard(a, false))}
          </div>
        </div>
      </div>
    );
  }

  // ── MANAGE 模式：列表 ↔ 工作台 ──
  return (
    <div ref={overlayRef} style={overlayStyle} onClick={onClose}>
      <div ref={panelRef} style={panelStyle} onClick={(e) => e.stopPropagation()}>
        {/* 头部 */}
        <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--color-hairline)', display: 'flex', alignItems: 'center', gap: 12 }}>
          <h2 style={{ margin: 0, font: 'var(--text-title-md)', color: 'var(--color-ink)', flex: 1 }}>AI 资产库</h2>
          {typeFilterRow}
          <button style={primaryBtn} onClick={() => setCreateOpen(true)}>＋ 新建资产</button>
          <button style={{ ...ghostBtn, border: 'none', fontSize: 16 }} onClick={onClose}>✕</button>
        </div>

        {/* 列表视图：竖向卡片网格 */}
        {view === 'list' && (
          <div style={{ flex: 1, overflowY: 'auto', padding: 16, display: 'grid', gridTemplateColumns: '1fr', gap: 14, alignContent: 'start' }}>
            {loading && <p style={{ color: 'var(--color-muted-soft)', font: 'var(--text-body-sm)' }}>加载中…</p>}
            {!loading && filtered.length === 0 && (
              <p style={{ color: 'var(--color-muted-soft)', font: 'var(--text-body-sm)', gridColumn: '1 / -1', textAlign: 'center', padding: 48 }}>
                暂无资产，点「＋ 新建资产」创建人物/道具/场景卡
              </p>
            )}
            {filtered.map((a) => renderCard(a, false))}
          </div>
        )}

        {/* 工作台视图：左列表 + 右预览 */}
        {view === 'workbench' && selected && (
          <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
            <div style={{ width: 240, flexShrink: 0, borderRight: '1px solid var(--color-hairline)', overflowY: 'auto', padding: 12 }}>
              <button style={{ ...ghostBtn, marginBottom: 10, width: '100%' }} onClick={() => setView('list')}>← 返回列表</button>
              {filtered.map((a) => renderCard(a, true))}
            </div>
            <div ref={previewRef} style={{ flex: 1, display: 'flex', flexDirection: 'column', overflowY: 'auto', padding: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
                <h3 style={{ margin: 0, font: 'var(--text-title-md)', color: 'var(--color-ink)', flex: 1 }}>{selected.name}</h3>
                <span style={{ fontSize: 11, color: 'var(--color-primary)', background: 'var(--color-surface-card)', padding: '2px 8px', borderRadius: 999 }}>
                  {TYPE_LABEL[selected.type]}{selected.projectId ? '' : ' · 全局'}
                </span>
              </div>
              <p style={{ margin: '0 0 12px', font: 'var(--text-body-sm)', color: 'var(--color-muted)' }}>{selected.description || '（无文字约束）'}</p>

              {selected.images.length > 0 ? (
                <>
                  <div style={{ height: 360, position: 'relative', flexShrink: 0 }}>
                    <DepthCarousel
                      key={selected.id}
                      items={selected.images.map((img) => ({ image: assetUrl(img.url), alt: img.fileName || img.url }))}
                      onChange={(i) => setCurrentIndex(i)}
                      depth={220} spread={90} tilt={22} perspective={1400}
                      visibleCards={4} falloff={0.2} blur={6} loop
                    />
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 12 }}>
                    <span style={{ font: 'var(--text-caption)', color: 'var(--color-muted)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      当前图片：{selected.images[currentIndex]?.fileName || '未命名'}
                    </span>
                    <label style={{ ...ghostBtn, cursor: 'pointer' }}>
                      📷 上传图片
                      <input type="file" accept="image/*" multiple hidden onChange={(e) => { void handleUpload(selected.id, e.target.files); e.target.value = ''; }} />
                    </label>
                    <button
                      style={{ ...ghostBtn, color: 'var(--color-error)' }}
                      disabled={selected.images.length === 0}
                      onClick={() => { const img = selected.images[currentIndex]; if (img) handleDeleteImage(selected.id, img.id); }}
                    >
                      删除当前图片
                    </button>
                  </div>
                </>
              ) : (
                <div style={{ height: 200, background: 'var(--color-surface-card)', borderRadius: 'var(--rounded-md)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 10, color: 'var(--color-muted-soft)', font: 'var(--text-caption)' }}>
                  暂无图片
                  <label style={{ ...ghostBtn, cursor: 'pointer' }}>
                    📷 上传一张作为参考图
                    <input type="file" accept="image/*" multiple hidden onChange={(e) => { void handleUpload(selected.id, e.target.files); e.target.value = ''; }} />
                  </label>
                </div>
              )}

              <div style={{ display: 'flex', gap: 8, marginTop: 'auto', paddingTop: 14 }}>
                <button style={ghostBtn} onClick={() => { setEditTarget(selected); setEditName(selected.name); setEditDesc(selected.description); }}>✏️ 编辑资产</button>
                <button style={{ ...ghostBtn, color: 'var(--color-error)' }} onClick={() => handleDelete(selected)}>🗑 删除资产</button>
              </div>
            </div>
          </div>
        )}

        {/* 新建资产弹窗 */}
        {createOpen && (
          <div style={{ ...overlayStyle, zIndex: 220 }} onClick={() => setCreateOpen(false)}>
            <div style={{ width: 460, background: 'white', borderRadius: 'var(--rounded-lg)', padding: 20, boxShadow: '0 12px 48px rgba(20,20,19,0.25)' }} onClick={(e) => e.stopPropagation()}>
              <h3 style={{ margin: '0 0 14px', font: 'var(--text-title-md)', color: 'var(--color-ink)' }}>新建资产</h3>
              <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
                {TYPES.map((t) => (
                  <button key={t.value} onClick={() => setCreateType(t.value)}
                    style={{ ...ghostBtn, background: createType === t.value ? 'var(--color-primary)' : 'white', color: createType === t.value ? 'white' : 'var(--color-muted)', borderColor: createType === t.value ? 'var(--color-primary)' : 'var(--color-hairline)' }}>
                    {t.label}
                  </button>
                ))}
                <label style={{ font: 'var(--text-caption)', color: 'var(--color-muted)', display: 'flex', alignItems: 'center', gap: 4, marginLeft: 'auto' }}>
                  <input type="checkbox" checked={createGlobal} onChange={(e) => setCreateGlobal(e.target.checked)} />
                  全局
                </label>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                <input style={inputStyle} placeholder="名称（如 阿伟）" value={createName} onChange={(e) => setCreateName(e.target.value)} />
                <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical' }} placeholder="文字约束（外貌/外观/构成描述，生成时注入）" value={createDesc} onChange={(e) => setCreateDesc(e.target.value)} />
                <label style={{ ...ghostBtn, cursor: 'pointer', textAlign: 'center' }}>
                  📷 选择相片（可多选，可反复添加）
                  <input
                    type="file"
                    accept="image/*"
                    multiple
                    hidden
                    onChange={(e) => {
                      const files = Array.from(e.target.files || []);
                      setCreateFiles((prev) => {
                        const seen = new Set(prev.map((p) => `${p.file.name}:${p.file.size}`));
                        const added = files
                          .filter((f) => !seen.has(`${f.name}:${f.size}`))
                          .map((f) => ({ file: f, url: URL.createObjectURL(f) }));
                        return [...prev, ...added];
                      });
                      e.target.value = '';
                    }}
                  />
                </label>
                {createFiles.length > 0 && (
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    {createFiles.map((p, i) => (
                      <div key={`${p.file.name}-${i}`} style={{ position: 'relative', width: 64, height: 64, borderRadius: 8, overflow: 'hidden', border: '1px solid var(--color-hairline)', background: 'var(--color-surface-card)', flexShrink: 0 }}>
                        <img src={p.url} alt={p.file.name} style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
                        <button
                          onClick={() => removeCreateFile(i)}
                          style={{ position: 'absolute', top: 2, right: 2, width: 18, height: 18, borderRadius: 999, border: 'none', background: 'rgba(20,20,19,0.6)', color: 'white', cursor: 'pointer', fontSize: 11, lineHeight: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
                          title="移除"
                        >
                          ✕
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              <div style={{ display: 'flex', gap: 8, marginTop: 16, justifyContent: 'flex-end' }}>
                <button style={ghostBtn} onClick={() => setCreateOpen(false)}>取消</button>
                <button style={{ ...primaryBtn, opacity: createName.trim() ? 1 : 0.5 }} disabled={!createName.trim()} onClick={handleCreate}>创建</button>
              </div>
            </div>
          </div>
        )}

        {/* 编辑资产弹窗 */}
        {editTarget && (
          <div style={{ ...overlayStyle, zIndex: 220 }} onClick={() => setEditTarget(null)}>
            <div style={{ width: 420, background: 'white', borderRadius: 'var(--rounded-lg)', padding: 20, boxShadow: '0 12px 48px rgba(20,20,19,0.25)' }} onClick={(e) => e.stopPropagation()}>
              <h3 style={{ margin: '0 0 14px', font: 'var(--text-title-md)', color: 'var(--color-ink)' }}>编辑资产</h3>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                <input style={inputStyle} placeholder="名称" value={editName} onChange={(e) => setEditName(e.target.value)} />
                <textarea style={{ ...inputStyle, minHeight: 80, resize: 'vertical' }} placeholder="文字约束" value={editDesc} onChange={(e) => setEditDesc(e.target.value)} />
              </div>
              <div style={{ display: 'flex', gap: 8, marginTop: 16, justifyContent: 'flex-end' }}>
                <button style={ghostBtn} onClick={() => setEditTarget(null)}>取消</button>
                <button style={{ ...primaryBtn, opacity: editName.trim() ? 1 : 0.5 }} disabled={!editName.trim()} onClick={handleEdit}>保存</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
