import { useProjectStore, type ToastMessage } from '../../stores/projectStore';

function toastStyle(type: ToastMessage['type']): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '8px 14px',
    borderRadius: 'var(--rounded-sm)',
    background: type === 'success' ? '#e8f5e9' : '#fbe9e7',
    border: `1px solid ${type === 'success' ? '#a5d6a7' : '#ef9a9a'}`,
    color: 'var(--color-ink)',
    fontSize: 12,
    fontWeight: 500,
    boxShadow: '0 2px 8px rgba(0,0,0,.12)',
    pointerEvents: 'auto',
    animation: 'fadeIn 0.2s ease',
  };
}

export function ToastContainer() {
  const toasts = useProjectStore((s) => s.toasts);
  const dismissToast = useProjectStore((s) => s.dismissToast);

  if (toasts.length === 0) return null;

  return (
    <>
      <style>{`
        @keyframes toastFadeIn { from { opacity:0; transform:translateX(16px); } to { opacity:1; transform:translateX(0); } }
      `}</style>
      <div
        style={{
          position: 'fixed',
          top: 64,
          right: 16,
          zIndex: 2000,
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
          pointerEvents: 'none',
        }}
      >
        {toasts.map((t) => (
          <div
            key={t.id}
            style={{
              ...toastStyle(t.type),
              animation: 'toastFadeIn 0.25s ease',
            }}
            onClick={() => dismissToast(t.id)}
          >
            <span style={{ fontSize: 14 }}>
              {t.type === 'success' ? '✅' : '❌'}
            </span>
            <span>
              分镜{t.sceneNumber} {t.kind === 'image' ? '生图' : '生视频'}
              {t.type === 'success' ? '成功' : '失败'}
            </span>
            <span
              style={{
                marginLeft: 4,
                fontSize: 10,
                color: 'var(--color-muted)',
                cursor: 'pointer',
              }}
            >
              ✕
            </span>
          </div>
        ))}
      </div>
    </>
  );
}
