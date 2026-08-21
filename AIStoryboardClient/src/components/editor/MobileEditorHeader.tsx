import { DS } from '../agent/ChatComposer';
import { useProjectStore } from '../../stores/projectStore';

interface MobileEditorHeaderProps {
  onMenuClick: () => void;
  onScript: () => void;
}

/** 手机端编辑器顶栏：☰ 菜单 | 项目名 | 📝 剧本 */
export function MobileEditorHeader({ onMenuClick, onScript }: MobileEditorHeaderProps) {
  const currentProject = useProjectStore((s) => s.currentProject);

  return (
    <div style={{
      height: 56, flexShrink: 0,
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '0 12px',
      borderBottom: `1px solid ${DS.border}`,
      background: 'white',
    }}>
      {/* 左：☰ */}
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

      {/* 中：项目名 */}
      <span style={{
        fontSize: 16, fontWeight: 600, color: DS.ink,
        overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        flex: 1, textAlign: 'center', margin: '0 8px',
      }}>
        {currentProject?.name ?? '分镜脚本'}
      </span>

      {/* 右：剧本输入 */}
      <button
        onClick={onScript}
        title="剧本输入"
        style={{
          width: 40, height: 40, border: 'none', background: 'transparent',
          borderRadius: 10, cursor: 'pointer', display: 'flex',
          alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          color: DS.ink,
        }}
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" />
          <path d="M14 2v6h6M12 18v-6M9 15h6" />
        </svg>
      </button>
    </div>
  );
}
