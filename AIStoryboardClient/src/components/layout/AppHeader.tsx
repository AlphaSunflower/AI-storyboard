import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import { useProjectStore } from '../../stores/projectStore';
import { notify } from '../../stores/toastStore';
import { ChevronDown, MessageSquare, Pencil, Save, Trash2 } from 'lucide-react';
import { ProjectDropdown } from './ProjectDropdown';
import { ContextMenu } from '../common/ContextMenu';
import MoonLogo from '../agent/MoonLogo';
import StaggeredMenu from '../StaggeredMenu';

// ── component ────────────────────────────────────────────────────────
// 编辑器顶栏：品牌 + 保存 / 居中的项目切换 / 智能体入口 + 菜单。
// 设计语言与 /chat 对齐：暖色 canvas、hairline 分隔、8px 控件圆角。

export function AppHeader() {
  const navigate = useNavigate();
  const logout = useAuthStore((s) => s.logout);
  const { currentProject, updateProject } = useProjectStore();

  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [renameProjectOpen, setRenameProjectOpen] = useState(false);
  const [renameProjectName, setRenameProjectName] = useState('');
  const deleteProject = useProjectStore((s) => s.deleteProject);

  // Ctrl/Cmd+S 保存当前项目（与「保存」按钮同逻辑）
  const handleSave = () => {
    if (currentProject) updateProject(currentProject.id, { status: 'active' });
  };

  /** 重命名当前项目：成功后右下角确认，失败回显错误 */
  const submitProjectRename = async () => {
    const name = renameProjectName.trim();
    if (!currentProject || !name) return;
    setRenameProjectOpen(false);
    try {
      await updateProject(currentProject.id, { name });
      notify({ title: '已重命名项目', description: `「${name}」` });
    } catch (err) {
      notify({ title: '重命名失败', description: err instanceof Error ? err.message : undefined, variant: 'error' });
    }
  };

  /** 删除当前项目：先确认，成功/失败都给一次性提示 */
  const removeCurrentProject = async () => {
    if (!currentProject) return;
    const name = currentProject.name;
    if (!confirm(`确定要删除项目「${name}」吗？`)) return;
    try {
      await deleteProject(currentProject.id);
      notify({ title: '已删除项目', description: `「${name}」` });
    } catch (err) {
      notify({ title: '删除项目失败', description: err instanceof Error ? err.message : undefined, variant: 'error' });
    }
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

  const currentName = currentProject?.name || '选择项目';
  const isDraft = currentProject?.status === 'draft';

  return (
    <>
      <header className="app-header">
        {/* Left: 品牌 + 保存 */}
        <div className="app-header__left">
          <button
            type="button"
            className="app-brand"
            onClick={() => navigate('/chat')}
            aria-label="返回 Moon 智能体"
          >
            <span className="app-brand__mark"><MoonLogo variant="mark" size={17} tone="brand" /></span>
            <span className="app-brand__text">AIGC 分镜</span>
          </button>

          {currentProject && (
            <button type="button" className="btn btn--ghost btn--sm app-header__save" onClick={handleSave} aria-label="保存项目">
              <Save size={14} strokeWidth={1.9} />
              <span className="app-header__save-label">保存</span>
            </button>
          )}
        </div>

        {/* Center: 项目选择 */}
        <div className="app-header__center">
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
                onClick: () => { void removeCurrentProject(); },
              },
            ]}
          >
            <button
              type="button"
              className="project-switch"
              onClick={() => setDropdownOpen(!dropdownOpen)}
              onMouseDown={(e) => e.stopPropagation()}
              aria-label="切换项目"
              aria-expanded={dropdownOpen}
            >
              <span className="project-switch__name">{currentName}</span>
              {isDraft && <span className="project-switch__status">草稿</span>}
              <ChevronDown size={14} strokeWidth={2} color="var(--color-muted)" />
            </button>
          </ContextMenu>
          <ProjectDropdown open={dropdownOpen} onClose={() => setDropdownOpen(false)} />
        </div>

        {/* Right: 智能体入口 + ReactBits 菜单 */}
        <div className="app-header__right">
          <button type="button" className="btn btn--soft btn--sm" onClick={() => navigate('/chat')}>
            <MessageSquare size={14} strokeWidth={1.9} />
            Moon 智能体
          </button>

          <StaggeredMenu
            position="right"
            menuButtonColor="#141413"
            openMenuButtonColor="#141413"
            accentColor="var(--color-primary)"
            colors={['var(--color-primary)', 'var(--color-surface-card)', 'var(--color-canvas)']}
            displayItemNumbering={false}
            displaySocials={false}
            items={[
              { label: 'Moon Chat', ariaLabel: 'Moon Chat', onClick: () => navigate('/chat') },
              { label: '运行观测', ariaLabel: '运行观测', onClick: () => navigate('/observability') },
              { label: '项目库', ariaLabel: '项目库', onClick: () => navigate('/library') },
              { label: '使用文档', ariaLabel: '使用文档', onClick: () => navigate('/docs') },
              { label: '个人信息', ariaLabel: '个人信息', onClick: () => navigate('/account') },
              { label: '退出登录', ariaLabel: '退出登录', onClick: logout, color: 'var(--color-error)' },
            ]}
          />
        </div>
      </header>

      {/* ── 项目重命名弹窗 ─────────────────────────────────────────── */}
      {renameProjectOpen && currentProject && (
        <div className="modal-overlay" onClick={() => setRenameProjectOpen(false)}>
          <div className="modal-card" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="重命名项目">
            <h3 className="modal-card__title">重命名项目</h3>
            <input
              autoFocus
              className="field-input"
              value={renameProjectName}
              onChange={(e) => setRenameProjectName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && renameProjectName.trim()) {
                  void submitProjectRename();
                }
              }}
              placeholder="输入项目名称"
            />
            <div className="modal-card__actions">
              <button type="button" className="btn btn--ghost btn--md" onClick={() => setRenameProjectOpen(false)}>
                取消
              </button>
              <button
                type="button"
                className="btn btn--primary btn--md"
                disabled={!renameProjectName.trim()}
                onClick={() => { void submitProjectRename(); }}
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
