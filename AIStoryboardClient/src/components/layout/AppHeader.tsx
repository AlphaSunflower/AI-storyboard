import { useState, useRef, useEffect } from 'react';
import { useAuthStore } from '../../stores/authStore';
import { useProjectStore } from '../../stores/projectStore';
import type { ProjectResponse } from '../../api/projects';

const headerHeight = 48;

// ── shared inline styles ────────────────────────────────────────────

const modalOverlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(20, 20, 19, 0.35)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 100,
};

const modalStyle: React.CSSProperties = {
  background: 'white',
  borderRadius: 'var(--rounded-md)',
  boxShadow: '0 8px 32px rgba(20, 20, 19, 0.18)',
  padding: 24,
  minWidth: 320,
  maxWidth: 440,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)',
  font: 'var(--text-body-sm)',
  color: 'var(--color-ink)',
  outline: 'none',
  boxSizing: 'border-box',
};

const primaryBtnStyle: React.CSSProperties = {
  padding: '6px 18px',
  height: 32,
  border: 'none',
  borderRadius: 'var(--rounded-md)',
  background: 'var(--color-primary)',
  color: 'white',
  font: 'var(--text-caption)',
  cursor: 'pointer',
};

const secondaryBtnStyle: React.CSSProperties = {
  padding: '6px 18px',
  height: 32,
  border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)',
  background: 'white',
  color: 'var(--color-muted)',
  font: 'var(--text-caption)',
  cursor: 'pointer',
  marginRight: 8,
};

// ── component ────────────────────────────────────────────────────────

export function AppHeader() {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const {
    projects,
    currentProject,
    loadProject,
    createProject,
    updateProject,
    deleteProject,
    loadProjects,
  } = useProjectStore();

  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // rename
  const [renameTarget, setRenameTarget] = useState<ProjectResponse | null>(null);
  const [renameName, setRenameName] = useState('');

  // delete
  const [deleteTarget, setDeleteTarget] = useState<ProjectResponse | null>(null);

  // Close dropdown when clicking outside
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const handleSelectProject = (id: string) => {
    loadProject(id);
    setDropdownOpen(false);
  };

  const handleNewProject = async () => {
    try {
      const p = await createProject('未命名项目', 'movie', '16:9');
      loadProject(p.id);
    } catch {
      // silently fail
    }
    setDropdownOpen(false);
  };

  // ── rename handlers ──────────────────────────────────────────────

  const handleRenameStart = (p: ProjectResponse) => {
    setRenameTarget(p);
    setRenameName(p.name);
  };

  const handleRenameSubmit = async () => {
    if (!renameTarget || !renameName.trim()) return;
    try {
      await updateProject(renameTarget.id, { name: renameName.trim() });
      await loadProjects();
    } catch {
      // silently fail
    }
    setRenameTarget(null);
    setRenameName('');
  };

  // ── delete handlers ──────────────────────────────────────────────

  const handleDeleteConfirm = (p: ProjectResponse) => {
    setDeleteTarget(p);
  };

  const handleDeleteExecute = async () => {
    if (!deleteTarget) return;
    try {
      await deleteProject(deleteTarget.id);
      await loadProjects();
    } catch {
      // silently fail
    }
    setDeleteTarget(null);
    setDropdownOpen(false);
  };

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
          <span
            style={{
              font: 'var(--text-display-sm)',
              color: 'var(--color-primary)',
              fontSize: 18,
              lineHeight: 1,
              marginRight: 4,
            }}
          >
            🎬
          </span>

          {/* Project dropdown */}
          <div ref={dropdownRef} style={{ position: 'relative' }}>
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

            {dropdownOpen && (
              <div
                style={{
                  position: 'absolute',
                  top: 36,
                  left: 0,
                  width: 260,
                  background: 'white',
                  border: '1px solid var(--color-hairline)',
                  borderRadius: 'var(--rounded-md)',
                  boxShadow: '0 4px 12px rgba(20,20,19,0.1)',
                  zIndex: 20,
                  overflow: 'hidden',
                }}
              >
                {/* New project button */}
                <button
                  onClick={handleNewProject}
                  style={{
                    width: '100%',
                    padding: '8px 14px',
                    border: 'none',
                    borderBottom: '1px solid var(--color-hairline-soft)',
                    background: 'var(--color-canvas)',
                    color: 'var(--color-primary)',
                    font: 'var(--text-caption)',
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 6,
                  }}
                >
                  + 新建项目
                </button>

                {/* Project list */}
                <div style={{ maxHeight: 240, overflowY: 'auto' }}>
                  {projects.length === 0 ? (
                    <p style={{ padding: 12, color: 'var(--color-muted-soft)', fontSize: 12, textAlign: 'center' }}>
                      暂无项目
                    </p>
                  ) : (
                    projects.map((p) => (
                      <div
                        key={p.id}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          borderBottom: '1px solid var(--color-hairline-soft)',
                          background: currentProject?.id === p.id ? 'var(--color-surface-card)' : 'white',
                        }}
                      >
                        {/* project name — clickable */}
                        <button
                          onClick={() => handleSelectProject(p.id)}
                          style={{
                            flex: 1,
                            padding: '8px 6px 8px 14px',
                            border: 'none',
                            background: 'none',
                            color: 'var(--color-body)',
                            font: 'var(--text-body-sm)',
                            cursor: 'pointer',
                            textAlign: 'left',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            minWidth: 0,
                          }}
                        >
                          <span style={{ fontWeight: currentProject?.id === p.id ? 600 : 400 }}>
                            {p.name}
                          </span>
                          <span style={{ fontSize: 11, color: 'var(--color-muted-soft)', marginLeft: 8 }}>
                            {p.status === 'draft' ? '草稿' : ''}
                          </span>
                        </button>

                        {/* action buttons */}
                        <div style={{ display: 'flex', alignItems: 'center', paddingRight: 4, flexShrink: 0 }}>
                          <button
                            onClick={(e) => { e.stopPropagation(); handleRenameStart(p); }}
                            title="重命名"
                            style={{
                              width: 28,
                              height: 28,
                              border: 'none',
                              background: 'none',
                              cursor: 'pointer',
                              fontSize: 14,
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              borderRadius: 'var(--rounded-sm)',
                              color: 'var(--color-muted)',
                            }}
                          >
                            ✏️
                          </button>
                          <button
                            onClick={(e) => { e.stopPropagation(); handleDeleteConfirm(p); }}
                            title="删除"
                            style={{
                              width: 28,
                              height: 28,
                              border: 'none',
                              background: 'none',
                              cursor: 'pointer',
                              fontSize: 14,
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              borderRadius: 'var(--rounded-sm)',
                              color: 'var(--color-muted)',
                            }}
                          >
                            🗑️
                          </button>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Right: user + logout */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {user?.displayName && (
            <span style={{ font: 'var(--text-caption)', color: 'var(--color-muted)' }}>
              {user.displayName}
            </span>
          )}
          <button
            onClick={logout}
            style={{
              padding: '4px 14px',
              height: 32,
              border: '1px solid var(--color-hairline)',
              borderRadius: 'var(--rounded-md)',
              background: 'white',
              font: 'var(--text-caption)',
              color: 'var(--color-muted)',
              cursor: 'pointer',
            }}
          >
            退出
          </button>
        </div>
      </div>

      {/* ── rename modal ──────────────────────────────────────────── */}
      {renameTarget && (
        <div style={modalOverlayStyle} onClick={() => { setRenameTarget(null); setRenameName(''); }}>
          <div style={modalStyle} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>
              重命名项目
            </h3>
            <input
              autoFocus
              value={renameName}
              onChange={(e) => setRenameName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleRenameSubmit(); }}
              style={inputStyle}
              placeholder="输入项目名称"
            />
            <div style={{ marginTop: 16, textAlign: 'right' }}>
              <button
                style={secondaryBtnStyle}
                onClick={() => { setRenameTarget(null); setRenameName(''); }}
              >
                取消
              </button>
              <button
                style={primaryBtnStyle}
                disabled={!renameName.trim()}
                onClick={handleRenameSubmit}
              >
                确定
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── delete confirm modal ───────────────────────────────────── */}
      {deleteTarget && (
        <div style={modalOverlayStyle} onClick={() => setDeleteTarget(null)}>
          <div style={modalStyle} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px', font: 'var(--text-body)', color: 'var(--color-ink)' }}>
              删除项目
            </h3>
            <p style={{ margin: '0 0 16px', font: 'var(--text-body-sm)', color: 'var(--color-muted)' }}>
              确定要删除项目「{deleteTarget.name}」吗？此操作无法撤销。
            </p>
            <div style={{ textAlign: 'right' }}>
              <button
                style={secondaryBtnStyle}
                onClick={() => setDeleteTarget(null)}
              >
                取消
              </button>
              <button
                style={{ ...primaryBtnStyle, background: '#e53e3e' }}
                onClick={handleDeleteExecute}
              >
                删除
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
