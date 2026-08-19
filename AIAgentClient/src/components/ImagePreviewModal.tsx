import { useEffect } from 'react';
import { assetUrl } from '../config';

/**
 * 图片点击放大预览(灯箱):
 * - 遮罩点击 / ESC / 右上角关闭按钮均可关闭
 * - 大图 max 92vw x 88vh 居中显示,点击图片本身不关闭(便于查看细节)
 */
export function ImagePreviewModal({ url, onClose }: { url: string | null; onClose: () => void }) {
  useEffect(() => {
    if (!url) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    // 模态打开时锁定页面滚动,避免背景穿透滚动
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [url, onClose]);

  if (!url) return null;
  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 1000,
        background: 'rgba(20,20,19,0.78)', backdropFilter: 'blur(2px)',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
        cursor: 'zoom-out',
      }}
    >
      <button
        onClick={onClose}
        aria-label="关闭预览"
        style={{
          position: 'absolute', top: 16, right: 16,
          width: 36, height: 36, borderRadius: '50%',
          border: 'none', background: 'rgba(255,255,255,0.14)', color: 'white',
          fontSize: 18, cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}
      >
        ✕
      </button>
      <img
        src={assetUrl(url)}
        alt="预览大图"
        onClick={(e) => e.stopPropagation()}
        style={{
          maxWidth: '92vw', maxHeight: '88vh', objectFit: 'contain',
          borderRadius: 10, boxShadow: '0 8px 40px rgba(0,0,0,0.5)', cursor: 'zoom-out',
        }}
      />
    </div>
  );
}
