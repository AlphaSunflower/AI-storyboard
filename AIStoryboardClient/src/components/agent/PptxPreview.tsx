import { useEffect, useRef, useState } from 'react';
import { FileText, LoaderCircle } from 'lucide-react';
import { hardenOfficeDom } from '../../lib/docxPreview';

const PPTX_WIDTH = 960;

export function PptxPreview({ source }: { source: Blob }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    const staging = document.createElement('div');
    containerRef.current?.replaceChildren();
    setStatus('loading');
    setError('');

    void Promise.all([
      import('pptx-preview'),
      source.arrayBuffer(),
    ])
      .then(async ([{ init }, buffer]) => {
        const previewer = init(staging, {
          width: PPTX_WIDTH,
          mode: 'list',
        });
        await previewer.preview(buffer);
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
        setError(reason instanceof Error ? reason.message : 'PowerPoint 文档渲染失败');
      });

    return () => {
      cancelled = true;
      staging.replaceChildren();
    };
  }, [source]);

  return (
    <div className="document-preview__pptx-shell">
      {status === 'loading' && (
        <div className="document-preview__status">
          <LoaderCircle className="is-spinning" size={24} strokeWidth={1.7} />
          <span>正在按幻灯片格式渲染…</span>
        </div>
      )}
      {status === 'error' && (
        <div className="document-preview__status is-error">
          <FileText size={28} strokeWidth={1.5} />
          <strong>PowerPoint 文档渲染失败</strong>
          <span>{error}</span>
        </div>
      )}
      <div
        ref={containerRef}
        className="document-preview__pptx"
        aria-hidden={status !== 'ready'}
      />
    </div>
  );
}
