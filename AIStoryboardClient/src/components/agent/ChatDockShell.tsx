import { useEffect, useState, type ReactNode } from 'react';
import { ChevronDown, ChevronRight, ChevronUp, LoaderCircle, Moon } from 'lucide-react';
import './ChatDockShell.css';

export type ChatDockPresentation = 'pill' | 'open' | 'history' | 'hidden';

interface ChatDockShellProps {
  active: boolean;
  presentation: ChatDockPresentation;
  title: string;
  streaming: boolean;
  onOpen: () => void;
  onCollapse: () => void;
  onHide: () => void;
  onRestore: () => void;
  onShowHistory: () => void;
  onCollapseHistory: () => void;
  children: ReactNode;
}

export function ChatDockShell({
  active,
  presentation,
  title,
  streaming,
  onOpen,
  onCollapse,
  onHide,
  onRestore,
  onShowHistory,
  onCollapseHistory,
  children,
}: ChatDockShellProps) {
  const [handleVisible, setHandleVisible] = useState(false);
  const historyOpen = presentation === 'history';
  const panelOpen = presentation === 'open' || historyOpen;

  useEffect(() => {
    if (presentation !== 'hidden') setHandleVisible(false);
  }, [presentation]);

  useEffect(() => {
    if (!active || !panelOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCollapse();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [active, onCollapse, panelOpen]);

  const content = (
    <div
      id="moon-chat-dock-content"
      className="chat-dock-shell__content"
      aria-hidden={active && !panelOpen}
    >
      {children}
    </div>
  );

  if (!active) {
    return <div className="chat-dock-shell is-split">{content}</div>;
  }

  return (
    <>
      {panelOpen && (
        <div
          className="chat-dock-shell__backdrop"
          aria-label="关闭 Moon 浮窗"
          onClick={onCollapse}
        />
      )}
      <section
        className={`chat-dock-shell is-docked is-${presentation}`}
        aria-label="Moon 智能体悬浮聊天"
        aria-expanded={historyOpen}
      >
        {content}

        {panelOpen && (
          <button
            type="button"
            className="chat-dock-shell__history-toggle"
            onClick={historyOpen ? onCollapseHistory : onShowHistory}
            aria-label={historyOpen ? '收起聊天记录' : '查看聊天记录'}
            aria-expanded={historyOpen}
          >
            <span className="chat-dock-shell__history-title">{title}</span>
            <span className="chat-dock-shell__history-action">
              {streaming && <LoaderCircle size={13} className="is-spinning" />}
              <span>{historyOpen ? '收起聊天记录' : '查看聊天记录'}</span>
              {historyOpen
                ? <ChevronDown size={15} strokeWidth={2} />
                : <ChevronRight size={15} strokeWidth={2} />}
            </span>
          </button>
        )}

        {presentation === 'pill' && (
          <>
            <button
              type="button"
              className="chat-dock-shell__pill"
              onClick={onOpen}
              aria-label={`打开 Moon 智能体：${title}`}
              aria-controls="moon-chat-dock-content"
            >
              <span className="chat-dock-shell__pill-icon"><Moon size={17} strokeWidth={1.9} /></span>
              <span className="chat-dock-shell__pill-title">{title}</span>
              <span className="chat-dock-shell__pill-status">
                {streaming && <LoaderCircle size={13} className="is-spinning" />}
                <ChevronUp size={16} strokeWidth={2} />
              </span>
            </button>
            <button
              type="button"
              className="chat-dock-shell__pill-hide"
              onClick={onHide}
              aria-label="隐藏 Moon 智能体"
              title="隐藏 Moon 智能体"
            >
              <ChevronDown size={14} strokeWidth={2} />
            </button>
          </>
        )}

        {presentation === 'hidden' && (
          <div
            className="chat-dock-shell__hover-zone"
            data-testid="chat-dock-hover-zone"
            tabIndex={0}
            aria-label="Moon 智能体底部把手"
            onMouseEnter={() => setHandleVisible(true)}
            onMouseLeave={() => setHandleVisible(false)}
            onFocus={() => setHandleVisible(true)}
            onBlur={() => setHandleVisible(false)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                onRestore();
              }
            }}
          >
            {handleVisible && (
              <button
                type="button"
                className="chat-dock-shell__handle"
                onClick={onRestore}
                aria-label="显示 Moon 智能体"
              >
                <Moon size={15} strokeWidth={1.9} />
              </button>
            )}
          </div>
        )}
      </section>
    </>
  );
}
