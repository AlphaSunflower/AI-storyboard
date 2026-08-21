import { DS } from './ChatComposer';

interface MobileHeaderProps {
  title: string;
  onMenuClick: () => void;
  onNewConversation: () => void;
}

/**
 * 手机端顶部导航栏（仿 DeepSeek 移动端）：
 * 左侧 ☰ 汉堡菜单 | 中间会话标题 | 右侧 ＋ 新建对话
 */
export function MobileHeader({ title, onMenuClick, onNewConversation }: MobileHeaderProps) {
  return (
    <div style={{
      height: 56, flexShrink: 0,
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '0 12px',
      borderBottom: `1px solid ${DS.border}`,
      background: 'white',
    }}>
      {/* 左：汉堡菜单 */}
      <button
        onClick={onMenuClick}
        title="菜单"
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

      {/* 中：会话标题 */}
      <span style={{
        fontSize: 16, fontWeight: 600, color: DS.ink,
        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        flex: 1, textAlign: 'center', margin: '0 8px',
      }}>
        {title}
      </span>

      {/* 右：新建对话 */}
      <button
        onClick={onNewConversation}
        title="新建对话"
        style={{
          width: 40, height: 40, border: 'none', background: 'transparent',
          borderRadius: 10, cursor: 'pointer', display: 'flex',
          alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          color: DS.ink, fontSize: 20,
        }}
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M12 5v14M5 12h14" />
        </svg>
      </button>
    </div>
  );
}
