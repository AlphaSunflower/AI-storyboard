import { useEffect, useRef, useState, useCallback, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import './ContextMenu.css';

export interface ContextMenuItem {
  label: string;
  icon?: ReactNode;
  danger?: boolean;
  disabled?: boolean;
  /** 在这一项之前画一条分隔线：把「删除」这类破坏性操作和上面的常规操作分开。 */
  dividerBefore?: boolean;
  onClick: () => void;
}

/** 菜单宽度：定位时用它做视口夹取，和 CSS 里的 min-width 保持一致。 */
const MENU_WIDTH = 168;
/** 单行高度估算：用于在拿不到真实高度时先把菜单夹进视口。 */
const ITEM_HEIGHT = 34;
const VIEWPORT_PADDING = 8;

function clampToViewport(x: number, y: number, itemCount: number) {
  const estimatedHeight = itemCount * ITEM_HEIGHT + VIEWPORT_PADDING * 2;
  return {
    x: Math.max(VIEWPORT_PADDING, Math.min(x, window.innerWidth - MENU_WIDTH - VIEWPORT_PADDING)),
    y: Math.max(VIEWPORT_PADDING, Math.min(y, window.innerHeight - estimatedHeight)),
  };
}

/**
 * 受控右键菜单：坐标和条目由调用方提供，自己只负责渲染、定位与关闭。
 *
 * 之所以要有这个受控版本：声明式包装会插入一层 `display: contents` 的 span，
 * 而 `display: contents` 不生成盒子，GSAP 打在上面的 y/scale 会被静默忽略。
 * 素材网格的入场动画遍历 `grid.children`，一旦每个格子被包一层就会整体失效，
 * 所以网格这类场景改为「一个实例 + 每个格子报坐标」。
 */
export function ContextMenuSurface({
  x,
  y,
  items,
  onClose,
}: {
  x: number;
  y: number;
  items: ContextMenuItem[];
  onClose: () => void;
}) {
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onDown = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) onClose();
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
  }, [onClose]);

  // 打开后把焦点收进菜单，键盘用户才能用方向键/Tab 走到条目上。
  useEffect(() => {
    menuRef.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
  }, []);

  const position = clampToViewport(x, y, items.length);

  return createPortal(
    <div
      ref={menuRef}
      className="context-menu"
      aria-label="操作菜单"
      style={{ left: position.x, top: position.y, minWidth: MENU_WIDTH }}
    >
      {items.map((item) => (
        <div key={item.label}>
          {item.dividerBefore && <div className="context-menu__divider" role="separator" />}
          {/* 这里刻意用普通 button 而不是 role="menuitem"：菜单语义要配方向键漫游才算数，
              只贴个 role 会让元素从 button 角色里消失，读屏和既有用例都跟着变。 */}
          <button
            type="button"
            disabled={item.disabled}
            className={`context-menu__item${item.danger ? ' is-danger' : ''}`}
            onClick={(event) => {
              event.stopPropagation();
              onClose();
              if (!item.disabled) item.onClick();
            }}
          >
            {item.icon && <span className="context-menu__icon">{item.icon}</span>}
            <span>{item.label}</span>
          </button>
        </div>
      ))}
    </div>,
    document.body,
  );
}

/** 声明式右键菜单：包裹目标元素，右键时在该处弹出操作列表。 */
export function ContextMenu({ items, children }: { items: ContextMenuItem[]; children: ReactNode }) {
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);

  const close = useCallback(() => setPos(null), []);

  const handleContextMenu = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setPos({ x: e.clientX, y: e.clientY });
  };

  return (
    <span onContextMenu={handleContextMenu} style={{ display: 'contents' }}>
      {children}
      {pos && <ContextMenuSurface x={pos.x} y={pos.y} items={items} onClose={close} />}
    </span>
  );
}
