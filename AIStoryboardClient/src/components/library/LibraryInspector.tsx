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
  Pencil,
  Sparkles,
  Trash2,
} from 'lucide-react';
import type {
  LibraryAsset,
  LibraryAssetDetail,
  LibraryFolderNode,
  LibraryGroupMediaItem,
  LibraryTag,
  MetadataState,
} from '../../api/library';
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
