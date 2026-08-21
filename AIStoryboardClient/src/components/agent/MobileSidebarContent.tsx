import { useState } from 'react';
import { AgentConversationList } from './AgentConversationList';
import { DS } from './ChatComposer';
import { ProjectDropdown } from '../layout/ProjectDropdown';
import { useProjectStore } from '../../stores/projectStore';

interface MobileSidebarContentProps {
  onSelectConversation: (id: string) => void;
  onClose: () => void;
  onOpenSettings: () => void;
  onOpenAssets: () => void;
  onNavigate: (path: string) => void;
  logout: () => void;
}

/**
 * 手机端 overlay 侧栏内容：
 * Moon Logo + 关闭 | 资源库 | 项目选择 | 会话列表 | 底部设置
 */
export function MobileSidebarContent({
  onSelectConversation, onClose, onOpenSettings, onOpenAssets, onNavigate, logout,
}: MobileSidebarContentProps) {
  const currentProject = useProjectStore((s) => s.currentProject);
  const [projectOpen, setProjectOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      {/* 顶部：Moon Logo + 关闭 */}
      <div style={{
        padding: '14px 16px 10px',
        borderBottom: '1px solid var(--color-hairline)',
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      }}>
        <span style={{ fontSize: 17, fontWeight: 700, color: DS.ink }}>☾ Moon 智能体</span>
        <button
          onClick={onClose}
          style={{
            width: 32, height: 32, border: 'none', background: 'transparent',
            borderRadius: 8, cursor: 'pointer', fontSize: 18, color: DS.textSecondary,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
        >✕</button>
      </div>

      {/* 工具栏：资源库 + 项目选择 */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '10px 12px 6px' }}>
        <button
          onClick={onOpenAssets}
          style={{
            width: '100%', display: 'flex', alignItems: 'center', gap: 8,
            border: 'none', borderRadius: 10, background: 'transparent',
            padding: '0 12px', height: 40, fontSize: 15,
            color: DS.ink, cursor: 'pointer', textAlign: 'left',
          }}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M3 3h7l2 3h9v13H3z" /></svg>
          资源库
        </button>

        <div style={{ position: 'relative', width: '100%' }}>
          <button
            onClick={() => setProjectOpen(!projectOpen)}
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
            <span style={{ fontSize: 10, color: DS.textCaption }}>▼</span>
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

      {/* 底部设置 */}
      <div style={{
        padding: '8px 12px',
        borderTop: '1px solid var(--color-hairline)',
      }}>
        {settingsOpen && (
          <div style={{
            background: 'white', border: `1px solid ${DS.border}`, borderRadius: 12,
            boxShadow: '0 4px 16px rgba(0, 0, 0, 0.1)', padding: 6, marginBottom: 6,
          }}>
            {[
              { label: '个人信息', onClick: onOpenSettings },
              { label: '使用文档', onClick: () => onNavigate('/docs') },
              { label: '编辑器', onClick: () => onNavigate('/editor') },
              { label: '退出登录', onClick: logout, color: '#d92d20' },
            ].map((it) => (
              <button
                key={it.label}
                onClick={it.onClick}
                style={{
                  width: '100%', textAlign: 'left', padding: '9px 12px', border: 'none',
                  background: 'transparent', borderRadius: 8, fontSize: 14, cursor: 'pointer',
                  color: it.color ?? DS.ink, display: 'flex', alignItems: 'center', gap: 8,
                }}
              >{it.label}</button>
            ))}
          </div>
        )}
        <button
          onClick={() => setSettingsOpen(!settingsOpen)}
          style={{
            width: '100%', border: 'none', background: 'transparent', borderRadius: 10,
            padding: '0 12px', height: 42, fontSize: 15, color: 'var(--color-muted)', cursor: 'pointer',
            display: 'flex', alignItems: 'center', gap: 6,
          }}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3" /><path d="M10 2l1.5 2.5L14 3l-.5 2.8 2.5 1.2-1.5 2.3 2.3 1.5-2.8.5.5 2.8-2.5-1.2L10 17l-.5-2.8-2.5 1.2 1.5-2.3-2.3-1.5 2.8-.5L8.5 8.7 11 9.9z" /></svg>
          设置
        </button>
      </div>
    </div>
  );
}
