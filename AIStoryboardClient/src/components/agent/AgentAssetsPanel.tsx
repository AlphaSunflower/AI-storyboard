import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import type { AgentAsset } from '../../api/agent';
import { agentApi } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { useDocumentPreviewStore } from '../../stores/documentPreviewStore';
import { assetUrl } from '../../config';
import { notify } from '../../stores/toastStore';
import { Tooltip } from '../common/Tooltip';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { ContextMenuSurface, type ContextMenuItem } from '../common/ContextMenu';
import { getSceneImageUrls } from '../../lib/sceneImages';
import { copyMediaAsset, openInCanvas, quoteAsReference } from '../../lib/chatMediaActions';
import { downloadMedia } from '../../lib/mediaDownload';
import {
  AGENT_ASSET_DRAG_MIME,
  serializeAgentAssetDragPayload,
  type AgentAssetDragKind,
} from '../../lib/agentAssetDrag';
import { ImagePreviewModal } from './ImagePreviewModal';
import { Copy, Download, FileText, Film, Folder, Frame, Image as ImageIcon, ImagePlus, Images, LayoutList, Paperclip, Play, Sparkles, Trash2, X } from 'lucide-react';
import {
  assetUrls,
  documentAttachmentForAsset,
  fileNameFromAssetUrl,
  getAgentAssetCounts,
  splitAgentAssets,
  type AgentAssetBuckets,
  type AgentAssetDetailTarget,
  type AssetsTab,
} from './assetBrowserModel';
import './AgentAssetsPanel.css';

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|avif|svg|bmp)(?:[?#]|$)/i;

function gridAssetsFor(tab: AssetsTab, buckets: AgentAssetBuckets) {
  if (tab === 'all') return buckets.all;
  if (tab === 'video') return buckets.video;
  if (tab === 'other') return buckets.other;
  return buckets.image;
}

function emptyCopyFor(tab: AssetsTab, hasAnyAsset: boolean) {
  if (hasAnyAsset) {
    if (tab === 'image') return '暂无图片素材';
    if (tab === 'video') return '暂无视频素材';
    if (tab === 'other') return '暂无其他素材';
    return '暂无产出素材';
  }
  if (tab === 'video') return '暂无产出素材，生成的视频会出现在这里';
  if (tab === 'image' || tab === 'all') return '暂无产出素材，生成的图片和视频会出现在这里';
  return '暂无产出素材，生成的文件会出现在这里';
}

interface AgentAssetBrowserProps {
  tab: AssetsTab;
  active: boolean;
  onOpenEditor: () => void;
  conversationId?: string | null;
  /**
   * 点媒体格子的去向。生成中心传进来，于是点一张素材会就地展开详情
   * （左图右信息）；不传时退回灯箱看大图，产出素材弹窗仍是老行为。
   */
  onOpenDetail?: (target: AgentAssetDetailTarget) => void;
}

/** 右键菜单的锚点与上下文：一个网格共用一个菜单实例，格子只上报坐标。 */
interface AssetMenuState {
  x: number;
  y: number;
  asset: AgentAsset;
  url: string;
  index: number;
  kind: AgentAssetDragKind;
}

/** 待确认的删除目标：按 resultIndex 只删这一张，null 表示删整条。 */
interface PendingDelete {
  assetId: string;
  resultIndex: number | null;
  name: string;
}

/**
 * 素材浏览内容：供产出素材弹窗和生成中心复用。
 * 只负责当前分类的内容、动画和预览，分类切换由外层容器控制。
 */
export function AgentAssetBrowser({
  tab,
  active,
  onOpenEditor,
  conversationId,
  onOpenDetail,
}: AgentAssetBrowserProps) {
  const { assets, loadAssets, addRefImageUrls, addPendingAttachments } = useAgentStore();
  const scenes = useProjectStore((state) => state.scenes);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewType, setPreviewType] = useState<'image' | 'video'>('image');
  const [menu, setMenu] = useState<AssetMenuState | null>(null);
  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null);
  const [deleting, setDeleting] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const gridRef = useRef<HTMLDivElement>(null);
  const storiesRef = useRef<HTMLDivElement>(null);

  const records = assets?.records ?? [];
  const total = assets?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / (assets?.size ?? 20)));
  const buckets = splitAgentAssets(assets);
  const counts = getAgentAssetCounts(assets);
  const conversationScenes = conversationId === undefined
    ? scenes
    : scenes.filter((scene) => scene.conversationId === conversationId);
  const tabCount = tab === 'storyboard' ? conversationScenes.length : counts[tab];

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

  /** 复制：图片走位图（浏览器不支持时自动退回链接），视频和文档只能复制带时效的链接。 */
  const copyAsset = (url: string, kind: AgentAssetDragKind) => {
    void copyMediaAsset(url, kind);
  };

  const setAsReference = (asset: AgentAsset, url: string, kind: AgentAssetDragKind) => {
    if (kind === 'video') {
      quoteAsReference('video', url);
      return;
    }
    if (kind === 'document') {
      addPendingAttachments([documentAttachmentForAsset(asset, url)]);
      notify({ title: '已加入输入框素材', description: fileNameFromAssetUrl(url), variant: 'info' });
      return;
    }
    addRefImageUrls([url]);
    notify({ title: '已引用为参考图', description: '已加入输入框参考素材', variant: 'info' });
  };

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    setDeleting(true);
    try {
      await agentApi.deleteAsset(pendingDelete.assetId, pendingDelete.resultIndex ?? undefined);
      notify({ title: '已删除', description: `${pendingDelete.name} 已从会话资产移除`, variant: 'success' });
      setPendingDelete(null);
      await loadAssets(assets?.page ?? 1);
    } catch (error) {
      notify({
        title: '删除失败',
        description: error instanceof Error ? error.message : '请稍后重试',
        variant: 'error',
      });
    } finally {
      setDeleting(false);
    }
  };

  const buildMenuItems = (state: AssetMenuState): ContextMenuItem[] => {
    const { asset, url, index, kind } = state;
    const name = kind === 'document' ? fileNameFromAssetUrl(url) : `这张${kind === 'video' ? '视频' : '图片'}`;
    const items: ContextMenuItem[] = [
      {
        label: '下载',
        icon: <Download size={14} strokeWidth={1.8} />,
        onClick: () => void downloadMedia(assetUrl(url), fileNameFromAssetUrl(url)),
      },
      {
        label: '复制',
        icon: <Copy size={14} strokeWidth={1.8} />,
        onClick: () => void copyAsset(url, kind),
      },
      {
        label: '设为参考',
        icon: <ImagePlus size={14} strokeWidth={1.8} />,
        onClick: () => setAsReference(asset, url, kind),
      },
    ];
    if (kind !== 'document') {
      items.push({
        label: '去画布编辑',
        icon: <Frame size={14} strokeWidth={1.8} />,
        onClick: () => openInCanvas(kind, url),
      });
    }
    items.push({
      label: '删除',
      icon: <Trash2 size={14} strokeWidth={1.8} />,
      danger: true,
      dividerBefore: true,
      onClick: () => setPendingDelete({
        assetId: asset.id,
        // 整条记录只有这一张图时直接删整条，避免留下一条空记录。
        resultIndex: assetUrls(asset).length > 1 ? index : null,
        name,
      }),
    });
    return items;
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
          ) : tabCount === 0 ? (
            <p style={{ textAlign: 'center', color: 'var(--color-muted-soft)', fontSize: 14, marginTop: 48 }}>
              {emptyCopyFor(tab, records.length > 0)}
            </p>
          ) : tab === 'document' ? (
            <div className="agent-assets-documents">
              {buckets.document.flatMap((asset) => assetUrls(asset).map((url, index) => {
                const attachment = documentAttachmentForAsset(asset, url);
                return (
                  <div
                    className="agent-asset-document"
                    key={`${asset.id}-${url}`}
                    onContextMenu={(event) => {
                      event.preventDefault();
                      setMenu({ x: event.clientX, y: event.clientY, asset, url, index, kind: 'document' });
                    }}
                  >
                    <Tooltip label="打开文档">
                    <button
                      type="button"
                      onClick={() => useDocumentPreviewStore.getState().openDocument(attachment)}
                      aria-label={`打开 ${attachment.name}`}
                    >
                      <FileText size={16} strokeWidth={1.8} />
                      <span>{attachment.name}</span>
                    </button>
                    </Tooltip>
                    <Tooltip label="下载文件">
                    <a
                      href={assetUrl(url)}
                      download={attachment.name}
                      target="_blank"
                      rel="noreferrer"
                      onClick={() => notify({ title: '已开始下载', description: attachment.name, variant: 'info' })}
                      aria-label={`下载 ${attachment.name}`}
                    >
                      <Download size={14} strokeWidth={1.8} />
                    </a>
                    </Tooltip>
                  </div>
                );
              }))}
            </div>
          ) : (
            <div ref={gridRef} className="agent-assets-grid">
              {gridAssetsFor(tab, buckets).flatMap((asset) => assetUrls(asset).map((url, index) => (
                <AssetTile
                  key={`${asset.id}-${index}`}
                  asset={asset}
                  url={url}
                  active={active}
                  onPreview={previewMedia}
                  onOpenDetail={onOpenDetail}
                  onOpenMenu={(position, kind) => {
                    setMenu({ ...position, asset, url, index, kind });
                  }}
                />
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
      {menu && (
        <ContextMenuSurface
          x={menu.x}
          y={menu.y}
          items={buildMenuItems(menu)}
          onClose={() => setMenu(null)}
        />
      )}
      <ConfirmDialog
        open={pendingDelete !== null}
        danger
        busy={deleting}
        title="删除这条素材？"
        description={pendingDelete && (
          <>
            {pendingDelete.name}将从会话资产中移除，存储里的文件会一并删除，此操作不可撤销。
            {pendingDelete.resultIndex === null && '该记录保留在生成记录中，状态标记为已删除。'}
          </>
        )}
        confirmLabel="删除"
        onConfirm={() => void confirmDelete()}
        onCancel={() => setPendingDelete(null)}
      />
    </>
  );
}

interface AssetTileProps {
  asset: AgentAsset;
  url: string;
  /** 所在面板是否可见：不可见时不拉视频元数据，避免隐藏的侧边栏偷偷发请求。 */
  active: boolean;
  onPreview: (url: string, type: 'image' | 'video') => void;
  onOpenDetail?: (target: AgentAssetDetailTarget) => void;
  onOpenMenu: (position: { x: number; y: number }, kind: AgentAssetDragKind) => void;
}

/**
 * 网格里的单个资产格子。视频出胶片首帧，图片出缩略图，文档和其他非媒体文件出图标格。
 *
 * 操作入口收敛成两条：点开看详情，右键出菜单。格子本身不再是按钮 ——
 * 小格子上塞操作按钮既挤又和右键菜单重复。
 */
function AssetTile({
  asset,
  url,
  active,
  onPreview,
  onOpenDetail,
  onOpenMenu,
}: AssetTileProps) {
  const isVideo = asset.type === 'video';
  const isDocument = asset.type === 'document';
  // 生成的图片和视频直接出缩略图；其他类型（上传参考图等）多数其实是图片，能看出来就按图片渲染。
  // 注意这里必须显式放进 isVideo：写成「非视频且非文档且像图片」，视频会被判成文件格，
  // 下面那条 <video> 分支就成了死代码，视频只能显示成一个回形针图标。
  const isMedia = asset.type === 'image' || isVideo
    || (!isDocument && IMAGE_EXT.test(url));

  if (!isMedia) {
    const attachment = documentAttachmentForAsset(asset, url);
    const kind: AgentAssetDragKind = isDocument ? 'document' : 'image';
    return (
      <button
        type="button"
        className="agent-asset-tile is-file"
        onClick={() => useDocumentPreviewStore.getState().openDocument(attachment)}
        onContextMenu={(event) => {
          event.preventDefault();
          onOpenMenu({ x: event.clientX, y: event.clientY }, kind);
        }}
        onKeyDown={(event) => {
          // 右键菜单必须能用键盘打开，否则这些操作对键盘用户完全不可达。
          if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return;
          event.preventDefault();
          const rect = event.currentTarget.getBoundingClientRect();
          onOpenMenu({ x: rect.left, y: rect.bottom }, kind);
        }}
        title={`打开 ${attachment.name}`}
      >
        {isDocument ? <FileText size={18} strokeWidth={1.8} /> : <Paperclip size={18} strokeWidth={1.8} />}
        <span>{attachment.name}</span>
      </button>
    );
  }

  const kind: AgentAssetDragKind = isVideo ? 'video' : 'image';
  const name = fileNameFromAssetUrl(url);
  /** 点开素材：宿主接了详情就进详情，没接就退回灯箱看大图。 */
  const openMedia = () => {
    if (onOpenDetail) {
      onOpenDetail({ asset, url, index: assetUrls(asset).indexOf(url), kind });
      return;
    }
    onPreview(url, isVideo ? 'video' : 'image');
  };

  return (
    <div
      className="agent-asset-tile is-media"
      role="button"
      tabIndex={0}
      draggable
      onClick={openMedia}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          openMedia();
          return;
        }
        if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return;
        event.preventDefault();
        const rect = event.currentTarget.getBoundingClientRect();
        onOpenMenu({ x: rect.left, y: rect.bottom }, kind);
      }}
      onContextMenu={(event) => {
        event.preventDefault();
        onOpenMenu({ x: event.clientX, y: event.clientY }, kind);
      }}
      onDragStart={(event) => {
        event.dataTransfer.effectAllowed = 'copy';
        event.dataTransfer.setData(
          AGENT_ASSET_DRAG_MIME,
          serializeAgentAssetDragPayload({ url, name, kind }),
        );
      }}
      title={isVideo ? '点击查看视频详情，右键更多操作' : '点击查看图片详情，右键更多操作'}
    >
      {isVideo ? (
        <video src={assetUrl(url)} muted preload={active ? 'metadata' : 'none'} />
      ) : (
        <img src={assetUrl(url)} alt="" loading="lazy" draggable={false} />
      )}
      {isVideo && (
        <span className="agent-asset-tile__badge" aria-hidden="true">
          <Play size={9} fill="currentColor" /> 视频
        </span>
      )}
    </div>
  );
}

/** 产出素材弹窗的分类，保持历史四项，不跟随生成中心的筛选扩展。 */
const ASSET_TABS: { key: AssetsTab; label: string; icon: React.ReactNode }[] = [
  { key: 'image', label: '图片', icon: <Images size={14} strokeWidth={1.8} /> },
  { key: 'video', label: '视频', icon: <Film size={14} strokeWidth={1.8} /> },
  { key: 'document', label: '文档', icon: <FileText size={14} strokeWidth={1.8} /> },
  { key: 'storyboard', label: '分镜', icon: <LayoutList size={14} strokeWidth={1.8} /> },
];

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
    if (key === 'document') return counts.document;
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
