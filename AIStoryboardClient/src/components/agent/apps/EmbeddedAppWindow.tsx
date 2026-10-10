import { useEffect, useRef, useState } from 'react';
import { ExternalLink, FileText, Frame, Grip, Library, LoaderCircle, Maximize2, Minimize2, Minus, PanelRight, PenLine, RefreshCw, X } from 'lucide-react';
import {
  CANVAS_AUTH_MESSAGE,
  isEmbeddedAppReadyMessage,
} from '../../../lib/embeddedAppMessages';
import { getAccessToken } from '../../../lib/authSession';
import type { AgentAttachment } from '../../../api/agent';
import { DocumentPreviewSurface } from '../DocumentPreviewSurface';
import { CHAT_DOCK_MIN_APP_WIDTH } from '../chatDockLayout';
import './EmbeddedAppWindow.css';

export interface EmbeddedAppDescriptor {
  key: 'canvas' | 'editor' | 'docs' | 'library' | 'document' | 'side-chat';
  title: string;
  url: string;
  projectId?: string;
  canvasMode?: CanvasModeOptions;
  documentAttachment?: AgentAttachment;
}

export interface CanvasModeOptions {
  conversationId: string;
  projectId: string;
  parentOrigin: string;
  canvasId?: string;
}

interface EmbeddedAppWindowProps {
  tabs: EmbeddedAppDescriptor[];
  activeKey: EmbeddedAppDescriptor['key'];
  onSelectTab: (key: EmbeddedAppDescriptor['key']) => void;
  onCloseTab: (key: EmbeddedAppDescriptor['key']) => void;
  onCloseWindow: () => void;
  onMinimize?: () => void;
  docked: boolean;
  splitMaxWidth: number;
  expandedSplitMaxWidth: number;
  sidebarCollapsed: boolean;
  sidebarCollapseWidth: number;
  onCollapseSidebar: () => void;
  onRequestDock: () => void;
  onLayoutChange: (layout: { width: number; height: number; fullscreen: boolean }) => void;
  minimized?: boolean;
}

const DEFAULT_WIDTH = 420;
const MIN_WIDTH = CHAT_DOCK_MIN_APP_WIDTH;
const MIN_HEIGHT = 420;
const CLOSE_ANIMATION_MS = 300;

function defaultHeight() {
  return Math.max(MIN_HEIGHT, Math.min(820, window.innerHeight - 132));
}

function appIcon(key: EmbeddedAppDescriptor['key']) {
  if (key === 'canvas') return <Frame size={13} strokeWidth={1.9} />;
  if (key === 'editor') return <PenLine size={13} strokeWidth={1.9} />;
  if (key === 'library') return <Library size={13} strokeWidth={1.9} />;
  if (key === 'document') return <FileText size={13} strokeWidth={1.9} />;
  if (key === 'side-chat') return <PanelRight size={13} strokeWidth={1.9} />;
  return <FileText size={13} strokeWidth={1.9} />;
}

export function EmbeddedAppWindow({
  tabs,
  activeKey,
  onSelectTab,
  onCloseTab,
  onCloseWindow,
  onMinimize,
  docked,
  splitMaxWidth,
  expandedSplitMaxWidth,
  sidebarCollapsed,
  sidebarCollapseWidth,
  onCollapseSidebar,
  onRequestDock,
  onLayoutChange,
  minimized = false,
}: EmbeddedAppWindowProps) {
  const [width, setWidth] = useState(() => Math.max(
    MIN_WIDTH,
    Math.min(
      activeKey === 'document' ? 760 : activeKey === 'library' ? 620 : DEFAULT_WIDTH,
      splitMaxWidth,
    ),
  ));
  const [height, setHeight] = useState(defaultHeight);
  const [fullscreen, setFullscreen] = useState(false);
  const [loadedUrls, setLoadedUrls] = useState<Record<string, string>>({});
  const [refreshTokens, setRefreshTokens] = useState<Record<string, number>>({});
  const [closingWindow, setClosingWindow] = useState(false);
  const closeTimerRef = useRef<number | null>(null);
  const frameRefs = useRef<Record<string, HTMLIFrameElement | null>>({});
  const canvasLocked = tabs.some((tab) => tab.key === 'canvas' && tab.canvasMode);

  const postCanvasAuth = (tab: EmbeddedAppDescriptor | undefined, source: Window | null) => {
    if (!tab || tab.key !== 'canvas' || !source) return;
    const accessToken = getAccessToken();
    if (!accessToken) return;
    let targetOrigin = window.location.origin;
    try {
      targetOrigin = new URL(tab.url, window.location.origin).origin;
    } catch {
      return;
    }
    source.postMessage({ type: CANVAS_AUTH_MESSAGE, accessToken }, targetOrigin);
  };

  useEffect(() => () => {
    if (closeTimerRef.current !== null) window.clearTimeout(closeTimerRef.current);
  }, []);

  useEffect(() => {
    onLayoutChange({ width, height, fullscreen });
  }, [fullscreen, height, onLayoutChange, width]);

  useEffect(() => {
    if (docked) return;
    setWidth((current) => Math.min(splitMaxWidth, Math.max(MIN_WIDTH, current)));
  }, [docked, splitMaxWidth]);

  useEffect(() => {
    if (activeKey !== 'document') return;
    setWidth((current) => Math.min(
      Math.max(MIN_WIDTH, window.innerWidth - 32),
      Math.max(680, current),
    ));
  }, [activeKey]);

  useEffect(() => {
    const onResize = () => {
      setHeight((current) => Math.min(current, Math.max(MIN_HEIGHT, window.innerHeight - 32)));
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      if (activeKey === 'canvas' && tabs.some((tab) => tab.key === 'canvas' && tab.canvasMode)) return;
      if (fullscreen) setFullscreen(false);
      else if (activeKey) onCloseTab(activeKey);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activeKey, fullscreen, onCloseTab, tabs]);

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (!isEmbeddedAppReadyMessage(event.data)) return;
      const frame = frameRefs.current[event.data.key];
      if (!frame || frame.contentWindow !== event.source) return;
      const tab = tabs.find((item) => item.key === event.data.key);
      if (!tab) return;
      try {
        if (event.origin !== new URL(tab.url, window.location.origin).origin) return;
      } catch {
        return;
      }
      setLoadedUrls((current) => ({ ...current, [tab.key]: tab.url }));
      postCanvasAuth(tab, frame.contentWindow);
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [tabs]);

  const startResize = (event: React.PointerEvent<HTMLButtonElement>) => {
    event.preventDefault();
    const resizeTarget = event.currentTarget;
    const pointerId = event.pointerId;
    resizeTarget.setPointerCapture?.(pointerId);
    const startX = event.clientX;
    const startY = event.clientY;
    const startWidth = width;
    const startHeight = height;
    let allowSidebarCollapse = !sidebarCollapsed;
    let allowDockedWidth = docked;
    let maxSplitWidth = sidebarCollapsed ? expandedSplitMaxWidth : splitMaxWidth;
    document.body.style.cursor = 'nesw-resize';
    document.body.style.userSelect = 'none';

    const onMove = (moveEvent: PointerEvent) => {
      if (moveEvent.pointerId !== pointerId) return;
      const requestedWidth = startWidth + startX - moveEvent.clientX;
      if (allowSidebarCollapse && requestedWidth >= sidebarCollapseWidth) {
        allowSidebarCollapse = false;
        maxSplitWidth = expandedSplitMaxWidth;
        onCollapseSidebar();
      }
      if (!allowDockedWidth && requestedWidth > maxSplitWidth) {
        allowDockedWidth = true;
        onRequestDock();
      }
      const maxWidth = allowDockedWidth
        ? Math.max(MIN_WIDTH, window.innerWidth - 32)
        : maxSplitWidth;
      setWidth(Math.min(maxWidth, Math.max(MIN_WIDTH, requestedWidth)));
      setHeight(Math.min(
        Math.max(MIN_HEIGHT, window.innerHeight - 32),
        Math.max(MIN_HEIGHT, startHeight + moveEvent.clientY - startY),
      ));
    };
    const onUp = (upEvent: PointerEvent) => {
      if (upEvent.pointerId !== pointerId) return;
      resizeTarget.releasePointerCapture?.(pointerId);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      document.removeEventListener('pointermove', onMove);
      document.removeEventListener('pointerup', onUp);
      document.removeEventListener('pointercancel', onUp);
    };
    document.addEventListener('pointermove', onMove);
    document.addEventListener('pointerup', onUp);
    document.addEventListener('pointercancel', onUp);
  };

  const openActiveInBrowser = () => {
    const activeTab = tabs.find((tab) => tab.key === activeKey);
    if (!activeTab || activeTab.key === 'document' || activeTab.key === 'side-chat') return;
    const targetUrl = new URL(activeTab.url, window.location.origin);
    targetUrl.searchParams.delete('embedded');
    const target = targetUrl.toString();
    window.open(target, '_blank', 'noopener,noreferrer');
  };

  const refreshActivePage = () => {
    if (!activeKey || activeKey === 'document') return;
    setRefreshTokens((current) => ({
      ...current,
      [activeKey]: (current[activeKey] ?? 0) + 1,
    }));
    setLoadedUrls((current) => {
      const next = { ...current };
      delete next[activeKey];
      return next;
    });
  };

  const closeWindowWithParticles = () => {
    if (closingWindow) return;
    setClosingWindow(true);
    closeTimerRef.current = window.setTimeout(() => {
      closeTimerRef.current = null;
      onCloseWindow();
    }, CLOSE_ANIMATION_MS);
  };

  return (
    <section
      className={[
        'embedded-app-window',
        fullscreen ? 'is-fullscreen' : '',
        closingWindow ? 'is-closing' : '',
        minimized ? 'is-minimized' : '',
      ].filter(Boolean).join(' ')}
      style={fullscreen ? undefined : { width, height }}
      aria-label="应用窗口"
      aria-busy={closingWindow}
      aria-hidden={minimized}
    >
      <header className="embedded-app-window__header">
        <div className="embedded-app-window__tabs" role="tablist" aria-label="应用标签页">
          {tabs.map((tab) => {
            const active = tab.key === activeKey;
            return (
              <div key={tab.key} className={`embedded-app-window__tab${active ? ' is-active' : ''}`}>
                <button
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => onSelectTab(tab.key)}
                  title={tab.title}
                >
                  {appIcon(tab.key)}
                  <span>{tab.title}</span>
                </button>
                {!(tab.key === 'canvas' && tab.canvasMode) && (
                  <button
                    type="button"
                    className="embedded-app-window__tab-close"
                    onClick={() => onCloseTab(tab.key)}
                    title={`关闭${tab.title}`}
                    aria-label={`关闭${tab.title}`}
                  >
                    <X size={12} strokeWidth={2.2} />
                  </button>
                )}
              </div>
            );
          })}
        </div>

        <div className="embedded-app-window__actions">
          {activeKey !== 'document' && (
            <>
              <button
                type="button"
                onClick={refreshActivePage}
                title="刷新当前页面"
                aria-label="刷新当前页面"
              >
                <RefreshCw size={16} strokeWidth={1.9} />
              </button>
              {activeKey !== 'side-chat' && (
                <button
                  type="button"
                  onClick={openActiveInBrowser}
                  title="跳转到网页"
                  aria-label="跳转到网页"
                >
                  <ExternalLink size={16} strokeWidth={1.9} />
                </button>
              )}
            </>
          )}
          <button
            type="button"
            onClick={onMinimize}
            title="最小化为图标"
            aria-label="最小化为图标"
          >
            <Minus size={17} strokeWidth={2} />
          </button>
          <button
            type="button"
            onClick={() => setFullscreen((value) => !value)}
            title={fullscreen ? '退出全屏' : '全屏'}
            aria-label={fullscreen ? '退出全屏' : '全屏'}
          >
            {fullscreen ? <Minimize2 size={16} strokeWidth={1.9} /> : <Maximize2 size={16} strokeWidth={1.9} />}
          </button>
          <button
            type="button"
            disabled={canvasLocked}
            onClick={closeWindowWithParticles}
            title={canvasLocked ? '请先离开画布编辑模式' : '关闭全部窗口'}
            aria-label={canvasLocked ? '先离开画布编辑模式' : '关闭全部窗口'}
          >
            <X size={17} strokeWidth={2} />
          </button>
        </div>
      </header>

      <div className="embedded-app-window__body">
        {tabs.map((tab) => {
          const active = tab.key === activeKey;
          const localDocument = tab.key === 'document';
          const loading = !localDocument && loadedUrls[tab.key] !== tab.url;
          return (
            <div key={tab.key} className={`embedded-app-window__frame${active ? ' is-active' : ''}`} aria-hidden={!active}>
              {loading && (
                <div className="embedded-app-window__loading">
                  <LoaderCircle size={20} className="is-spinning" />
                  <span>正在打开{tab.title}</span>
                </div>
              )}
              {localDocument ? (
                <div className="embedded-app-window__document">
                  {tab.documentAttachment && (
                    <DocumentPreviewSurface attachment={tab.documentAttachment} />
                  )}
                </div>
              ) : (
                <iframe
                  key={`${tab.key}:${refreshTokens[tab.key] ?? 0}`}
                  ref={(node) => { frameRefs.current[tab.key] = node; }}
                  src={tab.url}
                  data-app-key={tab.key}
                  title={tab.title}
                  onLoad={() => {
                    setLoadedUrls((current) => ({ ...current, [tab.key]: tab.url }));
                    postCanvasAuth(tab, frameRefs.current[tab.key]?.contentWindow ?? null);
                  }}
                  allow="clipboard-read; clipboard-write; camera; microphone; fullscreen"
                  allowFullScreen
                  tabIndex={active ? 0 : -1}
                />
              )}
            </div>
          );
        })}
      </div>

      {!fullscreen && (
        <button
          type="button"
          className="embedded-app-window__resize"
          onPointerDown={startResize}
          title="拖拽调整窗口大小"
          aria-label="拖拽调整窗口大小"
        >
          <Grip size={15} strokeWidth={1.8} />
        </button>
      )}

    </section>
  );
}
