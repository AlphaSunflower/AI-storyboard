import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { assetUrl } from '../../config';

/**
 * 图片/视频点击放大预览（灯箱）：
 * - 遮罩点击 / ESC / 右上角关闭按钮均可关闭
 * - 大图 max 92vw x 88vh 居中显示，点击媒体本身不关闭
 */
export function ImagePreviewModal({
  url,
  type = 'image',
  onClose,
}: {
  url: string | null;
  type?: 'image' | 'video';
  onClose: () => void;
}) {
  useEffect(() => {
    if (!url) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [url, onClose]);

  if (!url) return null;
  return createPortal(
    <div
      role="dialog"
      aria-label="图片预览"
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 4000,
        // 不透明背景避免背后的动图/SVG 继续透出，与前景副本形成双图闪烁。
        background: '#0f0f0e',
        isolation: 'isolate',
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
        cursor: 'zoom-out',
      }}
    >
      <button
        onClick={(event) => {
          event.stopPropagation();
          onClose();
        }}
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
      {type === 'video' ? (
        <video
          src={assetUrl(url)}
          controls
          autoPlay
          onClick={(e) => e.stopPropagation()}
          style={{
            maxWidth: '92vw', maxHeight: '88vh', objectFit: 'contain',
            borderRadius: 10, boxShadow: '0 8px 40px rgba(0,0,0,0.5)',
            background: '#111',
          }}
        />
      ) : (
        <img
          src={assetUrl(url)}
          alt="预览大图"
          onClick={(e) => e.stopPropagation()}
          style={{
            maxWidth: '92vw', maxHeight: '88vh', objectFit: 'contain',
            borderRadius: 10, boxShadow: '0 8px 40px rgba(0,0,0,0.5)', cursor: 'zoom-out',
          }}
        />
      )}
    </div>,
    document.body,
  );
}
