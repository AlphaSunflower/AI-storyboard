import { useEffect, useRef, useState, useCallback } from 'react';
import { createPortal } from 'react-dom';

export interface ContextMenuItem {
  label: string;
  icon?: React.ReactNode;
  danger?: boolean;
  disabled?: boolean;
  onClick: () => void;
}

/** 通用右键菜单：包裹目标元素，右键弹出操作列表 */
export function ContextMenu({ items, children }: { items: ContextMenuItem[]; children: React.ReactNode }) {
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  const close = useCallback(() => setPos(null), []);

  useEffect(() => {
    if (!pos) return;
    const onDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) close();
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
    // 用 mousedown 而非 click，确保在其他 click handler 之前关闭
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [pos, close]);

  const handleContextMenu = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    // 确保菜单不超出视口
    const x = Math.min(e.clientX, window.innerWidth - 180);
    const y = Math.min(e.clientY, window.innerHeight - items.length * 38 - 16);
    setPos({ x, y });
  };

  return (
    <div onContextMenu={handleContextMenu} style={{ display: 'contents' }}>
      {children}
      {pos && createPortal(
        <div
          ref={menuRef}
          style={{
            position: 'fixed',
            left: pos.x,
            top: pos.y,
            minWidth: 140,
            padding: 4,
            background: 'white',
            border: '1px solid rgba(0,0,0,0.10)',
            borderRadius: 10,
            boxShadow: '0 8px 24px rgba(0,0,0,0.14)',
            zIndex: 9999,
          }}
        >
          {items.map((it) => (
            <button
              key={it.label}
              disabled={it.disabled}
              onClick={(e) => { e.stopPropagation(); close(); if (!it.disabled) it.onClick(); }}
              style={{
                display: 'block',
                width: '100%',
                textAlign: 'left',
                padding: '8px 12px',
                border: 'none',
                background: 'transparent',
                borderRadius: 6,
                fontSize: 13,
                cursor: it.disabled ? 'not-allowed' : 'pointer',
                color: it.danger ? '#d92d20' : it.disabled ? 'var(--color-muted-soft)' : 'var(--color-ink)',
              }}
              onMouseEnter={(e) => { if (!it.disabled) (e.target as HTMLElement).style.background = 'rgba(38,49,72,0.06)'; }}
              onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
            >
              {it.icon && <span style={{ marginRight: 6 }}>{it.icon}</span>}
              {it.label}
            </button>
          ))}
        </div>,
        document.body,
      )}
    </div>
  );
}
