import { useEffect, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';

export interface LibraryActionItem {
  id: string;
  label: string;
  icon?: React.ReactNode;
  danger?: boolean;
  disabled?: boolean;
}

export interface LibraryActionMenuProps {
  open: boolean;
  x: number;
  y: number;
  title?: string;
  items: LibraryActionItem[];
  onAction: (id: string) => void;
  onClose: () => void;
}

export function LibraryActionMenu({
  open,
  x,
  y,
  title,
  items,
  onAction,
  onClose,
}: LibraryActionMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null);
  const position = useMemo(() => {
    const width = 210;
    const height = Math.min(420, 48 + items.length * 38);
    return {
      left: Math.max(8, Math.min(x, window.innerWidth - width - 8)),
      top: Math.max(8, Math.min(y, window.innerHeight - height - 8)),
    };
  }, [items.length, x, y]);

  useEffect(() => {
    if (!open) return;
    const close = (event: Event) => {
      const target = event.target;
      const insideMenu = target instanceof Node && menuRef.current?.contains(target);
      const insideMenuItem = target instanceof Element && Boolean(target.closest('button'));
      if (!insideMenu || !insideMenuItem) onClose();
    };
    const key = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    const closeOnScroll = () => onClose();
    const closeOnBlur = () => onClose();
    document.addEventListener('pointerdown', close, true);
    document.addEventListener('scroll', closeOnScroll, true);
    document.addEventListener('keydown', key);
    window.addEventListener('blur', closeOnBlur);
    return () => {
      document.removeEventListener('pointerdown', close, true);
      document.removeEventListener('scroll', closeOnScroll, true);
      document.removeEventListener('keydown', key);
      window.removeEventListener('blur', closeOnBlur);
    };
  }, [onClose, open]);

  if (!open) return null;
  return createPortal(
    <div
      ref={menuRef}
      className="library-action-menu"
      style={position}
      role="menu"
      onContextMenu={(event) => event.preventDefault()}
    >
      {title && <strong>{title}</strong>}
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          role="menuitem"
          className={item.danger ? 'is-danger' : ''}
          disabled={item.disabled}
          onClick={() => {
            if (item.disabled) return;
            onAction(item.id);
          }}
        >
          {item.icon}
          <span>{item.label}</span>
        </button>
      ))}
    </div>,
    document.body,
  );
}
