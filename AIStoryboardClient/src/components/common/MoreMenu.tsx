import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

export interface MoreMenuItem {
  label: string;
  danger?: boolean;
  disabled?: boolean;
  onClick: () => void;
}

/**
 * 三点「⋯」菜单按钮（DeepSeek 风格）：点击弹出纵向操作列表（重命名/归档/删除等），
 * 替代直接平铺小图标。菜单 portal 到 body + fixed 定位，避免被 overflow 容器裁剪；
 * 按钮与菜单内容 mousedown/click 全部 stopPropagation——嵌套在其他弹层内部
 * （如 ProjectDropdown 的 document 点击外部关闭监听）时不会误触外层关闭。
 * 近视口底部/左缘时自动向上/向右翻转展开。
 */
export function MoreMenu({ items, size = 28 }: { items: MoreMenuItem[]; size?: number }) {
  const [open, setOpen] = useState(false);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  // 点击外部 / Esc 关闭
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!btnRef.current?.contains(e.target as Node) && !menuRef.current?.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const stop = (e: React.SyntheticEvent) => e.stopPropagation();
  const rect = btnRef.current?.getBoundingClientRect();

  return (
    <>
      <button
        ref={btnRef}
        onClick={(e) => { stop(e); setOpen(!open); }}
        onMouseDown={stop}
        title="更多操作"
        style={{
          width: size,
          height: size,
          border: 'none',
          background: 'transparent',
          cursor: 'pointer',
          borderRadius: 8,
          color: 'var(--color-muted)',
          fontSize: 15,
          lineHeight: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexShrink: 0,
        }}
        onMouseEnter={(e) => { (e.target as HTMLElement).style.background = 'rgba(38, 49, 72, 0.06)'; }}
        onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
      >
        ⋯
      </button>
      {open && rect && createPortal(
        <div
          ref={menuRef}
          onMouseDown={stop}
          onClick={stop}
          style={{
            position: 'fixed',
            // 默认在按钮下方右对齐展开；空间不足时向上翻转 / 向左展开
            top: rect.bottom + 4 + 180 <= window.innerHeight ? rect.bottom + 4 : undefined,
            bottom: rect.bottom + 4 + 180 > window.innerHeight ? window.innerHeight - rect.top + 4 : undefined,
            right: rect.right - 130 >= 0 ? window.innerWidth - rect.right : undefined,
            left: rect.right - 130 < 0 ? rect.left : undefined,
            minWidth: 120,
            padding: 6,
            background: 'white',
            border: '1px solid rgba(0, 0, 0, 0.10)',
            borderRadius: 12,
            boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)',
            zIndex: 2000,
          }}
        >
          {items.map((it) => (
            <button
              key={it.label}
              disabled={it.disabled}
              onClick={() => { setOpen(false); if (!it.disabled) it.onClick(); }}
              style={{
                width: '100%',
                textAlign: 'left',
                padding: '9px 12px',
                border: 'none',
                background: 'transparent',
                borderRadius: 8,
                fontSize: 14,
                cursor: it.disabled ? 'not-allowed' : 'pointer',
                color: it.danger ? '#d92d20' : it.disabled ? 'var(--color-muted-soft)' : 'var(--color-ink)',
              }}
              onMouseEnter={(e) => { if (!it.disabled) (e.target as HTMLElement).style.background = 'rgba(38, 49, 72, 0.06)'; }}
              onMouseLeave={(e) => { (e.target as HTMLElement).style.background = 'transparent'; }}
            >
              {it.label}
            </button>
          ))}
        </div>,
        document.body,
      )}
    </>
  );
}
