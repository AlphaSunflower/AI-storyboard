import { useEffect } from 'react';
import { useProjectStore } from '../../stores/projectStore';

export function ProjectContent() {
  const { projects, currentProject, loadProjects, loadProject } = useProjectStore();

  useEffect(() => { loadProjects(); }, [loadProjects]);

  if (!projects.length) {
    return (
      <div className="flex items-center justify-center py-12 text-sm" style={{ color: 'var(--color-muted)' }}>
        暂无项目
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {projects.map((p) => {
        const active = currentProject?.id === p.id;
        return (
          <button
            key={p.id}
            onClick={() => loadProject(p.id)}
            className="text-left px-4 py-3 rounded-lg transition-all text-sm"
            style={{
              background: active ? 'rgba(204,120,92,0.08)' : 'transparent',
              color: 'var(--color-ink)',
              border: `1px solid ${active ? 'var(--color-primary)' : 'var(--color-hairline)'}`,
              outline: active ? '2px solid var(--color-primary)' : 'none',
              outlineOffset: '2px',
            }}
          >
            {p.name}
          </button>
        );
      })}
    </div>
  );
}
