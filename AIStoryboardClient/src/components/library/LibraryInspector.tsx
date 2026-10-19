import { useEffect, useState } from 'react';
import {
  Boxes,
  Download,
  Film,
  FolderPen,
  FolderMinus,
  FolderOpen,
  FolderPlus,
  FileText,
  Images,
  MessageSquare,
  Pencil,
  Sparkles,
  Trash2,
  X,
  Search,
} from 'lucide-react';
import type {
  AssetColorLabel,
  AssetFlag,
  AssetUsage,
  LibraryAsset,
  LibraryAssetDetail,
  LibraryFolderNode,
  LibraryGroupInsight,
  LibraryGroupMediaItem,
  LibraryTag,
  MetadataState,
} from '../../api/library';
import type { ProjectAssetGroupBinding } from '../../api/agent';
import { assetUrl } from '../../config';
import { authorizedFetch } from '../../lib/authSession';
import { notify } from '../../stores/toastStore';
import { Tooltip } from '../common/Tooltip';
import { ImagePreviewModal } from '../agent/ImagePreviewModal';
import { AssetIcon } from './LibraryTree';
import type { DocumentPreviewTarget } from './DocumentPreviewModal';

const METADATA_STATE_LABEL: Record<MetadataState, string> = {
  NONE: '未分析',
  AI_GENERATING: 'AI 生成中',
  AI_GENERATED: 'AI 生成',
  AI_FAILED: 'AI 分析失败',
  EDITED: '已编辑',
};

export interface LibraryInspectorProps {
  mode: 'empty' | 'folder' | 'asset' | 'selection' | 'group-media';
  folder?: LibraryFolderNode | null;
  detail?: LibraryAssetDetail | null;
  groupMedia?: LibraryGroupMediaItem | null;
  selectedAssets: LibraryAsset[];
  tags: LibraryTag[];
  projectName?: string;
  busy?: boolean;
  onEdit: (asset: LibraryAsset) => void;
  onTrash: (asset: LibraryAsset) => void;
  onPromote: (asset: LibraryAsset) => void;
  onAddToGroup: (asset: LibraryAsset) => void;
  onUseForVideo: (asset: LibraryAsset) => void;
  onOpenGroup?: (asset: LibraryAsset) => void;
  onEditGroupMedia?: (item: LibraryGroupMediaItem) => void;
  onRemoveGroupMedia?: (item: LibraryGroupMediaItem) => void;
  onToggleTag: (asset: LibraryAsset, tag: LibraryTag) => void;
  onCreateSubfolder: (folder: LibraryFolderNode) => void;
  onRenameFolder: (folder: LibraryFolderNode) => void;
  onDeleteFolder: (folder: LibraryFolderNode) => void;
  onOpenFolder: (folder: LibraryFolderNode) => void;
  onBulkTrash: () => void;
  onBulkPromote: () => void;
  onBulkMove: () => void;
  onBulkMerge: () => void;
  onRequestAiEnrichment: (asset: LibraryAsset) => void;
  onRequestFolderAiEnrichment: (folder: LibraryFolderNode) => void;
  onPreviewDocument?: (target: DocumentPreviewTarget) => void;
  /** 产出素材的来源对话标题（由页面按 conversationId 合并）。 */
  conversationTitle?: string;
  /** 从产出素材跳回 Moon 对话。 */
  onOpenConversation?: (conversationId: string) => void;
  /** 资产组被哪些对话使用 + 绑定到当前对话（只对 GROUP 素材有值）。 */
  groupUsage?: GroupUsagePanelProps;
  /** 挑片标记：星级 / 旗标 / 色标。 */
  onMark?: (asset: LibraryAsset, marks: {
    rating?: number;
    flag?: AssetFlag | null;
    colorLabel?: AssetColorLabel | null;
  }) => void;
  /** 素材使用位置；null 表示已查询但没有引用，undefined 表示尚未查询。 */
  usage?: AssetUsage | null;
  usageLoading?: boolean;
  /** 智能搜索：找相似素材。 */
  onFindSimilar?: (asset: LibraryAsset) => void;
}

/** 资产组使用情况面板的入参；项目库资产组浏览区和检查器共用。 */
export interface GroupUsagePanelProps {
  insight?: LibraryGroupInsight;
  bindings: ProjectAssetGroupBinding[];
  /** 页面已经格式化好的「最近快照」文案。 */
  lastSnapshotLabel?: string;
  /** 当前项目可选的对话（标题来自 MoonAgent）。 */
  conversations: Array<{ id: string; title: string }>;
  selectedConversationId: string;
  /** Moon 里当前打开的对话，仅用于在下拉里标「当前」。 */
  currentConversationId?: string;
  busy?: boolean;
  onSelectConversation: (conversationId: string) => void;
  onBind: () => void;
  onUnbind: (conversationId: string, title: string) => void;
  onOpenConversation: (conversationId: string) => void;
}

/** 资产组使用情况：被哪些对话绑定、快照是否过期、绑定到所选对话。 */
export function GroupUsagePanel({
  insight,
  bindings,
  lastSnapshotLabel,
  conversations,
  selectedConversationId,
  currentConversationId,
  busy,
  onSelectConversation,
  onBind,
  onUnbind,
  onOpenConversation,
}: GroupUsagePanelProps) {
  return (
    <section className="library-group-usage">
      <div className="library-group-usage__facts">
        <span><b>{bindings.length}</b> 个对话在使用</span>
        <span><b>{insight?.mediaCount ?? 0}</b> 项素材</span>
        <span>最近快照 <b>{lastSnapshotLabel || '暂无'}</b></span>
      </div>
      <div className="library-group-usage__bindings">
        {bindings.length === 0 ? (
          <span className="is-empty">还没有绑定到任何对话</span>
        ) : bindings.map((binding) => {
          const title = binding.conversationTitle || `对话 · ${binding.conversationId.slice(0, 8)}`;
          const stale = insight ? binding.groupVersion !== insight.version : false;
          return (
            <span key={binding.conversationId} className="library-group-usage__chip">
              <button type="button" title="打开对话" onClick={() => onOpenConversation(binding.conversationId)}>
                <MessageSquare size={12} /> {title}
              </button>
              {stale && <em title="绑定后资产组已更新，重新绑定可刷新快照">快照过期</em>}
              <button
                type="button"
                className="is-remove"
                aria-label={`解除绑定：${title}`}
                disabled={busy}
                onClick={() => onUnbind(binding.conversationId, title)}
              >
                <X size={11} />
              </button>
            </span>
          );
        })}
      </div>
      {conversations.length === 0 ? (
        <span className="library-group-usage__hint">
          这个项目还没有对话，先到 Moon 里创建一个再绑定
        </span>
      ) : (
        <div className="library-group-usage__bind-row">
          <select
            aria-label="选择要绑定的对话"
            value={selectedConversationId}
            disabled={busy}
            onChange={(event) => onSelectConversation(event.target.value)}
          >
            {conversations.map((conversation) => (
              <option key={conversation.id} value={conversation.id}>
                {conversation.title}{conversation.id === currentConversationId ? '（当前）' : ''}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="library-group-usage__bind"
            disabled={!selectedConversationId || busy}
            title="把该资产组绑定到所选 Moon 对话"
            onClick={onBind}
          >
            <Boxes size={13} /> 绑定到所选对话
          </button>
        </div>
      )}
    </section>
  );
}

export function LibraryInspector(props: LibraryInspectorProps) {
  if (props.mode === 'folder' && props.folder) return <FolderInspector {...props} folder={props.folder} />;
  if (props.mode === 'selection') return <SelectionInspector {...props} />;
  if (props.mode === 'group-media' && props.groupMedia) {
    return <GroupMediaInspector {...props} groupMedia={props.groupMedia} />;
  }
  if (props.mode === 'asset' && props.detail) return <AssetInspector {...props} detail={props.detail} />;
  return (
    <aside className="library-inspector is-empty">
      <span className="library-inspector__empty-icon"><Images size={24} /></span>
      <h2>项目素材工作台</h2>
      <p>选择目录查看统计，或选择素材查看预览、标签和批量操作。</p>
    </aside>
  );
}

function FolderInspector({
  folder,
  busy,
  onCreateSubfolder,
  onRenameFolder,
  onDeleteFolder,
  onOpenFolder,
  onRequestFolderAiEnrichment,
}: LibraryInspectorProps & { folder: LibraryFolderNode }) {
  return (
    <aside className="library-inspector">
      <span className="library-inspector__eyebrow">DIRECTORY</span>
      <div className="library-inspector__title">
        <span className="library-inspector__icon"><FolderOpen size={18} /></span>
        <div><h2>{folder.name}</h2><p>目录版本 v{folder.version} · 双击卡片或点击下方按钮进入</p></div>
      </div>
      <div className="library-inspector__stats">
        <span><strong>{folder.directAssetCount}</strong><small>直接素材</small></span>
        <span><strong>{folder.descendantAssetCount}</strong><small>全部素材</small></span>
      </div>
      <div className="library-inspector__actions">
        <button
          className="is-ai"
          onClick={() => onRequestFolderAiEnrichment(folder)}
          disabled={busy}
        >
          <Sparkles size={15} /> AI 完善文件夹信息
        </button>
        <button onClick={() => onOpenFolder(folder)}><FolderOpen size={15} /> 打开文件夹</button>
        <button onClick={() => onCreateSubfolder(folder)}><FolderPlus size={15} /> 新建子目录</button>
        <button onClick={() => onRenameFolder(folder)}><FolderPen size={15} /> 重命名</button>
        <Tooltip label="连同子目录一起删除">
          <button
            className="is-danger"
            aria-label="递归删除文件夹"
            onClick={() => onDeleteFolder(folder)}
          >
            <Trash2 size={15} /> 删除文件夹
          </button>
        </Tooltip>
      </div>
    </aside>
  );
}

function AssetInspector({
  detail,
  tags,
  projectName,
  busy,
  onEdit,
  onTrash,
  onPromote,
  onAddToGroup,
  onUseForVideo,
  onOpenGroup,
  onToggleTag,
  onRequestAiEnrichment,
  onPreviewDocument,
  conversationTitle,
  onOpenConversation,
  groupUsage,
  onMark,
  usage,
  usageLoading,
  onFindSimilar,
}: LibraryInspectorProps & { detail: LibraryAssetDetail }) {
  const { asset, media } = detail;
  const [tagEditing, setTagEditing] = useState(false);
  const aiGenerating = asset.metadataState === 'AI_GENERATING';
  const canEnrich = asset.type === 'IMAGE' && asset.status === 'ACTIVE';
  const primary = media.find((item) => item.primary) || media[0];
  const primaryUrl = primary ? assetUrl(primary.contentUrl) : '';
  const isMedia = asset.type === 'IMAGE' || asset.type === 'VIDEO' || asset.type === 'AUDIO';
  const isAttachment = asset.type === 'DOCUMENT' || asset.type === 'OTHER';
  const isDocument = asset.type === 'DOCUMENT';
  const documentTarget: DocumentPreviewTarget | null = primary && isDocument
    ? {
        url: primary.contentUrl,
        name: primary.fileName || asset.name,
        mimeType: primary.mimeType,
      }
    : null;
  const description = (asset.metadataState === 'EDITED'
    ? asset.description || asset.aiDescription
    : asset.aiDescription || asset.description)
    || (aiGenerating && canEnrich
      ? 'AI 正在完善这张素材的描述与标签。'
      : '暂无描述');

  useEffect(() => {
    setTagEditing(false);
  }, [asset.id]);

  return (
    <aside className="library-inspector">
      <span className="library-inspector__eyebrow">{asset.type}</span>
      <div className="library-inspector__title">
        <span className="library-inspector__icon"><AssetIcon type={asset.type} /></span>
        <div><h2>{asset.name}</h2><p>{projectName || '项目库'}</p></div>
      </div>
      <div className="library-inspector__preview">
        {isDocument ? (
          <button
            type="button"
            className="library-inspector__document-card"
            disabled={!documentTarget}
            onClick={() => documentTarget && onPreviewDocument?.(documentTarget)}
          >
            <FileText size={34} strokeWidth={1.6} />
            <strong title={primary?.fileName || asset.name}>{primary?.fileName || asset.name}</strong>
            <span>{primary?.mimeType || '文档'}</span>
            <em>点击放大预览</em>
          </button>
        ) : primary?.type === 'VIDEO' ? (
          <video src={primaryUrl} controls preload="metadata" draggable={false} />
        ) : primary?.type === 'AUDIO' ? (
          <audio src={primaryUrl} controls preload="metadata" />
        ) : asset.type === 'OTHER' ? (
          <span><AssetIcon type={asset.type} /></span>
        ) : primary ? (
          <AuthenticatedPreviewImage src={primaryUrl} alt={asset.name} />
        ) : (
          <span><AssetIcon type={asset.type} /></span>
        )}
      </div>
      <p className="library-inspector__description">
        {description}
      </p>
      <span className={`library-inspector__metadata is-${asset.metadataState.toLowerCase()}`}>
        {METADATA_STATE_LABEL[asset.metadataState]}
      </span>
      <div className="library-inspector__facts">
        <span>版本 <b>v{asset.version}</b></span>
        <span>媒体 <b>{media.length}</b></span>
      </div>
      {onMark && asset.type !== 'GROUP' && (
        <section className="library-inspector__marks">
          <h3>挑片</h3>
          <div className="library-marks__rating" role="group" aria-label="星级">
            {[1, 2, 3, 4, 5].map((value) => (
              <button
                key={value}
                type="button"
                className={value <= (asset.rating ?? 0) ? 'is-on' : ''}
                aria-label={`${value} 星`}
                onClick={() => onMark(asset, {
                  rating: value,
                  flag: asset.flag ?? null,
                  colorLabel: asset.colorLabel ?? null,
                })}
              >
                ★
              </button>
            ))}
            <button
              type="button"
              className="is-clear"
              onClick={() => onMark(asset, {
                rating: 0,
                flag: asset.flag ?? null,
                colorLabel: asset.colorLabel ?? null,
              })}
            >
              清除
            </button>
          </div>
          <div className="library-marks__flags">
            <button
              type="button"
              className={asset.flag === 'pick' ? 'is-on is-pick' : ''}
              onClick={() => onMark(asset, {
                rating: asset.rating ?? 0,
                flag: asset.flag === 'pick' ? null : 'pick',
                colorLabel: asset.colorLabel ?? null,
              })}
            >
              已选
            </button>
            <button
              type="button"
              className={asset.flag === 'reject' ? 'is-on is-reject' : ''}
              onClick={() => onMark(asset, {
                rating: asset.rating ?? 0,
                flag: asset.flag === 'reject' ? null : 'reject',
                colorLabel: asset.colorLabel ?? null,
              })}
            >
              弃用
            </button>
          </div>
          <div className="library-marks__colors">
            {(['red', 'yellow', 'green', 'blue', 'purple'] as const).map((color) => (
              <button
                key={color}
                type="button"
                className={`is-${color}${asset.colorLabel === color ? ' is-on' : ''}`}
                aria-label={`色标 ${color}`}
                onClick={() => onMark(asset, {
                  rating: asset.rating ?? 0,
                  flag: asset.flag ?? null,
                  colorLabel: asset.colorLabel === color ? null : color,
                })}
              />
            ))}
          </div>
        </section>
      )}
      {asset.type === 'GROUP' && groupUsage && <GroupUsagePanel {...groupUsage} />}
      {onFindSimilar && asset.type !== 'GROUP' && (
        <button
          type="button"
          className="library-inspector__similar"
          onClick={() => onFindSimilar(asset)}
        >
          <Search size={13} /> 找相似
        </button>
      )}
      {usage !== undefined || usageLoading ? (
        <section className="library-inspector__usage">
          <h3>使用位置{usage ? ` · ${usage.total}` : ''}</h3>
          {usageLoading ? (
            <small>正在统计…</small>
          ) : !usage || usage.total === 0 ? (
            <small>还没有被分镜、对话、资产组或画布引用</small>
          ) : (
            <div className="library-usage-groups">
              {usage.scenes.length > 0 && (
                <div>
                  <span>分镜</span>
                  {usage.scenes.map((ref) => (
                    <em key={ref.id} title={ref.detail || undefined}>
                      {ref.title}{ref.detail ? ` · ${ref.detail}` : ''}
                    </em>
                  ))}
                </div>
              )}
              {usage.conversations.length > 0 && (
                <div>
                  <span>对话</span>
                  {usage.conversations.map((ref) => (
                    <button
                      key={ref.id}
                      type="button"
                      title="打开对话"
                      onClick={() => onOpenConversation?.(ref.id)}
                    >
                      {ref.title}
                    </button>
                  ))}
                </div>
              )}
              {usage.groups.length > 0 && (
                <div>
                  <span>资产组</span>
                  {usage.groups.map((ref) => (
                    <em key={ref.id}>{ref.title}</em>
                  ))}
                </div>
              )}
              {usage.canvases.length > 0 && (
                <div>
                  <span>画布</span>
                  {usage.canvases.map((ref) => (
                    <em key={ref.id}>{ref.title}</em>
                  ))}
                </div>
              )}
            </div>
          )}
        </section>
      ) : null}
      {asset.conversationId && (
        <button
          type="button"
          className="library-inspector__conversation"
          title="回到产出这张素材的对话"
          onClick={() => onOpenConversation?.(asset.conversationId!)}
        >
          <MessageSquare size={13} />
          <span>{conversationTitle || '产出对话'}</span>
        </button>
      )}
      <section className={`library-inspector__tags${tagEditing ? ' is-editing' : ''}`}>
        <header>
          <h3>标签</h3>
          <label className="library-inspector__tags-switch">
            <input
              type="checkbox"
              role="switch"
              checked={tagEditing}
              disabled={busy}
              onChange={(event) => setTagEditing(event.target.checked)}
            />
            <span className="library-inspector__tags-switch-track" aria-hidden="true" />
            <span className="library-inspector__tags-switch-label">
              {tagEditing ? '编辑中' : '编辑标签'}
            </span>
          </label>
        </header>
        <div>
          {tags.map((tag) => {
            const active = asset.tags.some((item) => item.id === tag.id);
            return (
              <Tooltip key={tag.id} label={tagEditing ? '点按添加或移除该标签' : '开启「编辑标签」后可调整'}>
              <button
                className={active ? 'is-active' : ''}
                disabled={busy || !tagEditing}
                aria-disabled={busy || !tagEditing}
                aria-label={tag.name}
                onClick={() => onToggleTag(asset, tag)}
              >
                {active && <Sparkles size={11} />} {tag.name}
              </button>
              </Tooltip>
            );
          })}
          {tags.length === 0 && <small>暂无标签</small>}
        </div>
      </section>
      <div className="library-inspector__actions">
        {canEnrich && (
          <Tooltip label={aiGenerating ? 'AI 正在完善这张素材' : 'AI 完善描述与标签'}>
          <button
            className="is-ai"
            onClick={() => onRequestAiEnrichment(asset)}
            disabled={busy || aiGenerating}
          >
            <Sparkles size={15} /> {aiGenerating ? 'AI 完善中…' : 'AI 文件完善信息'}
          </button>
          </Tooltip>
        )}
        <button onClick={() => onEdit(asset)} disabled={busy}><Pencil size={15} /> 编辑信息</button>
        {isAttachment && primaryUrl && (
          <a
            className="library-inspector__action-download"
            href={primaryUrl}
            download={primary?.fileName || asset.name}
            onClick={() => notify({
              title: '已开始下载',
              description: primary?.fileName || asset.name,
              variant: 'info',
            })}
          >
            <Download size={15} /> 下载文件
          </a>
        )}
        {asset.type === 'GROUP' ? (
          <>
            <button onClick={() => onOpenGroup?.(asset)} disabled={busy}><FolderOpen size={15} /> 打开资产组</button>
            <button onClick={() => onUseForVideo(asset)} disabled={busy}><Film size={15} /> 用此组生视频</button>
          </>
        ) : isMedia ? (
          <button onClick={() => onAddToGroup(asset)} disabled={busy}><Boxes size={15} /> 加入资产组</button>
        ) : null}
        {asset.scope === 'PROJECT' && (
          <button onClick={() => onPromote(asset)} disabled={busy}><Sparkles size={15} /> 提升到全局</button>
        )}
        <button className="is-danger" onClick={() => onTrash(asset)} disabled={busy}>
          <Trash2 size={15} /> 移入回收站
        </button>
      </div>
    </aside>
  );
}

export function AuthenticatedPreviewImage({
  src,
  alt,
  className = 'library-inspector__preview-button',
  onActivate,
  title,
}: {
  src: string;
  alt: string;
  className?: string;
  onActivate?: () => void;
  title?: string;
}) {
  const [objectUrl, setObjectUrl] = useState('');
  const [failed, setFailed] = useState(false);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    let disposed = false;
    let createdUrl = '';
    const controller = new AbortController();
    setObjectUrl('');
    setFailed(false);

    authorizedFetch(src, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`预览加载失败: ${response.status}`);
        return response.blob();
      })
      .then((blob) => {
        createdUrl = URL.createObjectURL(blob);
        if (disposed) URL.revokeObjectURL(createdUrl);
        else setObjectUrl(createdUrl);
      })
      .catch((error: unknown) => {
        if (!disposed && !(error instanceof DOMException && error.name === 'AbortError')) {
          setFailed(true);
        }
      });

    return () => {
      disposed = true;
      controller.abort();
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [src]);

  if (failed) return <span><AssetIcon type="IMAGE" /></span>;
  if (!objectUrl) return <span className="is-loading">正在加载预览</span>;
  return (
    <>
      <Tooltip
        className="tooltip-fill"
        label={title || (onActivate ? '单击查看详情，双击放大' : '点击放大')}
      >
      <button
        type="button"
        className={className}
        aria-label={onActivate ? '查看素材信息' : '放大查看图片'}
        onClick={() => {
          if (onActivate) onActivate();
          else setExpanded(true);
        }}
        onDoubleClick={(event) => {
          event.stopPropagation();
          setExpanded(true);
        }}
      >
        <img src={objectUrl} alt={alt} draggable={false} />
      </button>
      </Tooltip>
      {expanded && (
        <ImagePreviewModal
          url={objectUrl}
          onClose={() => setExpanded(false)}
        />
      )}
    </>
  );
}

function GroupMediaInspector({
  groupMedia,
  projectName,
  busy,
  onEditGroupMedia,
  onRemoveGroupMedia,
}: LibraryInspectorProps & { groupMedia: LibraryGroupMediaItem }) {
  const { media, assetName, assetDescription, assetTags } = groupMedia;
  const title = assetName || media.fileName || '组内素材';
  const previewUrl = assetUrl(media.contentUrl);
  return (
    <aside className="library-inspector">
      <span className="library-inspector__eyebrow">{media.type}</span>
      <div className="library-inspector__title">
        <span className="library-inspector__icon"><AssetIcon type={media.type} /></span>
        <div>
          <h2>{title}</h2>
          <p>{projectName || '项目库'} · 资产组素材</p>
        </div>
      </div>
      <div className="library-inspector__preview">
        {media.type === 'VIDEO' ? (
          <video src={previewUrl} controls preload="metadata" draggable={false} />
        ) : media.type === 'AUDIO' ? (
          <audio src={previewUrl} controls preload="metadata" />
        ) : (
          <AuthenticatedPreviewImage src={previewUrl} alt={title} />
        )}
      </div>
      <div className="library-inspector__media-facts">
        <span>文件 <b>{media.fileName || '未命名'}</b></span>
        {media.width && media.height && <span>尺寸 <b>{media.width}×{media.height}</b></span>}
        <span>大小 <b>{formatFileSize(media.sizeBytes)}</b></span>
      </div>
      <p className="library-inspector__description">
        {assetDescription || media.constraintText || '暂无图片信息'}
      </p>
      <section className="library-inspector__tags">
        <h3>标签</h3>
        <div>
          {assetTags.map((tag) => <span key={tag.id}>{tag.name}</span>)}
          {assetTags.length === 0 && <small>暂无标签</small>}
        </div>
      </section>
      <div className="library-inspector__actions">
        <button onClick={() => onEditGroupMedia?.(groupMedia)} disabled={busy}>
          <Pencil size={15} /> 编辑文件信息
        </button>
        <button
          className="is-danger"
          onClick={() => onRemoveGroupMedia?.(groupMedia)}
          disabled={busy}
        >
          <FolderMinus size={15} /> 移出资产组
        </button>
      </div>
    </aside>
  );
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function SelectionInspector({ selectedAssets, busy, onBulkTrash, onBulkPromote, onBulkMove, onBulkMerge }: LibraryInspectorProps) {
  const counts = selectedAssets.reduce<Record<string, number>>((result, asset) => {
    result[asset.type] = (result[asset.type] || 0) + 1;
    return result;
  }, {});
  return (
    <aside className="library-inspector">
      <span className="library-inspector__eyebrow">MULTI SELECTION</span>
      <div className="library-inspector__title">
        <span className="library-inspector__icon"><Images size={18} /></span>
        <div><h2>已选择 {selectedAssets.length} 项</h2><p>批量操作会一次性提交</p></div>
      </div>
      <div className="library-inspector__stats">
        <span><strong>{counts.IMAGE || 0}</strong><small>图片</small></span>
        <span><strong>{counts.VIDEO || 0}</strong><small>视频</small></span>
        <span><strong>{counts.AUDIO || 0}</strong><small>音频</small></span>
      </div>
      <div className="library-inspector__selected-list">
        {selectedAssets.slice(0, 12).map((asset) => (
          <span key={asset.id}><AssetIcon type={asset.type} /> {asset.name}</span>
        ))}
        {selectedAssets.length > 12 && <small>另有 {selectedAssets.length - 12} 项</small>}
      </div>
      <div className="library-inspector__actions">
        <button onClick={onBulkMove} disabled={busy}><FolderPen size={15} /> 移动到目录</button>
        <button onClick={onBulkMerge} disabled={busy}><Boxes size={15} /> 合为资产组</button>
        <button onClick={onBulkPromote} disabled={busy}><Sparkles size={15} /> 提升到全局</button>
        <button className="is-danger" onClick={onBulkTrash} disabled={busy}><Trash2 size={15} /> 移入回收站</button>
      </div>
    </aside>
  );
}
