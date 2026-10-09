import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ChevronRight, Download, FileAudio, FileText, FileVideo,
  Check, ImagePlus, Images, Library, LoaderCircle, MessageSquarePlus, Pencil, Play, Trash2,
} from 'lucide-react';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from './ImagePreviewModal';
import type { AgentAttachment, AgentMessageMetadata } from '../../api/agent';
import { hitlFromMetadata, useAgentStore } from '../../stores/agentStore';
import {
  selectAnnotationsForMessage,
  useChatAnnotationStore,
  type ChatAnnotation,
} from '../../stores/chatAnnotationStore';
import { useProjectStore } from '../../stores/projectStore';
import { findQuoteRange, selectionOccurrence, stripAnnotationContext } from '../../lib/chatAnnotation';
import { AnnotationListPopover } from './AnnotationChip';
import {
  ANNOTATION_POPOVER_WIDTH, popoverPosition, toAnchorRect, type AnchorRect,
} from './annotationPopover';
import { ReasoningPanel } from './ReasoningPanel';
import { SuggestedActions } from './SuggestedActions';
import { HumanInputCard } from './HumanInputCard';
import { VideoPlanCard } from './VideoPlanCard';
import { AgentTaskCard } from './AgentTaskCard';
import { StoryboardResultActions } from './StoryboardResultActions';
import { CanvasRunCard } from './CanvasRunCard';
import { ContextMenu } from '../common/ContextMenu';
import { DeferredExternalImage } from '../common/DeferredExternalImage';
import { importMediaToProjectLibrary } from '../../lib/projectLibraryImport';
import { isExternalImageSource } from '../../lib/externalImages';
import { useAutoGrowTextarea } from '../../hooks/useAutoGrowTextarea';
import { remarkCjkStrong } from './remarkCjkStrong';
import { remarkMark } from './remarkMark';
import { remarkBreaks } from './remarkBreaks';
import {
  CHAT_MEDIA_DRAG_MIME,
  serializeDragPayload,
  type ChatMediaDragPayload,
} from '../../lib/projectLibraryDrag';
import { libraryMediaIdFromUrl } from '../../lib/libraryMedia';
import './MessageBubble.css';

type MessageMedia = { url: string; type: 'IMAGE' | 'VIDEO' };
const IMAGE_BATCH_THRESHOLD = 4;

function extractMessageMedia(content: string): MessageMedia[] {
  const normalized = content.replace(/\\n/g, '\n').replace(/\r\n/g, '\n');
  const found = new Map<string, MessageMedia>();
  for (const match of normalized.matchAll(/!\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
    found.set(match[1], { url: match[1], type: 'IMAGE' });
  }
  for (const match of normalized.matchAll(/((?:https?:\/\/|\/api\/files\/videos\/)\S+?\.(?:mp4|webm)(?:\?\S*)?)/gi)) {
    found.set(match[1], { url: match[1], type: 'VIDEO' });
  }
  for (const match of normalized.matchAll(/((?:https?:\/\/|\/)\S+?\.(?:png|jpe?g|gif|webp)(?:\?\S*)?)/gi)) {
    found.set(match[1], { url: match[1], type: 'IMAGE' });
  }
  return Array.from(found.values());
}

function fileNameFromUrl(url: string, fallback: string): string {
  try {
    const path = new URL(url, window.location.origin).pathname;
    const name = decodeURIComponent(path.split('/').filter(Boolean).at(-1) || '');
    return name || fallback;
  } catch {
    return fallback;
  }
}

function standaloneAttachmentContent(content: string): AgentAttachment | null {
  const value = content.trim();
  if (!value || /\s/.test(value)) return null;
  const isLibraryMedia = /^\/?api\/library\/media\/[^/]+\/content(?:\?.*)?$/i.test(value);
  const isImageFile = /\/api\/files\/images\/[^/?#]+(?:\?.*)?$/i.test(value)
    || /\.(?:png|jpe?g|gif|webp)(?:\?.*)?$/i.test(value);
  if (!isLibraryMedia && !isImageFile) return null;
  return {
    url: value,
    name: isLibraryMedia ? '项目库图片' : fileNameFromUrl(value, '对话图片'),
    mimeType: 'image/*',
    kind: 'image',
    source: isLibraryMedia ? 'library' : 'upload',
    mediaId: libraryMediaIdFromUrl(value) ?? undefined,
  };
}

function messageInputAttachments(metadata?: AgentMessageMetadata): AgentAttachment[] {
  const input = metadata?.messageInput;
  if (!input) return [];
  if (input.attachments?.length) return input.attachments;
  return [
    ...(input.picUrls ?? []).map((url, index) => ({
      url,
      name: fileNameFromUrl(url, `图片 ${index + 1}`),
      mimeType: 'image/*',
      kind: 'image' as const,
      source: url.includes('/api/library/media/') ? 'library' as const : 'upload' as const,
      mediaId: libraryMediaIdFromUrl(url) ?? undefined,
    })),
    ...(input.referenceVideos ?? []).map((url, index) => ({
      url,
      name: fileNameFromUrl(url, `视频 ${index + 1}`),
      mimeType: 'video/*',
      kind: 'video' as const,
      source: url.includes('/api/library/media/') ? 'library' as const : 'upload' as const,
      mediaId: libraryMediaIdFromUrl(url) ?? undefined,
    })),
    ...(input.referenceAudios ?? []).map((url, index) => ({
      url,
      name: fileNameFromUrl(url, `音频 ${index + 1}`),
      mimeType: 'audio/*',
      kind: 'audio' as const,
      source: url.includes('/api/library/media/') ? 'library' as const : 'upload' as const,
      mediaId: libraryMediaIdFromUrl(url) ?? undefined,
    })),
  ];
}

function FileSize({ value }: { value?: number }) {
  if (!value || value <= 0) return null;
  const units = ['B', 'KB', 'MB', 'GB'];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return <small style={{ color: 'var(--color-muted)', flexShrink: 0 }}>{size.toFixed(unit === 0 ? 0 : 1)} {units[unit]}</small>;
}

function VideoAttachmentThumbnail({
  url,
  label,
  onPreview,
}: {
  url: string;
  label: string;
  onPreview: () => void;
}) {
  const [failed, setFailed] = useState(false);
  if (failed) return <FileVideo size={15} />;
  return (
    <button
      type="button"
      onClick={onPreview}
      aria-label={`预览 ${label}`}
      style={{
        position: 'relative', width: 30, height: 30, padding: 0,
        border: 'none', borderRadius: 7, overflow: 'hidden',
        background: 'rgb(24,28,36)', cursor: 'zoom-in', flexShrink: 0,
      }}
    >
      <video
        src={assetUrl(url)}
        muted
        playsInline
        preload="metadata"
        onError={() => setFailed(true)}
        style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
      />
      <span style={{
        position: 'absolute', inset: 0, display: 'grid', placeItems: 'center',
        color: 'white', background: 'rgba(15,17,21,0.24)',
      }}>
        <Play size={13} fill="currentColor" />
      </span>
    </button>
  );
}

function MessageAttachmentStrip({
  attachments,
  onPreview,
}: {
  attachments: AgentAttachment[];
  onPreview: (attachment: AgentAttachment) => void;
}) {
  if (attachments.length === 0) return null;
  return (
    <div
      className="chat-message-attachments"
      style={{
        display: 'flex', alignItems: 'center', gap: 6, maxWidth: '100%',
        overflowX: 'auto', padding: '2px 0 6px', scrollbarWidth: 'thin',
      }}
    >
      {attachments.map((attachment, index) => {
        const label = attachment.name || `附件 ${index + 1}`;
        const icon = attachment.kind === 'audio'
            ? <FileAudio size={15} />
            : attachment.kind === 'file'
              ? <FileText size={15} />
              : null;
        return (
          <span
            key={`${attachment.url}-${index}`}
            title={label}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 6, flex: '0 0 auto',
              maxWidth: 220, height: 38,
              padding: attachment.kind === 'image' || attachment.kind === 'video' ? 3 : '0 9px',
              border: '1px solid rgba(65,118,230,0.14)', borderRadius: 9,
              background: 'rgba(255,255,255,0.72)', color: 'rgb(65,118,230)',
              fontSize: 12, overflow: 'hidden',
            }}
          >
            {attachment.kind === 'image' ? (
              <button
                type="button"
                onClick={() => onPreview(attachment)}
                aria-label={`预览 ${label}`}
                style={{ width: 30, height: 30, padding: 0, border: 'none', borderRadius: 7, overflow: 'hidden', background: 'transparent', cursor: 'zoom-in', flexShrink: 0 }}
              >
                <img src={assetUrl(attachment.url)} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
              </button>
            ) : attachment.kind === 'video' ? (
              <VideoAttachmentThumbnail
                url={attachment.url}
                label={label}
                onPreview={() => onPreview(attachment)}
              />
            ) : icon}
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'rgb(55,60,70)' }}>
              {label}
            </span>
            <FileSize value={attachment.sizeBytes} />
          </span>
        );
      })}
    </div>
  );
}

function isStandaloneImageUrl(value: string): boolean {
  return /^(https?:\/\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|data:image\/[a-z+]+;base64,\S+)$/i.test(value);
}

/**
 * 批次卡片已经承载图片清单，正文只保留说明文字，避免同一批图片在消息里出现两次。
 */
function stripBatchImageMarkdown(content: string): string {
  const normalized = content.replace(/\\n/g, '\n').replace(/\r\n/g, '\n');
  return normalized
    .replace(/!\[[^\]]*\]\([^)]+\)/g, '')
    .split('\n')
    .filter((line) => {
      const trimmed = line.trim();
      return !isStandaloneImageUrl(trimmed)
        && !/^已生成\s*\d+\s*张图片$/u.test(trimmed)
        && !trimmed.includes('(async-task:');
    })
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function BatchThumbnail({ url }: { url: string }) {
  return (
    <DeferredExternalImage
      src={assetUrl(url)}
      source={url}
      alt=""
    />
  );
}

function GenerationImageBatch({
  media,
  streaming,
  onOpen,
}: {
  media: MessageMedia[];
  streaming?: boolean;
  onOpen?: () => void;
}) {
  const covers = media.slice(0, IMAGE_BATCH_THRESHOLD);
  const overflow = Math.max(0, media.length - covers.length);
  const content = (
    <>
      <span className="chat-image-batch__covers" aria-hidden="true">
        {covers.map((item, index) => (
          <span key={`${item.url}-${index}`} className="chat-image-batch__cover">
            <BatchThumbnail url={item.url} />
            {index === covers.length - 1 && overflow > 0 && (
              <span className="chat-image-batch__more">+{overflow}</span>
            )}
          </span>
        ))}
      </span>
      <span className="chat-image-batch__copy">
        <strong>{streaming ? '图片生成中' : '本批图片已生成'}</strong>
        <small>
          {streaming
            ? `已生成 ${media.length} 张，任务仍在进行`
            : `共 ${media.length} 张，集中到生成中心查看`}
        </small>
      </span>
      {onOpen && (
        <span className="chat-image-batch__action">
          <span>查看全部</span>
          <ChevronRight size={15} strokeWidth={1.9} />
        </span>
      )}
    </>
  );

  if (!onOpen) {
    return <div className="chat-image-batch is-static">{content}</div>;
  }
  return (
    <button
      type="button"
      className="chat-image-batch"
      onClick={onOpen}
      aria-label={`查看全部 ${media.length} 张生成图片`}
    >
      {content}
    </button>
  );
}

function imageExtension(url: string, mime: string): string {
  if (mime.includes('png')) return 'png';
  if (mime.includes('webp')) return 'webp';
  if (mime.includes('gif')) return 'gif';
  if (mime.includes('jpeg') || mime.includes('jpg')) return 'jpg';
  if (mime.includes('webm')) return 'webm';
  if (mime.includes('mp4')) return 'mp4';
  const matched = url.match(/\.(png|jpe?g|gif|webp)(?:[?#]|$)/i)?.[1];
  return matched?.toLowerCase().replace('jpeg', 'jpg') ?? 'png';
}

async function downloadImage(url: string, alt?: string) {
  const filenameBase = (alt?.trim() || 'chat-image')
    .replace(/[\\/:*?"<>|]+/g, '-')
    .replace(/\s+/g, '-');
  try {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const blob = await response.blob();
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = `${filenameBase || 'chat-image'}.${imageExtension(url, blob.type)}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  } catch {
    const link = document.createElement('a');
    link.href = url;
    link.download = `${filenameBase || 'chat-image'}.${imageExtension(url, '')}`;
    link.target = '_blank';
    link.rel = 'noreferrer';
    document.body.appendChild(link);
    link.click();
    link.remove();
  }
}

/**
 * 图片加载失败时降级为"（图片已过期）"占位文本：
 * 防御历史消息里残留的带签名 URL（/files/tools/ 带 timestamp+sign，过期后 403）
 * 渲染成裂图 + alt 文件名的难看效果。
 * onClick 可选：点击图片放大预览（灯箱）。
 */
function ImgWithFallback({
  src, source, alt, style, onClick, onImport, importDisabled, importing, dragPayload, deferExternalLoad = false,
}: {
  src: string; source?: string; alt?: string; style?: React.CSSProperties; onClick?: () => void;
  onImport?: () => void; importDisabled?: boolean; importing?: boolean; dragPayload?: ChatMediaDragPayload | null;
  deferExternalLoad?: boolean;
}) {
  const [failed, setFailed] = useState(false);
  const [loadRequested, setLoadRequested] = useState(() => !deferExternalLoad || !isExternalImageSource(source));
  useEffect(() => {
    setLoadRequested(!deferExternalLoad || !isExternalImageSource(source));
  }, [deferExternalLoad, source]);
  if (failed) {
    return (
      <span style={{ color: 'var(--color-muted-soft)', fontSize: 12, fontStyle: 'italic', margin: '4px 0' }}>
        （图片已过期或无法加载）
      </span>
    );
  }
  if (deferExternalLoad && isExternalImageSource(source) && !loadRequested) {
    return (
      <button
        type="button"
        aria-label="点击加载外部图片"
        onClick={() => setLoadRequested(true)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          minWidth: 180,
          minHeight: 96,
          margin: '4px 0',
          padding: '16px 18px',
          border: '1px dashed var(--color-hairline)',
          borderRadius: 8,
          background: 'var(--color-canvas)',
          color: 'var(--color-muted)',
          cursor: 'pointer',
          font: 'inherit',
          fontSize: 13,
        }}
      >
        <Images size={18} strokeWidth={1.7} />
        <span>点击加载外部图片</span>
      </button>
    );
  }
  return (
    <ContextMenu
      items={[
        {
          label: '下载',
          icon: <Download size={13} strokeWidth={1.8} />,
          onClick: () => { void downloadImage(src, alt); },
        },
        {
          label: '上传图片',
          icon: <ImagePlus size={13} strokeWidth={1.8} />,
          onClick: () => useAgentStore.getState().addRefImageUrls([source || src]),
        },
        {
          label: importing ? '正在导入素材库' : '导入素材库',
          icon: <Library size={13} strokeWidth={1.8} />,
          disabled: importDisabled || importing,
          onClick: () => onImport?.(),
        },
      ]}
    >
      <img
        src={src}
        alt={alt ?? ''}
        style={{ ...style, cursor: onClick ? 'zoom-in' : undefined }}
        onClick={onClick}
        onError={() => setFailed(true)}
        referrerPolicy="no-referrer"
        loading="lazy"
        draggable={Boolean(dragPayload)}
        onDragStart={(event) => {
          if (!dragPayload) return;
          event.dataTransfer.setData(CHAT_MEDIA_DRAG_MIME, serializeDragPayload(dragPayload));
          event.dataTransfer.effectAllowed = 'copy';
        }}
      />
    </ContextMenu>
  );
}

function VideoWithActions({
  src,
  source,
  onImport,
  importDisabled,
  importing,
  dragPayload,
}: {
  src: string;
  source: string;
  onImport?: () => void;
  importDisabled?: boolean;
  importing?: boolean;
  dragPayload?: ChatMediaDragPayload | null;
}) {
  return (
    <ContextMenu
      items={[
        {
          label: '下载',
          icon: <Download size={13} strokeWidth={1.8} />,
          onClick: () => { void downloadImage(src, 'chat-video'); },
        },
        {
          label: importing ? '正在导入素材库' : '导入素材库',
          icon: <Library size={13} strokeWidth={1.8} />,
          disabled: importDisabled || importing,
          onClick: () => onImport?.(),
        },
      ]}
    >
      <video
        src={src}
        controls
        style={{ maxWidth: '100%', maxHeight: 240, borderRadius: 8, margin: '4px 0', display: 'block' }}
        data-source={source}
        draggable={Boolean(dragPayload)}
        onDragStart={(event) => {
          if (!dragPayload) return;
          event.dataTransfer.setData(CHAT_MEDIA_DRAG_MIME, serializeDragPayload(dragPayload));
          event.dataTransfer.effectAllowed = 'copy';
        }}
      />
    </ContextMenu>
  );
}

/** 轻量渲染：**加粗**、换行、![]() 图片、[]() 链接、视频 URL 直接识别；onImgClick 为图片点击预览回调 */
function renderContent(
  content: string,
  onImgClick: (url: string) => void,
  onImportMedia?: (media: MessageMedia) => void,
  importDisabled?: boolean,
  importing?: boolean,
  dragProjectId?: string,
) {
  // LLM 输出的 JSON 字符串中 \n 可能被转义为字面 \\n，split('\n') 无法匹配；
  // 统一将字面 \\n（及 \r\n）归一为真正换行后再拆行
  const normalized = content.replace(/\\n/g, '\n').replace(/\r\n/g, '\n');
  const lines = normalized.split('\n');
  return lines.map((line, i) => {
    // 图片 ![alt](url)
    const imgMatch = line.match(/!\[([^\]]*)\]\(([^)]+)\)/);
    if (imgMatch) {
      return (
          <ImgWithFallback
          key={i}
          src={assetUrl(imgMatch[2])}
          source={imgMatch[2]}
          alt={imgMatch[1]}
          onClick={() => onImgClick(imgMatch[2])}
          onImport={() => onImportMedia?.({ url: imgMatch[2], type: 'IMAGE' })}
          importDisabled={importDisabled}
          importing={importing}
          dragPayload={dragProjectId ? {
            projectId: dragProjectId,
            mediaType: 'IMAGE',
            url: imgMatch[2],
            name: imgMatch[1] || '对话图片',
          } : null}
            style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 8, margin: '4px 0', display: 'block' }}
            deferExternalLoad
          />
      );
    }
    // 视频 URL（http(s) 绝对地址或后端相对路径 /api/files/videos/ 开头的 .mp4/.webm 直接渲染；
    // assetUrl() 对 http 原样返回、对相对路径拼 BACKEND 前缀）
    const videoMatch = line.match(/(?:https?:\/\/|\/api\/files\/videos\/)\S+\.(mp4|webm)(\?\S*)?/i);
    if (videoMatch) {
      return (
        <VideoWithActions
          key={i}
          src={assetUrl(videoMatch[0])}
          source={videoMatch[0]}
          onImport={() => onImportMedia?.({ url: videoMatch[0], type: 'VIDEO' })}
          importDisabled={importDisabled}
          importing={importing}
          dragPayload={dragProjectId ? {
            projectId: dragProjectId,
            mediaType: 'VIDEO',
            url: videoMatch[0],
            name: '对话视频',
          } : null}
        />
      );
    }
    // 裸图片 URL（整行为 .png/.jpg/.jpeg/.gif/.webp 链接或 data: URI）直接渲染——
    // 生图节点常直接输出 URL 文本而非 markdown 图片语法
    const rawImg = line.trim().match(/^(https?:\/\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|data:image\/[a-z+]+;base64,\S+)$/i);
    if (rawImg) {
      const raw = rawImg[0];
      return (
          <ImgWithFallback
          key={i}
          src={raw.startsWith('data:') || raw.startsWith('http') ? raw : assetUrl(raw)}
          source={raw}
          alt=""
          onClick={() => onImgClick(raw)}
          onImport={() => onImportMedia?.({ url: raw, type: 'IMAGE' })}
          importDisabled={importDisabled}
          importing={importing}
          dragPayload={dragProjectId ? {
            projectId: dragProjectId,
            mediaType: 'IMAGE',
            url: raw,
            name: '对话图片',
          } : null}
            style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 8, margin: '4px 0', display: 'block' }}
            deferExternalLoad
          />
      );
    }
    // 加粗
    const parts = line.split(/(\*\*[^*]+\*\*)/g).map((part, j) =>
      part.startsWith('**') && part.endsWith('**') ? (
        <strong key={j}>{part.slice(2, -2)}</strong>
      ) : (
        <span key={j}>{part}</span>
      ),
    );
    return <div key={i} style={{ marginBottom: 2 }}>{parts}</div>;
  });
}

/** Markdown 渲染（Moon 助手消息）：保留 `[链接](url)`、视频裸 URL、图片 markdown / 裸图片 URL 的原有处理。 */
function MarkdownContent({
  content,
  onImgClick,
  onImportMedia,
  importDisabled,
  importing,
  dragProjectId,
}: {
  content: string;
  onImgClick: (url: string) => void;
  onImportMedia?: (media: MessageMedia) => void;
  importDisabled?: boolean;
  importing?: boolean;
  dragProjectId?: string;
}) {
  const normalized = content.replace(/\\n/g, '\n').replace(/\r\n/g, '\n');
  const lines = normalized.split('\n');
  const nodes: React.ReactNode[] = [];
  let buf: string[] = [];
  const flush = (key: number) => {
    if (buf.length === 0) return;
    const md = buf.map((line) => {
      // 整行纯图片 URL → markdown 图片语法，交给 react-markdown 渲染
      const rawImg = line.trim().match(/^(https?:\/\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?|data:image\/[a-z+]+;base64,\S+)$/i);
      return rawImg ? `![图片](${rawImg[0]})` : line;
    }).join('\n');
    nodes.push(
      <div key={key} className="chat-markdown" style={{ wordBreak: 'break-word' }}>
        <ReactMarkdown
          remarkPlugins={[remarkGfm, remarkCjkStrong, remarkMark, remarkBreaks]}
          components={{
            table: ({ children }) => (
              <div className="chat-markdown-table-wrap">
                <table>{children}</table>
              </div>
            ),
            img: ({ src, alt }) => (
              <ImgWithFallback
                src={assetUrl(src ?? '')}
                source={src ?? ''}
                alt={alt ?? ''}
                onClick={() => onImgClick(src ?? '')}
                onImport={() => onImportMedia?.({ url: src ?? '', type: 'IMAGE' })}
                importDisabled={importDisabled}
                importing={importing}
                dragPayload={dragProjectId ? {
                  projectId: dragProjectId,
                  mediaType: 'IMAGE',
                  url: src ?? '',
                  name: alt || '对话图片',
                } : null}
                style={{ maxWidth: '100%', maxHeight: 220, borderRadius: 8, margin: '4px 0', display: 'block' }}
                deferExternalLoad
              />
            ),
            a: ({ href, children }) => <a href={href} target="_blank" rel="noreferrer">{children}</a>,
          }}
        >{md}</ReactMarkdown>
      </div>
    );
    buf = [];
  };
  lines.forEach((line, i) => {
    const videoMatch = line.trim().match(/^(?:https?:\/\/|\/api\/files\/videos\/)\S+\.(mp4|webm)(\?\S*)?$/i);
    if (videoMatch) {
      flush(i);
      nodes.push(
        <VideoWithActions
          key={i}
          src={assetUrl(videoMatch[0])}
          source={videoMatch[0]}
          onImport={() => onImportMedia?.({ url: videoMatch[0], type: 'VIDEO' })}
          importDisabled={importDisabled}
          importing={importing}
          dragPayload={dragProjectId ? {
            projectId: dragProjectId,
            mediaType: 'VIDEO',
            url: videoMatch[0],
            name: '对话视频',
          } : null}
        />
      );
    } else {
      buf.push(line);
    }
  });
  flush(lines.length);
  return <>{nodes}</>;
}

/** 消息时间格式化：今天 → HH:MM；跨天 → MM-DD HH:MM */
function formatTime(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const today = new Date();
  const sameDay = d.getFullYear() === today.getFullYear() && d.getMonth() === today.getMonth() && d.getDate() === today.getDate();
  if (sameDay) return `${hh}:${mm}`;
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hh}:${mm}`;
}

function formatTokenCount(value: number): string {
  return new Intl.NumberFormat('en-US').format(Math.max(0, value));
}

function formatElapsed(ms: number): string {
  const safeMs = Math.max(0, ms);
  if (safeMs < 1000) return `${Math.round(safeMs)}ms`;
  const seconds = safeMs / 1000;
  if (seconds < 10) return `${seconds.toFixed(1).replace(/\.0$/, '')}s`;
  return `${Math.round(seconds)}s`;
}

/**
 * Smooths tiny SSE chunks into a readable reveal. The target always stays the
 * server payload, so a faster stream only shortens the catch-up duration.
 */
function useStreamingReveal(content: string, streaming: boolean): string {
  const [displayed, setDisplayed] = useState(streaming ? '' : content);
  const displayedRef = useRef(displayed);
  const frameRef = useRef<number | null>(null);

  useEffect(() => {
    if (frameRef.current !== null) {
      window.cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
    }

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!streaming || reduced || !content.startsWith(displayedRef.current)) {
      displayedRef.current = content;
      setDisplayed(content);
      return;
    }

    const start = displayedRef.current;
    const delta = content.length - start.length;
    if (delta <= 0) return;

    const startedAt = performance.now();
    const duration = Math.min(620, Math.max(90, delta * 9));
    const tick = (now: number) => {
      const progress = Math.min(1, (now - startedAt) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      const next = content.slice(0, start.length + Math.ceil(delta * eased));
      if (next !== displayedRef.current) {
        displayedRef.current = next;
        setDisplayed(next);
      }
      if (progress < 1) {
        frameRef.current = window.requestAnimationFrame(tick);
      } else {
        frameRef.current = null;
      }
    };

    frameRef.current = window.requestAnimationFrame(tick);
    return () => {
      if (frameRef.current !== null) {
        window.cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
    };
  }, [content, streaming]);

  return streaming ? displayed : content;
}

/** CSS Custom Highlight API 支持探测（不支持时只保留消息级胶囊 + 列表）。 */
function getHighlightSupport() {
  if (typeof window === 'undefined' || typeof CSS === 'undefined') return null;
  const registry = (CSS as unknown as {
    highlights?: { set(name: string, value: unknown): void; delete(name: string): void };
  }).highlights;
  const HighlightCtor = (window as unknown as { Highlight?: new (...ranges: Range[]) => unknown }).Highlight;
  if (!registry || typeof HighlightCtor !== 'function') return null;
  return { registry, HighlightCtor };
}

/** 选中文字后先浮出操作条，点「引用」才打开评论框（避免一选中就直接弹评论）。 */
function SelectionActionToolbar({
  anchor,
  onQuote,
  onDismiss,
}: {
  anchor: AnchorRect;
  onQuote: () => void;
  onDismiss: () => void;
}) {
  const cardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onDown = (event: MouseEvent) => {
      if (cardRef.current?.contains(event.target as Node)) return;
      onDismiss();
    };
    const onScroll = () => onDismiss();
    document.addEventListener('mousedown', onDown);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [onDismiss]);

  return createPortal(
    <div
      ref={cardRef}
      data-annotation-ui
      role="toolbar"
      aria-label="选中文字操作"
      style={{
        position: 'fixed', ...popoverPosition(anchor, 180), zIndex: 2200,
        display: 'inline-flex', alignItems: 'center', padding: 4,
        background: 'white', borderRadius: 12,
        border: '1px solid rgba(0,0,0,0.08)', boxShadow: '0 8px 24px rgba(15,17,21,0.16)',
      }}
    >
      <button
        type="button"
        aria-label="引用这段内容"
        onMouseDown={(event) => event.preventDefault()}
        onClick={onQuote}
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 6,
          border: 'none', background: 'transparent', cursor: 'pointer',
          padding: '6px 10px', borderRadius: 8, fontSize: 13, fontWeight: 500,
          color: 'rgb(15, 17, 21)', whiteSpace: 'nowrap',
        }}
        onMouseEnter={(event) => { event.currentTarget.style.background = 'rgba(65, 118, 230, 0.08)'; }}
        onMouseLeave={(event) => { event.currentTarget.style.background = 'transparent'; }}
      >
        <MessageSquarePlus size={14} strokeWidth={1.9} />
        引用
      </button>
    </div>,
    document.body,
  );
}

/**
 * 注释编辑器：新建（输入 + ✓）与编辑（输入 + 删除 / 取消 / 保存）复用同一张卡。
 * 放在 body 的 portal 里，避免被消息气泡的 transform / overflow 裁剪。
 */
function AnnotationEditor({
  mode,
  initialComment,
  anchor,
  onSave,
  onDelete,
  onCancel,
}: {
  mode: 'create' | 'edit';
  initialComment: string;
  anchor: AnchorRect;
  onSave: (comment: string) => void;
  onDelete?: () => void;
  onCancel: () => void;
}) {
  const [value, setValue] = useState(initialComment);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const cardRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.setSelectionRange(initialComment.length, initialComment.length);
  }, [initialComment]);

  useEffect(() => {
    const onDown = (event: MouseEvent) => {
      if (cardRef.current?.contains(event.target as Node)) return;
      onCancel();
    };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onCancel(); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [onCancel]);

  return createPortal(
    <div
      ref={cardRef}
      data-annotation-ui
      data-annotation-editor
      role="dialog"
      aria-label={mode === 'create' ? '添加注释' : '编辑注释'}
      style={{
        position: 'fixed', ...popoverPosition(anchor), width: ANNOTATION_POPOVER_WIDTH, zIndex: 2300,
        background: 'white', borderRadius: 16, padding: 10,
        border: '1px solid rgba(0,0,0,0.08)', boxShadow: '0 12px 32px rgba(15,17,21,0.16)',
        display: 'flex', flexDirection: 'column', gap: 8,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8 }}>
        <textarea
          ref={inputRef}
          aria-label="注释内容"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing) return;
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              onSave(value);
            }
          }}
          rows={1}
          placeholder="添加可选评论..."
          style={{
            flex: 1, minWidth: 0, resize: 'none', border: 'none', outline: 'none',
            background: 'rgb(246,247,249)', borderRadius: 12, padding: '9px 12px',
            fontSize: 14, lineHeight: 1.5, color: 'rgb(15,17,21)', fontFamily: 'inherit',
          }}
        />
        {mode === 'create' && (
          <button
            type="button"
            aria-label="保存注释"
            onClick={() => onSave(value)}
            style={{
              width: 36, height: 36, flexShrink: 0, borderRadius: 12, border: 'none',
              background: 'rgb(15,17,21)', color: 'white', cursor: 'pointer',
              display: 'grid', placeItems: 'center',
            }}
          >
            <Check size={16} strokeWidth={2.4} />
          </button>
        )}
      </div>
      {mode === 'edit' && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <button
            type="button"
            aria-label="删除注释"
            onClick={onDelete}
            style={{
              border: 'none', background: 'transparent', cursor: 'pointer',
              color: 'rgb(122,127,136)', padding: 4, display: 'grid', placeItems: 'center',
            }}
          >
            <Trash2 size={16} strokeWidth={1.9} />
          </button>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <button
              type="button"
              onClick={onCancel}
              style={{
                borderRadius: 10, border: '1px solid rgba(0,0,0,0.10)', background: 'white',
                color: 'rgb(60,64,72)', fontSize: 13, padding: '7px 14px', cursor: 'pointer',
              }}
            >取消</button>
            <button
              type="button"
              onClick={() => onSave(value)}
              style={{
                borderRadius: 10, border: 'none', background: 'rgb(15,17,21)',
                color: 'white', fontSize: 13, padding: '7px 14px', cursor: 'pointer',
              }}
            >保存</button>
          </div>
        </div>
      )}
    </div>,
    document.body,
  );
}

export function MessageBubble({ messageId, conversationId, role, content, streaming, variant = 'default', createdAt, metadata, editable, onEdit, onEditMessage, onOpenGenerationCenter }: {
  messageId?: string;
  conversationId?: string;
  role: 'user' | 'assistant'; content: string; streaming?: boolean;
  variant?: 'default' | 'deepseek'; createdAt?: string; metadata?: AgentMessageMetadata;
  editable?: boolean; onEdit?: () => void;
  onEditMessage?: (messageId: string, content: string) => Promise<void>;
  onOpenGenerationCenter?: () => void;
}) {
  const isUser = role === 'user';
  const ds = variant === 'deepseek';
  const canvasRunMeta = metadata?.kind === 'canvas-run' ? metadata : null;
  const currentProject = useProjectStore((state) => state.currentProject);
  const archivedConversation = useAgentStore((state) =>
    state.conversations.find((conversation) => conversation.id === state.activeConversationId)?.status === 'archived',
  );
  // 归档 / 旧编排会话不允许新增或编辑注释
  const annotationsLocked = useAgentStore((state) => {
    const conversation = state.conversations.find((item) => item.id === state.activeConversationId);
    return conversation?.status === 'archived' || conversation?.orchestrationMode === 'LEGACY';
  });
  // 图片点击放大预览（灯箱）
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewType, setPreviewType] = useState<'image' | 'video'>('image');
  const openPreview = (url: string, type: 'image' | 'video' = 'image') => {
    setPreviewType(type);
    setPreviewUrl(url);
  };
  const [copied, setCopied] = useState(false);
  const [editing, setEditing] = useState(false);
  const [editDraft, setEditDraft] = useState(content);
  const [editSaving, setEditSaving] = useState(false);
  const [editFocused, setEditFocused] = useState(false);
  const [projectImportState, setProjectImportState] = useState<'idle' | 'importing' | 'done' | 'error'>('idle');
  const storyboardResultReady = useAgentStore((state) => {
    if (metadata?.storyboardReview?.stage === 'COMPLETED') return true;
    return Boolean(metadata?.taskCards?.some((card) => {
      const task = state.tasks[card.taskId];
      return card.kind === 'storyboard'
        && task?.status === 'completed'
        && task.stage === 'completed';
    }));
  });
  const bubbleRef = useRef<HTMLDivElement>(null);
  const editRef = useRef<HTMLTextAreaElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const rawDisplayContent = useStreamingReveal(content, role === 'assistant' && !!streaming);
  // 用户消息正文里可能带着发送时拼进去的注释上下文块，渲染时剥掉，只显示用户真正输入
  const displayContent = isUser ? stripAnnotationContext(rawDisplayContent).userText : rawDisplayContent;

  // 消息内注释（选中文字 → 评论），仅 /chat 的 deepseek 变体启用
  const annotationsVisible = ds && !!messageId;
  const annotationEditable = annotationsVisible && !editing && !annotationsLocked;
  const allAnnotations = useChatAnnotationStore((state) => state.annotations);
  const messageAnnotations = useMemo(
    () => selectAnnotationsForMessage(allAnnotations, messageId),
    [allAnnotations, messageId],
  );
  const annotationCount = messageAnnotations.length;
  const highlightName = useMemo(
    () => `chat-quote-${(messageId ?? 'draft').replace(/[^a-zA-Z0-9_-]/g, '')}`,
    [messageId],
  );
  const [draftAnchor, setDraftAnchor] = useState<{ quote: string; occurrence?: number; rect: AnchorRect } | null>(null);
  const [selectionAction, setSelectionAction] = useState<{ quote: string; occurrence?: number; rect: AnchorRect } | null>(null);
  const [listAnchor, setListAnchor] = useState<AnchorRect | null>(null);
  // 每条注释在原文里的高亮位置（视口坐标），供徽标定位与「编辑框贴着原文」使用
  const [annotationAnchors, setAnnotationAnchors] = useState<Record<string, AnchorRect>>({});
  const editingId = useChatAnnotationStore((state) => state.editingId);
  const messageMedia = extractMessageMedia(displayContent);
  const legacyAttachment = isUser ? standaloneAttachmentContent(displayContent) : null;
  const userAttachments = isUser
    ? (messageInputAttachments(metadata).length > 0
        ? messageInputAttachments(metadata)
        : legacyAttachment ? [legacyAttachment] : [])
    : [];
  const userTextContent = legacyAttachment ? '' : displayContent;
  const generatedImages = messageMedia.filter((item) => item.type === 'IMAGE');
  const imageBatch = !isUser && generatedImages.length >= IMAGE_BATCH_THRESHOLD ? generatedImages : null;
  const renderedContent = imageBatch ? stripBatchImageMarkdown(displayContent) : displayContent;
  const importDisabled = !currentProject?.id || messageMedia.length === 0;

  const clearHighlight = useCallback(() => {
    getHighlightSupport()?.registry.delete(highlightName);
  }, [highlightName]);

  // 选中气泡内文字 → 先浮出「引用」操作条，不直接弹评论
  const handleAnnotationSelection = useCallback(() => {
    if (!annotationEditable || !messageId || !conversationId) return;
    const selection = typeof window !== 'undefined' ? window.getSelection() : null;
    const root = contentRef.current;
    if (!selection || selection.isCollapsed || selection.rangeCount === 0 || !root) return;
    const range = selection.getRangeAt(0);
    if (!root.contains(range.commonAncestorContainer)) return;
    const quote = selection.toString().replace(/\s+$/, '');
    if (!quote.trim()) return;
    const rect = typeof range.getBoundingClientRect === 'function' ? range.getBoundingClientRect() : null;
    if (!rect || (!rect.width && !rect.height)) return;
    setSelectionAction({ quote, occurrence: selectionOccurrence(root, range, quote), rect: toAnchorRect(rect) });
  }, [annotationEditable, messageId, conversationId]);

  const handleStartAnnotation = useCallback(() => {
    if (!selectionAction) return;
    setDraftAnchor(selectionAction);
    setSelectionAction(null);
  }, [selectionAction]);

  const handleCreateAnnotation = useCallback((comment: string) => {
    if (!draftAnchor || !messageId || !conversationId) return;
    useChatAnnotationStore.getState().addAnnotation({
      conversationId,
      messageId,
      quote: draftAnchor.quote,
      comment,
      occurrence: draftAnchor.occurrence,
    });
    window.getSelection()?.removeAllRanges();
    setDraftAnchor(null);
  }, [draftAnchor, messageId, conversationId]);

  const openAnnotationEditor = useCallback((annotation: ChatAnnotation) => {
    useChatAnnotationStore.getState().openEditor(annotation.id);
    setListAnchor(null);
  }, []);

  // 正在编辑的注释（store 驱动：消息徽标、输入框上的胶囊列表都能触发）
  const editingAnnotation = useMemo(
    () => messageAnnotations.find((annotation) => annotation.id === editingId) ?? null,
    [messageAnnotations, editingId],
  );

  const handleSaveAnnotationComment = useCallback((comment: string) => {
    if (!editingAnnotation) return;
    useChatAnnotationStore.getState().updateComment(editingAnnotation.id, comment);
    useChatAnnotationStore.getState().closeEditor();
  }, [editingAnnotation]);

  const handleDeleteEditingAnnotation = useCallback(() => {
    if (!editingAnnotation) return;
    useChatAnnotationStore.getState().removeAnnotation(editingAnnotation.id);
    useChatAnnotationStore.getState().closeEditor();
  }, [editingAnnotation]);

  const badgeAnchor = useMemo(() => {
    const first = messageAnnotations[0];
    return first ? annotationAnchors[first.id] ?? null : null;
  }, [messageAnnotations, annotationAnchors]);

  // 原文滚出视口时收起徽标，避免它悬在顶部挡到别的消息
  const badgeVisible = useMemo(() => {
    if (!badgeAnchor) return false;
    const viewportHeight = typeof window === 'undefined' ? 0 : window.innerHeight;
    return badgeAnchor.top >= 0 && badgeAnchor.top <= viewportHeight;
  }, [badgeAnchor]);

  // 编辑框贴着被引用原文出现；原文定位不到时退回消息本体
  const editingAnchor = useMemo(() => {
    if (!editingAnnotation) return null;
    const anchored = annotationAnchors[editingAnnotation.id] ?? badgeAnchor;
    if (anchored) return anchored;
    const root = contentRef.current;
    if (!root) return null;
    const rect = root.getBoundingClientRect();
    // /chat 同时渲染手机端与桌面端布局，隐藏的那份 rect 全是 0；
    // 不能拿它当锚点，否则编辑框会跑到窗口左上角。
    if (!rect.width && !rect.height) return null;
    return toAnchorRect(rect);
  }, [editingAnnotation, annotationAnchors, badgeAnchor]);

  const handleAnnotationBadgeClick = useCallback(() => {
    if (messageAnnotations.length === 1) {
      openAnnotationEditor(messageAnnotations[0]);
      return;
    }
    if (badgeAnchor) setListAnchor(badgeAnchor);
  }, [messageAnnotations, badgeAnchor, openAnnotationEditor]);

  // 高亮 + 徽标定位：只读测量，不改 React 管理的 DOM
  useEffect(() => {
    if (!annotationsVisible || annotationCount === 0) {
      setAnnotationAnchors({});
      clearHighlight();
      return;
    }
    const root = contentRef.current;
    if (!root) return;
    let frame = 0;
    const apply = () => {
      const support = getHighlightSupport();
      const ranges: Range[] = [];
      const nextAnchors: Record<string, AnchorRect> = {};
      for (const annotation of messageAnnotations) {
        const range = findQuoteRange(root, annotation.quote, annotation.occurrence ?? 0);
        if (!range) continue;
        ranges.push(range);
        if (typeof range.getBoundingClientRect === 'function') {
          const rect = range.getBoundingClientRect();
          if (rect.width || rect.height) nextAnchors[annotation.id] = toAnchorRect(rect);
        }
      }
      if (support && ranges.length > 0) {
        support.registry.set(highlightName, new support.HighlightCtor(...ranges));
      }
      setAnnotationAnchors((previous) => {
        const previousKeys = Object.keys(previous);
        const nextKeys = Object.keys(nextAnchors);
        const unchanged = previousKeys.length === nextKeys.length
          && nextKeys.every((key) => previous[key]
            && Math.abs(previous[key].top - nextAnchors[key].top) < 0.5
            && Math.abs(previous[key].left - nextAnchors[key].left) < 0.5);
        return unchanged ? previous : nextAnchors;
      });
    };
    const schedule = () => {
      if (typeof window.requestAnimationFrame !== 'function') { apply(); return; }
      if (frame) window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(apply);
    };
    schedule();
    let observer: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(schedule);
      observer.observe(root);
    }
    window.addEventListener('resize', schedule);
    window.addEventListener('scroll', schedule, true);
    return () => {
      if (frame) window.cancelAnimationFrame(frame);
      observer?.disconnect();
      window.removeEventListener('resize', schedule);
      window.removeEventListener('scroll', schedule, true);
      clearHighlight();
    };
  }, [annotationsVisible, annotationCount, messageAnnotations, displayContent, highlightName, clearHighlight]);

  // 点击空白处关闭注释列表；点列表本身不关
  useEffect(() => {
    if (!listAnchor) return;
    const onDown = (event: MouseEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.closest('[data-annotation-list]')) return;
      setListAnchor(null);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [listAnchor]);

  // HITL 卡片：读取 metadata.hitl（新契约）；旧消息无 metadata 时正常渲染 content
  const hitlMeta = metadata?.hitl;
  const isHitl = !!hitlMeta && !hitlMeta.pending && metadata?.kind !== 'video-plan';
  const hitlInfo = hitlMeta
    ? hitlFromMetadata(metadata)
    : null;
  const submittedActionId = hitlMeta?.selectedActionId;
  const metrics = !isUser ? metadata?.metrics : undefined;
  const metricsTitle = metrics
    ? [
        `输入 ${formatTokenCount(metrics.inputTokens)}`,
        `输出 ${formatTokenCount(metrics.outputTokens)}`,
        metrics.cachedInputTokens > 0 ? `缓存 ${formatTokenCount(metrics.cachedInputTokens)}` : '',
        metrics.complete ? '' : '部分模型未返回用量',
        `耗时 ${formatElapsed(metrics.durationMs)}`,
      ].filter(Boolean).join(' · ')
    : '';
  const submittedActionTitle = hitlMeta?.selectedActionTitle;
  const videoPlanInfo = metadata?.kind === 'video-plan' && metadata.videoPlan && !metadata.hitl?.pending
    ? {
        planToken: metadata.videoPlan.formToken ?? '',
        message: metadata.videoPlan.message,
        duration: metadata.videoPlan.duration,
        picUrl: metadata.videoPlan.picUrl ?? '',
        actions: metadata.videoPlan.actions,
        messageId: metadata.videoPlan.messageId ?? metadata.hitl?.messageId,
        models: metadata.videoPlan.models,
        recommended: metadata.videoPlan.recommended,
        reasons: metadata.videoPlan.reasons,
      }
    : null;
  const hitlPlanContent = !isUser && renderedContent.trim()
    && (isHitl || videoPlanInfo)
    && renderedContent.trim() !== (hitlInfo?.formContent ?? videoPlanInfo?.message ?? '').trim()
    ? (
        <div style={{ width: '100%', maxWidth: '100%', marginBottom: 8 }}>
          <MarkdownContent
            content={renderedContent}
            onImgClick={(url) => openPreview(url, 'image')}
            onImportMedia={(media) => void handleImportMedia([media])}
            importDisabled={importDisabled}
          />
        </div>
      )
    : null;
  const editShell = editing ? {
    width: '100%',
    maxWidth: '100%',
    padding: '10px 14px',
    borderRadius: 'var(--rounded-md)',
    background: 'var(--color-canvas)',
    border: `1px solid ${editFocused ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
    boxShadow: editFocused ? '0 0 0 3px rgba(204, 120, 92, 0.14)' : 'none',
    color: 'var(--color-ink)',
    fontSize: 16,
    lineHeight: 1.55,
    whiteSpace: 'normal' as const,
  } : {};

  const handleCopy = () => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }).catch(() => { /* 剪贴板不可用时静默 */ });
  };

  const beginEdit = () => {
    onEdit?.();
    if (!messageId) return;
    setEditDraft(content);
    setEditing(true);
  };

  const cancelEdit = () => {
    setEditDraft(content);
    setEditing(false);
  };

  const submitEdit = async () => {
    const next = editDraft.trim();
    if (!messageId || !next || editSaving) return;
    if (next === content.trim()) {
      setEditing(false);
      return;
    }
    setEditSaving(true);
    setEditing(false);
    try {
      await onEditMessage?.(messageId, next);
      // 正文改了，原有注释的锚点失效，直接清掉这条消息的注释
      useChatAnnotationStore.getState().removeForMessage(messageId);
    } catch {
      setEditing(true);
    } finally {
      setEditSaving(false);
    }
  };

  useEffect(() => {
    if (!editing) setEditDraft(content);
  }, [content, editing]);

  useEffect(() => {
    if (!editing) return;
    const frame = window.requestAnimationFrame(() => {
      editRef.current?.focus();
      editRef.current?.setSelectionRange(editDraft.length, editDraft.length);
    });
    return () => window.cancelAnimationFrame(frame);
  }, [editing]);
  useAutoGrowTextarea(editRef, editDraft);

  useEffect(() => {
    setProjectImportState('idle');
  }, [content]);

  const handleImportMedia = async (media: MessageMedia[]) => {
    if (!currentProject?.id || media.length === 0) return;
    setProjectImportState('importing');
    const results = await Promise.allSettled(media.map((item) => importMediaToProjectLibrary({
      projectId: currentProject.id,
      producer: 'moon-chat',
      url: item.url,
      mediaType: item.type,
      label: item.type === 'VIDEO' ? 'Moon 生成视频' : 'Moon 生成图片',
    })));
    setProjectImportState(results.every((result) => result.status === 'fulfilled') ? 'done' : 'error');
  };

  // 新消息入场：从下往上轻微浮入（仅挂载时播放一次）
  useGSAP(() => {
    if (!bubbleRef.current) return;
    gsap.fromTo(
      bubbleRef.current,
      { y: 12, opacity: 0, scale: 0.98 },
      {
        y: 0, opacity: 1, scale: 1, duration: 0.3, ease: 'power2.out',
        onComplete: () => {
          // 清除残留 transform：气泡作为 containing block 会让内部 fixed 灯箱（ImagePreviewModal）错位
          gsap.set(bubbleRef.current, { clearProps: 'transform' });
        },
      }
    );
  }, { scope: bubbleRef });

  // HITL 卡片消息：渲染确认卡片（选择后高亮 + 禁用），不单独渲染 content 文本避免重复
  if (videoPlanInfo) {
    return (
      <div ref={bubbleRef} className="msg-row" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', marginBottom: ds ? 16 : 18 }}>
        {metadata?.reasoning || (metadata?.agentTrace && metadata.agentTrace.length > 0) ? (
          <ReasoningPanel reasoning={metadata.reasoning} agentTrace={metadata.agentTrace} streaming={false} />
        ) : null}
        {hitlPlanContent}
        <VideoPlanCard
          info={videoPlanInfo}
          submitted
          archived={archivedConversation}
          submittedActionId={metadata?.hitl?.selectedActionId}
          submittedActionTitle={metadata?.hitl?.selectedActionTitle}
        />
      </div>
    );
  }

  if (isHitl && hitlInfo) {
    return (
      <div ref={bubbleRef} className="msg-row" style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', marginBottom: ds ? 16 : 18 }}>
        {metadata?.reasoning || (metadata?.agentTrace && metadata.agentTrace.length > 0) ? (
          <ReasoningPanel reasoning={metadata.reasoning} agentTrace={metadata.agentTrace} streaming={false} />
        ) : null}
        {hitlPlanContent}
        <HumanInputCard
          info={hitlInfo}
          archived={archivedConversation}
          submittedActionId={submittedActionId}
          submittedActionTitle={submittedActionTitle}
          customText={hitlMeta?.customText ?? hitlMeta?.selectedCustomText}
          params={hitlMeta?.params ?? hitlMeta?.selectedParams}
          assetIds={hitlMeta?.assetIds ?? hitlMeta?.selectedAssetIds}
        />
      </div>
    );
  }

  return (
    <>
    {!isUser && canvasRunMeta && (
      <CanvasRunCard metadata={canvasRunMeta} conversationId={conversationId} />
    )}
    <div ref={bubbleRef} className="msg-row" style={{ display: 'flex', flexDirection: 'column', alignItems: isUser ? 'flex-end' : 'flex-start', marginBottom: ds ? 16 : 18 }}>
      {/* 助手消息：正文上方渲染推理面板（实时/已思考） */}
      {!isUser && (metadata?.reasoning || (metadata?.agentTrace && metadata.agentTrace.length > 0) || (streaming && !content)) && (
        <ReasoningPanel reasoning={metadata?.reasoning} agentTrace={metadata?.agentTrace} streaming={streaming} />
      )}
      <div
        ref={contentRef}
        onMouseUp={handleAnnotationSelection}
        onKeyUp={handleAnnotationSelection}
        style={ds
          ? // DeepSeek 风格：用户=浅蓝 22px 圆角气泡(≤525px)；助手=无气泡纯文本
            {
              position: 'relative',
              maxWidth: isUser ? 'min(525px, 82%)' : '100%',
              padding: isUser ? '10px 16px' : 0,
              borderRadius: isUser ? 22 : 0,
              background: isUser ? 'rgb(237, 243, 254)' : 'transparent',
              color: 'rgb(15, 17, 21)',
              fontSize: 16,
              lineHeight: 1.6,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              textAlign: 'left',
              ...editShell,
            }
          : {
              position: 'relative',
              maxWidth: '82%',
              padding: '12px 18px',
              borderRadius: isUser ? '14px 14px 4px 14px' : '14px 14px 14px 4px',
              background: isUser ? 'var(--color-primary)' : 'var(--color-surface-card)',
              color: isUser ? 'white' : 'var(--color-body)',
              // 抽屉空间有限,正文 15px(全局 16px 偏大)
              fontSize: 15,
              lineHeight: 1.7,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
              // 全局 #root 有 text-align: center（模板遗留），必须显式左对齐，否则气泡内文字继承居中
              textAlign: 'left',
              ...editShell,
            }}
      >
        {editing ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <textarea
              ref={editRef}
              aria-label="编辑消息内容"
              value={editDraft}
              onChange={(event) => setEditDraft(event.target.value)}
              onFocus={() => setEditFocused(true)}
              onBlur={() => setEditFocused(false)}
              onKeyDown={(event) => {
                if (event.nativeEvent.isComposing) return;
                if (event.key === 'Escape') {
                  event.preventDefault();
                  cancelEdit();
                } else if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault();
                  void submitEdit();
                }
              }}
              rows={3}
              style={{
                width: '100%', minHeight: 72, border: 'none', outline: 'none', resize: 'vertical',
                background: 'transparent', color: 'inherit', fontFamily: 'inherit',
                fontSize: 16, lineHeight: 1.55, padding: 0,
              }}
            />
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button
                type="button"
                onClick={cancelEdit}
                style={{
                  height: 40, padding: '0 16px', borderRadius: 'var(--rounded-md)',
                  border: '1px solid var(--color-hairline)',
                  background: 'var(--color-canvas)', color: 'var(--color-ink)',
                  cursor: 'pointer', fontSize: 14, fontWeight: 500,
                }}
              >取消</button>
              <button
                type="button"
                onClick={() => void submitEdit()}
                disabled={!editDraft.trim() || editSaving}
                style={{
                  height: 40, padding: '0 18px', borderRadius: 'var(--rounded-md)', border: 'none',
                  background: 'var(--color-primary)',
                  color: 'var(--color-on-primary)',
                  cursor: !editDraft.trim() || editSaving ? 'not-allowed' : 'pointer',
                  opacity: !editDraft.trim() || editSaving ? 0.5 : 1,
                  fontSize: 14, fontWeight: 500,
                }}
              >{editSaving ? '发送中…' : '发送'}</button>
            </div>
          </div>
        ) : (displayContent || userAttachments.length > 0) ? (
          <span className="chat-live-text">
            {role === 'assistant'
              ? (
                <>
                  {renderedContent && (
                    <MarkdownContent
                      content={renderedContent}
                      onImgClick={(url) => openPreview(url, 'image')}
                      onImportMedia={(media) => void handleImportMedia([media])}
                      importDisabled={importDisabled}
                      importing={projectImportState === 'importing'}
                      dragProjectId={currentProject?.id}
                    />
                  )}
                  {imageBatch && (
                    <GenerationImageBatch
                      media={imageBatch}
                      streaming={streaming}
                      onOpen={onOpenGenerationCenter}
                    />
                  )}
                </>
              )
              : (
                <>
                  <MessageAttachmentStrip
                    attachments={userAttachments}
                    onPreview={(attachment) => openPreview(
                      attachment.url,
                      attachment.kind === 'video' ? 'video' : 'image',
                    )}
                  />
                  {userTextContent && renderContent(
                    userTextContent,
                    (url) => openPreview(url, 'image'),
                    (media) => void handleImportMedia([media]),
                    importDisabled,
                    projectImportState === 'importing',
                    currentProject?.id,
                  )}
                </>
              )}
          </span>
        ) : canvasRunMeta ? null : <span style={{ opacity: 0.6 }}>…</span>}
        {/* C 组：流式回复中的打字机光标 */}
        {streaming && content && (
          <span
            style={{
              display: 'inline-block',
              width: 2, height: 16,
              marginLeft: 2,
              verticalAlign: 'text-bottom',
              background: isUser ? (ds ? 'rgb(65, 118, 230)' : 'white') : (ds ? 'rgb(65, 118, 230)' : 'var(--color-primary)'),
              animation: 'typeCursor 1s steps(1) infinite',
            }}
          />
        )}
        <style>{`@keyframes typeCursor { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }`}</style>
        {annotationsVisible && (
          <style>{`::highlight(${highlightName}) { background-color: rgba(65, 118, 230, 0.16); }`}</style>
        )}
      </div>
      {!isUser && metadata?.taskCards?.map((task) => (
        <AgentTaskCard key={task.taskId} taskId={task.taskId} />
      ))}
      {!isUser && storyboardResultReady && (
        <StoryboardResultActions />
      )}
      {/* 助手消息：正文下方渲染建议动作 */}
      {!isUser && metadata?.suggestedActions && metadata.suggestedActions.length > 0 && (
        <SuggestedActions actions={metadata.suggestedActions} />
      )}
      {/* 消息 meta 行：时间 + 复制，直接常显 */}
      {!streaming && !editing && (
        <div
          style={{
            display: 'flex', alignItems: 'center', gap: 8, marginTop: 4,
            padding: ds ? '0 2px' : '0 4px',
            flexWrap: 'wrap', rowGap: 3,
          }}
        >
          <span style={{ fontSize: ds ? 12 : 11, color: ds ? 'rgb(162, 164, 166)' : 'var(--color-muted-soft)' }}>
            {formatTime(createdAt)}
          </span>
          <button
            onClick={handleCopy}
            title="复制内容"
            onMouseEnter={(e) => { const el = e.currentTarget; el.style.background = 'rgba(0,0,0,0.05)'; el.style.color = ds ? 'rgb(65, 118, 230)' : 'var(--color-primary)'; }}
            onMouseLeave={(e) => { const el = e.currentTarget; el.style.background = 'transparent'; el.style.color = ds ? 'rgb(162, 164, 166)' : 'var(--color-muted)'; }}
            style={{
              border: 'none', background: 'transparent', cursor: 'pointer',
              fontSize: ds ? 12 : 11, padding: '1px 4px', borderRadius: 6,
              color: ds ? 'rgb(162, 164, 166)' : 'var(--color-muted)',
            }}
          >{copied ? '✓ 已复制' : '⧉ 复制'}</button>
          {isUser && editable && (onEdit || onEditMessage) && (
            <button
              onClick={beginEdit}
              title="编辑消息"
              onMouseEnter={(e) => {
                const el = e.currentTarget;
                el.style.background = 'rgba(0,0,0,0.05)';
                el.style.color = ds ? 'rgb(65, 118, 230)' : 'var(--color-primary)';
              }}
              onMouseLeave={(e) => {
                const el = e.currentTarget;
                el.style.background = 'transparent';
                el.style.color = ds ? 'rgb(162, 164, 166)' : 'var(--color-muted)';
              }}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 4,
                border: 'none', background: 'transparent', cursor: 'pointer',
                fontSize: ds ? 12 : 11, padding: '1px 4px', borderRadius: 6,
                color: ds ? 'rgb(162, 164, 166)' : 'var(--color-muted)',
              }}
            >
              <Pencil size={12} strokeWidth={1.8} />
              编辑
            </button>
          )}
          {!isUser && messageMedia.length > 0 && (
            <button
              onClick={() => void handleImportMedia(messageMedia)}
              disabled={importDisabled || projectImportState === 'importing'}
              title={currentProject?.id ? '导入素材库' : '请先选择项目'}
              onMouseEnter={(e) => {
                if (importDisabled || projectImportState === 'importing') return;
                const el = e.currentTarget;
                el.style.background = 'rgba(0,0,0,0.05)';
                el.style.color = ds ? 'rgb(65, 118, 230)' : 'var(--color-primary)';
              }}
              onMouseLeave={(e) => {
                const el = e.currentTarget;
                el.style.background = 'transparent';
                el.style.color = ds ? 'rgb(162, 164, 166)' : 'var(--color-muted)';
              }}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 4,
                border: 'none', background: 'transparent', cursor: importDisabled ? 'not-allowed' : 'pointer',
                fontSize: ds ? 12 : 11, padding: '1px 4px', borderRadius: 6,
                color: ds ? 'rgb(162, 164, 166)' : 'var(--color-muted)',
                opacity: importDisabled ? 0.55 : 1,
              }}
            >
              {projectImportState === 'importing'
                ? <LoaderCircle size={12} className="is-spinning" />
                : <Library size={12} strokeWidth={1.8} />}
              {projectImportState === 'done'
                ? '已导入素材库'
                : projectImportState === 'error' ? '重试导入素材库' : '导入素材库'}
            </button>
          )}
          {metrics && (
            <span
              title={metricsTitle}
              style={{
                display: 'inline-flex', alignItems: 'center', gap: 8,
                fontSize: ds ? 12 : 11, color: ds ? 'rgb(162, 164, 166)' : 'var(--color-muted-soft)',
                whiteSpace: 'nowrap',
              }}
            >
              {metrics.totalTokens > 0 && (
                <span>{metrics.complete ? '' : '≥ '}{formatTokenCount(metrics.totalTokens)} tokens</span>
              )}
              <span>{formatElapsed(metrics.durationMs)}</span>
            </span>
          )}
        </div>
      )}
    </div>
    <ImagePreviewModal
      url={previewUrl}
      type={previewType}
      onClose={() => setPreviewUrl(null)}
    />
    {annotationsVisible && annotationCount > 0 && badgeAnchor && badgeVisible && createPortal(
      <button
        type="button"
        data-annotation-ui
        aria-label={`${annotationCount} 条注释徽标`}
        title="查看 / 编辑注释"
        onClick={handleAnnotationBadgeClick}
        style={{
          position: 'fixed',
          top: Math.max(8, badgeAnchor.top - 10),
          left: badgeAnchor.right - 8,
          minWidth: 20, height: 20, padding: '0 6px', borderRadius: 999,
          border: '2px solid white', background: 'rgb(65, 118, 230)', color: 'white',
          fontSize: 11, fontWeight: 700, lineHeight: 1, cursor: 'pointer',
          boxShadow: '0 2px 8px rgba(65, 118, 230, 0.45)', zIndex: 2100,
        }}
      >
        {annotationCount}
      </button>,
      document.body,
    )}
    {annotationsVisible && listAnchor && messageAnnotations.length > 0 && (
      <AnnotationListPopover
        annotations={messageAnnotations}
        anchor={listAnchor}
        onEdit={openAnnotationEditor}
        onDelete={(id) => useChatAnnotationStore.getState().removeAnnotation(id)}
      />
    )}
    {annotationEditable && selectionAction && (
      <SelectionActionToolbar
        anchor={selectionAction.rect}
        onQuote={handleStartAnnotation}
        onDismiss={() => setSelectionAction(null)}
      />
    )}
    {annotationEditable && draftAnchor && (
      <AnnotationEditor
        mode="create"
        initialComment=""
        anchor={draftAnchor.rect}
        onSave={handleCreateAnnotation}
        onCancel={() => {
          window.getSelection()?.removeAllRanges();
          setDraftAnchor(null);
        }}
      />
    )}
    {annotationEditable && editingAnnotation && editingAnchor && (
      <AnnotationEditor
        mode="edit"
        initialComment={editingAnnotation.comment}
        anchor={editingAnchor}
        onSave={handleSaveAnnotationComment}
        onDelete={handleDeleteEditingAnnotation}
        onCancel={() => useChatAnnotationStore.getState().closeEditor()}
      />
    )}
    </>
  );
}
