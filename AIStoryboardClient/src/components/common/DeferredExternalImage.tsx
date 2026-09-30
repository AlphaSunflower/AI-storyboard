import { useState, type CSSProperties } from 'react';
import { Images } from 'lucide-react';
import { isExternalImageSource } from '../../lib/externalImages';

export function DeferredExternalImage({
  src,
  source,
  alt,
  className,
  style,
}: {
  src: string;
  source?: string;
  alt?: string;
  className?: string;
  style?: CSSProperties;
}) {
  const external = isExternalImageSource(source ?? src);
  const [requested, setRequested] = useState(!external);
  const [failed, setFailed] = useState(false);

  if (failed) return null;
  if (external && !requested) {
    return (
      <button
        type="button"
        className={className}
        aria-label="点击加载外部图片"
        onClick={() => setRequested(true)}
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 8,
          minWidth: 148,
          minHeight: 80,
          padding: '12px 14px',
          border: '1px dashed var(--color-hairline)',
          borderRadius: 8,
          background: 'var(--color-canvas)',
          color: 'var(--color-muted)',
          cursor: 'pointer',
          font: 'inherit',
          fontSize: 12,
          ...style,
        }}
      >
        <Images size={16} strokeWidth={1.7} />
        <span>点击加载外部图片</span>
      </button>
    );
  }

  return (
    <img
      className={className}
      src={src}
      alt={alt ?? ''}
      style={style}
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
    />
  );
}
