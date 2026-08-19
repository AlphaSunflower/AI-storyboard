import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { useProjectStore } from '../../stores/projectStore';
import WarpText from '../WarpText';
import SpecularButton from '../SpecularButton';
import StaggeredMenu from '../StaggeredMenu';
import { AssetLibraryPanel } from '../asset/AssetLibraryPanel';
import { PersonalInfoModal } from '../agent/PersonalInfoModal';
import { ProjectDropdown } from './ProjectDropdown';

const headerHeight = 64;

// ── component ────────────────────────────────────────────────────────

export function AppHeader() {
  const navigate = useNavigate();
  const logout = useAuthStore((s) => s.logout);
  const { currentProject, updateProject } = useProjectStore();

  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [assetLibraryOpen, setAssetLibraryOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);

  // Ctrl/Cmd+S 保存当前项目（与「💾 保存」按钮同逻辑）
  const handleSave = () => {
    if (currentProject) updateProject(currentProject.id, { status: 'active' });
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        handleSave();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentProject, updateProject]);

  // ── helpers ──────────────────────────────────────────────────────

  const currentName = currentProject?.name || '选择项目';

  // ── render ───────────────────────────────────────────────────────

  return (
    <>
      <div
        style={{
          height: headerHeight,
          minHeight: headerHeight,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 var(--space-md)',
          borderBottom: '1px solid var(--color-hairline)',
          background: 'white',
          zIndex: 10,
        }}
      >
        {/* Left: project selector */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <WarpText
            text="AlphaSunflower AI分镜"
            color="#cc785c"
            fontSize={26}
            fontWeight={700}
            warpStrength={0.06}
            warpScale={1.4}
            speed={0.4}
            pointerInfluence={0.35}
            pointerStrength={0.3}
            refraction={0.012}
            ripple
            style={{ width: 360, height: 64, minHeight: 64, flexShrink: 0 }}
          />

          {/* Save button */}
          {currentProject && (
            <button
              onClick={handleSave}
              style={{
                padding: '4px 10px',
                fontSize: 12,
                background: 'transparent',
                border: '1px solid var(--color-primary)',
                color: 'var(--color-primary)',
                borderRadius: 'var(--rounded-sm)',
                cursor: 'pointer',
              }}
            >
              💾 保存
            </button>
          )}

          {/* Project dropdown（提取组件，编辑器/聊天页共用） */}
          <div style={{ position: 'relative' }}>
            <button
              onClick={() => setDropdownOpen(!dropdownOpen)}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                padding: '4px 12px',
                height: 32,
                border: '1px solid var(--color-hairline)',
                borderRadius: 'var(--rounded-md)',
                background: 'white',
                font: 'var(--text-caption)',
                color: 'var(--color-ink)',
                cursor: 'pointer',
                minWidth: 140,
              }}
            >
              <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {currentName}
              </span>
              <span style={{ fontSize: 10, color: 'var(--color-muted)' }}>▼</span>
            </button>
            <ProjectDropdown open={dropdownOpen} onClose={() => setDropdownOpen(false)} />
          </div>
        </div>

        {/* Right: 资产库 + 菜单 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <SpecularButton
            size="sm"
            radius={10}
            tintOpacity={0}
            textColor="#cc785c"
            lineColor="#cc785c"
            baseColor="#cc785c"
            intensity={0.9}
            thickness={1.2}
            onClick={() => setAssetLibraryOpen(true)}
          >
            🧩 资产库
          </SpecularButton>
          <StaggeredMenu
            position="right"
            menuButtonColor="#141413"
            openMenuButtonColor="#141413"
            accentColor="#cc785c"
            colors={['#cc785c', '#efe9de', '#faf9f5']}
            displayItemNumbering={false}
            displaySocials={false}
            items={[
              { label: 'Moon Chat', ariaLabel: 'Moon Chat', onClick: () => navigate('/chat') },
              { label: '使用文档', ariaLabel: '使用文档', onClick: () => navigate('/docs') },
              { label: '个人信息', ariaLabel: '个人信息', onClick: () => setProfileOpen(true) },
              { label: '退出登录', ariaLabel: '退出登录', onClick: logout, color: 'var(--color-error)' },
            ]}
          />
        </div>
      </div>

      {/* ── AI 资产库面板 ─────────────────────────────────────────── */}
      {assetLibraryOpen && <AssetLibraryPanel onClose={() => setAssetLibraryOpen(false)} />}
      {/* ── 个人信息弹窗 ─────────────────────────────────────────── */}
      {profileOpen && <PersonalInfoModal onClose={() => setProfileOpen(false)} />}
    </>
  );
}
