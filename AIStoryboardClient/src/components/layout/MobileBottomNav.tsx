import { useNavigate, useLocation } from 'react-router-dom';

interface MobileBottomNavProps {
  onOpenAssets: () => void;
}

/** 手机端底部 Tab 栏：聊天 | 分镜 | 资产库 */
export function MobileBottomNav({ onOpenAssets }: MobileBottomNavProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const isActive = (path: string) => location.pathname === path;

  return (
    <div style={{
      height: 56, flexShrink: 0,
      display: 'flex', alignItems: 'center', justifyContent: 'space-around',
      borderTop: '1px solid var(--color-hairline)',
      background: 'white',
    }}>
      <TabBtn
        active={isActive('/chat')}
        onClick={() => navigate('/chat')}
        label="聊天"
        icon={<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" /></svg>}
      />
      <TabBtn
        active={isActive('/editor')}
        onClick={() => navigate('/editor')}
        label="分镜"
        icon={<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18" /><line x1="7" y1="2" x2="7" y2="22" /><line x1="17" y1="2" x2="17" y2="22" /><line x1="2" y1="12" x2="22" y2="12" /></svg>}
      />
      <TabBtn
        active={false}
        onClick={onOpenAssets}
        label="资产库"
        icon={<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 3h7l2 3h9v13H3z" /></svg>}
      />
    </div>
  );
}

function TabBtn({ active, onClick, label, icon }: { active: boolean; onClick: () => void; label: string; icon: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      style={{
        display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
        border: 'none', background: 'transparent', cursor: 'pointer',
        color: active ? 'var(--color-primary)' : 'var(--color-muted)',
        fontSize: 11, fontWeight: active ? 600 : 400,
        padding: '4px 16px',
      }}
    >
      {icon}
      {label}
    </button>
  );
}
