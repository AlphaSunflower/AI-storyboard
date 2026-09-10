import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { assetUrl } from '../../config';
import { getSceneImageUrls } from '../../lib/sceneImages';
import { parseResultUrls } from '../../stores/generationRecordsStore';
import { ImagePreviewModal } from './ImagePreviewModal';
import { Folder, Images, Film, LayoutList, Sparkles, ChevronRight } from 'lucide-react';
import type { AgentAsset } from '../../api/agent';

type AssetsTab = 'image' | 'video' | 'storyboard';

const TABS: { key: AssetsTab; label: string; icon: React.ReactNode }[] = [
  { key: 'image', label: '图片', icon: <Images size={14} strokeWidth={1.8} /> },
  { key: 'video', label: '视频', icon: <Film size={14} strokeWidth={1.8} /> },
  { key: 'storyboard', label: '分镜列表', icon: <LayoutList size={14} strokeWidth={1.8} /> },
];

/**
 * 当前对话的产出素材弹窗（文件夹图标入口）：
 * - 分类：图片 / 视频 / 分镜列表
 * - 图片、视频为素材缩略图网格（可预览 / 删除 / 分页）；分镜列表展示当前项目分镜
 * - 打开时刷新素材列表；遮罩点击 / ✕ / ESC 关闭
 */
export function AgentAssetsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const { assets, loadAssets, deleteAsset, addRefImageUrls, refImageUrls } = useAgentStore();
  const scenes = useProjectStore((s) => s.scenes);
  const [tab, setTab] = useState<AssetsTab>('image');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewType, setPreviewType] = useState<'image' | 'video'>('image');
  const rootRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const gridRef = useRef<HTMLDivElement>(null);
  const storiesRef = useRef<HTMLDivElement>(null);

  const records = assets?.records ?? [];
  const total = assets?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / (assets?.size ?? 20)));

  const urlsFor = (a: AgentAsset): string[] => {
    const parsed = parseResultUrls(a.resultUrls ?? null);
    return parsed.length ? parsed : (a.url ? [a.url] : []);
  };

  // 当前分类下可见的素材
  const imageAssets = records.filter((a) => a.type === 'image');
  const videoAssets = records.filter((a) => a.type === 'video');
  const imageTiles = assets?.imageTotal ?? imageAssets.reduce((n, a) => n + urlsFor(a).length, 0);
  const videoTiles = assets?.videoTotal ?? videoAssets.reduce((n, a) => n + urlsFor(a).length, 0);

  // ── 弹窗入场：对话框淡入 + 上浮 ──
  useGSAP(() => {
    if (!open || !dialogRef.current) return;
    gsap.fromTo(
      dialogRef.current,
      { opacity: 0, y: 16, scale: 0.98 },
      { opacity: 1, y: 0, scale: 1, duration: 0.3, ease: 'power2.out', clearProps: 'transform' }
    );
  }, { dependencies: [open], scope: rootRef, revertOnUpdate: true });

  // ── 图片 / 视频网格：打开、翻页、切分类时 stagger 浮现 ──
  useGSAP(() => {
    if (!open || tab === 'storyboard') return;
    const grid = gridRef.current;
    if (!grid || grid.children.length === 0) return;
    const tiles = Array.from(grid.children);
    gsap.fromTo(
      tiles,
      { y: 12, opacity: 0, scale: 0.96 },
      { y: 0, opacity: 1, scale: 1, duration: 0.28, ease: 'power2.out', stagger: 0.03, clearProps: 'transform' }
    );
  }, { dependencies: [open, tab, assets?.records?.length, assets?.page], scope: rootRef, revertOnUpdate: true });

  // ── 分镜列表：切到该分类时 stagger 浮入 ──
  useGSAP(() => {
    if (!open || tab !== 'storyboard') return;
    const list = storiesRef.current;
    if (!list || list.children.length === 0) return;
    const rows = Array.from(list.children);
    gsap.fromTo(
      rows,
      { y: 10, opacity: 0 },
      { y: 0, opacity: 1, duration: 0.26, ease: 'power2.out', stagger: 0.035, clearProps: 'transform' }
    );
  }, { dependencies: [open, tab, scenes.length], scope: rootRef, revertOnUpdate: true });

  // 打开时刷新素材列表
  useEffect(() => {
    if (open) void loadAssets();
  }, [open, loadAssets]);

  // ESC 关闭
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const previewMedia = (url: string, type: 'image' | 'video') => {
    setPreviewType(type);
    setPreviewUrl(url);
  };

  const renderStoryboard = () => {
    if (scenes.length === 0) {
      return (
        <div style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14, padding: '48px 0' }}>
          当前项目暂无分镜，去对话里让 Moon 生成吧
        </div>
      );
    }
    return (
      <div ref={storiesRef} style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '2px 2px 0' }}>
          <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>共 {scenes.length} 个分镜</span>
          <button
            onClick={() => { onClose(); navigate('/editor'); }}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 4, border: 'none',
              background: 'transparent', color: 'var(--color-primary)', fontSize: 12, cursor: 'pointer',
              padding: '4px 8px', borderRadius: 6,
            }}
          ><Sparkles size={13} strokeWidth={1.8} /> 打开分镜编辑器</button>
        </div>
        {scenes.map((s) => {
          const imageUrls = getSceneImageUrls(s);
          return (
          <div
            key={s.id}
            onClick={() => { onClose(); navigate('/editor'); }}
            style={{
              display: 'flex', gap: 10, alignItems: 'center', padding: 10, borderRadius: 12,
              border: '1px solid var(--color-hairline)', background: 'white', cursor: 'pointer',
              transition: 'border-color 0.15s, box-shadow 0.15s',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.borderColor = 'var(--color-primary)'; e.currentTarget.style.boxShadow = '0 4px 14px rgba(204,120,92,0.12)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.borderColor = 'var(--color-hairline)'; e.currentTarget.style.boxShadow = ''; }}
          >
            <span style={{
              flexShrink: 0, width: 30, height: 30, borderRadius: 8, background: 'var(--color-surface-soft)',
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, fontWeight: 700,
              color: 'var(--color-primary)',
            }}>{s.sceneNumber}</span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13, color: 'var(--color-ink)', fontWeight: 500, marginBottom: 3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {s.scriptContent?.trim() || '（无描述）'}
              </div>
              <div style={{ display: 'flex', gap: 6 }}>
                <SceneStatus label="图" ok={imageUrls.length > 0} />
                <SceneStatus label="视频" ok={Boolean(s.videoUrl)} />
              </div>
            </div>
            {(imageUrls.length > 0 || s.videoUrl) && (
              <div style={{ display: 'flex', gap: 4, flexShrink: 0 }}>
                {imageUrls.slice(0, 2).map((url, index) => (
                  <Thumb key={`${url}-${index}`} url={url} type="image" onClick={() => previewMedia(url, 'image')} />
                ))}
                {imageUrls.length > 2 && <span style={{ fontSize: 11, color: 'var(--color-muted)', alignSelf: 'center' }}>+{imageUrls.length - 2}</span>}
                {s.videoUrl && <Thumb url={s.videoUrl} type="video" onClick={() => previewMedia(s.videoUrl, 'video')} />}
              </div>
            )}
            <ChevronRight size={15} strokeWidth={1.8} style={{ color: 'var(--color-muted)', flexShrink: 0 }} />
          </div>
        )})}
      </div>
    );
  };

  return (
    <>
      <div
        ref={rootRef}
        onClick={onClose}
        style={{
          position: 'fixed', inset: 0, zIndex: 300,
          background: 'rgba(20, 20, 19, 0.45)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
        }}
      >
        <div
          ref={dialogRef}
          onClick={(e) => e.stopPropagation()}
          style={{
            width: 680, maxWidth: '94vw', maxHeight: '80vh',
            background: 'white', borderRadius: 14, boxShadow: '0 12px 48px rgba(0,0,0,0.22)',
            display: 'flex', flexDirection: 'column', overflow: 'hidden',
          }}
        >
          {/* 头部 */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '18px 22px', borderBottom: '1px solid var(--color-hairline)' }}>
            <span style={{ fontSize: 17, fontWeight: 600, color: 'var(--color-ink)', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              <Folder size={15} strokeWidth={1.8} /> 产出素材
            </span>
            <button
              onClick={onClose}
              aria-label="关闭素材面板"
              style={{ width: 34, height: 34, border: 'none', borderRadius: '50%', background: 'var(--color-surface-soft)', color: 'var(--color-muted)', cursor: 'pointer', fontSize: 16, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
            >
              ✕
            </button>
          </div>

          {/* 分类 tabs */}
          <div style={{ display: 'flex', gap: 6, padding: '12px 22px 0', alignItems: 'center' }}>
            {TABS.map((t) => {
              const active = tab === t.key;
              const count = t.key === 'image' ? imageTiles : t.key === 'video' ? videoTiles : scenes.length;
              return (
                <button
                  key={t.key}
                  onClick={() => setTab(t.key)}
                  style={{
                    display: 'inline-flex', alignItems: 'center', gap: 5, border: '1px solid transparent',
                    background: active ? 'var(--color-primary)' : 'transparent', color: active ? '#fff' : 'var(--color-muted)',
                    borderRadius: 8, padding: '5px 12px', fontSize: 13, cursor: 'pointer', transition: 'background 0.15s, color 0.15s', fontWeight: 500,
                  }}
                >
                  {t.icon} {t.label}
                  <span style={{
                    fontSize: 11, fontWeight: 600, padding: '0 5px', borderRadius: 8,
                    background: active ? 'rgba(255,255,255,0.18)' : 'var(--color-surface-soft)', color: active ? '#fff' : 'var(--color-muted)',
                  }}>{count}</span>
                </button>
              );
            })}
          </div>

          {/* 内容 */}
          <div style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
            {tab === 'storyboard' ? (
              renderStoryboard()
            ) : tab === 'image' && imageTiles === 0 ? (
              <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14, marginTop: 48 }}>
                {records.length === 0 ? '暂无产出素材——生成的图片/视频会出现在这里' : '暂无图片素材'}
              </p>
            ) : tab === 'video' && videoTiles === 0 ? (
              <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14, marginTop: 48 }}>
                {records.length === 0 ? '暂无产出素材——生成的图片/视频会出现在这里' : '暂无视频素材'}
              </p>
            ) : (
              <div ref={gridRef} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(92px, 1fr))', gap: 10 }}>
                {(tab === 'image' ? imageAssets : videoAssets).map((a) => urlsFor(a).map((url, idx) => (
                  <div
                    key={`${a.id}-${idx}`}
                    onClick={() => previewMedia(url, a.type === 'video' ? 'video' : 'image')}
                    title={a.type === 'video' ? '点击预览视频' : '点击预览大图'}
                    style={{
                      position: 'relative', aspectRatio: '1', borderRadius: 10, overflow: 'hidden',
                      background: 'var(--color-surface-soft)',
                      cursor: 'zoom-in',
                    }}
                  >
                    {a.type === 'video' ? (
                      <video src={assetUrl(url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} muted />
                    ) : (
                      <img src={assetUrl(url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                    )}
                    <button
                      onClick={(e) => { e.stopPropagation(); if (window.confirm('删除该素材？')) void deleteAsset(a.id); }}
                      title="删除"
                      style={{
                        position: 'absolute', top: 4, right: 4, width: 18, height: 18,
                        border: 'none', borderRadius: '50%', background: 'rgba(198, 69, 69, 0.9)',
                        color: 'white', fontSize: 10, cursor: 'pointer', lineHeight: 1,
                      }}
                    >
                      ×
                    </button>
                    {a.type === 'image' && (
                      <button
                        onClick={(e) => { e.stopPropagation(); addRefImageUrls([url]); }}
                        disabled={refImageUrls.includes(url)}
                        title={refImageUrls.includes(url) ? '已设为参考图' : '设为下一条消息的参考图'}
                        style={{
                          position: 'absolute', bottom: 4, right: 4, padding: '3px 6px',
                          border: 'none', borderRadius: 6, fontSize: 10, lineHeight: 1,
                          background: refImageUrls.includes(url) ? 'rgba(93,184,114,0.9)' : 'rgba(204,120,92,0.92)',
                          color: 'white', cursor: refImageUrls.includes(url) ? 'default' : 'pointer',
                        }}
                      >
                        {refImageUrls.includes(url) ? '已设参考' : '设为参考'}
                      </button>
                    )}
                  </div>
                )))}
              </div>
            )}
          </div>
          {/* 分页（图片 / 视频分类共用素材分页） */}
          {tab !== 'storyboard' && totalPages > 1 && (
            <div style={{ display: 'flex', justifyContent: 'center', gap: 8, padding: '8px 0 12px', borderTop: '1px solid var(--color-hairline)' }}>
              <button disabled={(assets?.page ?? 1) <= 1} onClick={() => void loadAssets((assets?.page ?? 1) - 1)} style={{ fontSize: 13, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '4px 12px', background: 'white', cursor: 'pointer' }}>上一页</button>
              <span style={{ fontSize: 13, color: 'var(--color-muted)', alignSelf: 'center' }}>{assets?.page ?? 1} / {totalPages}</span>
              <button disabled={(assets?.page ?? 1) >= totalPages} onClick={() => void loadAssets((assets?.page ?? 1) + 1)} style={{ fontSize: 13, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '4px 12px', background: 'white', cursor: 'pointer' }}>下一页</button>
            </div>
          )}
        </div>
      </div>
      {/* 缩略图点击预览大图 */}
      <ImagePreviewModal url={previewUrl} type={previewType} onClose={() => setPreviewUrl(null)} />
    </>
  );
}

function SceneStatus({ label, ok }: { label: string; ok: boolean }) {
  return (
    <span style={{
      fontSize: 11, padding: '1px 6px', borderRadius: 6,
      border: ok ? '1px solid rgba(93,184,114,0.35)' : '1px solid var(--color-hairline)',
      background: ok ? 'rgba(93,184,114,0.1)' : 'var(--color-surface-soft)', color: ok ? '#3d9a56' : 'var(--color-muted)',
    }}>{ok ? label : `${label} 待生成`}</span>
  );
}

function Thumb({ url, type, onClick }: { url: string; type: 'image' | 'video'; onClick: () => void }) {
  return (
    <div
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      style={{ width: 44, height: 44, borderRadius: 8, overflow: 'hidden', background: 'var(--color-surface-soft)', cursor: 'zoom-in', flexShrink: 0 }}
    >
      {type === 'video' ? (
        <video src={assetUrl(url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} muted />
      ) : (
        <img src={assetUrl(url)} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
      )}
    </div>
  );
}
