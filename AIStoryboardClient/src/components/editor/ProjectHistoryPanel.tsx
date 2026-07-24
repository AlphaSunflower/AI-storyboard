import { useProjectStore } from '../../stores/projectStore';

const coralColor = '#FF6B6B';

export function ProjectHistoryPanel() {
  const { projects, currentProject, loadProject } = useProjectStore();

  if (projects.length === 0) {
    return null;
  }

  return (
    <div
      style={{
        overflowY: 'auto',
        flex: 1,
        minHeight: 0,
      }}
    >
      {projects.map((p) => {
        const isActive = currentProject?.id === p.id;

        return (
          <div
            key={p.id}
            onClick={() => loadProject(p.id)}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '6px 8px',
              borderRadius: 'var(--rounded-sm)',
              background: isActive ? `${coralColor}12` : 'transparent',
              cursor: 'pointer',
              fontSize: 13,
              color: isActive ? coralColor : 'var(--color-body)',
              fontWeight: isActive ? 600 : 400,
              borderLeft: isActive ? `3px solid ${coralColor}` : '3px solid transparent',
              transition: 'background 0.12s ease',
            }}
          >
            <span
              style={{
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                flex: 1,
                marginRight: 8,
              }}
            >
              {p.name}
            </span>
            {p.status === 'draft' && (
              <span
                style={{
                  fontSize: 10,
                  color: 'var(--color-muted)',
                  background: 'var(--color-canvas-muted)',
                  padding: '1px 6px',
                  borderRadius: 8,
                  flexShrink: 0,
                }}
              >
                草稿
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
