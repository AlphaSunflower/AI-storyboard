import { DS } from './ChatComposer';
import { Tooltip, TooltipGroup } from '../common/Tooltip';
import { Bell } from 'lucide-react';

interface MobileHeaderProps {
  title: string;
  onMenuClick: () => void;
  onNewConversation: () => void;
  /** 打开站内通知抽屉；不传则不渲染铃铛（其余页面复用同一个顶栏时保持原样）。 */
  onOpenNotifications?: () => void;
  notificationCount?: number;
}

/**
 * 手机端顶部导航栏（仿 DeepSeek 移动端）：
 * 左侧 ☰ 汉堡菜单 | 中间会话标题 | 右侧 ＋ 新建对话
 */
export function MobileHeader({
  title, onMenuClick, onNewConversation, onOpenNotifications, notificationCount = 0,
}: MobileHeaderProps) {
  return (
    <TooltipGroup>
    <div style={{
      height: 56, flexShrink: 0,
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '0 12px',
      borderBottom: `1px solid ${DS.border}`,
      background: 'var(--color-panel)',
    }}>
      {/* 左：汉堡菜单 */}
      <Tooltip side="bottom" label="菜单">
      <button
        onClick={onMenuClick}
        aria-label="菜单"
        style={{
          width: 40, height: 40, border: 'none', background: 'transparent',
          borderRadius: 10, cursor: 'pointer', display: 'flex',
          alignItems: 'center', justifyContent: 'center', flexShrink: 0,
        }}
      >
        <svg width="20" height="20" viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M17.2027 4.90034V6.43655H2.79724V4.90034H17.2027Z" fill="currentColor" />
          <path d="M10.9603 13.0634V14.5996H2.79724V13.0634H10.9603Z" fill="currentColor" />
        </svg>
      </button>
      </Tooltip>

      {/* 中：会话标题 */}
      <span style={{
        fontSize: 16, fontWeight: 600, color: DS.ink,
        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        flex: 1, textAlign: 'center', margin: '0 8px',
      }}>
        {title}
      </span>

      {/* 右：通知 + 新建对话 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 2, flexShrink: 0 }}>
        {onOpenNotifications && (
          <Tooltip side="bottom" label="通知">
            <button
              onClick={onOpenNotifications}
              aria-label="通知"
              style={{
                position: 'relative',
                width: 40, height: 40, border: 'none', background: 'transparent',
                borderRadius: 10, cursor: 'pointer', display: 'flex',
                alignItems: 'center', justifyContent: 'center', color: DS.ink,
              }}
            >
              <Bell size={19} strokeWidth={1.8} />
              {notificationCount > 0 && (
                <span
                  aria-hidden="true"
                  style={{
                    position: 'absolute', top: 5, right: 4,
                    minWidth: 15, height: 15, padding: '0 4px',
                    borderRadius: 999, background: 'var(--color-primary)',
                    color: 'var(--color-on-primary)',
                    fontSize: 9.5, fontWeight: 700, lineHeight: '15px',
                    textAlign: 'center', boxSizing: 'border-box',
                  }}
                >
                  {notificationCount > 99 ? '99+' : notificationCount}
                </span>
              )}
            </button>
          </Tooltip>
        )}
        <Tooltip side="bottom" label="新建对话">
        <button
          onClick={onNewConversation}
          aria-label="新建对话"
          style={{
            width: 40, height: 40, border: 'none', background: 'transparent',
            borderRadius: 10, cursor: 'pointer', display: 'flex',
            alignItems: 'center', justifyContent: 'center', color: DS.ink, fontSize: 20,
          }}
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </button>
        </Tooltip>
      </div>
    </div>
    </TooltipGroup>
  );
}
