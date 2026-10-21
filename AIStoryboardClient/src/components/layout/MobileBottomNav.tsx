import { useNavigate, useLocation } from 'react-router-dom';
import { Library, MessageCircle, UserRound } from 'lucide-react';

type MobileTabId = 'chat' | 'library' | 'mine';

interface MobileBottomNavProps {
  /**
   * 手机主壳 Tab 栏的定位方式：
   * - `fixed`（默认）：页面自身是长文档滚动流（项目库、我的），Tab 栏固定贴底；
   * - `false`：页面是 `100dvh + flex column` 全屏壳（对话、分镜编辑器），Tab 栏作为最后一个 flex 子项。
   */
  fixed?: boolean;
  /** 未读数角标：值为 0 / undefined 时不显示。 */
  badges?: Partial<Record<MobileTabId, number>>;
  /** 画布内嵌子窗的旧版两 Tab（分镜 / 分镜库），只用在 `?embedded=1` 的 iframe 里。 */
  embeddedEditor?: boolean;
}

/** 手机端主导航：智能体对话 | 项目库 | 我的。 */
const TABS: Array<{
  id: MobileTabId;
  label: string;
  path: string;
  icon: typeof MessageCircle;
  match: (pathname: string) => boolean;
}> = [
  {
    id: 'chat',
    label: '智能体对话',
    path: '/chat',
    icon: MessageCircle,
    // /chat/storyboards 属于「项目库」语义，不能被对话 Tab 抢高亮
    match: (pathname) => pathname === '/chat' || (pathname.startsWith('/chat/') && !pathname.startsWith('/chat/storyboards')),
  },
  {
    id: 'library',
    label: '项目库',
    path: '/library',
    icon: Library,
    match: (pathname) => pathname.startsWith('/library') || pathname.startsWith('/chat/storyboards'),
  },
  {
    id: 'mine',
    label: '我的',
    path: '/account',
    icon: UserRound,
    match: (pathname) => pathname.startsWith('/account'),
  },
];

const SAFE_AREA = 'env(safe-area-inset-bottom, 0px)';

export function MobileBottomNav({ fixed = true, badges, embeddedEditor = false }: MobileBottomNavProps) {
  const navigate = useNavigate();
  const location = useLocation();

  if (embeddedEditor) {
    return <EmbeddedAppNav pathname={location.pathname} navigate={navigate} />;
  }

  return (
    <nav
      aria-label="手机端主导航"
      style={{
        ...(fixed
          ? { position: 'fixed' as const, left: 0, right: 0, bottom: 0, zIndex: 60 }
          : { flexShrink: 0 }),
        height: `calc(56px + ${SAFE_AREA})`,
        paddingBottom: SAFE_AREA,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-around',
        borderTop: '1px solid var(--color-hairline)',
        background: 'var(--color-panel)',
        boxSizing: 'border-box',
      }}
    >
      {TABS.map((tab) => {
        const active = tab.match(location.pathname);
        const badge = badges?.[tab.id] ?? 0;
        const Icon = tab.icon;
        return (
          <button
            key={tab.id}
            type="button"
            onClick={() => navigate(tab.path)}
            aria-current={active ? 'page' : undefined}
            aria-label={tab.label}
            style={{
              position: 'relative',
              flex: 1,
              minWidth: 0,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 2,
              height: '100%',
              border: 'none',
              background: 'transparent',
              cursor: 'pointer',
              color: active ? 'var(--color-primary)' : 'var(--color-muted)',
              fontSize: 11,
              fontWeight: active ? 600 : 400,
            }}
          >
            <span style={{ position: 'relative', display: 'inline-flex' }}>
              <Icon size={21} strokeWidth={active ? 2.2 : 1.8} />
              {badge > 0 && (
                <span
                  aria-hidden="true"
                  style={{
                    position: 'absolute',
                    top: -4,
                    right: -8,
                    minWidth: 15,
                    height: 15,
                    padding: '0 4px',
                    borderRadius: 999,
                    background: 'var(--color-primary)',
                    color: 'var(--color-on-primary)',
                    fontSize: 9.5,
                    fontWeight: 700,
                    lineHeight: '15px',
                    textAlign: 'center',
                    boxSizing: 'border-box',
                  }}
                >
                  {badge > 99 ? '99+' : badge}
                </span>
              )}
            </span>
            <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%' }}>
              {tab.label}
            </span>
          </button>
        );
      })}
    </nav>
  );
}

/** 画布内嵌子窗的两 Tab 导航（不是手机主壳，保留原行为）。 */
function EmbeddedAppNav({
  pathname,
  navigate,
}: {
  pathname: string;
  navigate: ReturnType<typeof useNavigate>;
}) {
  const params = new URLSearchParams(window.location.search);
  params.set('embedded', '1');
  const query = params.toString();
  return (
    <div style={{
      height: 56, flexShrink: 0,
      display: 'flex', alignItems: 'center', justifyContent: 'space-around',
      borderTop: '1px solid var(--color-hairline)',
      background: 'var(--color-panel)',
    }}>
      <EmbeddedTab
        active={pathname === '/editor'}
        onClick={() => navigate(`/editor?${query}`)}
        label="分镜"
        icon={<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="2" width="20" height="20" rx="2.18" /><line x1="7" y1="2" x2="7" y2="22" /><line x1="17" y1="2" x2="17" y2="22" /><line x1="2" y1="12" x2="22" y2="12" /></svg>}
      />
      <EmbeddedTab
        active={pathname === '/chat/storyboards'}
        onClick={() => navigate(`/chat/storyboards?${query}`)}
        label="分镜库"
        icon={<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><line x1="8" y1="6" x2="21" y2="6" /><line x1="8" y1="12" x2="21" y2="12" /><line x1="8" y1="18" x2="21" y2="18" /><line x1="3" y1="6" x2="3.01" y2="6" /><line x1="3" y1="12" x2="3.01" y2="12" /><line x1="3" y1="18" x2="3.01" y2="18" /></svg>}
      />
    </div>
  );
}

function EmbeddedTab({ active, onClick, label, icon }: { active: boolean; onClick: () => void; label: string; icon: React.ReactNode }) {
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
