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
  };
}

/** E12: 成功勾选 SVG——stroke 从 0 画到完整（1s 内的小仪式），失败保持 ✕ 文本 */
function StatusIcon({ type }: { type: ToastMessage['type'] }) {
  if (type !== 'success') {
    return <span style={{ fontSize: 14 }}>❌</span>;
  }
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="#2e7d32"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ flexShrink: 0 }}
    >
      <path
        d="M4 12.5l5 5L20 6.5"
        style={{
          strokeDasharray: 24,
          strokeDashoffset: 24,
          animation: 'toastCheckDraw 0.6s ease-out 0.15s forwards',
        }}
      />
      <style>{`
        @keyframes toastCheckDraw { to { stroke-dashoffset: 0; } }
      `}</style>
    </svg>
  );
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
            <StatusIcon type={t.type} />
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
