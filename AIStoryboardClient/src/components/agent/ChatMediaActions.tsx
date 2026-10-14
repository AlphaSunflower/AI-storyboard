import {
  useCallback, useEffect, useRef, useState,
  type MouseEvent as ReactMouseEvent, type ReactNode, type RefObject,
} from 'react';
import { createPortal } from 'react-dom';
import {
  Check, Clapperboard, Copy, Download, Ellipsis, Eraser, Expand, Film, Frame, ImagePlus,
  LoaderCircle, Orbit, Paintbrush, RotateCcw, Sparkles, Wand2,
} from 'lucide-react';
import { Tooltip, TooltipGroup } from '../common/Tooltip';
import { useAgentStore } from '../../stores/agentStore';
import { notify } from '../../stores/toastStore';
import { agentApi } from '../../api/agent';
import { assetUrl } from '../../config';
import { copyImageToClipboard, copyPlainText } from '../../lib/chatBlockExport';
import { isUnimplementedMediaAction, planActionBar } from '../../lib/mediaActionBar';
import { downloadMedia } from '../../lib/mediaDownload';
import { openInCanvas, quoteAsReference, type ChatMediaKind } from '../../lib/chatMediaActions';
import {
  extractVideoFrame,
  frameFileName,
  frameLabel,
  frameMessageContent,
  probeVideoDurationSeconds,
  type VideoFramePosition,
} from '../../lib/videoFrameExtract';
import './ChatMediaActions.css';

export type { ChatMediaKind };

export interface ChatMediaActionsProps {
  kind: ChatMediaKind;
  /** 已解析 URL：复制、下载、抽帧都基于它 */
  src: string;
  /** 原始来源：引用为参考素材、带入画布用 */
  source: string;
  alt?: string;
}

interface PanelAnchor {
  top: number;
  bottom: number;
  left: number;
  right: number;
}

interface PanelItem {
  label: string;
  icon: ReactNode;
  onClick: () => void;
  /** 需要把面板锚在这个触发器上时传入（例如「获取帧」） */
  triggerRef?: RefObject<HTMLSpanElement | null>;
  active?: boolean;
}

function notImplemented(label: string): void {
  notify({
    title: '未实现该功能',
    description: `${label}暂未开放`,
    variant: 'info',
    key: `media-action-unimplemented-${label}`,
  });
}

function triggerUnimplemented(label: string): () => void {
  return () => {
    if (isUnimplementedMediaAction(label)) notImplemented(label);
  };
}

function IconAction({
  label,
  onClick,
  children,
  className,
}: {
  label: string;
  onClick: (event: ReactMouseEvent<HTMLButtonElement>) => void;
  children: ReactNode;
  className?: string;
}) {
  return (
    <Tooltip label={label}>
      <button
        type="button"
        className={`chat-media-actions__button${className ? ` ${className}` : ''}`}
        aria-label={label}
        onClick={(event) => {
          event.stopPropagation();
          onClick(event);
        }}
      >
        {children}
      </button>
    </Tooltip>
  );
}

/** 悬浮面板：portal + fixed，避免被消息区 overflow 裁剪 */
function FloatingPanel({
  anchor,
  trigger,
  onClose,
  align = 'end',
  preferAbove = false,
  children,
}: {
  anchor: PanelAnchor;
  trigger: HTMLElement | null;
  onClose: () => void;
  align?: 'start' | 'end';
  preferAbove?: boolean;
  children: ReactNode;
}) {
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (panelRef.current?.contains(target) || trigger?.contains(target)) return;
      onClose();
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [onClose, trigger]);

  const width = 196;
  const maxLeft = Math.max(8, window.innerWidth - width - 8);
  const left = align === 'end'
    ? Math.min(Math.max(8, anchor.right - width), maxLeft)
    : Math.min(Math.max(8, anchor.left), maxLeft);
  // 底部条上的菜单默认向上展开，只有上方确实放不下且下方更宽敞时才落到下面
  const PANEL_HEIGHT = 240;
  const roomAbove = anchor.top - 8;
  const roomBelow = window.innerHeight - anchor.bottom - 8;
  const openAbove = preferAbove
    ? roomAbove >= PANEL_HEIGHT || roomAbove >= roomBelow
    : roomBelow < PANEL_HEIGHT && roomAbove > roomBelow;
  const below = !openAbove;

  return createPortal(
    <div
      ref={panelRef}
      className="chat-media-panel"
      style={{
        position: 'fixed',
        top: below ? anchor.bottom + 8 : undefined,
        bottom: below ? undefined : window.innerHeight - anchor.top + 8,
        left,
        width,
        zIndex: 3000,
      }}
    >
      {children}
    </div>,
    document.body,
  );
}

export function ChatMediaActions({ kind, src, source, alt }: ChatMediaActionsProps) {
  const sendMessage = useAgentStore((state) => state.sendMessage);
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<number | null>(null);
  const [menuAnchor, setMenuAnchor] = useState<PanelAnchor | null>(null);
  const [frameAnchor, setFrameAnchor] = useState<PanelAnchor | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [frameBusy, setFrameBusy] = useState(false);
  const [durationSeconds, setDurationSeconds] = useState(0);
  const [customSeconds, setCustomSeconds] = useState(0);
  /** 画框宽度：底部条按它决定露出几个按钮，放不下的挪进「⋯」 */
  const [frameWidth, setFrameWidth] = useState(0);
  const rootRef = useRef<HTMLSpanElement>(null);
  const menuTriggerRef = useRef<HTMLSpanElement>(null);
  const frameTriggerRef = useRef<HTMLSpanElement>(null);

  useEffect(() => () => {
    if (copyTimer.current !== null) window.clearTimeout(copyTimer.current);
  }, []);

  useEffect(() => {
    const element = rootRef.current;
    if (!element) return undefined;
    setFrameWidth(element.getBoundingClientRect().width);
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver((entries) => {
      setFrameWidth(entries[0]?.contentRect.width ?? 0);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const anchorOf = (element: HTMLElement): PanelAnchor => {
    const rect = element.getBoundingClientRect();
    return { top: rect.top, bottom: rect.bottom, left: rect.left, right: rect.right };
  };

  const handleCopy = useCallback(async () => {
    const ok = kind === 'image'
      ? (await copyImageToClipboard(src)) !== 'failed'
      : await copyPlainText(source || src);
    if (!ok) return;
    setCopied(true);
    if (copyTimer.current !== null) window.clearTimeout(copyTimer.current);
    copyTimer.current = window.setTimeout(() => {
      copyTimer.current = null;
      setCopied(false);
    }, 1400);
  }, [kind, source, src]);

  const handleDownload = () => {
    void downloadMedia(src, alt || (kind === 'video' ? 'chat-video' : 'chat-image'));
  };

  const handleRegenerate = () => {
    const label = kind === 'image' ? '这张图片' : '这个视频';
    const content = kind === 'image'
      ? '重新生成这张图片，保持相同的提示词与风格'
      : '重新生成这个视频，保持相同的提示词与参数';
    const opts = kind === 'image' ? { picUrls: [source || src] } : { referenceVideos: [source || src] };
    void sendMessage(content, opts).catch(() => {
      notify({ title: '重新生成失败', description: `${label}重新提交失败，请稍后重试`, variant: 'error' });
    });
  };

  const openFramePanel = () => {
    const trigger = frameTriggerRef.current;
    if (!trigger) return;
    setFrameAnchor(anchorOf(trigger));
    setDurationSeconds(0);
    setCustomSeconds(0);
    void probeVideoDurationSeconds(assetUrl(source || src))
      .then((duration) => {
        setDurationSeconds(duration);
        setCustomSeconds(duration / 2);
      })
      .catch(() => { /* 时长拿不到时只禁用自定义帧 */ });
  };

  const runExtractFrame = async (position: VideoFramePosition) => {
    if (frameBusy) return;
    setFrameBusy(true);
    const label = frameLabel(position);
    try {
      const blob = await extractVideoFrame(assetUrl(source || src), {
        position,
        customSeconds,
      });
      const file = new File([blob], frameFileName(label), { type: 'image/png' });
      const response = await agentApi.uploadImage(
        file,
        useAgentStore.getState().activeConversationId ?? undefined,
        'video-frame',
        'image',
      );
      const url = response.data.data.url;
      useAgentStore.getState().addLocalMediaMessage(frameMessageContent(label, url));
      setFrameAnchor(null);
      notify({ title: `已提取${label}`, description: '结果已生成到对话中', variant: 'success' });
    } catch (error) {
      notify({
        title: '抽帧失败',
        description: error instanceof Error ? error.message : '请稍后重试',
        variant: 'error',
      });
    } finally {
      setFrameBusy(false);
    }
  };

  /** 全部图片/视频操作：宽度够就全铺在底部条，不够才从尾部挪进「⋯」 */
  const mediaActions: PanelItem[] = kind === 'image'
    ? [
      { label: '重新生成', icon: <RotateCcw size={16} strokeWidth={1.9} />, onClick: handleRegenerate },
      { label: '智能改图', icon: <Wand2 size={16} strokeWidth={1.9} />, onClick: triggerUnimplemented('智能改图') },
      { label: '智能超清', icon: <Sparkles size={16} strokeWidth={1.9} />, onClick: triggerUnimplemented('智能超清') },
      { label: '局部重绘', icon: <Paintbrush size={16} strokeWidth={1.9} />, onClick: triggerUnimplemented('局部重绘') },
      { label: '去画布编辑', icon: <Frame size={16} strokeWidth={1.9} />, onClick: () => openInCanvas(kind, source || src) },
      { label: '消除笔', icon: <Eraser size={16} strokeWidth={1.9} />, onClick: triggerUnimplemented('消除笔') },
      { label: '扩图', icon: <Expand size={16} strokeWidth={1.9} />, onClick: triggerUnimplemented('扩图') },
      { label: '多角度', icon: <Orbit size={16} strokeWidth={1.9} />, onClick: triggerUnimplemented('多角度') },
      {
        label: '引用为参考图',
        icon: <ImagePlus size={16} strokeWidth={1.9} />,
        onClick: () => quoteAsReference('image', source || src),
      },
    ]
    : [
      { label: '重新生成', icon: <RotateCcw size={16} strokeWidth={1.9} />, onClick: () => setConfirmOpen(true) },
      {
        label: '获取帧',
        icon: <Film size={16} strokeWidth={1.9} />,
        onClick: () => (frameAnchor ? setFrameAnchor(null) : openFramePanel()),
        triggerRef: frameTriggerRef,
        active: !!frameAnchor,
      },
      { label: '去画布编辑', icon: <Frame size={16} strokeWidth={1.9} />, onClick: () => openInCanvas(kind, source || src) },
      {
        label: '引用为参考视频',
        icon: <Clapperboard size={16} strokeWidth={1.9} />,
        onClick: () => quoteAsReference('video', source || src),
      },
    ];

  const barPlan = planActionBar(frameWidth, mediaActions.length);
  const visibleActions = mediaActions.slice(0, barPlan.visible);
  const menuItems: PanelItem[] = mediaActions.slice(barPlan.visible);

  return (
    <span ref={rootRef} className="chat-media-actions" data-annotation-ui data-kind={kind}>
      {/* 右上角：复制 / 下载（改图类操作统一在底部条，三个点也在底部） */}
      <span className="chat-block-actions chat-block-actions--light chat-media-actions__corner">
        <TooltipGroup>
          <Tooltip label={copied ? '已复制' : `复制${kind === 'image' ? '图片' : '视频'}`}>
            <button
              type="button"
              className="chat-block-actions__button"
              aria-label={copied ? '已复制' : `复制${kind === 'image' ? '图片' : '视频'}`}
              onClick={(event) => {
                event.stopPropagation();
                void handleCopy();
              }}
            >
              {copied ? <Check size={13} strokeWidth={2} /> : <Copy size={13} strokeWidth={1.8} />}
            </button>
          </Tooltip>
          <Tooltip label={`下载${kind === 'image' ? '图片' : '视频'}`}>
            <button
              type="button"
              className="chat-block-actions__button"
              aria-label={`下载${kind === 'image' ? '图片' : '视频'}`}
              onClick={(event) => {
                event.stopPropagation();
                handleDownload();
              }}
            >
              <Download size={13} strokeWidth={1.8} />
            </button>
          </Tooltip>
        </TooltipGroup>
      </span>

      {/* 底部动作条：能放下的主操作依次露出，其余与次级操作一起进「⋯」 */}
      <span
        className="chat-media-actions__bar"
        role="toolbar"
        aria-label={kind === 'image' ? '图片操作' : '视频操作'}
      >
        {visibleActions.map((action) => (
          <span key={action.label} ref={action.triggerRef} className="chat-media-actions__trigger">
            <IconAction
              label={action.label}
              className={action.active ? 'is-active' : undefined}
              onClick={action.onClick}
            >
              {action.icon}
            </IconAction>
          </span>
        ))}
        {menuItems.length > 0 && (
          <span ref={menuTriggerRef} className="chat-media-actions__trigger">
            <IconAction
              label="更多操作"
              className={menuAnchor ? 'is-active' : undefined}
              onClick={(event) => setMenuAnchor(menuAnchor ? null : anchorOf(event.currentTarget))}
            >
              <Ellipsis size={16} strokeWidth={1.9} />
            </IconAction>
          </span>
        )}
      </span>

      {menuAnchor && (
        <FloatingPanel
          anchor={menuAnchor}
          trigger={menuTriggerRef.current}
          onClose={() => setMenuAnchor(null)}
          preferAbove
        >
          <div className="chat-media-panel__list" role="menu">
            {menuItems.map((item) => (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                className="chat-media-panel__item"
                onClick={(event) => {
                  event.stopPropagation();
                  setMenuAnchor(null);
                  item.onClick();
                }}
              >
                {item.icon}
                <span>{item.label}</span>
              </button>
            ))}
          </div>
        </FloatingPanel>
      )}

      {frameAnchor && (
        <FloatingPanel
          anchor={frameAnchor}
          trigger={frameTriggerRef.current}
          onClose={() => (frameBusy ? undefined : setFrameAnchor(null))}
          preferAbove
        >
          <div className="chat-media-panel__frame">
            <div className="chat-media-panel__frame-head">
              <strong>获取帧</strong>
              <span>输出 PNG 并生成到对话中</span>
            </div>
            <div className="chat-media-panel__frame-actions">
              <button
                type="button"
                disabled={frameBusy}
                onClick={() => void runExtractFrame('first')}
              >
                首帧
              </button>
              <button
                type="button"
                disabled={frameBusy}
                onClick={() => void runExtractFrame('last')}
              >
                尾帧
              </button>
            </div>
            <label className="chat-media-panel__scrubber">
              <span>自定义帧</span>
              <b>{`${customSeconds.toFixed(1)}s`}</b>
              <input
                type="range"
                min={0}
                max={Math.max(0.1, durationSeconds || 1)}
                step={0.1}
                value={Math.min(customSeconds, Math.max(0.1, durationSeconds || 1))}
                disabled={frameBusy || durationSeconds <= 0}
                onChange={(event) => setCustomSeconds(Number(event.target.value))}
              />
              <button
                type="button"
                disabled={frameBusy || durationSeconds <= 0}
                onClick={() => void runExtractFrame('custom')}
              >
                {frameBusy ? <LoaderCircle size={13} className="is-spinning" /> : null}
                抽取
              </button>
            </label>
            {durationSeconds <= 0 && <small>正在读取视频时长…</small>}
          </div>
        </FloatingPanel>
      )}

      {confirmOpen && createPortal(
        <div
          className="chat-media-confirm__backdrop"
          role="presentation"
          onPointerDown={(event) => {
            if (event.target === event.currentTarget) setConfirmOpen(false);
          }}
        >
          <div
            className="chat-media-confirm"
            role="alertdialog"
            aria-modal="true"
            aria-label="重新生成视频确认"
            onPointerDown={(event) => event.stopPropagation()}
          >
            <strong>重新生成该视频？</strong>
            <p>会再次调用视频生成，可能产生新的额度消耗；原视频保持不动。</p>
            <div className="chat-media-confirm__actions">
              <button type="button" onClick={() => setConfirmOpen(false)}>取消</button>
              <button
                type="button"
                className="is-primary"
                onClick={() => {
                  setConfirmOpen(false);
                  handleRegenerate();
                }}
              >
                确认重新生成
              </button>
            </div>
          </div>
        </div>,
        document.body,
      )}
    </span>
  );
}
