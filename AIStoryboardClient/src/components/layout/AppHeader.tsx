import { useState, useRef, useEffect } from 'react';
import { useAuthStore } from '../../stores/authStore';
import { useProjectStore } from '../../stores/projectStore';

const headerHeight = 48;

export function AppHeader() {
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const { projects, currentProject, loadProject, createProject } = useProjectStore();
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

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

  const currentName = currentProject?.name || '选择项目';

  return (
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
                width: 220,
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
                    <button
                      key={p.id}
                      onClick={() => handleSelectProject(p.id)}
                      style={{
                        width: '100%',
                        padding: '8px 14px',
                        border: 'none',
                        borderBottom: '1px solid var(--color-hairline-soft)',
                        background: currentProject?.id === p.id ? 'var(--color-surface-card)' : 'white',
                        color: 'var(--color-body)',
                        font: 'var(--text-body-sm)',
                        cursor: 'pointer',
                        textAlign: 'left',
                      }}
                    >
                      <span style={{ fontWeight: currentProject?.id === p.id ? 600 : 400 }}>
                        {p.name}
                      </span>
                      <span style={{ fontSize: 11, color: 'var(--color-muted-soft)', marginLeft: 8 }}>
                        {p.status === 'draft' ? '草稿' : ''}
                      </span>
                    </button>
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
  );
}
