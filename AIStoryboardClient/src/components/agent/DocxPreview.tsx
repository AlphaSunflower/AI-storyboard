import { useEffect, useRef, useState } from 'react';
import { FileText, LoaderCircle } from 'lucide-react';
import { DOCX_PREVIEW_OPTIONS, hardenOfficeDom } from '../../lib/docxPreview';

export function DocxPreview({ source }: { source: Blob }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    const staging = document.createElement('div');
    containerRef.current?.replaceChildren();
    setStatus('loading');
    setError('');

    void import('docx-preview')
      .then(({ renderAsync }) => renderAsync(source, staging, staging, DOCX_PREVIEW_OPTIONS))
      .then(() => {
        if (cancelled) return;
        hardenOfficeDom(staging);
        const container = containerRef.current;
        if (!container) return;
        container.replaceChildren(...Array.from(staging.childNodes));
        setStatus('ready');
      })
      .catch((reason: unknown) => {
        if (cancelled) return;
        setStatus('error');
        setError(reason instanceof Error ? reason.message : 'Word 文档渲染失败');
      });

    return () => {
      cancelled = true;
      staging.replaceChildren();
    };
  }, [source]);

  return (
    <div className="document-preview__docx-shell">
      {status === 'loading' && (
        <div className="document-preview__status">
          <LoaderCircle className="is-spinning" size={24} strokeWidth={1.7} />
          <span>正在按 Word 页面格式渲染…</span>
        </div>
      )}
      {status === 'error' && (
        <div className="document-preview__status is-error">
          <FileText size={28} strokeWidth={1.5} />
          <strong>Word 文档渲染失败</strong>
          <span>{error}</span>
        </div>
      )}
      <div
        ref={containerRef}
        className="document-preview__docx"
        aria-hidden={status !== 'ready'}
      />
    </div>
  );
}
