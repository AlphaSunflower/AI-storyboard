import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useProjectStore } from '../../stores/projectStore';
import { notify } from '../../stores/toastStore';
import type { ProjectResponse } from '../../api/projects';
import { MoreMenu } from '../common/MoreMenu';
import { usePresence } from '../../hooks/usePresence';
import { Plus } from 'lucide-react';

const modalOverlayStyle: React.CSSProperties = {
  position: 'fixed', inset: 0, background: 'rgba(20, 20, 19, 0.35)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 300,
};
const modalStyle: React.CSSProperties = {
  background: 'white', borderRadius: 'var(--rounded-md)',
  boxShadow: '0 8px 32px rgba(20, 20, 19, 0.18)', padding: 24,
  minWidth: 320, maxWidth: 440,
};
const inputStyle: React.CSSProperties = {
  width: '100%', padding: '8px 12px', border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)', fontSize: 14, color: 'var(--color-ink)',
  outline: 'none', boxSizing: 'border-box', fontFamily: 'inherit',
};
const secondaryBtnStyle: React.CSSProperties = {
  padding: '6px 18px', height: 32, border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)', background: 'white', color: 'var(--color-muted)',
  font: 'var(--text-caption)', cursor: 'pointer', marginRight: 8,
};
const primaryBtnStyle: React.CSSProperties = {
  padding: '6px 18px', height: 32, border: 'none', borderRadius: 'var(--rounded-md)',
  background: 'var(--color-primary)', color: 'white', font: 'var(--text-caption)', cursor: 'pointer',
};

/**
 * 项目选择下拉（含新建/重命名/删除）——从 AppHeader 提取，编辑器顶栏与 /chat 侧栏共用。
 * 由外部控制开合（open/onClose），内部管理重命名/删除弹窗。
 * anchor 传入触发按钮 ref 时，弹层 portal 到 body + fixed 定位（可被 overflow 容器裁剪的场景，
 * 如 /chat 会话栏内部）；不传 anchor 保持原 absolute 行为（AppHeader 顶栏）。
 * popupRight：弹层右缘对齐按钮右缘（"往右靠"）。
 */
export function ProjectDropdown({ open, onClose, popupRight, anchor }: {
  open: boolean; onClose: () => void; popupRight?: boolean;
  anchor?: React.RefObject<HTMLElement | null>;
}) {
  const { projects, currentProject, loadProject, createProject, updateProject, deleteProject, loadProjects } = useProjectStore();
  const [renameTarget, setRenameTarget] = useState<ProjectResponse | null>(null);
  const [renameName, setRenameName] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<ProjectResponse | null>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const { mounted, visible } = usePresence(open, 180);

  // 点击外部关闭下拉
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      const t = e.target as Node;
      const inMenu = dropdownRef.current?.contains(t);
      const inAnchor = anchor?.current?.contains(t);
      // 触发按钮上的 mousedown 交由按钮 onClick 自行切换，避免「mousedown 关闭 + click 又打开」导致无法收起
      if (!inMenu && !inAnchor) onClose();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onClose, anchor, open]);

  if (!mounted) return null;

  const handleSelectProject = (id: string) => {
    loadProject(id);
    const name = projects.find((p) => p.id === id)?.name;
    notify({ title: name ? `已切换到「${name}」` : '已切换项目', variant: 'info' });
    onClose();
  };

  const handleNewProject = async () => {
    try {
      const p = await createProject('未命名项目', 'movie', '16:9');
      loadProject(p.id);
    } catch (err) {
      notify({ title: '新建项目失败', description: err instanceof Error ? err.message : undefined, variant: 'error' });
    }
    onClose();
  };

  const handleRenameSubmit = async () => {
    if (!renameTarget || !renameName.trim()) return;
    try {
      await updateProject(renameTarget.id, { name: renameName.trim() });
      await loadProjects();
      notify({ title: '已重命名项目', description: `「${renameName.trim()}」` });
    } catch (err) {
      notify({ title: '重命名失败', description: err instanceof Error ? err.message : undefined, variant: 'error' });
    }
    setRenameTarget(null);
    setRenameName('');
  };

  const handleDeleteExecute = async () => {
    if (!deleteTarget) return;
    try {
      await deleteProject(deleteTarget.id);
      await loadProjects();
      notify({ title: '已删除项目', description: `「${deleteTarget.name}」` });
    } catch (err) {
      notify({ title: '删除项目失败', description: err instanceof Error ? err.message : undefined, variant: 'error' });
    }
    setDeleteTarget(null);
    onClose();
  };

  // 弹层内容（新建 + 项目列表，含重命名/删除操作）
  const menuContent = (
    <>
      <button
        onClick={handleNewProject}
        style={{
          width: '100%', padding: '10px 14px', border: 'none',
          borderBottom: '1px solid var(--color-hairline)',
          background: 'transparent', color: 'var(--color-primary)',
          fontSize: 13, fontWeight: 600, cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6,
        }}
      >
        <Plus size={14} strokeWidth={2} /> 新建项目
      </button>

      <div style={{ maxHeight: 260, overflowY: 'auto', padding: 4 }}>
        {projects.length === 0 ? (
          <p style={{ padding: 12, color: 'var(--color-muted-soft)', fontSize: 12, textAlign: 'center' }}>
            暂无项目
          </p>
        ) : (
          projects.map((p, index) => (
            <div
              key={p.id}
              className="project-dropdown-item"
              onMouseEnter={(e) => {
                if (currentProject?.id !== p.id) e.currentTarget.style.background = 'var(--color-surface-subtle)';
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.background = currentProject?.id === p.id ? 'var(--color-primary-tint)' : 'transparent';
              }}
              style={{
                '--item-index': index,
                display: 'flex', alignItems: 'center',
                borderRadius: 8,
                background: currentProject?.id === p.id ? 'var(--color-primary-tint)' : 'transparent',
              } as React.CSSProperties}
            >
              <button
                onClick={() => handleSelectProject(p.id)}
                style={{
                  flex: 1, padding: '8px 6px 8px 10px', border: 'none', background: 'none',
                  color: 'var(--color-ink)', fontSize: 13, cursor: 'pointer',
                  textAlign: 'left', overflow: 'hidden', textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap', minWidth: 0,
                }}
              >
                <span style={{ fontWeight: currentProject?.id === p.id ? 600 : 400 }}>{p.name}</span>
                <span style={{ fontSize: 11, color: 'var(--color-muted-soft)', marginLeft: 8 }}>
                  {p.status === 'draft' ? '草稿' : ''}
                </span>
              </button>
              <MoreMenu
                items={[
                  { label: '重命名', onClick: () => { setRenameTarget(p); setRenameName(p.name); } },
                  { label: '删除', danger: true, disabled: projects.length <= 1, onClick: () => setDeleteTarget(p) },
                ]}
              />
            </div>
          ))
        )}
      </div>
    </>
  );

  // 项目菜单浮层（控件 8 / 浮层 12；阴影走 token）
  const menuStyle: React.CSSProperties = {
    width: 264,
    background: 'white',
    border: '1px solid var(--color-hairline)',
    borderRadius: 12,
    boxShadow: 'var(--shadow-popover)',
    zIndex: 2000,
    overflow: 'hidden',
  };
  const menuClassName = `project-dropdown-menu${visible ? ' is-open' : ''}`;

  // anchor 传入（/chat 会话栏内）：portal 到 body + fixed 定位，弹层向右展开（按钮右缘 +8），不被左缘裁剪
  if (anchor?.current) {
    const r = anchor.current.getBoundingClientRect();
    return createPortal(
      <div ref={dropdownRef}>
        <div className={menuClassName} style={{ ...menuStyle, position: 'fixed', top: r.bottom + 6, left: r.right + 8 }}>
          {menuContent}
        </div>
        {deleteTarget && (
          <div style={modalOverlayStyle} onClick={() => setDeleteTarget(null)}>
            <div style={modalStyle} onClick={(e) => e.stopPropagation()}>
              <h3 style={{ margin: '0 0 12px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>删除项目</h3>
              <p style={{ margin: '0 0 16px', font: 'var(--text-body-sm)', color: 'var(--color-muted)' }}>
                确定要删除项目「{deleteTarget.name}」吗？此操作无法撤销。
              </p>
              <div style={{ textAlign: 'right' }}>
                <button style={secondaryBtnStyle} onClick={() => setDeleteTarget(null)}>取消</button>
                <button style={{ ...primaryBtnStyle, background: '#e53e3e' }} onClick={handleDeleteExecute}>删除</button>
              </div>
            </div>
          </div>
        )}
      </div>,
      document.body,
    );
  }

  return (
    <div ref={dropdownRef}>
      <div className={menuClassName} style={{ ...menuStyle, position: 'absolute', top: 40, left: popupRight ? 60 : 0 }}>
        {menuContent}
      </div>

      {renameTarget && (
        <div style={modalOverlayStyle} onClick={() => { setRenameTarget(null); setRenameName(''); }}>
          <div style={modalStyle} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>重命名项目</h3>
            <input
              autoFocus
              value={renameName}
              onChange={(e) => setRenameName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleRenameSubmit(); }}
              style={inputStyle}
              placeholder="输入项目名称"
            />
            <div style={{ marginTop: 16, textAlign: 'right' }}>
              <button style={secondaryBtnStyle} onClick={() => { setRenameTarget(null); setRenameName(''); }}>取消</button>
              <button style={primaryBtnStyle} disabled={!renameName.trim()} onClick={handleRenameSubmit}>确定</button>
            </div>
          </div>
        </div>
      )}

      {deleteTarget && (
        <div style={modalOverlayStyle} onClick={() => setDeleteTarget(null)}>
          <div style={modalStyle} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>删除项目</h3>
            <p style={{ margin: '0 0 16px', font: 'var(--text-body-sm)', color: 'var(--color-muted)' }}>
              确定要删除项目「{deleteTarget.name}」吗？此操作无法撤销。
            </p>
            <div style={{ textAlign: 'right' }}>
              <button style={secondaryBtnStyle} onClick={() => setDeleteTarget(null)}>取消</button>
              <button style={{ ...primaryBtnStyle, background: '#e53e3e' }} onClick={handleDeleteExecute}>删除</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
