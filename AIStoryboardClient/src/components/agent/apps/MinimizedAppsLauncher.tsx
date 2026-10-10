import { useEffect, useRef, useState } from 'react';
import { Frame, GripVertical, Library, PanelRight, PenLine, FileText, X } from 'lucide-react';
import type { EmbeddedAppDescriptor } from './EmbeddedAppWindow';
import './MinimizedAppsLauncher.css';

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function appIcon(key: EmbeddedAppDescriptor['key']) {
  if (key === 'canvas') return <Frame size={18} strokeWidth={1.9} />;
  if (key === 'editor') return <PenLine size={18} strokeWidth={1.9} />;
  if (key === 'library') return <Library size={18} strokeWidth={1.9} />;
  if (key === 'side-chat') return <PanelRight size={18} strokeWidth={1.9} />;
  return <FileText size={18} strokeWidth={1.9} />;
}

export function MinimizedAppsLauncher({
  tabs,
  activeKey,
  onRestore,
  onCloseAll,
}: {
  tabs: EmbeddedAppDescriptor[];
  activeKey: EmbeddedAppDescriptor['key'];
  onRestore: () => void;
  onCloseAll: () => void;
}) {
  const [top, setTop] = useState(() => clamp(window.innerHeight * 0.42, 92, window.innerHeight - 150));
  const dragRef = useRef<{ startY: number; startTop: number; moved: boolean } | null>(null);
  const activeTab = tabs.find((tab) => tab.key === activeKey) ?? tabs[0];

  useEffect(() => {
    const onResize = () => {
      setTop((current) => clamp(current, 92, Math.max(92, window.innerHeight - 150)));
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const startDrag = (event: React.PointerEvent<HTMLElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    dragRef.current = {
      startY: event.clientY,
      startTop: top,
      moved: false,
    };
  };

  const moveDrag = (event: React.PointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    if (!drag) return;
    const delta = event.clientY - drag.startY;
    if (Math.abs(delta) > 4) {
      drag.moved = true;
    }
    setTop(clamp(drag.startTop + delta, 92, Math.max(92, window.innerHeight - 150)));
  };

  const endDrag = () => {
    dragRef.current = null;
  };

  const restore = () => {
    onRestore();
  };

  return (
    <aside
      className="minimized-apps"
      style={{ top }}
      aria-label={`应用窗口已最小化，共 ${tabs.length} 个标签`}
    >
      <button
        type="button"
        className="minimized-apps__close"
        onPointerDown={(event) => event.stopPropagation()}
        onClick={(event) => {
          event.stopPropagation();
          onCloseAll();
        }}
        title="关闭全部窗口"
        aria-label="关闭全部窗口"
      >
        <X size={12} strokeWidth={2.4} />
      </button>
      <button
        type="button"
        className="minimized-apps__restore"
        onClick={restore}
        title={activeTab ? `展开${activeTab.title}` : '展开应用窗口'}
        aria-label={activeTab ? `展开${activeTab.title}` : '展开应用窗口'}
      >
        <span className="minimized-apps__icon" aria-hidden="true">
          {activeTab && appIcon(activeTab.key)}
        </span>
        <span className="minimized-apps__count">{tabs.length}</span>
      </button>
      <button
        type="button"
        className="minimized-apps__drag"
        onPointerDown={startDrag}
        onPointerMove={moveDrag}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        title="上下调整图标位置"
        aria-label="上下调整图标位置"
      >
        <GripVertical size={11} strokeWidth={2} />
      </button>
    </aside>
  );
}
