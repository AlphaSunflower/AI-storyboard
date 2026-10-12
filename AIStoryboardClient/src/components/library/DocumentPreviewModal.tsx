import { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { Download, X } from 'lucide-react';
import { assetUrl } from '../../config';
import { notify } from '../../stores/toastStore';
import { DocumentPreviewSurface } from '../agent/DocumentPreviewSurface';
import './DocumentPreviewModal.css';

export interface DocumentPreviewTarget {
  url: string;
  name: string;
  mimeType?: string;
}

/**
 * 文档类素材的放大预览：详情面板只放一个入口，真正的解析与翻页在灯箱里完成，
 * 避免在窄栏内嵌 PDF/Word 预览导致内容被挤压、无法滚动。
 */
export function DocumentPreviewModal({
  target,
  onClose,
}: {
  target: DocumentPreviewTarget | null;
  onClose: () => void;
}) {
  useEffect(() => {
    if (!target) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = previousOverflow;
    };
  }, [target, onClose]);

  if (!target) return null;
  return createPortal(
    <div
      className="library-doc-preview"
      role="dialog"
      aria-modal="true"
      aria-label={`预览 ${target.name}`}
      onClick={onClose}
    >
      <section className="library-doc-preview__panel" onClick={(event) => event.stopPropagation()}>
        <header className="library-doc-preview__header">
          <div className="library-doc-preview__title">
            <strong title={target.name}>{target.name}</strong>
            <span>{target.mimeType || '文档'}</span>
          </div>
          <div className="library-doc-preview__actions">
            <a
              href={assetUrl(target.url)}
              download={target.name}
              onClick={() => notify({ title: '已开始下载', description: target.name, variant: 'info' })}
            >
              <Download size={15} /> 下载
            </a>
            <button type="button" onClick={onClose} aria-label="关闭预览">
              <X size={16} />
            </button>
          </div>
        </header>
        <div className="library-doc-preview__body">
          <DocumentPreviewSurface
            attachment={{
              url: target.url,
              name: target.name,
              mimeType: target.mimeType ?? '',
              kind: 'file',
            }}
          />
        </div>
      </section>
    </div>,
    document.body,
  );
}
