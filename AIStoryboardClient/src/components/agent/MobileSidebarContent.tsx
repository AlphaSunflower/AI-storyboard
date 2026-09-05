import { useState } from 'react';
import { AgentConversationList } from './AgentConversationList';
import { DS } from './ChatComposer';
import { ProjectDropdown } from '../layout/ProjectDropdown';
import { Moon, User, LogOut } from 'lucide-react';
import { useProjectStore } from '../../stores/projectStore';
import { useAuthStore } from '../../stores/authStore';

interface MobileSidebarContentProps {
  onSelectConversation: (id: string) => void;
  onClose: () => void;
  onOpenSettings: () => void;
  onOpenRecords?: () => void;
  logout: () => void;
}

/**
 * 手机端 overlay 侧栏内容：
 * Moon Logo + 关闭 | 资源库 | 项目选择 | 会话列表 | 底部设置
 */
export function MobileSidebarContent({
  onSelectConversation, onClose, onOpenSettings, onOpenRecords, logout,
}: MobileSidebarContentProps) {
  const currentProject = useProjectStore((s) => s.currentProject);
  const userName = useAuthStore((s) => s.user?.displayName || '未登录');
  const [projectOpen, setProjectOpen] = useState(false);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      {/* 顶部：Moon Logo + 关闭 */}
      <div style={{
        padding: '14px 16px 10px',
        borderBottom: '1px solid var(--color-hairline)',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      }}>
        <span style={{ fontSize: 17, fontWeight: 700, color: DS.ink, display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <Moon size={15} strokeWidth={1.8} /> Moon 智能体
        </span>
        <button
          onClick={onClose}
          style={{
            width: 32, height: 32, border: 'none', background: 'transparent',
            borderRadius: 8, cursor: 'pointer', fontSize: 18, color: DS.textSecondary,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
        >✕</button>
      </div>

      {/* 工具栏：项目选择 */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '10px 12px 6px' }}>
        {onOpenRecords && (
          <button
            onClick={onOpenRecords}
            style={{
              width: '100%', display: 'flex', alignItems: 'center', gap: 8,
              border: 'none', borderRadius: 10, background: 'transparent',
              padding: '0 12px', height: 40, fontSize: 15,
              color: DS.ink, cursor: 'pointer', textAlign: 'left',
            }}
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 12a9 9 0 1 0 9-9" /><path d="M3 3h6v6" /></svg>
            生成记录
          </button>
        )}

        <div style={{ position: 'relative', width: '100%' }}>
          <button
            onClick={() => setProjectOpen(!projectOpen)}
            onMouseDown={(e) => e.stopPropagation()}
            style={{
              width: '100%', display: 'flex', alignItems: 'center', gap: 6,
              border: 'none', borderRadius: 10, background: 'transparent',
              padding: '0 12px', height: 40, fontSize: 15,
              color: DS.ink, cursor: 'pointer',
            }}
          >
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2" /><path d="M8 4v16M16 4v16" /></svg>
            <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {currentProject?.name ?? '选择项目'}
            </span>
            <span style={{ fontSize: 10, color: DS.textCaption }}><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="M6 9l6 6 6-6"/></svg></span>
          </button>
          <ProjectDropdown open={projectOpen} onClose={() => setProjectOpen(false)} />
        </div>
      </div>

      {/* 会话列表（flex:1 可滚动） */}
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
        <AgentConversationList
          width={undefined}
          toolbar={null}
          onSelectOverride={onSelectConversation}
          hideHeader
        />
      </div>

      {/* 底部：用户资料 + 退出登录（替代原设置菜单） */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 4,
        padding: '8px 12px', borderTop: '1px solid var(--color-hairline)',
      }}>
        <button
          onClick={onOpenSettings}
          title="个人信息"
          style={{
            flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', gap: 8,
            border: 'none', background: 'transparent', borderRadius: 10,
            padding: '6px 10px', fontSize: 15, color: DS.ink, cursor: 'pointer',
          }}
          onMouseEnter={(e) => { e.currentTarget.style.background = DS.hover; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}
        >
          <User size={18} strokeWidth={1.8} />
          <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{userName}</span>
        </button>
        <button
          onClick={logout}
          title="退出登录"
          style={{
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            width: 34, height: 34, border: 'none', background: 'transparent', borderRadius: 8, cursor: 'pointer', color: 'var(--color-muted)',
          }}
          onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(217, 45, 32, 0.08)'; e.currentTarget.style.color = '#d92d20'; }}
          onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--color-muted)'; }}
        >
          <LogOut size={18} strokeWidth={1.8} />
        </button>
      </div>
    </div>
  );
}
