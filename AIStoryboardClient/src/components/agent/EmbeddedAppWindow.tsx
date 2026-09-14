import { useEffect, useState } from 'react';
import { ExternalLink, FileText, Frame, Grip, LoaderCircle, Maximize2, Minimize2, PenLine, X } from 'lucide-react';
import './EmbeddedAppWindow.css';

export interface EmbeddedAppDescriptor {
  key: 'canvas' | 'editor' | 'docs';
  title: string;
  url: string;
  projectId?: string;
}

interface EmbeddedAppWindowProps {
  tabs: EmbeddedAppDescriptor[];
  activeKey: string;
  onSelectTab: (key: string) => void;
  onCloseTab: (key: string) => void;
  onCloseWindow: () => void;
  onLayoutChange: (layout: { width: number; height: number; fullscreen: boolean }) => void;
}

const DEFAULT_WIDTH = 420;
const MIN_WIDTH = 360;
const MAX_WIDTH = 760;
const MIN_HEIGHT = 420;

function defaultHeight() {
  return Math.max(MIN_HEIGHT, Math.min(820, window.innerHeight - 132));
}

function appIcon(key: EmbeddedAppDescriptor['key']) {
  if (key === 'canvas') return <Frame size={13} strokeWidth={1.9} />;
  if (key === 'editor') return <PenLine size={13} strokeWidth={1.9} />;
  return <FileText size={13} strokeWidth={1.9} />;
}

export function EmbeddedAppWindow({
  tabs,
  activeKey,
  onSelectTab,
  onCloseTab,
  onCloseWindow,
  onLayoutChange,
}: EmbeddedAppWindowProps) {
  const [width, setWidth] = useState(DEFAULT_WIDTH);
  const [height, setHeight] = useState(defaultHeight);
  const [fullscreen, setFullscreen] = useState(false);
  const [loadedUrls, setLoadedUrls] = useState<Record<string, string>>({});

  useEffect(() => {
    onLayoutChange({ width, height, fullscreen });
  }, [fullscreen, height, onLayoutChange, width]);

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
      if (fullscreen) setFullscreen(false);
      else if (activeKey) onCloseTab(activeKey);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activeKey, fullscreen, onCloseTab]);

  const startResize = (event: React.MouseEvent) => {
    event.preventDefault();
    const startX = event.clientX;
    const startY = event.clientY;
    const startWidth = width;
    const startHeight = height;
    document.body.style.cursor = 'nesw-resize';
    document.body.style.userSelect = 'none';

    const onMove = (moveEvent: MouseEvent) => {
      setWidth(Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, startWidth + startX - moveEvent.clientX)));
      setHeight(Math.min(
        Math.max(MIN_HEIGHT, window.innerHeight - 32),
        Math.max(MIN_HEIGHT, startHeight + moveEvent.clientY - startY),
      ));
    };
    const onUp = () => {
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  const openActiveInBrowser = () => {
    const activeTab = tabs.find((tab) => tab.key === activeKey);
    if (!activeTab) return;
    const target = new URL(activeTab.url, window.location.origin).toString();
    window.open(target, '_blank', 'noopener,noreferrer');
  };

  return (
    <section
      className={`embedded-app-window${fullscreen ? ' is-fullscreen' : ''}`}
      style={fullscreen ? undefined : { width, height }}
      aria-label="应用窗口"
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
                <button
                  type="button"
                  className="embedded-app-window__tab-close"
                  onClick={() => onCloseTab(tab.key)}
                  title={`关闭${tab.title}`}
                  aria-label={`关闭${tab.title}`}
                >
                  <X size={12} strokeWidth={2.2} />
                </button>
              </div>
            );
          })}
        </div>

        <div className="embedded-app-window__actions">
          <button
            type="button"
            onClick={openActiveInBrowser}
            title="跳转到网页"
            aria-label="跳转到网页"
          >
            <ExternalLink size={16} strokeWidth={1.9} />
          </button>
          <button
            type="button"
            onClick={() => setFullscreen((value) => !value)}
            title={fullscreen ? '退出全屏' : '全屏'}
            aria-label={fullscreen ? '退出全屏' : '全屏'}
          >
            {fullscreen ? <Minimize2 size={16} strokeWidth={1.9} /> : <Maximize2 size={16} strokeWidth={1.9} />}
          </button>
          <button type="button" onClick={onCloseWindow} title="关闭应用窗口" aria-label="关闭应用窗口">
            <X size={17} strokeWidth={2} />
          </button>
        </div>
      </header>

      <div className="embedded-app-window__body">
        {tabs.map((tab) => {
          const active = tab.key === activeKey;
          const loading = loadedUrls[tab.key] !== tab.url;
          return (
            <div key={tab.key} className={`embedded-app-window__frame${active ? ' is-active' : ''}`} aria-hidden={!active}>
              {loading && (
                <div className="embedded-app-window__loading">
                  <LoaderCircle size={20} className="is-spinning" />
                  <span>正在打开{tab.title}</span>
                </div>
              )}
              <iframe
                src={tab.url}
                title={tab.title}
                onLoad={() => setLoadedUrls((current) => ({ ...current, [tab.key]: tab.url }))}
                allow="clipboard-read; clipboard-write; camera; microphone; fullscreen"
                allowFullScreen
                tabIndex={active ? 0 : -1}
              />
            </div>
          );
        })}
      </div>

      {!fullscreen && (
        <button
          type="button"
          className="embedded-app-window__resize"
          onMouseDown={startResize}
          title="拖拽调整窗口大小"
          aria-label="拖拽调整窗口大小"
        >
          <Grip size={15} strokeWidth={1.8} />
        </button>
      )}
    </section>
  );
}
