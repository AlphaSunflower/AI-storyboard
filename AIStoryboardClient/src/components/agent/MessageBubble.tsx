import { useEffect, useRef, useState } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ChevronRight, Download, ImagePlus, Images, Library, LoaderCircle, Pencil } from 'lucide-react';
import { assetUrl } from '../../config';
import { ImagePreviewModal } from './ImagePreviewModal';
import type { AgentMessageMetadata } from '../../api/agent';
import { hitlFromMetadata, useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import { ReasoningPanel } from './ReasoningPanel';
import { SuggestedActions } from './SuggestedActions';
import { HumanInputCard } from './HumanInputCard';
import { VideoPlanCard } from './VideoPlanCard';
import { AgentTaskCard } from './AgentTaskCard';
import { StoryboardResultActions } from './StoryboardResultActions';
import { ContextMenu } from '../common/ContextMenu';
import { importMediaToProjectLibrary } from '../../lib/projectLibraryImport';
import { useAutoGrowTextarea } from '../../hooks/useAutoGrowTextarea';
import { remarkCjkStrong } from './remarkCjkStrong';
import {
  CHAT_MEDIA_DRAG_MIME,
  serializeDragPayload,
  type ChatMediaDragPayload,
} from '../../lib/projectLibraryDrag';
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
  const [failed, setFailed] = useState(false);
  if (failed) {
    return <span className="chat-image-batch__fallback"><Images size={14} strokeWidth={1.7} /></span>;
  }
  return (
    <img
      src={assetUrl(url)}
      alt=""
      loading="lazy"
      onError={() => setFailed(true)}
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
  src, source, alt, style, onClick, onImport, importDisabled, importing, dragPayload,
}: {
  src: string; source?: string; alt?: string; style?: React.CSSProperties; onClick?: () => void;
  onImport?: () => void; importDisabled?: boolean; importing?: boolean; dragPayload?: ChatMediaDragPayload | null;
}) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <span style={{ color: 'var(--color-muted-soft)', fontSize: 12, fontStyle: 'italic', margin: '4px 0' }}>
        （图片已过期或无法加载）
      </span>
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
      <div key={key} style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
        <ReactMarkdown
          remarkPlugins={[remarkGfm, remarkCjkStrong]}
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

export function MessageBubble({ messageId, role, content, streaming, variant = 'default', createdAt, metadata, editable, onEdit, onEditMessage, onOpenGenerationCenter }: {
  messageId?: string;
  role: 'user' | 'assistant'; content: string; streaming?: boolean;
  variant?: 'default' | 'deepseek'; createdAt?: string; metadata?: AgentMessageMetadata;
  editable?: boolean; onEdit?: () => void;
  onEditMessage?: (messageId: string, content: string) => Promise<void>;
  onOpenGenerationCenter?: () => void;
}) {
  const isUser = role === 'user';
  const ds = variant === 'deepseek';
  const currentProject = useProjectStore((state) => state.currentProject);
  // 图片点击放大预览（灯箱）
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
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
  const displayContent = useStreamingReveal(content, role === 'assistant' && !!streaming);
  const messageMedia = extractMessageMedia(displayContent);
  const generatedImages = messageMedia.filter((item) => item.type === 'IMAGE');
  const imageBatch = !isUser && generatedImages.length >= IMAGE_BATCH_THRESHOLD ? generatedImages : null;
  const renderedContent = imageBatch ? stripBatchImageMarkdown(displayContent) : displayContent;
  const importDisabled = !currentProject?.id || messageMedia.length === 0;
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
        <VideoPlanCard
          info={videoPlanInfo}
          submitted
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
        <HumanInputCard
          info={hitlInfo}
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
    <div ref={bubbleRef} className="msg-row" style={{ display: 'flex', flexDirection: 'column', alignItems: isUser ? 'flex-end' : 'flex-start', marginBottom: ds ? 16 : 18 }}>
      {/* 助手消息：正文上方渲染推理面板（实时/已思考） */}
      {!isUser && (metadata?.reasoning || (metadata?.agentTrace && metadata.agentTrace.length > 0) || (streaming && !content)) && (
        <ReasoningPanel reasoning={metadata?.reasoning} agentTrace={metadata?.agentTrace} streaming={streaming} />
      )}
      <div
        style={ds
          ? // DeepSeek 风格：用户=浅蓝 22px 圆角气泡(≤525px)；助手=无气泡纯文本
            {
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
        ) : displayContent ? (
          <span className="chat-live-text">
            {role === 'assistant'
              ? (
                <>
                  {renderedContent && (
                    <MarkdownContent
                      content={renderedContent}
                      onImgClick={setPreviewUrl}
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
              : renderContent(
                  displayContent,
                  setPreviewUrl,
                  (media) => void handleImportMedia([media]),
                  importDisabled,
                  projectImportState === 'importing',
                  currentProject?.id,
                )}
          </span>
        ) : <span style={{ opacity: 0.6 }}>…</span>}
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
    <ImagePreviewModal url={previewUrl} onClose={() => setPreviewUrl(null)} />
    </>
  );
}
