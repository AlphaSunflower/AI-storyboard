import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { useObjectUrl } from '../hooks/useObjectUrl';
import { useMediaStore } from '../store/mediaStore';

export type ImagePreviewTarget = {
  assetId: string;
  label: string;
};

export function ImagePreviewOverlay({
  target,
  onClose,
  present = true,
}: {
  target: ImagePreviewTarget;
  onClose: () => void;
  present?: boolean;
}) {
  const asset = useMediaStore((state) => state.assets[target.assetId]);
  const url = useObjectUrl(asset?.originalBlob ?? asset?.previewBlob);

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    addEventListener('keydown', closeOnEscape);
    return () => removeEventListener('keydown', closeOnEscape);
  }, [onClose]);

  return createPortal(
    <div
      className="image-fullscreen record-image-preview motion-fade"
      data-present={present}
      role="dialog"
      aria-modal="true"
      aria-label="图片预览"
      onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}
    >
      {url
        ? <img className="fullscreen-media" src={url} alt={target.label} decoding="async" />
        : <div className="fullscreen-media-loading">正在加载原图…</div>}
      <header>
        <strong>{target.label}</strong>
        <span>{asset ? `${asset.width} × ${asset.height}` : '本地图片预览'}</span>
      </header>
      <button type="button" autoFocus aria-label="关闭预览" onClick={onClose}><X size={20} /></button>
    </div>,
    document.body,
  );
}
