import { CheckCircle2, CircleAlert, Info } from 'lucide-react';
import SwipeToast from '../ui/SwipeToast';
import { MAX_VISIBLE_TOASTS, useToastStore, type ToastVariant } from '../../stores/toastStore';
import './ToastHost.css';

/** 燃线颜色按语义走既有 token：成功绿 / 失败红 / 中性珊瑚 */
const FUSE_COLOR: Record<ToastVariant, string> = {
  success: 'var(--color-success)',
  error: 'var(--color-error)',
  info: 'var(--color-primary)',
};

const ICON_COLOR: Record<ToastVariant, string> = {
  success: '#5db872',
  error: '#c64545',
  info: '#cc785c',
};

function variantIcon(variant: ToastVariant) {
  const props = { size: 15, strokeWidth: 1.9, color: ICON_COLOR[variant] };
  if (variant === 'error') return <CircleAlert {...props} />;
  if (variant === 'info') return <Info {...props} />;
  return <CheckCircle2 {...props} />;
}

/** 全局通知栈：固定在右下角，最新的贴近角落，同屏最多 MAX_VISIBLE_TOASTS 条 */
export function ToastHost() {
  const toasts = useToastStore((state) => state.toasts);
  const dismiss = useToastStore((state) => state.dismiss);

  if (toasts.length === 0) return null;

  // 超出上限的旧通知保持挂载但翻成关闭态，让它们走完滑出动画再回收
  const visibleFrom = Math.max(0, toasts.length - MAX_VISIBLE_TOASTS);

  return (
    <div className="toast-host" aria-label="操作提示">
      {toasts.map((toast, index) => (
        <SwipeToast
          key={toast.id}
          inline
          open={index >= visibleFrom}
          title={toast.title}
          description={toast.description ?? ''}
          icon={variantIcon(toast.variant)}
          duration={toast.duration}
          background="var(--color-canvas)"
          color="var(--color-ink)"
          fuseColor={FUSE_COLOR[toast.variant]}
          width={320}
          radius={12}
          slideMs={320}
          settleBounce={0.2}
          swipeDistance={40}
          fuse="bottom"
          pauseOnHover
          closeButton
          onClose={() => dismiss(toast.id)}
        />
      ))}
    </div>
  );
}
