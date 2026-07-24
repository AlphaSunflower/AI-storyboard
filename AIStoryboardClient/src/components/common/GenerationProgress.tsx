import { useProjectStore } from '../../stores/projectStore';

export function GenerationProgress() {
  const scriptGenerationStatus = useProjectStore(
    (s) => s.scriptGenerationStatus,
  );
  const scriptGenerationMessage = useProjectStore(
    (s) => s.scriptGenerationMessage,
  );

  if (scriptGenerationStatus === 'idle') {
    return null;
  }

  if (scriptGenerationStatus === 'generating') {
    return (
      <div
        style={{
          padding: '6px 16px',
          backgroundColor: '#1e3a5f',
          color: '#93c5fd',
          fontSize: 13,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          borderBottom: '1px solid #1e3a5f',
        }}
      >
        <span>⏳</span>
        <span>{scriptGenerationMessage}</span>
      </div>
    );
  }

  if (scriptGenerationStatus === 'done') {
    return (
      <div
        style={{
          padding: '6px 16px',
          backgroundColor: '#14532d',
          color: '#86efac',
          fontSize: 13,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          borderBottom: '1px solid #14532d',
        }}
      >
        <span>✅</span>
        <span>分镜生成完成</span>
      </div>
    );
  }

  if (scriptGenerationStatus === 'error') {
    return (
      <div
        style={{
          padding: '6px 16px',
          backgroundColor: '#7f1d1d',
          color: '#fca5a5',
          fontSize: 13,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          borderBottom: '1px solid #7f1d1d',
        }}
      >
        <span>❌</span>
        <span>{scriptGenerationMessage}</span>
      </div>
    );
  }

  return null;
}
