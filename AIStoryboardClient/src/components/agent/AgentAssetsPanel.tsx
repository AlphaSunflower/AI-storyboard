import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { assetUrl } from '../../config';
import { getSceneImageUrls } from '../../lib/sceneImages';
import { ImagePreviewModal } from './ImagePreviewModal';
import { Film, Folder, Image as ImageIcon, Images, LayoutList, Play, Sparkles, X } from 'lucide-react';
import { assetUrls, getAgentAssetCounts, type AssetsTab } from './assetBrowserModel';
import './AgentAssetsPanel.css';

const ASSET_TABS: { key: AssetsTab; label: string; icon: React.ReactNode }[] = [
  { key: 'image', label: '图片', icon: <Images size={14} strokeWidth={1.8} /> },
  { key: 'video', label: '视频', icon: <Film size={14} strokeWidth={1.8} /> },
  { key: 'storyboard', label: '分镜', icon: <LayoutList size={14} strokeWidth={1.8} /> },
];

interface AgentAssetBrowserProps {
  tab: AssetsTab;
  active: boolean;
  onOpenEditor: () => void;
  conversationId?: string | null;
}

/**
 * 素材浏览内容：供产出素材弹窗和生成中心复用。
 * 只负责当前分类的内容、动画和预览，分类切换由外层容器控制。
 */
export function AgentAssetBrowser({ tab, active, onOpenEditor, conversationId }: AgentAssetBrowserProps) {
  const { assets, loadAssets, addRefImageUrls, refImageUrls } = useAgentStore();
  const scenes = useProjectStore((state) => state.scenes);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewType, setPreviewType] = useState<'image' | 'video'>('image');
  const rootRef = useRef<HTMLDivElement>(null);
  const gridRef = useRef<HTMLDivElement>(null);
  const storiesRef = useRef<HTMLDivElement>(null);

  const records = assets?.records ?? [];
  const total = assets?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / (assets?.size ?? 20)));
  const imageAssets = records.filter((asset) => asset.type === 'image');
  const videoAssets = records.filter((asset) => asset.type === 'video');
  const counts = getAgentAssetCounts(assets);
  const conversationScenes = conversationId === undefined
    ? scenes
    : scenes.filter((scene) => scene.conversationId === conversationId);

  useGSAP(() => {
    if (!active || tab === 'storyboard') return;
    const grid = gridRef.current;
    if (!grid || grid.children.length === 0) return;
    gsap.fromTo(
      Array.from(grid.children),
      { y: 12, opacity: 0, scale: 0.96 },
      { y: 0, opacity: 1, scale: 1, duration: 0.28, ease: 'power2.out', stagger: 0.03, clearProps: 'transform' }
    );
  }, { dependencies: [active, tab, assets?.records?.length, assets?.page], scope: rootRef, revertOnUpdate: true });

  useGSAP(() => {
    if (!active || tab !== 'storyboard') return;
    const list = storiesRef.current;
    if (!list || list.children.length === 0) return;
    gsap.fromTo(
      Array.from(list.children),
      { y: 10, opacity: 0 },
      { y: 0, opacity: 1, duration: 0.26, ease: 'power2.out', stagger: 0.035, clearProps: 'transform' }
    );
  }, { dependencies: [active, tab, conversationScenes.length], scope: rootRef, revertOnUpdate: true });

  useEffect(() => {
    if (active) void loadAssets(1);
  }, [active, loadAssets]);

  const previewMedia = (url: string, type: 'image' | 'video') => {
    setPreviewType(type);
    setPreviewUrl(url);
  };

  const renderStoryboard = () => {
    if (conversationScenes.length === 0) {
      return (
        <div style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14, padding: '48px 0' }}>
          当前对话暂无分镜，去对话里让 Moon 生成吧
        </div>
      );
    }
    return (
      <div ref={storiesRef} className="agent-storyboard-list">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '2px 2px 0' }}>
          <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>当前对话共 {conversationScenes.length} 个分镜</span>
          <button
            type="button"
            onClick={onOpenEditor}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 4, border: 'none',
              background: 'transparent', color: 'var(--color-primary)', fontSize: 12, cursor: 'pointer',
              padding: '4px 8px', borderRadius: 6,
            }}
          >
            <Sparkles size={13} strokeWidth={1.8} /> 打开分镜编辑器
          </button>
        </div>
        {conversationScenes.map((scene) => {
          const imageUrls = getSceneImageUrls(scene);
          const primaryImage = imageUrls[0];
          const secondaryImage = imageUrls[1];
          const hasMultipleImages = imageUrls.length > 1;
          return (
            <div
              key={scene.id}
              className="agent-storyboard-card"
              onClick={onOpenEditor}
            >
              <div className={`agent-storyboard-card__media${hasMultipleImages ? ' is-multi' : ''}`}>
                {primaryImage ? (
                  <img
                    src={assetUrl(primaryImage)}
                    alt={`分镜 ${scene.sceneNumber} 画面`}
                    loading="lazy"
                    onClick={(event) => {
                      event.stopPropagation();
                      previewMedia(primaryImage, 'image');
                    }}
                  />
                ) : scene.videoUrl ? (
                  <video
                    src={assetUrl(scene.videoUrl)}
                    muted
                    playsInline
                    preload="metadata"
                    onClick={(event) => {
                      event.stopPropagation();
                      previewMedia(scene.videoUrl!, 'video');
                    }}
                  />
                ) : (
                  <span className="agent-storyboard-card__placeholder"><ImageIcon size={22} strokeWidth={1.5} /></span>
                )}
                {secondaryImage && (
                  <div className="agent-storyboard-card__second">
                    <img src={assetUrl(secondaryImage)} alt="" loading="lazy" />
                    <span className="agent-storyboard-card__more" aria-label={`还有 ${imageUrls.length - 1} 张图片`}>...</span>
                  </div>
                )}
                {scene.videoUrl && (
                  <span className="agent-storyboard-card__video-badge">
                    <Play size={9} fill="currentColor" /> 视频
                  </span>
                )}
              </div>
              <div className="agent-storyboard-card__copy">
                <div>
                  <div className="agent-storyboard-card__eyebrow">分镜 {String(scene.sceneNumber).padStart(2, '0')}</div>
                  <div className="agent-storyboard-card__title">
                  {scene.scriptContent?.trim() || '（无描述）'}
                  </div>
                </div>
                <div className="agent-storyboard-card__status">
                  <SceneStatus label="图" ok={imageUrls.length > 0} />
                  <SceneStatus label="视频" ok={Boolean(scene.videoUrl)} />
                </div>
              </div>
            </div>
          );
        })}
      </div>
    );
  };

  return (
    <>
      <div ref={rootRef} className="agent-assets-browser">
        <div className="agent-assets-browser__scroll">
          {tab === 'storyboard' ? (
            renderStoryboard()
          ) : tab === 'image' && counts.image === 0 ? (
            <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14, marginTop: 48 }}>
              {records.length === 0 ? '暂无产出素材，生成的图片和视频会出现在这里' : '暂无图片素材'}
            </p>
          ) : tab === 'video' && counts.video === 0 ? (
            <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14, marginTop: 48 }}>
              {records.length === 0 ? '暂无产出素材，生成的图片和视频会出现在这里' : '暂无视频素材'}
            </p>
          ) : (
            <div ref={gridRef} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(86px, 1fr))', gap: 9 }}>
              {(tab === 'image' ? imageAssets : videoAssets).flatMap((asset) => assetUrls(asset).map((url, index) => (
                <div
                  key={`${asset.id}-${index}`}
                  onClick={() => previewMedia(url, asset.type === 'video' ? 'video' : 'image')}
                  title={asset.type === 'video' ? '点击预览视频' : '点击预览大图'}
                  style={{
                    position: 'relative', aspectRatio: '1', borderRadius: 9, overflow: 'hidden',
                    background: 'var(--color-surface-soft)', cursor: 'zoom-in',
                  }}
                >
                  {asset.type === 'video' ? (
                    <video src={assetUrl(url)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} muted />
                  ) : (
                    <img src={assetUrl(url)} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                  )}
                  {asset.type === 'image' && (
                    <button
                      type="button"
                      onClick={(event) => {
                        event.stopPropagation();
                        addRefImageUrls([url]);
                      }}
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
        {tab !== 'storyboard' && totalPages > 1 && (
          <div style={{
            display: 'flex', justifyContent: 'center', gap: 8, padding: '8px 0 10px',
            borderTop: '1px solid var(--color-hairline)',
          }}>
            <button
              type="button"
              disabled={(assets?.page ?? 1) <= 1}
              onClick={() => void loadAssets((assets?.page ?? 1) - 1)}
              style={{ fontSize: 13, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '4px 12px', background: 'white', cursor: 'pointer' }}
            >
              上一页
            </button>
            <span style={{ fontSize: 13, color: 'var(--color-muted)', alignSelf: 'center' }}>
              {assets?.page ?? 1} / {totalPages}
            </span>
            <button
              type="button"
              disabled={(assets?.page ?? 1) >= totalPages}
              onClick={() => void loadAssets((assets?.page ?? 1) + 1)}
              style={{ fontSize: 13, border: '1px solid var(--color-hairline)', borderRadius: 6, padding: '4px 12px', background: 'white', cursor: 'pointer' }}
            >
              下一页
            </button>
          </div>
        )}
      </div>
      <ImagePreviewModal url={previewUrl} type={previewType} onClose={() => setPreviewUrl(null)} />
    </>
  );
}

/**
 * 原有产出素材弹窗。聊天抽屉仍可复用，但主聊天页的入口已统一到生成中心。
 */
export function AgentAssetsModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const { assets, activeConversationId } = useAgentStore();
  const scenes = useProjectStore((state) => state.scenes);
  const [tab, setTab] = useState<AssetsTab>('image');
  const rootRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const counts = getAgentAssetCounts(assets);

  useGSAP(() => {
    if (!open || !dialogRef.current) return;
    gsap.fromTo(
      dialogRef.current,
      { opacity: 0, y: 16, scale: 0.98 },
      { opacity: 1, y: 0, scale: 1, duration: 0.3, ease: 'power2.out', clearProps: 'transform' }
    );
  }, { dependencies: [open], scope: rootRef, revertOnUpdate: true });

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const countForTab = (key: AssetsTab) => {
    if (key === 'image') return counts.image;
    if (key === 'video') return counts.video;
    return activeConversationId
      ? scenes.filter((scene) => scene.conversationId === activeConversationId).length
      : scenes.length;
  };

  return (
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
        onClick={(event) => event.stopPropagation()}
        style={{
          width: 680, maxWidth: '94vw', maxHeight: '80vh',
          background: 'white', borderRadius: 14, boxShadow: '0 12px 48px rgba(0,0,0,0.22)',
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '18px 22px', borderBottom: '1px solid var(--color-hairline)' }}>
          <span style={{ fontSize: 17, fontWeight: 600, color: 'var(--color-ink)', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <Folder size={15} strokeWidth={1.8} /> 产出素材
          </span>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭素材面板"
            style={{
              width: 34, height: 34, border: 'none', borderRadius: '50%',
              background: 'var(--color-surface-soft)', color: 'var(--color-muted)',
              cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}
          >
            <X size={16} strokeWidth={2} />
          </button>
        </div>

        <div style={{ display: 'flex', gap: 6, padding: '12px 22px 0', alignItems: 'center' }}>
          {ASSET_TABS.map((item) => {
            const active = tab === item.key;
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => setTab(item.key)}
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 5, border: '1px solid transparent',
                  background: active ? 'var(--color-primary)' : 'transparent', color: active ? '#fff' : 'var(--color-muted)',
                  borderRadius: 8, padding: '5px 12px', fontSize: 13, cursor: 'pointer',
                  transition: 'background 0.15s, color 0.15s', fontWeight: 500,
                }}
              >
                {item.icon} {item.label}
                <span style={{
                  fontSize: 11, fontWeight: 600, padding: '0 5px', borderRadius: 8,
                  background: active ? 'rgba(255,255,255,0.18)' : 'var(--color-surface-soft)',
                  color: active ? '#fff' : 'var(--color-muted)',
                }}>
                  {countForTab(item.key)}
                </span>
              </button>
            );
          })}
        </div>

        <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
          <AgentAssetBrowser
            tab={tab}
            active={open}
            onOpenEditor={() => {
              onClose();
              navigate('/editor');
            }}
          />
        </div>
      </div>
    </div>
  );
}

function SceneStatus({ label, ok }: { label: string; ok: boolean }) {
  return (
    <span style={{
      fontSize: 11, padding: '1px 6px', borderRadius: 6,
      border: ok ? '1px solid rgba(93,184,114,0.35)' : '1px solid var(--color-hairline)',
      background: ok ? 'rgba(93,184,114,0.1)' : 'var(--color-surface-soft)',
      color: ok ? '#3d9a56' : 'var(--color-muted)',
    }}>
      {ok ? label : `${label} 待生成`}
    </span>
  );
}
