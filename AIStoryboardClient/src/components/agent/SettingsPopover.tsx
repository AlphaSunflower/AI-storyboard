import { createPortal } from 'react-dom';
import { DS } from './ChatComposer';

/**
 * 设置弹出菜单（portal 到 body，含边界检测）。
 * 抽取自 ChatPage collapsed/expanded 两处重复代码。
 */
export function SettingsPopover({
  anchorRef,
  onProfile,
  onDocs,
  onEditor,
  onLogout,
}: {
  anchorRef: React.RefObject<HTMLElement | null>;
  onProfile: () => void;
  onDocs: () => void;
  onEditor: () => void;
  onLogout: () => void;
}) {
  if (!anchorRef.current) return null;
  const r = anchorRef.current.getBoundingClientRect();
  const menuH = 200; // 预估菜单高度
  const menuW = 170;
  // 边界检测：向上展开时不超过视口顶部
  const top = Math.max(8, Math.min(r.top - menuH, window.innerHeight - menuH - 8));
  // 右侧定位，不超出视口右缘
  const left = Math.min(r.right + 8, window.innerWidth - menuW - 8);

  return createPortal(
    <div style={{
      position: 'fixed', top, left, width: menuW,
      background: 'white', border: `1px solid ${DS.border}`, borderRadius: 12,
      boxShadow: '0 8px 24px rgba(0, 0, 0, 0.12)', padding: 6, zIndex: 2000,
    }}>
      {[
        { label: '个人信息', icon: 'user' as const, onClick: onProfile },
        { label: '使用文档', icon: 'docs' as const, onClick: onDocs },
        { label: '编辑器', icon: 'editor' as const, onClick: onEditor },
        { label: '退出登录', icon: 'logout' as const, onClick: onLogout, color: '#d92d20' },
      ].map((it) => (
        <button
          key={it.label}
          onClick={it.onClick}
          style={{
            width: '100%', textAlign: 'left', padding: '9px 12px', border: 'none',
            background: 'transparent', borderRadius: 8, fontSize: 14, cursor: 'pointer',
            color: it.color ?? DS.ink, display: 'flex', alignItems: 'center', gap: 8,
          }}
          onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = DS.hover; }}
          onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent'; }}
        >
          <MenuIcon kind={it.icon} />
          {it.label}
        </button>
      ))}
    </div>,
    document.body,
  );
}

/** 菜单图标（复用，不再重复 SVG 路径） */
function MenuIcon({ kind }: { kind: 'user' | 'docs' | 'editor' | 'logout' }) {
  const p = { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none' as const, stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  switch (kind) {
    case 'user': return <svg {...p}><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>;
    case 'docs': return <svg {...p}><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/></svg>;
    case 'editor': return <svg {...p}><path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"/></svg>;
    case 'logout': return <svg {...p}><path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/></svg>;
  }
}
