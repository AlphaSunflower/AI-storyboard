import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { useProjectStore } from '../../stores/projectStore';
import { Save, Puzzle, Pencil, Trash2, ChevronDown } from 'lucide-react';
import { AssetLibraryPanel } from '../asset/AssetLibraryPanel';
import { PersonalInfoModal } from '../agent/PersonalInfoModal';
import { ProjectDropdown } from './ProjectDropdown';
import { ContextMenu } from '../common/ContextMenu';
import StaggeredMenu from '../StaggeredMenu';

const headerHeight = 64;

// ── component ────────────────────────────────────────────────────────
// 设计回归（2026-08-22）：去除标题栏装饰性特效（WarpText/SpecularButton/StaggeredMenu），
// 回归 tokens.css 设计系统——canvas 画布底色、hairline 控件、inline SVG 图标、静态品牌字。

export function AppHeader() {
  const navigate = useNavigate();
  const logout = useAuthStore((s) => s.logout);
  const { currentProject, updateProject } = useProjectStore();

  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [assetLibraryOpen, setAssetLibraryOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [renameProjectOpen, setRenameProjectOpen] = useState(false);
  const [renameProjectName, setRenameProjectName] = useState('');
  const deleteProject = useProjectStore((s) => s.deleteProject);

  // Ctrl/Cmd+S 保存当前项目（与「保存」按钮同逻辑）
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
          background: 'var(--color-canvas)',
          zIndex: 10,
        }}
      >
        {/* Left: 品牌字 + 保存 + 项目选择 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span
            style={{
              fontSize: 19,
              fontWeight: 650,
              letterSpacing: '-0.4px',
              color: 'var(--color-ink)',
              whiteSpace: 'nowrap',
              paddingRight: 4,
            }}
          >
            AlphaSunflower <span style={{ color: 'var(--color-primary)' }}>AI 分镜</span>
          </span>

          {/* Save button */}
          {currentProject && (
            <button
              onClick={handleSave}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 4,
                padding: '4px 10px',
                fontSize: 12,
                background: 'transparent',
                border: '1px solid var(--color-primary)',
                color: 'var(--color-primary)',
                borderRadius: 'var(--rounded-sm)',
                cursor: 'pointer',
              }}
            >
              <Save size={12} strokeWidth={1.8} />
              保存
            </button>
          )}

          {/* Project dropdown（提取组件，编辑器/聊天页共用） */}
          <div style={{ position: 'relative' }}>
            <ContextMenu
              items={[
                {
                  label: '重命名',
                  icon: <Pencil size={13} strokeWidth={1.8} />,
                  onClick: () => {
                    if (currentProject) {
                      setRenameProjectName(currentProject.name);
                      setRenameProjectOpen(true);
                    }
                  },
                },
                {
                  label: '删除项目',
                  icon: <Trash2 size={13} strokeWidth={1.8} />,
                  danger: true,
                  disabled: !currentProject,
                  onClick: () => {
                    if (currentProject && confirm(`确定要删除项目「${currentProject.name}」吗？`)) deleteProject(currentProject.id);
                  },
                },
              ]}
            >
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
                  background: 'var(--color-canvas)',
                  font: 'var(--text-caption)',
                  color: 'var(--color-ink)',
                  cursor: 'pointer',
                  minWidth: 140,
                }}
              >
                <span style={{ flex: 1, textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {currentName}
                </span>
                <ChevronDown size={12} strokeWidth={2} color="var(--color-muted)" />
              </button>
            </ContextMenu>
            <ProjectDropdown open={dropdownOpen} onClose={() => setDropdownOpen(false)} />
          </div>
        </div>

        {/* Right: 资产库 + 菜单 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <button
            onClick={() => setAssetLibraryOpen(true)}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              padding: '6px 14px',
              height: 32,
              border: '1px solid var(--color-hairline)',
              borderRadius: 'var(--rounded-md)',
              background: 'var(--color-canvas)',
              font: 'var(--text-caption)',
              color: 'var(--color-ink)',
              cursor: 'pointer',
            }}
          >
            <Puzzle size={14} strokeWidth={1.8} />
            资产库
          </button>
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
      {renameProjectOpen && currentProject && (
        <div
          style={{ position: 'fixed', inset: 0, zIndex: 9999, background: 'rgba(0,0,0,0.3)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          onClick={() => setRenameProjectOpen(false)}
        >
          <div style={{ background: 'white', borderRadius: 12, padding: 24, minWidth: 300 }} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>重命名项目</h3>
            <input
              autoFocus
              value={renameProjectName}
              onChange={(e) => setRenameProjectName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && renameProjectName.trim()) {
                  updateProject(currentProject.id, { name: renameProjectName.trim() });
                  setRenameProjectOpen(false);
                }
              }}
              style={{ width: '100%', padding: '8px 12px', border: '1px solid var(--color-hairline)', borderRadius: 8, fontSize: 14, outline: 'none' }}
              placeholder="输入项目名称"
            />
            <div style={{ marginTop: 16, textAlign: 'right', display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setRenameProjectOpen(false)} style={{ padding: '6px 16px', border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'white', cursor: 'pointer', fontSize: 13 }}>
                取消
              </button>
              <button
                disabled={!renameProjectName.trim()}
                onClick={() => {
                  updateProject(currentProject.id, { name: renameProjectName.trim() });
                  setRenameProjectOpen(false);
                }}
                style={{
                  padding: '6px 16px',
                  border: 'none',
                  borderRadius: 8,
                  background: 'var(--color-primary)',
                  color: '#fff',
                  cursor: 'pointer',
                  fontSize: 13,
                  opacity: renameProjectName.trim() ? 1 : 0.5,
                }}
              >
                确定
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
