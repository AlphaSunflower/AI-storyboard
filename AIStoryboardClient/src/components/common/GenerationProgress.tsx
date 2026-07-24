import { useProjectStore } from '../../stores/projectStore';

export function GenerationProgress() {
  const scriptGenerationStatus = useProjectStore((s) => s.scriptGenerationStatus);
  const scriptGenerationMessage = useProjectStore((s) => s.scriptGenerationMessage);

  if (scriptGenerationStatus === 'idle') return null;

  const base: React.CSSProperties = {
    padding: '8px var(--space-md)',
    fontSize: 13,
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    font: 'var(--text-body-sm)',
    borderBottom: '1px solid var(--color-hairline-soft)',
  };

  if (scriptGenerationStatus === 'generating') {
    return (
      <div style={{ ...base, background: 'var(--color-canvas)', color: 'var(--color-body-strong)' }}>
        <span style={{
          display: 'inline-block',
          width: 16, height: 16,
          borderRadius: '50%',
          border: '2px solid var(--color-primary)',
          borderTopColor: 'transparent',
          animation: 'spin 0.8s linear infinite',
        }} />
        <span>{scriptGenerationMessage}</span>
        <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
      </div>
    );
  }

  if (scriptGenerationStatus === 'done') {
    return (
      <div style={{ ...base, background: 'var(--color-canvas)', color: 'var(--color-success)' }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>✓</span>
        <span>分镜生成完成</span>
      </div>
    );
  }

  if (scriptGenerationStatus === 'error') {
    return (
      <div style={{ ...base, background: 'var(--color-canvas)', color: 'var(--color-error)' }}>
        <span style={{ fontWeight: 600 }}>✕</span>
        <span>{scriptGenerationMessage}</span>
      </div>
    );
  }

  return null;
}
