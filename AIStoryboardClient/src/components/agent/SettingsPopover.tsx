import { createPortal } from 'react-dom';
import { User, FileText, PenLine, LogOut } from 'lucide-react';
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
        { label: '个人信息', icon: <User size={16} strokeWidth={1.8} />, onClick: onProfile },
        { label: '使用文档', icon: <FileText size={16} strokeWidth={1.8} />, onClick: onDocs },
        { label: '编辑器', icon: <PenLine size={16} strokeWidth={1.8} />, onClick: onEditor },
        { label: '退出登录', icon: <LogOut size={16} strokeWidth={1.8} />, onClick: onLogout, color: '#d92d20' },
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
          {it.icon}
          {it.label}
        </button>
      ))}
    </div>,
    document.body,
  );
}

