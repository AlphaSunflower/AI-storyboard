import { useProjectStore } from '../../stores/projectStore';

const barHeight = 40;
const coralColor = '#FF6B6B';

export function ProjectHistoryPanel() {
  const { projects, currentProject, loadProject } = useProjectStore();

  if (projects.length === 0) {
    return null;
  }

  return (
    <div
      style={{
        height: barHeight,
        minHeight: barHeight,
        display: 'flex',
        alignItems: 'center',
        padding: '0 var(--space-md)',
        borderBottom: '1px solid var(--color-hairline)',
        background: 'var(--color-canvas)',
        gap: 8,
        overflowX: 'auto',
        overflowY: 'hidden',
        whiteSpace: 'nowrap',
        scrollbarWidth: 'none',
      }}
    >
      {projects.map((p) => {
        const isActive = currentProject?.id === p.id;

        return (
          <button
            key={p.id}
            onClick={() => loadProject(p.id)}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              padding: '4px 14px',
              height: 28,
              borderRadius: 14,
              border: isActive ? `1.5px solid ${coralColor}` : '1px solid var(--color-hairline)',
              background: isActive ? `${coralColor}15` : 'white',
              font: 'var(--text-caption)',
              color: isActive ? coralColor : 'var(--color-muted)',
              fontWeight: isActive ? 600 : 400,
              cursor: 'pointer',
              flexShrink: 0,
              transition: 'all 0.15s ease',
              whiteSpace: 'nowrap',
            }}
          >
            <span
              style={{
                width: 6,
                height: 6,
                borderRadius: '50%',
                background: isActive ? coralColor : 'var(--color-hairline)',
                flexShrink: 0,
              }}
            />
            {p.name}
            {p.status === 'draft' && (
              <span style={{ fontSize: 10, opacity: 0.6, marginLeft: 2 }}>
                草稿
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
